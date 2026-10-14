package com.sddnw.server.service;

import com.sddnw.server.config.AuthProperties;
import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.entity.history.AccountSessionEntity;
import com.sddnw.server.domain.enums.AccountRole;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.dto.AccountDto;
import com.sddnw.server.dto.AccountSummaryDto;
import com.sddnw.server.dto.LoginRequest;
import com.sddnw.server.dto.RegisterRequest;
import com.sddnw.server.dto.RegisterResultDto;
import com.sddnw.server.dto.SessionDto;
import com.sddnw.server.repository.history.AccountRepository;
import com.sddnw.server.repository.history.AccountSessionRepository;
import com.sddnw.server.repository.history.BuildTemplateRepository;
import com.sddnw.server.repository.history.FunnelStepRepository;
import com.sddnw.server.repository.history.ProblemReportRepository;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.springframework.data.domain.Sort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Вход в игру: регистрация, подтверждение почты, сеансы — п. 3.1.
 * <p>
 * <b>Учётная запись и игрок партии — разное.</b> Запись живёт между партиями и знает
 * только логин, почту и пароль; всё игровое — раса, колонии, флот — принадлежит игроку
 * внутри своей партии. Поэтому запись ни на что в партии не ссылается: удалённая запись
 * не должна уносить с собой сыгранное.
 * <p>
 * <b>Пароль хранится хешем</b> (BCrypt): в базе, в журналах и в ответах сервера открытого
 * пароля нет нигде. Проверка нарочно не говорит, что именно не сошлось — логин или
 * пароль: иначе подбор начинался бы с перебора логинов. Сам подбор останавливает
 * {@link LoginThrottle}: неудачи считаются по записи и по адресу, и после порога вход
 * закрывается на несколько минут.
 * <p>
 * <b>Регистрация подтверждается почтой:</b> запись заводится сразу, но до перехода по
 * ссылке из письма вход в неё закрыт. Ссылка одноразовая и с сроком; исключение одно —
 * администратор, он заводится сервером и почты не подтверждает (см. {@code AdminAccount}).
 * Не дошло письмо — его высылают заново ({@link #resend}) или запись подтверждает
 * администратор ({@link #confirmByAdmin}): застрявшая регистрация не должна занимать
 * почту навсегда.
 * <p>
 * <b>Логин — это почта.</b> Отдельного логина у записи нет: игрок называет себя тем же
 * адресом, на который пришло письмо, — одно имя вместо двух. Имя записи служит только
 * показу в интерфейсе игры и уникальным быть не обязано. Исключение опять же одно —
 * администратор: у него почты нет, и логин у него свой.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    /** Короче этого пароль не принимается: восемь знаков — общая нижняя граница. */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private final AccountRepository accountRepository;
    private final AccountSessionRepository sessionRepository;
    private final BuildTemplateRepository buildTemplateRepository;
    /** Библиотека проектов кораблей (пункт 96) уходит вместе с записью. */
    private final com.sddnw.server.repository.history.ShipTemplateRepository shipTemplateRepository;
    private final com.sddnw.server.repository.history.RaceTemplateRepository raceTemplateRepository;
    /** Подписки браузеров на зов о ходе (пункт 22) — уходят вместе с записью. */
    private final com.sddnw.server.repository.history.PushSubscriptionRepository pushSubscriptions;
    private final FunnelStepRepository funnelSteps;
    private final ProblemReportRepository problemReports;
    private final MailService mailService;
    private final AuthProperties properties;
    private final LoginThrottle throttle;
    private final RegistrationThrottle registrations;
    private final RegistrationChallenge challenge;
    private final GuestThrottle guests;
    private final Messages messages;
    private final com.sddnw.server.repository.history.AccountEntitlementRepository entitlements;

    /** История партий записи (пункт 18) — уходит вместе с ней; необязательна — тестам не нужна. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sddnw.server.repository.history.GameRecordRepository gameRecords;
    private final PasswordResetThrottle resets;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();

    /** Сколько живёт ссылка сброса пароля, минут — пункт 45: короче подтверждения, она ключ к записи. */
    public static final int RESET_MINUTES = 60;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accountRepository,
                          AccountSessionRepository sessionRepository,
                          BuildTemplateRepository buildTemplateRepository,
                          com.sddnw.server.repository.history.ShipTemplateRepository shipTemplateRepository,
                          com.sddnw.server.repository.history.RaceTemplateRepository raceTemplateRepository,
                          FunnelStepRepository funnelSteps,
                          ProblemReportRepository problemReports,
                          MailService mailService,
                          AuthProperties properties,
                          LoginThrottle throttle,
                          RegistrationThrottle registrations,
                          RegistrationChallenge challenge,
                          GuestThrottle guests,
                          Messages messages,
                          com.sddnw.server.repository.history.AccountEntitlementRepository entitlements,
                          PasswordResetThrottle resets,
                          com.sddnw.server.repository.history.PushSubscriptionRepository pushSubscriptions) {
        this.pushSubscriptions = pushSubscriptions;
        this.entitlements = entitlements;
        this.resets = resets;
        this.accountRepository = accountRepository;
        this.sessionRepository = sessionRepository;
        this.buildTemplateRepository = buildTemplateRepository;
        this.shipTemplateRepository = shipTemplateRepository;
        this.raceTemplateRepository = raceTemplateRepository;
        this.funnelSteps = funnelSteps;
        this.problemReports = problemReports;
        this.mailService = mailService;
        this.properties = properties;
        this.throttle = throttle;
        this.registrations = registrations;
        this.challenge = challenge;
        this.guests = guests;
        this.messages = messages;
    }

    /**
     * Регистрация — п. 3.1: запись заводится, письмо со ссылкой уходит, вход пока закрыт.
     * <p>
     * <b>Не ушло письмо — не будет и записи.</b> Отправка идёт внутри той же транзакции,
     * и её отказ откатывает всё: иначе логин остался бы занят записью, в которую никто
     * никогда не войдёт, — ни ссылки, ни способа её переслать. Игрок повторяет
     * регистрацию с теми же логином и почтой.
     *
     * <b>Регистраций с одного адреса — счётное число</b> ({@link RegistrationThrottle}):
     * каждая шлёт письмо на чужую почту, и без предела сервер оказывается рассылкой
     * чужими руками. Считаются заведённые записи, а не попытки: отказ письма не шлёт.
     *
     * @param clientAddress адрес, с которого пришёл запрос, — по нему и считаем
     * @return что сказать игроку: письмо ушло на почту или лежит в исходящих сервера
     */
    // Транзакция ПОСТОЯННОГО источника, а не игры: учётные записи живут месяцами, как и
    // журнал прогонов, и к партиям отношения не имеют вовсе. Без имени менеджера Spring
    // взял бы главный, игровой, — и в режиме балансового прогона регистрация ложилась бы
    // в память вместе с партией, а admin.txt переставал бы подходить.
    @Transactional("historyTransactionManager")
    public RegisterResultDto register(RegisterRequest request, String clientAddress) {
        registrations.requireAllowed(clientAddress);
        // Задачка тратится здесь, до всякой работы: она одноразовая, и её проверка должна
        // случиться и тогда, когда заявка отвалится дальше по любой другой причине.
        challenge.require(request.challengeId(), request.challengeAnswer());
        // Логин и почта — одно и то же: игроку хватает одного имени, и оно же есть адрес,
        // куда придёт письмо. Приводится к нижнему регистру, чтобы «Игрок@Почта» и
        // «игрок@почта» не оказались двумя разными записями.
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String login = email;
        String name = request.name().trim();
        requirePassword(request.password());

        if (accountRepository.findByLoginIgnoreCase(login).isPresent()
                || accountRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new ConflictException("auth.emailTaken");
        }

        AccountEntity account = new AccountEntity();
        account.setLogin(login);
        account.setEmail(email);
        account.setName(name);
        account.setPasswordHash(passwords.encode(request.password()));
        account.setRole(AccountRole.PLAYER);
        account.setConfirmed(Boolean.FALSE);
        account.setCreatedAt(OffsetDateTime.now());
        // Язык запроса — это язык, на котором игрок только что читал экран регистрации, и
        // запоминать его отдельной просьбой незачем (п. 3.5). Сев за другую машину, он
        // получит тот же язык, а не английский заново.
        account.setLocale(LocaleContextHolder.getLocale().getLanguage());
        // Ссылку и срок ставит sendConfirmation: у регистрации и повторной отправки они
        // обязаны быть одинаковыми.
        accountRepository.save(account);

        // Письмо отправляется до записи в журнал: сорвавшаяся отправка откатит и запись,
        // и строка «заведена учётная запись» оказалась бы неправдой.
        RegisterResultDto result = sendConfirmation(account, Boolean.FALSE);
        // Отмечаем после отправки: не ушло письмо — не было и регистрации, запись
        // откатится вместе с ним, и тратить на неё попытку адреса не за что.
        registrations.registered(clientAddress);
        log.info("Заведена учётная запись {}, ждёт подтверждения почты", login);
        return result;
    }

    /**
     * Гостевой вход — backlog-promo, пункт 1: запись без почты и пароля, одним нажатием.
     * <p>
     * <b>Зачем запись, а не «партия без записи».</b> Всё, что заводит партию и возвращает в
     * неё, держится на учётной записи: хозяин места в партии (миграция 077), «Ваши партии»,
     * язык и размер текста. Гость получает ту же запись с ролью {@link AccountRole#GUEST}, и
     * весь этот путь работает для него без единой ветки «а если гость».
     * <p>
     * <b>Ключ от записи — только пропуск.</b> Пароль у гостевой записи случайный и не
     * выдаётся никому: войти в неё логином нельзя, а потерянный пропуск (очищенный браузер)
     * значит потерянную запись. Это честная цена одного нажатия; кому есть что терять,
     * тот регистрируется.
     * <p>
     * <b>Записей с адреса — счётное число</b> ({@link GuestThrottle}): каждая — это
     * партия с галактикой в базе, и без предела базу заваливали бы циклом.
     */
    @Transactional("historyTransactionManager")
    public SessionDto guest(String clientAddress) {
        guests.requireAllowed(clientAddress);
        AccountEntity account = new AccountEntity();
        // Логин нужен колонке (он уникален и обязателен), но входить им некому: длинный
        // случайный хвост не даёт ему совпасть с чужим, а приставка — спутать с почтой.
        account.setLogin("guest-" + token().substring(0, 20));
        // Имя — один номер, без слова «гость»: имя лежит в базе, а у хранимого текста языка
        // быть не может. Подпись «Гость 4821» собирает клиент по роли записи.
        account.setName(String.valueOf(1000 + random.nextInt(9000)));
        account.setPasswordHash(passwords.encode(token()));
        account.setRole(AccountRole.GUEST);
        // Подтверждать гостю нечего: почты нет, а ключ от записи уже у него в руках.
        account.setConfirmed(Boolean.TRUE);
        account.setCreatedAt(OffsetDateTime.now());
        account.setLocale(LocaleContextHolder.getLocale().getLanguage());
        accountRepository.save(account);
        guests.entered(clientAddress);
        log.info("Заведена гостевая запись {}", account.getLogin());
        return open(account);
    }

    /**
     * Гость становится игроком, НЕ ТЕРЯЯ партий — field-backlog, пункт 1 (P0).
     * <p>
     * Прежде путь к регистрации у гостя был один — выйти и завести новую запись, а выход
     * терял гостевую вместе со всем сыгранным: места в партиях (`player.account_id`),
     * автосохранение и «Ваши партии» держатся за идентификатор ЗАПИСИ. Поэтому запись здесь
     * не заводится, а ПРЕВРАЩАЕТСЯ: та же строка получает почту, имя и пароль, и всё, что на
     * неё ссылается, остаётся как было.
     * <p>
     * <b>Гостем запись остаётся до подтверждения почты</b> ({@link #confirm}): адрес ещё
     * ничей, и роль игрока по непроверенной почте значила бы, что регистрацию можно пройти,
     * не имея ящика. Пока письмо не открыто, гость играет как играл — его пропуск живёт, —
     * а войти по почте и паролю нельзя (запись не подтверждена). Ошибся в адресе — просит
     * снова: прежняя ссылка перестаёт годиться, ключ от записи один.
     * <p>
     * Предел регистраций с адреса и задачка — те же, что у {@link #register}: письмо уходит
     * на чужую почту и здесь, и иначе этот путь стал бы обходом обоих.
     */
    @Transactional("historyTransactionManager")
    public RegisterResultDto upgradeGuest(String sessionToken, RegisterRequest request,
                                          String clientAddress) {
        AccountEntity account = require(sessionToken);
        if (account.getRole() != AccountRole.GUEST) {
            throw new ConflictException("auth.notGuest");
        }
        registrations.requireAllowed(clientAddress);
        challenge.require(request.challengeId(), request.challengeAnswer());
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        requirePassword(request.password());
        boolean taken = accountRepository.findByLoginIgnoreCase(email)
                .or(() -> accountRepository.findByEmailIgnoreCase(email))
                .filter(other -> !other.getId().equals(account.getId()))
                .isPresent();
        if (taken) {
            throw new ConflictException("auth.emailTaken");
        }

        account.setLogin(email);
        account.setEmail(email);
        account.setName(request.name().trim());
        account.setPasswordHash(passwords.encode(request.password()));
        // Вход по почте закрыт до ссылки — как у всякой новой регистрации. Пропуск гостя от
        // этого не гаснет: подтверждение спрашивает только вход (см. login).
        account.setConfirmed(Boolean.FALSE);
        accountRepository.save(account);

        RegisterResultDto result = sendConfirmation(account, Boolean.FALSE);
        registrations.registered(clientAddress);
        log.info("Гостевая запись {} ждёт подтверждения почты, чтобы стать игроком", account.getId());
        return result;
    }

    /**
     * Выслать письмо подтверждения заново — п. 3.1.
     * <p>
     * Без этого регистрация оказывалась ловушкой: письмо не дошло (почтовый сервер не
     * настроен, адрес прочитан не тем, ссылка просрочена) — логин занят, вход закрыт, и
     * сделать с этим нельзя ничего. Здесь запись получает <b>новую ссылку с новым
     * сроком</b>, а старая перестаёт годиться: двух ключей от одной записи быть не должно.
     * <p>
     * <b>Пароль спрашивается тот же, что и при входе.</b> Иначе сервер по чужой просьбе
     * слал бы письма на любой зарегистрированный адрес — а это и есть рассылка чужими
     * руками. Ответ поэтому и не скрывает, есть ли такая запись: пароль к ней у того, кто
     * спрашивает, уже есть.
     */
    @Transactional("historyTransactionManager")
    public RegisterResultDto resend(LoginRequest request, String clientAddress) {
        // Пароль спрашивается тот же, значит и подбирать его можно здесь же: счётчик
        // попыток общий с входом, иначе перебор просто переехал бы на этот эндпоинт.
        String name = request.login().trim();
        throttle.requireAllowed(name, clientAddress);
        AccountEntity account = findByLoginOrEmail(name)
                .orElseThrow(() -> {
                    throttle.failed(name, clientAddress);
                    return new ForbiddenException("auth.badCredentials");
                });
        if (!passwords.matches(request.password(), account.getPasswordHash())) {
            throttle.failed(name, clientAddress);
            throw new ForbiddenException("auth.badCredentials");
        }
        throttle.succeeded(name);
        if (Boolean.TRUE.equals(account.getConfirmed())) {
            throw new ConflictException("auth.alreadyConfirmed");
        }

        RegisterResultDto result = sendConfirmation(account, Boolean.TRUE);
        log.info("Письмо подтверждения выслано заново для {}", account.getLogin());
        return result;
    }

    /**
     * Заводит новую ссылку подтверждения и отправляет её письмом.
     * <p>
     * Одно место на регистрацию и на повторную отправку: срок ссылки, её вид и ответ
     * игроку должны быть одинаковыми, откуда бы письмо ни пошло.
     *
     * @param again письмо высылается заново: тогда игроку говорится и о том, что прежняя
     *              ссылка больше не годится, — иначе он открыл бы старое письмо и получил
     *              отказ, не поняв почему
     * @return что сказать игроку: письмо ушло на почту или лежит в исходящих сервера
     */
    private RegisterResultDto sendConfirmation(AccountEntity account, Boolean again) {
        account.setConfirmToken(token());
        account.setConfirmExpiresAt(OffsetDateTime.now().plusHours(properties.confirmHours()));
        accountRepository.save(account);

        String link = properties.clientUrl() + "/?confirm=" + account.getConfirmToken();
        /*
          ПУТЬ К ФАЙЛУ ПИСЬМА ИГРОКУ НЕ ПОКАЗЫВАЕТСЯ.

          Раньше показывался: без настроенной почты сервер отвечал регистрирующемуся
          «письмо легло в исходящие: C:\\stellar-dominion\\...\\mail-outbox\\20260926-...txt».
          Постороннему это бесполезно вдвойне — открыть чужую папку он не может, — а заодно
          рассказывает про устройство каталогов машины, на которой стоит сервер. На
          опубликованной игре это разглашение, и нашлось оно не чтением кода, а проходом
          по пути новичка (26.09.2026).

          Путь никуда не делся: MailService пишет его в журнал сервера, где он и нужен —
          хозяину, а не гостю. Гостю говорится то, что он может сделать: попросить
          администратора подтвердить запись со страницы «Запросы на регистрацию».
        */
        mailService.sendConfirmation(account.getEmail(), account.getLogin(), link);

        String notice = Boolean.TRUE.equals(mailService.sendsForReal())
                ? messages.get("auth.notice.sent", account.getEmail())
                : messages.get("auth.notice.outbox");
        return new RegisterResultDto(account.getLogin(), account.getEmail(),
                mailService.sendsForReal(),
                Boolean.TRUE.equals(again) ? messages.get("auth.notice.resent", notice) : notice);
    }

    /** Запись по логину или почте: вход и повторная отправка ищут её одинаково. */
    private java.util.Optional<AccountEntity> findByLoginOrEmail(String name) {
        return accountRepository.findByLoginIgnoreCase(name)
                .or(() -> accountRepository.findByEmailIgnoreCase(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * Подтверждение почты по ссылке из письма — п. 3.1.
     * <p>
     * Ссылка одноразовая: после перехода она стирается, иначе письмо оставалось бы
     * вечным ключом от записи. Сразу же выдаётся пропуск — игрок пришёл из письма и
     * второй раз вводить пароль ради того же самого не должен.
     */
    @Transactional("historyTransactionManager")
    public SessionDto confirm(String confirmToken) {
        AccountEntity account = accountRepository.findByConfirmToken(confirmToken)
                .orElseThrow(() -> new ForbiddenException("auth.linkInvalid"));
        if (account.getConfirmExpiresAt() != null
                && account.getConfirmExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ForbiddenException("auth.linkExpired");
        }

        account.setConfirmed(Boolean.TRUE);
        account.setConfirmToken(null);
        account.setConfirmExpiresAt(null);
        // Новая почта (пункт 45) становится логином только здесь: адрес проверен ссылкой.
        if (account.getPendingEmail() != null) {
            log.info("Запись {} сменила почту", account.getLogin());
            if (account.getLogin().equalsIgnoreCase(account.getEmail())) {
                account.setLogin(account.getPendingEmail());
            }
            account.setEmail(account.getPendingEmail());
            account.setPendingEmail(null);
        }
        // Гость, назвавший почту (upgradeGuest), становится игроком только здесь: адрес
        // проверен, и запись — со всеми его партиями — теперь обычная.
        if (account.getRole() == AccountRole.GUEST) {
            account.setRole(AccountRole.PLAYER);
            log.info("Гостевая запись {} стала учётной записью игрока", account.getId());
        }
        accountRepository.save(account);
        log.info("Учётная запись {} подтверждена", account.getLogin());
        return open(account);
    }

    /**
     * Вход по логину или почте — п. 3.1.
     * <p>
     * <b>Попытки считаются</b> ({@link LoginThrottle}): выставленный в интернет сервер
     * перебирают словарём, и одного медленного хеша против этого мало. Счётчик
     * спрашивается до проверки пароля — заперев вход, мы отнимаем у перебора и сам ответ
     * «верно или нет», и стоимость хеша.
     * <p>
     * <b>Неудачей считается только неверный пароль или неизвестный логин.</b> Отказ
     * «почта не подтверждена» приходит на верный пароль: подбором он не является, и
     * запирать за него значило бы мешать тому, кто пароль как раз знает.
     *
     * @param clientAddress адрес, с которого пришёл запрос: по нему ловится перебор,
     *                      идущий сразу по многим записям
     */
    @Transactional("historyTransactionManager")
    public SessionDto login(LoginRequest request, String clientAddress) {
        String name = request.login().trim();
        throttle.requireAllowed(name, clientAddress);
        // Не уточняем, что не сошлось: иначе подбор начинался бы с перебора логинов.
        AccountEntity account = findByLoginOrEmail(name)
                .orElseThrow(() -> {
                    throttle.failed(name, clientAddress);
                    return new ForbiddenException("auth.badCredentials");
                });

        if (!passwords.matches(request.password(), account.getPasswordHash())) {
            throttle.failed(name, clientAddress);
            throw new ForbiddenException("auth.badCredentials");
        }
        throttle.succeeded(name);
        if (!Boolean.TRUE.equals(account.getConfirmed())) {
            // Названо прямо, что делать: письмо могло не дойти, и без подсказки игрок
            // остаётся с занятым логином и без ссылки.
            throw new ForbiddenException("auth.notConfirmed");
        }

        // Просроченные пропуска убираются здесь: вход — единственный момент, когда их
        // становится больше, и отдельный планировщик ради этого заводить незачем.
        sessionRepository.deleteExpired(OffsetDateTime.now());
        return open(account);
    }

    /** Выход: пропуск перестаёт действовать сразу, а не по сроку. */
    @Transactional("historyTransactionManager")
    public void logout(String sessionToken) {
        sessionRepository.deleteById(sessionToken);
    }

    /**
     * Кто пришёл с этим пропуском — п. 3.1; это же проверка «игрок авторизован».
     * <p>
     * Спрашивается перед входом в партию: создать игру, присоединиться к чужой и
     * загрузить сохранение может только тот, кто назвал себя.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public AccountEntity require(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new ForbiddenException("auth.sessionRequired");
        }
        AccountSessionEntity session = sessionRepository.findById(sessionToken)
                .orElseThrow(() -> new ForbiddenException("auth.sessionUnknown"));
        if (session.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ForbiddenException("auth.sessionExpired");
        }
        return accountRepository.findById(session.getAccountId())
                .orElseThrow(() -> new ForbiddenException("auth.accountDeleted"));
    }

    /**
     * Пришедший с этим пропуском зарегистрирован, а не гость; иначе отказ —
     * backlog-promo, пункт 1.
     * <p>
     * Спрашивается там, где гость увёл бы себя из гостевой партии: вход в чужую партию и
     * загрузка сохранения. Отказ говорит, что делать, — зарегистрироваться, — а не просто
     * «нельзя»: гость, упёршийся в стену без объяснения, решит, что игра сломалась.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public AccountEntity requireRegistered(String sessionToken) {
        AccountEntity account = require(sessionToken);
        if (account.getRole() == AccountRole.GUEST) {
            throw new ForbiddenException("auth.guestRegister");
        }
        return account;
    }

    /**
     * Пришедший с этим пропуском — администратор; иначе отказ — п. 3.1.
     * <p>
     * Спрашивается там, где действие касается не своей партии, а хозяйства сервера:
     * сохранения лежат общей кучей и хозяина не имеют вовсе, поэтому удалить чужое
     * сохранение может только тот, кто отвечает за сервер. Прежде удаление не спрашивало
     * ничего, и снести чужую партию мог кто угодно.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public AccountEntity requireAdmin(String sessionToken) {
        AccountEntity account = require(sessionToken);
        if (account.getRole() != AccountRole.ADMIN) {
            throw new ForbiddenException("auth.adminOnly");
        }
        return account;
    }

    /**
     * Администратор, модератор или основатель — field-backlog, пункты 47, 70, 101 (решение хозяина
     * проекта 08.10.2026): страницы прибора балансировки — баланс рас и «как сделано» — открыты им, а не
     * всем. Основатель — действующее право {@code FOUNDER} (пакет основателя).
     */
    public AccountEntity requireInsider(String sessionToken) {
        AccountEntity account = require(sessionToken);
        if (!Boolean.TRUE.equals(insider(account))) {
            throw new ForbiddenException("auth.insiderOnly");
        }
        return account;
    }

    /**
     * Основатель — field-backlog, пункт 42: моды (свои наборы справочников) открыты пакету
     * основателя и администратору. Модератору нет: моды — творчество игрока, а не служба.
     */
    public AccountEntity requireFounder(String sessionToken) {
        AccountEntity account = require(sessionToken);
        if (account.getRole() != AccountRole.ADMIN
                && !activeEntitlements(account).contains(EntitlementService.FOUNDER)) {
            throw new ForbiddenException("mods.founderOnly");
        }
        return account;
    }

    /** Открыты ли записи страницы прибора — пункты 47, 70, 101. */
    public Boolean insider(AccountEntity account) {
        return account.getRole() == AccountRole.ADMIN || account.getRole() == AccountRole.MODERATOR
                || activeEntitlements(account).contains(EntitlementService.FOUNDER);
    }

    /**
     * Администратор или модератор — 08.10.2026: служебное, доверенное не только хозяину сервера
     * (подъём партии из файла, пункт 160).
     */
    public AccountEntity requireStaff(String sessionToken) {
        AccountEntity account = require(sessionToken);
        if (account.getRole() != AccountRole.ADMIN && account.getRole() != AccountRole.MODERATOR) {
            throw new ForbiddenException("auth.staffOnly");
        }
        return account;
    }

    /**
     * Назначить или снять модератора — 08.10.2026, администратором. Роль меняется только между
     * игроком и модератором: гостя сперва регистрируют, а администратор в игре один.
     */
    @Transactional("historyTransactionManager")
    public AccountSummaryDto changeRoleByAdmin(String sessionToken, UUID accountId, AccountRole role) {
        requireAdmin(sessionToken);
        if (role != AccountRole.PLAYER && role != AccountRole.MODERATOR) {
            throw new ConflictException("auth.roleNotAssignable");
        }
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("auth.accountNotFound", accountId));
        if (account.getRole() != AccountRole.PLAYER && account.getRole() != AccountRole.MODERATOR) {
            throw new ConflictException("auth.roleNotAssignable");
        }
        account.setRole(role);
        accountRepository.save(account);
        log.info("Администратор назначил записи {} роль {}", account.getLogin(), role);
        return new AccountSummaryDto(account.getId(), account.getLogin(), account.getEmail(),
                account.getName(), account.getRole().name(), messages.get(account.getRole().labelKey()),
                account.getConfirmed(), account.getCreatedAt());
    }

    /** Администратор ли за пропуском; пустой или неверный пропуск — нет. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public Boolean isAdmin(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return Boolean.FALSE;
        }
        try {
            return require(sessionToken).getRole() == AccountRole.ADMIN;
        } catch (com.sddnw.server.web.error.ApiException refused) {
            return Boolean.FALSE;
        }
    }

    /** Учётная запись пропуска для клиента — им же проверяется, жив ли сеанс. */
    /** Языки игры — п. 3.5: тот же список, что у {@code WebConfig.localeResolver}. */
    private static final Set<String> SUPPORTED_LOCALES = Set.of("en", "ru");

    /**
     * Ступени размера текста — п. 11.1: проценты, те же, что у переключателя на клиенте
     * ({@code TEXT_SCALES} в {@code ui/textScale.ts}).
     * <p>
     * Список закрытый, а не «от 100 до 200»: масштаб правит не только кегль, но и высоту
     * панелей, и проверены ступени поштучно — на каждой смотрели, что разметка не рвётся.
     */
    private static final Set<Integer> SUPPORTED_TEXT_SCALES = Set.of(100, 115, 130, 150);

    /**
     * Громкость музыки и эффектов — процент 0…100, ноль — выключено (09.10.2026, ползунки вместо
     * трёх ступеней; прежние ступени перевела миграция 139). Тот же предел у клиента
     * ({@code SOUND_MAX} в {@code audio/soundSettings.ts}).
     */
    private static final int SOUND_MAX = 100;

    /**
     * Сколько пар помещается в раскладке горячих клавиш — п. 11.1.
     * <p>
     * Предел, а не список действий: какие действия у карты есть, знает клиент, а сервер
     * бережёт колонку от того, чтобы в неё сложили словарь. Действий сейчас пять, дюжина
     * даёт запас на годы вперёд и всё равно влезает в 512 символов колонки.
     */
    private static final int MAX_HOTKEYS = 16;

    /**
     * Из чего состоят имя действия и код клавиши — буквы, цифры и ничего больше.
     * <p>
     * Проверяется ВИД, а не смысл: коды клавиш приходят от браузера
     * ({@code KeyboardEvent.code} — {@code KeyT}, {@code F4}, {@code Digit1}), и
     * перечислять их здесь значило бы завести вторую правду о раскладке, которая живёт на
     * клиенте. Сервер же следит за тем, чтобы в колонку не попало ни разметки, ни длинных
     * строк, ни пустоты.
     */
    private static final Pattern HOTKEY_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,23}");

    /** Разбор и сборка раскладки клавиш: в колонке она лежит одной строкой JSON. */
    private final ObjectMapper hotkeysMapper = new ObjectMapper();

    @Transactional(value = "historyTransactionManager", readOnly = true)
    public AccountDto current(String sessionToken) {
        return toDto(require(sessionToken));
    }

    public AccountDto toDto(AccountEntity account) {
        // Права считаются ОДИН раз: список и признак доступа к прибору обязаны сходиться.
        List<String> active = activeEntitlements(account);
        Boolean insider = account.getRole() == AccountRole.ADMIN || account.getRole() == AccountRole.MODERATOR
                || active.contains(EntitlementService.FOUNDER);
        return new AccountDto(account.getId(), account.getLogin(), account.getEmail(),
                account.getName(), account.getRole().name(),
                messages.get(account.getRole().labelKey()), account.getLocale(),
                account.getTextScale(), hotkeysOf(account),
                account.getMusicVolume(), account.getSfxVolume(),
                account.getNoviceHints(), dismissedOf(account), keysOf(account.getQuietWindows()),
                account.getReduceMotion(), account.getBannerGlyphs(), active,
                account.getPendingEmail(), account.getBanner(), account.getPlayerCode(), account.getAdvisor(),
                account.getCreatedAt(), keysOf(account.getSeenScenes()), account.getNotifyMail(), insider);
    }

    /**
     * Письма о ходе — field-backlog, пункты 22 и 113: по согласию, выключено по умолчанию. Без
     * подтверждённой почты согласие не даётся: письму некуда идти, а галочка, которая ничего не
     * делает, хуже её отсутствия. Ключ отписки заводится раз и живёт с записью.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeNotifyMail(String sessionToken, Boolean on) {
        AccountEntity account = require(sessionToken);
        if (Boolean.TRUE.equals(on) && (account.getEmail() == null || account.getEmail().isBlank())) {
            throw new ConflictException("notify.noEmail");
        }
        account.setNotifyMail(Boolean.TRUE.equals(on) ? Boolean.TRUE : null);
        if (account.getNotifyToken() == null) {
            account.setNotifyToken(UUID.randomUUID().toString().replace("-", ""));
        }
        accountRepository.save(account);
        return toDto(account);
    }

    /** Отписка из письма (пункт 22): ключ из ссылки, без входа. Неизвестный ключ — тихо «нет». */
    @Transactional("historyTransactionManager")
    public Boolean unsubscribeMail(String token) {
        if (token == null || token.isBlank()) {
            return Boolean.FALSE;
        }
        return accountRepository.findByNotifyToken(token).map(account -> {
            account.setNotifyMail(null);
            accountRepository.save(account);
            return Boolean.TRUE;
        }).orElse(Boolean.FALSE);
    }

    /**
     * Советник — field-backlog, пункт 23: да, нет или пусто — «по умолчанию», то есть выключен, а в
     * гостевой партии включён. Что советовать, считает сервер; показывать ли — решает клиент.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeAdvisor(String sessionToken, Boolean advisor) {
        AccountEntity account = require(sessionToken);
        account.setAdvisor(advisor);
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Сцены, увиденные целиком, — field-backlog, пункт 163: первое знакомство, первая война,
     * первый совет и первая кража идут сценой, а дальше — строкой с «показать сцену». Что значат
     * ключи, знает клиент, как и с подсказками.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeSeenScenes(String sessionToken, List<String> seen) {
        AccountEntity account = require(sessionToken);
        account.setSeenScenes(keysJson(seen));
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Игровой псевдоним по умолчанию — личный кабинет: им новая партия подписывает игрока, а в
     * самой партии имя по-прежнему можно сменить при входе. Гостю псевдоним — номер: менять его
     * незачем, запись гостевая.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeName(String sessionToken, String name) {
        AccountEntity account = requireRegistered(sessionToken);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 40) {
            throw new BadRequestException("auth.nameBad");
        }
        account.setName(trimmed);
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Своё знамя — field-backlog, пункт 144: цвет, выбранный раз за разом, становится «моим», и
     * новая партия предлагает его первым, если он свободен. Годны только знамёна игры
     * ({@code BannerColor}).
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeBanner(String sessionToken, String banner) {
        AccountEntity account = require(sessionToken);
        if (banner != null && java.util.Arrays.stream(com.sddnw.server.domain.enums.BannerColor.values())
                .noneMatch(color -> color.name().equals(banner))) {
            throw new BadRequestException("auth.bannerBad");
        }
        account.setBanner(banner);
        accountRepository.save(account);
        return toDto(account);
    }

    /** Действующие права записи — видами (backlog-promo, пункт 41). */
    private List<String> activeEntitlements(AccountEntity account) {
        OffsetDateTime now = OffsetDateTime.now();
        return entitlements.findAllByAccountIdOrderByStartsAtDesc(account.getId()).stream()
                .filter(row -> row.activeAt(now))
                .map(com.sddnw.server.domain.entity.history.AccountEntitlementEntity::getKind)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Попросить ссылку сброса пароля — backlog-promo, пункт 45.
     * <p>
     * <b>Ответ один и тот же, есть такая запись или нет</b>: иначе форма сброса стала бы
     * справочной «зарегистрирован ли этот адрес». И <b>просьб с адреса — счётное число</b>
     * ({@link PasswordResetThrottle}): каждая шлёт письмо, и без предела сервер стал бы рассылкой
     * чужими руками — тем же доводом, что предел регистраций.
     */
    @Transactional("historyTransactionManager")
    public String requestReset(String email, String clientAddress) {
        resets.requireAllowed(clientAddress);
        resets.spend(clientAddress);
        String address = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        accountRepository.findByEmailIgnoreCase(address)
                .filter(account -> account.getRole() != AccountRole.GUEST && Boolean.TRUE.equals(account.getConfirmed()))
                .ifPresent(account -> {
                    account.setResetToken(token());
                    account.setResetExpiresAt(OffsetDateTime.now().plusMinutes(RESET_MINUTES));
                    accountRepository.save(account);
                    mailService.sendReset(account.getEmail(), account.getName() == null ? account.getLogin() : account.getName(),
                            properties.clientUrl() + "/?reset=" + account.getResetToken(), RESET_MINUTES);
                    log.info("Ссылка сброса пароля выслана записи {}", account.getLogin());
                });
        return messages.get(Boolean.TRUE.equals(mailService.sendsForReal()) ? "auth.reset.sent" : "auth.reset.outbox");
    }

    /**
     * Сбросить пароль по ссылке — пункт 45: ссылка одноразовая и живёт час. Все прежние пропуска
     * записи гаснут — сброс просят, когда пароль мог попасть к чужому, — и сразу выдаётся новый.
     */
    @Transactional("historyTransactionManager")
    public SessionDto resetPassword(String resetToken, String newPassword) {
        AccountEntity account = accountRepository.findByResetToken(resetToken == null ? "" : resetToken)
                .orElseThrow(() -> new ForbiddenException("auth.linkInvalid"));
        if (account.getResetExpiresAt() == null || account.getResetExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new ForbiddenException("auth.linkExpired");
        }
        requirePassword(newPassword);
        account.setPasswordHash(passwords.encode(newPassword));
        account.setResetToken(null);
        account.setResetExpiresAt(null);
        accountRepository.save(account);
        sessionRepository.deleteByAccountId(account.getId());
        log.info("Запись {} сбросила пароль по ссылке", account.getLogin());
        return open(account);
    }

    /** Сменить пароль из настроек — пункт 45: прежний спрашивается, прочие пропуска гаснут. */
    @Transactional("historyTransactionManager")
    public void changePassword(String sessionToken, String oldPassword, String newPassword) {
        AccountEntity account = requireOwnPassword(sessionToken, oldPassword);
        requirePassword(newPassword);
        account.setPasswordHash(passwords.encode(newPassword));
        accountRepository.save(account);
        sessionRepository.deleteOthers(account.getId(), sessionToken);
        log.info("Запись {} сменила пароль", account.getLogin());
    }

    /**
     * Сменить почту — пункт 45: новая становится логином только после перехода по ссылке из
     * письма на неё ({@link #confirm}); до того вход по-прежнему по старой.
     */
    @Transactional("historyTransactionManager")
    public RegisterResultDto changeEmail(String sessionToken, String password, String newEmail) {
        AccountEntity account = requireOwnPassword(sessionToken, password);
        String address = newEmail == null ? "" : newEmail.trim().toLowerCase(Locale.ROOT);
        if (!address.contains("@") || address.length() > 254) {
            throw new BadRequestException("auth.emailInvalid");
        }
        if (accountRepository.findByEmailIgnoreCase(address).isPresent()) {
            throw new ConflictException("auth.emailTaken");
        }
        account.setPendingEmail(address);
        account.setConfirmToken(token());
        account.setConfirmExpiresAt(OffsetDateTime.now().plusHours(properties.confirmHours()));
        accountRepository.save(account);
        mailService.sendConfirmation(address, account.getName() == null ? account.getLogin() : account.getName(),
                properties.clientUrl() + "/?confirm=" + account.getConfirmToken());
        String notice = Boolean.TRUE.equals(mailService.sendsForReal())
                ? messages.get("auth.notice.sent", address)
                : messages.get("auth.notice.outbox");
        return new RegisterResultDto(account.getLogin(), address, mailService.sendsForReal(), notice);
    }

    /**
     * Удалить свою запись — пункт 45: этого требуют правила о персональных данных во многих
     * странах (пункт 39). Тем же путём, что удаление администратором, — сыгранное в партиях не
     * трогается, — но с паролем и не для единственного администратора.
     */
    @Transactional("historyTransactionManager")
    public void deleteSelf(String sessionToken, String password) {
        AccountEntity account = requireOwnPassword(sessionToken, password);
        if (account.getRole() == AccountRole.ADMIN) {
            throw new ConflictException("auth.selfDelete");
        }
        removeAccount(account);
        log.info("Запись {} удалена самим игроком", account.getLogin());
    }

    /** Запись по пропуску, если назван её пароль; гостю пароля спросить не с чего. */
    private AccountEntity requireOwnPassword(String sessionToken, String password) {
        AccountEntity account = require(sessionToken);
        if (account.getRole() == AccountRole.GUEST || account.getPasswordHash() == null) {
            throw new ConflictException("auth.guestNoPassword");
        }
        if (password == null || !passwords.matches(password, account.getPasswordHash())) {
            throw new ForbiddenException("auth.wrongPassword");
        }
        return account;
    }

    /**
     * Раскладка клавиш записи — п. 11.1: из колонки её отдаём разобранной, парами.
     * <p>
     * Пусто и неразобранное — одно и то же: {@code null}, то есть «игрок клавиш не
     * переназначал», и клиент берёт раскладку по умолчанию. Испорченная строка в колонке
     * НЕ РОНЯЕТ вход: запись читается при каждом запросе с пропуском, и отказ из-за
     * настройки вида запер бы игроку игру целиком.
     */
    private Map<String, String> hotkeysOf(AccountEntity account) {
        String stored = account.getHotkeys();
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return hotkeysMapper.readValue(stored, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception cause) {
            log.warn("Раскладка клавиш записи {} не разобралась, отдаём пустую: {}",
                    account.getId(), cause.getMessage());
            return null;
        }
    }

    /** Отклонённые подсказки записи; испорченная строка — пусто, вход она не роняет. */
    private List<String> dismissedOf(AccountEntity account) {
        return keysOf(account.getDismissedHints());
    }

    /** Список ключей из колонки JSON; испорченная строка — пусто, вход она не роняет. */
    private List<String> keysOf(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        try {
            return hotkeysMapper.readValue(stored, new TypeReference<List<String>>() { });
        } catch (Exception cause) {
            log.warn("Список ключей записи не разобрался, отдаём пустой: {}", cause.getMessage());
            return List.of();
        }
    }

    /** Сколько сцен помнится увиденными (пункт 163): сцен в игре меньше десятка. */
    static final int MAX_SEEN_SCENES = 20;

    /** Список ключей в колонку JSON — с проверкой вида, как у подсказок; пустой — пусто. */
    private String keysJson(List<String> keys) {
        if (keys == null || keys.size() > MAX_SEEN_SCENES
                || keys.stream().anyMatch(key -> key == null || !HINT_KEY.matcher(key).matches())) {
            throw new BadRequestException("auth.hintsBad");
        }
        try {
            return keys.isEmpty() ? null : hotkeysMapper.writeValueAsString(keys.stream().distinct().toList());
        } catch (Exception cause) {
            throw new BadRequestException("auth.hintsBad");
        }
    }

    /** Сколько подсказок можно отклонить: застава от того, чтобы в колонку сложили словарь. */
    static final int MAX_DISMISSED_HINTS = 100;

    /** Ключ подсказки: буквы, цифры, точка, дефис и подчёркивание. */
    private static final Pattern HINT_KEY = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /**
     * Запоминает подсказки — field-backlog, пункты 30 и 130: нужны ли они новичку и какие
     * отклонены насовсем. Живут в записи по тому же доводу, что язык и клавиши: отклонённый
     * на одной машине совет не должен возвращаться на другой. Проверяется вид, не смысл.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeHints(String sessionToken, Boolean noviceHints, List<String> dismissed) {
        AccountEntity account = require(sessionToken);
        if (dismissed.size() > MAX_DISMISSED_HINTS
                || dismissed.stream().anyMatch(key -> key == null || !HINT_KEY.matcher(key).matches())) {
            throw new BadRequestException("auth.hintsBad");
        }
        account.setNoviceHints(noviceHints);
        try {
            account.setDismissedHints(dismissed.isEmpty() ? null
                    : hotkeysMapper.writeValueAsString(dismissed.stream().distinct().toList()));
        } catch (Exception cause) {
            throw new BadRequestException("auth.hintsBad");
        }
        accountRepository.save(account);
        return toDto(account);
    }

    /** Сколько окон можно приглушить: окон, открывающихся сами, в игре меньше десятка. */
    static final int MAX_QUIET_WINDOWS = 20;

    /**
     * Запоминает окна, которые НЕ открываются сами, — field-backlog, пункт 164: разведанная
     * система, наука после прорыва, сцены. По умолчанию открываются все — для новичка это и
     * есть обучение, — а приглушённое живёт в записи, как клавиши. Какие окна бывают, знает
     * клиент; сервер проверяет только вид ключей.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeQuietWindows(String sessionToken, List<String> quiet) {
        AccountEntity account = require(sessionToken);
        if (quiet.size() > MAX_QUIET_WINDOWS
                || quiet.stream().anyMatch(key -> key == null || !HINT_KEY.matcher(key).matches())) {
            throw new BadRequestException("auth.windowsBad");
        }
        try {
            account.setQuietWindows(quiet.isEmpty() ? null
                    : hotkeysMapper.writeValueAsString(quiet.stream().distinct().toList()));
        } catch (Exception cause) {
            throw new BadRequestException("auth.windowsBad");
        }
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Запоминает, рисовать ли знаки знамён у хозяев на карте, — field-backlog, пункт 202.
     * Особая возможность: по умолчанию выключена, а включённая едет за игроком с машины на машину.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeGlyphs(String sessionToken, Boolean bannerGlyphs) {
        AccountEntity account = require(sessionToken);
        account.setBannerGlyphs(Boolean.TRUE.equals(bannerGlyphs) ? Boolean.TRUE : null);
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Запоминает, убирать ли движение, — field-backlog, пункт 170: да, нет или пусто — «как в
     * системе». Живёт в записи по тому же доводу, что размер текста: настройка доступности,
     * потерянная на второй машине, — это снова мигрень от вращающейся галактики.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeMotion(String sessionToken, Boolean reduceMotion) {
        AccountEntity account = require(sessionToken);
        account.setReduceMotion(reduceMotion);
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Запоминает язык игрока — п. 3.5.
     * <p>
     * Зовётся переключателем языка, а не настройками: язык меняют одним нажатием, и
     * отдельного экрана у него нет. Неизвестный язык не пишется вовсе: игра знает два, и
     * запись «de» значила бы для следующего входа английский с видом настроенного.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeLocale(String sessionToken, String locale) {
        AccountEntity account = require(sessionToken);
        if (!SUPPORTED_LOCALES.contains(locale)) {
            throw new BadRequestException("auth.localeUnknown", locale);
        }
        account.setLocale(locale);
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Запоминает размер текста интерфейса — п. 11.1.
     * <p>
     * Живёт рядом с языком и по той же причине: настройка из {@code localStorage} теряется
     * на второй машине, а размер текста игрок выбирает один раз и навсегда. Неизвестная
     * ступень — отказ 400: список закрыт, и запись «180» выглядела бы настройкой, которой
     * интерфейс не подчиняется.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeTextScale(String sessionToken, Integer textScale) {
        AccountEntity account = require(sessionToken);
        if (!SUPPORTED_TEXT_SCALES.contains(textScale)) {
            throw new BadRequestException("auth.textScaleUnknown", String.valueOf(textScale));
        }
        account.setTextScale(textScale);
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Запоминает громкость музыки и звуковых эффектов.
     * <p>
     * Четвёртая настройка вида в записи, по тому же доводу: выключенный звук должен остаться
     * выключенным на любой машине. Громкость вне 0…100 — отказ 400, как у размера текста.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeSound(String sessionToken, Integer musicVolume, Integer sfxVolume) {
        AccountEntity account = require(sessionToken);
        if (musicVolume < 0 || musicVolume > SOUND_MAX || sfxVolume < 0 || sfxVolume > SOUND_MAX) {
            throw new BadRequestException("auth.soundUnknown", musicVolume + "/" + sfxVolume);
        }
        account.setMusicVolume(musicVolume);
        account.setSfxVolume(sfxVolume);
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Запоминает раскладку горячих клавиш карты — п. 11.1.
     * <p>
     * Третья настройка вида, которая живёт в записи, и по тому же доводу, что язык с
     * размером текста: переназначенные клавиши помнят пальцами, а сев за другую машину,
     * игрок получал бы стандартные.
     * <p>
     * <b>Проверяется вид, а не смысл.</b> Сервер по горячей клавише не делает ничего:
     * какие у карты действия и какие клавиши им годятся — дело клиента
     * ({@code ui/hotkeys.ts}). Здесь стоит лишь застава от того, чтобы в колонку сложили
     * словарь или разметку: пар не больше {@link #MAX_HOTKEYS}, имена и коды — буквы и
     * цифры ({@link #HOTKEY_WORD}).
     * <p>
     * <b>Пустая раскладка — это «как было»</b>: колонка очищается, и клиент берёт свою
     * раскладку по умолчанию. Так работает кнопка «вернуть как было», и так же запись
     * выглядит у того, кто клавиш не трогал, — два способа сказать одно породили бы
     * вопрос, какой из них правдивее.
     */
    @Transactional("historyTransactionManager")
    public AccountDto changeHotkeys(String sessionToken, Map<String, String> hotkeys) {
        AccountEntity account = require(sessionToken);
        if (hotkeys.size() > MAX_HOTKEYS) {
            throw new BadRequestException("auth.hotkeysTooMany", String.valueOf(MAX_HOTKEYS));
        }
        Map<String, String> clean = new LinkedHashMap<>();
        hotkeys.forEach((action, code) -> {
            if (action == null || code == null
                    || !HOTKEY_WORD.matcher(action).matches()
                    || !HOTKEY_WORD.matcher(code).matches()) {
                throw new BadRequestException("auth.hotkeyBad", String.valueOf(action), String.valueOf(code));
            }
            clean.put(action, code);
        });
        try {
            account.setHotkeys(clean.isEmpty() ? null : hotkeysMapper.writeValueAsString(clean));
        } catch (Exception cause) {
            throw new BadRequestException("auth.hotkeyBad", "-", cause.getMessage());
        }
        accountRepository.save(account);
        return toDto(account);
    }

    /**
     * Все учётные записи — п. 3.1: их видит только администратор.
     * <p>
     * Нужен списку записей: подтвердить чужую регистрацию нельзя, не увидев, какая из них
     * застряла. Ни пароля, ни ссылки подтверждения наружу не уходит — только то, что
     * видно в списке.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<AccountSummaryDto> accounts(String sessionToken) {
        requireAdmin(sessionToken);
        return accountRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(account -> new AccountSummaryDto(
                        account.getId(),
                        account.getLogin(),
                        account.getEmail(),
                        account.getName(),
                        account.getRole().name(),
                        messages.get(account.getRole().labelKey()),
                        account.getConfirmed(),
                        account.getCreatedAt()))
                .toList();
    }

    /**
     * Подтверждение записи администратором — п. 3.1.
     * <p>
     * Последний ключ от застрявшей регистрации: письмо могло не дойти вовсе (почтовый
     * сервер не настроен, адрес закрыт, письмо съел спам-фильтр), и тогда игрок остаётся
     * с занятой почтой и закрытым входом. Администратор отвечает за сервер — ему и
     * решать, впускать ли.
     * <p>
     * Ссылка при этом гасится: подтверждать второй раз нечего, а живой ключ от чужой
     * записи оставлять незачем.
     */
    @Transactional("historyTransactionManager")
    public AccountSummaryDto confirmByAdmin(String sessionToken, UUID accountId) {
        requireAdmin(sessionToken);
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("auth.accountNotFound", accountId));
        if (Boolean.TRUE.equals(account.getConfirmed())) {
            throw new ConflictException("auth.accountAlreadyConfirmed", account.getLogin());
        }

        account.setConfirmed(Boolean.TRUE);
        account.setConfirmToken(null);
        account.setConfirmExpiresAt(null);
        // Гость, назвавший почту (upgradeGuest), — тем же правилом, что и по ссылке: адрес
        // за него подтвердил администратор, и запись становится обычной.
        if (account.getRole() == AccountRole.GUEST) {
            account.setRole(AccountRole.PLAYER);
        }
        accountRepository.save(account);
        log.info("Администратор подтвердил учётную запись {}", account.getLogin());

        return new AccountSummaryDto(account.getId(), account.getLogin(), account.getEmail(),
                account.getName(), account.getRole().name(), messages.get(account.getRole().labelKey()),
                account.getConfirmed(), account.getCreatedAt());
    }

    /**
     * Удаление учётной записи администратором — п. 3.1.
     * <p>
     * <b>Зачем.</b> Заявок накапливается больше, чем игроков: брошенные регистрации,
     * опечатки в адресе, следы прогонов. Подтверждать их незачем, а висеть в очереди они
     * будут вечно — занятая почта не освобождается сама.
     * <p>
     * <b>Сыгранное при этом не пропадает.</b> Запись ни на что в партии не ссылается:
     * раса, колонии и флот принадлежат игроку внутри партии, а не человеку между
     * партиями. Поэтому удаление записи не трогает ни партий, ни сохранений — оно
     * освобождает почту и убирает строку из списка.
     * <p>
     * <b>Себя удалить нельзя.</b> Администратор в игре один, и удаливший себя запирает
     * сервер: подтверждать чужие регистрации станет некому, а новая запись администратора
     * заводится только при следующем запуске и с новым паролем.
     * <p>
     * Сеансы удалённой записи гасятся здесь же: пропуск живёт в своей таблице и пережил бы
     * запись, оставшись действующим ключом от того, чего уже нет.
     */
    @Transactional("historyTransactionManager")
    public void deleteByAdmin(String sessionToken, UUID accountId) {
        AccountEntity admin = requireAdmin(sessionToken);
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("auth.accountNotFound", accountId));
        if (admin.getId().equals(account.getId())) {
            throw new ConflictException("auth.selfDelete");
        }

        removeAccount(account);
        log.info("Администратор удалил учётную запись {}", account.getLogin());
    }

    /** Удаление записи — одно на администратора и на самого игрока (пункт 45). */
    private void removeAccount(AccountEntity account) {
        sessionRepository.deleteByAccountId(account.getId());
        // Шаблоны стройки висят на записи без внешнего ключа (миграция 079): межбазового
        // ключа не бывает, значит и каскада нет — убираем их сами, иначе они пережили бы
        // хозяина и остались бы в базе ничьими.
        buildTemplateRepository.deleteByAccountId(account.getId());
        shipTemplateRepository.deleteByAccountId(account.getId());
        raceTemplateRepository.deleteByAccountId(account.getId());
        pushSubscriptions.deleteByAccountId(account.getId());
        // Шаги воронки (миграция 097) висят на записи так же — без ключа; ушла запись —
        // уходит и её след.
        funnelSteps.deleteByAccountId(account.getId());
        problemReports.deleteByAccountId(account.getId());
        // Права записи (пункт 41) уходят вместе с ней: за удалённой записью их некому держать.
        entitlements.deleteByAccountId(account.getId());
        // История партий (пункт 18) — тоже данные записи: ушла запись — её партии не идут ни в
        // долю среди похожих, ни в рейтинг соперников (пункты 19, 117).
        if (gameRecords != null) {
            gameRecords.deleteByAccountId(account.getId());
        }
        accountRepository.delete(account);
    }

    /** Заводит сеанс: новый пропуск со сроком из настроек. */
    private SessionDto open(AccountEntity account) {
        AccountSessionEntity session = new AccountSessionEntity();
        session.setToken(token());
        session.setAccountId(account.getId());
        session.setCreatedAt(OffsetDateTime.now());
        session.setExpiresAt(OffsetDateTime.now().plusDays(properties.sessionDays()));
        sessionRepository.save(session);
        return new SessionDto(session.getToken(), session.getExpiresAt(), toDto(account));
    }

    private void requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new ConflictException("auth.passwordShort", MIN_PASSWORD_LENGTH);
        }
    }

    /**
     * Случайная строка для пропуска и ссылки подтверждения.
     * <p>
     * 32 байта из {@link SecureRandom} в виде base64 без набивки: угадать такую нельзя, а
     * в адрес ссылки она помещается без экранирования.
     */
    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
