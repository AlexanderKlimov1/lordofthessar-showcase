package com.sddnw.server.web;

import com.sddnw.server.dto.AccountDto;
import com.sddnw.server.dto.AccountSummaryDto;
import com.sddnw.server.dto.ChallengeDto;
import com.sddnw.server.dto.HotkeysRequest;
import com.sddnw.server.dto.LocaleRequest;
import com.sddnw.server.dto.LoginRequest;
import com.sddnw.server.dto.RegisterRequest;
import com.sddnw.server.dto.RegisterResultDto;
import com.sddnw.server.dto.SessionDto;
import com.sddnw.server.dto.SoundRequest;
import com.sddnw.server.dto.TextScaleRequest;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.RegistrationChallenge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Вход в игру — п. 3.1: регистрация, подтверждение почты, вход и выход.
 * <p>
 * Единственные эндпоинты, которым пропуск учётной записи не нужен: без них его негде
 * взять. Всё, что заводит партию, спрашивает его — см. {@code GameController}.
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AccountService accountService;
    private final RegistrationChallenge registrationChallenge;
    private final com.sddnw.server.service.EntitlementService entitlementService;

    public AuthController(AccountService accountService,
                          RegistrationChallenge registrationChallenge,
                          com.sddnw.server.service.EntitlementService entitlementService) {
        this.entitlementService = entitlementService;
        this.accountService = accountService;
        this.registrationChallenge = registrationChallenge;
    }

    /**
     * Задачка перед регистрацией — п. 3.1: «докажите, что вы человек».
     * <p>
     * Спрашивается формой регистрации перед отправкой заявки. Ответ на вопрос остаётся у
     * сервера, а вопрос одноразовый: решённая задачка открывает дорогу одной заявке, а не
     * тысяче. Когда задачка выключена настройкой, эндпоинт отвечает пустотой, и форма
     * поля ответа не показывает.
     */
    @GetMapping("/challenge")
    public ChallengeDto challenge() {
        return Boolean.TRUE.equals(registrationChallenge.enabled())
                ? registrationChallenge.issue()
                : null;
    }

    /** Регистрация: запись заводится, ссылка подтверждения уходит на почту — п. 3.1. */
    @PostMapping("/register")
    public RegisterResultDto register(@Valid @RequestBody RegisterRequest request,
                                      HttpServletRequest http) {
        return accountService.register(request, clientAddress(http));
    }

    /**
     * Выслать письмо подтверждения заново — п. 3.1.
     * <p>
     * Спрашивает те же логин и пароль, что и вход: иначе сервер слал бы письма на чужие
     * адреса по чужой просьбе. Старая ссылка после этого не годится — ключ от записи один.
     */
    @PostMapping("/resend")
    public RegisterResultDto resend(@Valid @RequestBody LoginRequest request,
                                    HttpServletRequest http) {
        return accountService.resend(request, clientAddress(http));
    }

    /**
     * Подтверждение почты по ссылке из письма — п. 3.1.
     * <p>
     * Ссылка ведёт на клиент ({@code /?confirm=…}), а он уже зовёт этот эндпоинт: в ответ
     * приходит готовый пропуск, и игрок из письма попадает сразу в игру.
     */
    @PostMapping("/confirm")
    public SessionDto confirm(@RequestParam String token) {
        return accountService.confirm(token);
    }

    /**
     * Гостевой вход — backlog-promo, пункт 1: партия без регистрации, одним нажатием.
     * <p>
     * В ответ — обычный пропуск, как после входа: дальше гость идёт тем же путём, что и
     * зарегистрированный, только партия у него одна и её настройки назначает сервер
     * ({@code GuestGameRules}).
     */
    @PostMapping("/guest")
    public SessionDto guest(HttpServletRequest http) {
        return accountService.guest(clientAddress(http));
    }

    /**
     * Гость регистрируется, не теряя партий, — field-backlog, пункт 1: та же запись получает
     * почту и пароль, а игроком становится по ссылке из письма.
     */
    @PostMapping("/guest/register")
    public RegisterResultDto upgradeGuest(@Valid @RequestBody RegisterRequest request,
                                          @AccountToken String accountToken,
                                          HttpServletRequest http) {
        return accountService.upgradeGuest(accountToken, request, clientAddress(http));
    }

    /** Попросить ссылку сброса пароля — backlog-promo, пункт 45; ответ один, есть запись или нет. */
    @PostMapping("/reset/request")
    public java.util.Map<String, String> requestReset(@Valid @RequestBody com.sddnw.server.dto.PasswordResetRequest request,
                                                      HttpServletRequest http) {
        return java.util.Map.of("notice", accountService.requestReset(request.email(), clientAddress(http)));
    }

    /** Сбросить пароль по ссылке из письма — пункт 45; отвечает новым пропуском. */
    @PostMapping("/reset")
    public SessionDto reset(@Valid @RequestBody com.sddnw.server.dto.PasswordResetRequest request) {
        return accountService.resetPassword(request.token(), request.newPassword());
    }

    /** Сменить пароль из настроек — пункт 45. */
    @PostMapping("/password")
    public void changePassword(@AccountToken String accountToken,
                               @Valid @RequestBody com.sddnw.server.dto.PasswordResetRequest request) {
        accountService.changePassword(accountToken, request.password(), request.newPassword());
    }

    /** Сменить почту — пункт 45: новая становится логином после ссылки из письма на неё. */
    @PostMapping("/email")
    public RegisterResultDto changeEmail(@AccountToken String accountToken,
                                         @Valid @RequestBody com.sddnw.server.dto.PasswordResetRequest request) {
        return accountService.changeEmail(accountToken, request.password(), request.email());
    }

    /** Удалить свою запись — пункт 45, с паролем. */
    @PostMapping("/delete")
    public void deleteSelf(@AccountToken String accountToken,
                           @Valid @RequestBody com.sddnw.server.dto.PasswordResetRequest request) {
        accountService.deleteSelf(accountToken, request.password());
    }

    /** Права записи со всей историей — администратору (backlog-promo, пункт 41). */
    @GetMapping("/accounts/{accountId}/entitlements")
    public java.util.List<com.sddnw.server.dto.EntitlementDto> entitlements(@AccountToken String accountToken,
                                                                             @PathVariable UUID accountId) {
        return entitlementService.list(accountToken, accountId);
    }

    /** Выдать право руками — администратору (пункт 41). */
    @PostMapping("/accounts/{accountId}/entitlements")
    public com.sddnw.server.dto.EntitlementDto grant(@AccountToken String accountToken, @PathVariable UUID accountId,
                                                     @Valid @RequestBody com.sddnw.server.dto.GrantEntitlementRequest request) {
        return entitlementService.grant(accountToken, accountId, request.kind(), request.days(), request.note());
    }

    /** Отозвать право — администратору (пункт 41): строка остаётся, отмечается отзыв. */
    @DeleteMapping("/entitlements/{entitlementId}")
    public com.sddnw.server.dto.EntitlementDto revoke(@AccountToken String accountToken,
                                                      @PathVariable UUID entitlementId) {
        return entitlementService.revoke(accountToken, entitlementId);
    }

    /** Вход по логину или почте — п. 3.1. */
    @PostMapping("/login")
    public SessionDto login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return accountService.login(request, clientAddress(http));
    }

    /**
     * Адрес, с которого пришёл запрос, — для счётчика попыток входа
     * ({@code LoginThrottle}).
     * <p>
     * <b>Берётся адрес соединения, а не заголовок {@code X-Forwarded-For}:</b> заголовок
     * подделывается одной строкой, и защита от перебора, доверяющая ему, снимается тем же
     * перебором — каждой попытке свой выдуманный адрес. За обратным прокси адрес
     * подставляет сам Spring, если ему разрешить: {@code server.forward-headers-strategy}
     * в настройках. Тогда заголовку доверяет уже прокси, а не кто угодно из интернета.
     */
    private String clientAddress(HttpServletRequest http) {
        String address = http.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }

    /**
     * Список учётных записей — п. 3.1: только администратору.
     * <p>
     * Нужен подтверждению чужой регистрации: не увидев списка, застрявшую запись не
     * найти. Пароля и ссылки подтверждения в ответе нет.
     */
    @GetMapping("/accounts")
    public List<AccountSummaryDto> accounts(@AccountToken String accountToken) {
        return accountService.accounts(accountToken);
    }

    /**
     * Подтверждение чужой записи администратором — п. 3.1.
     * <p>
     * Последний ключ от застрявшей регистрации: письмо могло не дойти вовсе, и тогда
     * игрок остаётся с занятой почтой и закрытым входом.
     */
    @PostMapping("/accounts/{accountId}/confirm")
    public AccountSummaryDto confirmAccount(@PathVariable UUID accountId,
                                            @AccountToken String accountToken) {
        return accountService.confirmByAdmin(accountToken, accountId);
    }

    /**
     * Удаление учётной записи администратором — п. 3.1.
     * <p>
     * Брошенные заявки и опечатки в адресе иначе висят в очереди вечно, а занятая почта
     * не освобождается сама. Сыгранное при этом остаётся: запись ни на что в партии не
     * ссылается. Свою запись удалить нельзя — см. {@code AccountService.deleteByAdmin}.
     */
    /** Назначить или снять модератора — 08.10.2026, администратором. */
    @org.springframework.web.bind.annotation.PutMapping("/accounts/{accountId}/role")
    public AccountSummaryDto changeRole(@PathVariable UUID accountId, @AccountToken String accountToken,
                                        @RequestBody java.util.Map<String, String> request) {
        com.sddnw.server.domain.enums.AccountRole role;
        try {
            role = com.sddnw.server.domain.enums.AccountRole.valueOf(request.getOrDefault("role", ""));
        } catch (IllegalArgumentException unknown) {
            throw new com.sddnw.server.web.error.ConflictException("auth.roleNotAssignable");
        }
        return accountService.changeRoleByAdmin(accountToken, accountId, role);
    }

    @DeleteMapping("/accounts/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@PathVariable UUID accountId, @AccountToken String accountToken) {
        accountService.deleteByAdmin(accountToken, accountId);
    }

    /** Выход: пропуск перестаёт действовать сразу. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AccountToken String accountToken) {
        accountService.logout(accountToken);
    }

    /** Кто вошёл — этим же клиент проверяет при запуске, жив ли сохранённый пропуск. */
    @GetMapping("/me")
    public AccountDto me(@AccountToken String accountToken) {
        return accountService.current(accountToken);
    }

    /**
     * Запомнить язык игрока — п. 3.5.
     * <p>
     * Зовётся переключателем языка сразу за переключением: язык меняют одним нажатием, и
     * экрана настроек у игры нет. Неизвестный язык — отказ 400: игра знает два, и запись
     * третьего выглядела бы настройкой, которая ничего не делает.
     */
    @PutMapping("/locale")
    public AccountDto locale(@Valid @RequestBody LocaleRequest request,
                             @AccountToken String accountToken) {
        return accountService.changeLocale(accountToken, request.locale());
    }

    /**
     * Запомнить размер текста интерфейса — п. 11.1.
     * <p>
     * Свой адрес, а не общий «настройки»: настроек ровно две, обе меняются одним нажатием
     * переключателя, и общий адрес заставил бы клиент присылать язык всякий раз, когда
     * игрок трогает размер.
     */
    @PutMapping("/text-scale")
    public AccountDto textScale(@Valid @RequestBody TextScaleRequest request,
                                @AccountToken String accountToken) {
        return accountService.changeTextScale(accountToken, request.textScale());
    }

    /**
     * Запомнить громкость музыки и звуковых эффектов: обе ступени разом — переключатели
     * стоят рядом, и настройка у них одна.
     */
    @PutMapping("/sound")
    public AccountDto sound(@Valid @RequestBody SoundRequest request,
                            @AccountToken String accountToken) {
        return accountService.changeSound(accountToken, request.musicVolume(), request.sfxVolume());
    }

    /**
     * Запомнить раскладку горячих клавиш карты — п. 11.1.
     * <p>
     * Уходит она ЦЕЛИКОМ, с экрана настроек: раскладку правят разом и сохраняют одним
     * нажатием. Пустая раскладка значит «как было» — колонка очищается, и клиент берёт
     * свою раскладку по умолчанию.
     */
    @PutMapping("/hotkeys")
    public AccountDto hotkeys(@Valid @RequestBody HotkeysRequest request,
                              @AccountToken String accountToken) {
        return accountService.changeHotkeys(accountToken, request.hotkeys());
    }

    /**
     * Запомнить подсказки — field-backlog, пункты 30 и 130: нужны ли подсказки новичку и какие
     * отклонены насовсем. Уходят целиком; пустой список — «вернуть все подсказки».
     */
    @PutMapping("/hints")
    public AccountDto hints(@Valid @RequestBody com.sddnw.server.dto.HintsRequest request,
                            @AccountToken String accountToken) {
        return accountService.changeHints(accountToken, request.noviceHints(), request.dismissedHints());
    }

    /**
     * Окна, которые не открываются сами, — field-backlog, пункт 164. Уходят целиком; пустой
     * список — «открывать все».
     */
    @PutMapping("/windows")
    public AccountDto windows(@Valid @RequestBody com.sddnw.server.dto.QuietWindowsRequest request,
                              @AccountToken String accountToken) {
        return accountService.changeQuietWindows(accountToken, request.quietWindows());
    }

    /** Своё знамя — field-backlog, пункт 144: предлагается первым в новой партии, если свободно. */
    @PutMapping("/banner")
    public AccountDto banner(@RequestBody com.sddnw.server.dto.BannerRequest request,
                             @AccountToken String accountToken) {
        return accountService.changeBanner(accountToken, request.banner());
    }

    /** Знаки знамён у хозяев на карте — field-backlog, пункт 202. */
    @PutMapping("/glyphs")
    public AccountDto glyphs(@RequestBody com.sddnw.server.dto.GlyphsRequest request,
                             @AccountToken String accountToken) {
        return accountService.changeGlyphs(accountToken, request.bannerGlyphs());
    }

    /** Меньше движения — field-backlog, пункт 170: пусто — как в системе. */
    @PutMapping("/motion")
    public AccountDto motion(@RequestBody com.sddnw.server.dto.MotionRequest request,
                             @AccountToken String accountToken) {
        return accountService.changeMotion(accountToken, request.reduceMotion());
    }

    /** Советник — field-backlog, пункт 23: пусто — по умолчанию. */
    @PutMapping("/advisor")
    public AccountDto advisor(@RequestBody com.sddnw.server.dto.AdvisorRequest request,
                              @AccountToken String accountToken) {
        return accountService.changeAdvisor(accountToken, request.advisor());
    }

    /** Сцены, увиденные целиком, — field-backlog, пункт 163. Уходят целиком. */
    @PutMapping("/scenes")
    public AccountDto scenes(@RequestBody com.sddnw.server.dto.SeenScenesRequest request,
                             @AccountToken String accountToken) {
        return accountService.changeSeenScenes(accountToken,
                request.seenScenes() == null ? java.util.List.of() : request.seenScenes());
    }

    /** Игровой псевдоним по умолчанию — личный кабинет. */
    @PutMapping("/name")
    public AccountDto name(@RequestBody com.sddnw.server.dto.NameRequest request,
                           @AccountToken String accountToken) {
        return accountService.changeName(accountToken, request.name());
    }
}
