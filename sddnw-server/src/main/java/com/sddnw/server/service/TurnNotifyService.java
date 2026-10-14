package com.sddnw.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.config.AuthProperties;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.dto.TurnEventDto;
import com.sddnw.server.repository.history.AccountRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Зов вне игры — field-backlog, пункты 22, 113 и 178: письмо и уведомление браузера, когда в
 * партии с людьми ждут вашего хода, когда ход посчитан и когда на вашу колонию напали.
 * <p>
 * В сетевой партии часы считают ход без игрока, а через три минуты отсутствия его империю ведёт
 * ИИ ({@link PresenceService}): партия уходит вперёд, а человек об этом не знает. Правила зова:
 * <ul>
 *     <li><b>только по согласию</b> — письма включаются в личном кабинете (по умолчанию
 *     выключены), уведомления браузера — подпиской самого браузера;</li>
 *     <li><b>только в партии с людьми</b> — в одиночной партии ход ждёт игрока сам;</li>
 *     <li><b>только когда действие доступно</b> — «ваш черёд», а не «давно не заходили»;</li>
 *     <li><b>не больше одного зова за ход партии</b> на игрока, и не чаще раза в
 *     {@link #PUSH_GAP} (пункт 178: напоминание — не давление);</li>
 *     <li><b>письмо — одно на пропущенную цепочку ходов</b>: следующее уйдёт, только когда игрок
 *     вернулся и снова ушёл, и только ушедшему больше {@link #MAIL_ABSENCE} назад;</li>
 *     <li><b>без имён</b> — «соперники закончили ход», а не «Петя ждёт только вас».</li>
 * </ul>
 * Шлётся ПОСЛЕ фиксации хода, как события подписчикам, и своим потоком: письмо и служба
 * уведомлений могут отвечать секундами, а игрок, закончивший ход, ждать их не должен. Память о
 * том, кого и когда звали, живёт в памяти сервера: после перезапуска худшее, что случится, —
 * лишний зов.
 */
@Service
public class TurnNotifyService {

    private static final Logger log = LoggerFactory.getLogger(TurnNotifyService.class);

    /** Не чаще одного уведомления браузера на игрока партии за это время. */
    static final Duration PUSH_GAP = Duration.ofMinutes(5);

    /** Письмо — только тому, кто не действовал в партии хотя бы столько. */
    static final Duration MAIL_ABSENCE = Duration.ofMinutes(10);

    /**
     * События отчёта, означающие «на вашу колонию напали»: чужой флот вышел к её обороне,
     * десант высадился, колония потеряна.
     */
    static final Set<String> ATTACK_KEYS = Set.of("turn.encounter.colonyVisited");
    static final Set<String> ATTACK_CODES = Set.of("INVASION", "COLONY_LOST");

    private final AccountRepository accounts;
    private final WebPushService push;
    private final MailService mail;
    private final Messages messages;
    private final PresenceService presence;
    private final AuthProperties properties;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "turn-notify");
        thread.setDaemon(true);
        return thread;
    });

    /** Последний ход, за который игрока звали, и когда. */
    private final Map<UUID, Integer> calledTurn = new ConcurrentHashMap<>();
    private final Map<UUID, OffsetDateTime> calledAt = new ConcurrentHashMap<>();
    /** Когда игроку ушло последнее письмо по этой партии. */
    private final Map<UUID, OffsetDateTime> mailedAt = new ConcurrentHashMap<>();

    public TurnNotifyService(AccountRepository accounts, WebPushService push, MailService mail, Messages messages,
                             PresenceService presence, AuthProperties properties) {
        this.accounts = accounts;
        this.push = push;
        this.mail = mail;
        this.messages = messages;
        this.presence = presence;
        this.properties = properties;
    }

    /** Кого позвать и о чём — собирается в транзакции хода, отправляется после неё. */
    record Call(UUID playerId, UUID accountId, String gameId, String gameName, Integer turn, String kind,
                Boolean attacked, OffsetDateTime lastAction) {
    }

    /** Игрок закончил ход, а других ещё ждут: «соперники закончили ход» — тем, кого ждут. */
    public void waiting(GameEntity game, List<PlayerEntity> players, List<UUID> waiting) {
        if (!Boolean.TRUE.equals(network(players))) {
            return;
        }
        List<Call> calls = new ArrayList<>();
        for (PlayerEntity player : players) {
            if (waiting.contains(player.getId()) && Boolean.TRUE.equals(callable(player))) {
                calls.add(call(game, player, "waiting", Boolean.FALSE));
            }
        }
        afterCommit(calls);
    }

    /** Ход посчитан: «ваш черёд» всем людям партии, а тем, на кого напали, — и об этом. */
    public void advanced(GameEntity game, List<PlayerEntity> players, TurnReport report) {
        if (!Boolean.TRUE.equals(network(players))) {
            return;
        }
        List<Call> calls = new ArrayList<>();
        for (PlayerEntity player : players) {
            if (Boolean.TRUE.equals(callable(player))) {
                calls.add(call(game, player, "advanced", attacked(report.events(player.getId()))));
            }
        }
        afterCommit(calls);
    }

    /** Партия с людьми: двое и больше живых людей с учётной записью. */
    static Boolean network(List<PlayerEntity> players) {
        return players.stream().filter(TurnNotifyService::human).count() >= 2;
    }

    /** На колонию игрока напали — по событиям его отчёта хода. */
    static Boolean attacked(List<TurnEventDto> events) {
        return events.stream().anyMatch(event -> ATTACK_CODES.contains(event.code())
                || (event.key() != null && ATTACK_KEYS.contains(event.key())));
    }

    private static boolean human(PlayerEntity player) {
        return player.getPlayerType() == PlayerType.HUMAN && player.getAccountId() != null
                && player.getConcededTurn() == null;
    }

    private Boolean callable(PlayerEntity player) {
        return human(player);
    }

    private Call call(GameEntity game, PlayerEntity player, String kind, Boolean attacked) {
        return new Call(player.getId(), player.getAccountId(), game.getId().toString(), game.getName(), game.getTurn(),
                kind, attacked, presence.lastAction(player.getId()));
    }

    private void afterCommit(List<Call> calls) {
        if (calls.isEmpty()) {
            return;
        }
        Runnable job = () -> sender.submit(() -> calls.forEach(this::deliver));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            job.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                job.run();
            }
        });
    }

    /** Один зов: решить, звать ли, и позвать — браузером и, если пора, письмом. */
    void deliver(Call call) {
        try {
            OffsetDateTime now = OffsetDateTime.now();
            Integer lastTurn = calledTurn.get(call.playerId());
            OffsetDateTime lastAt = calledAt.get(call.playerId());
            // Зов о нападении — исключение из «раза за ход»: это не напоминание, а новость.
            boolean already = lastTurn != null && lastTurn >= call.turn() && !Boolean.TRUE.equals(call.attacked());
            boolean tooSoon = lastAt != null && lastAt.plus(PUSH_GAP).isAfter(now) && !Boolean.TRUE.equals(call.attacked());
            if (already || tooSoon) {
                return;
            }
            AccountEntity account = accounts.findById(call.accountId()).orElse(null);
            if (account == null) {
                return;
            }
            calledTurn.put(call.playerId(), call.turn());
            calledAt.put(call.playerId(), now);
            Locale locale = Locale.forLanguageTag(account.getLocale() == null ? "en" : account.getLocale());
            String body = text(locale, call);
            String title = messages.get(locale, "notify.push.title", call.gameName());
            push.send(account.getId(), json.writeValueAsString(Map.of(
                    "title", title, "body", body, "tag", "turn-" + call.gameId(), "gameId", call.gameId())));
            mailIfAway(account, call, locale, title, body, now);
        } catch (Exception failure) {
            log.warn("Зов о ходе игрока {} не ушёл: {}", call.playerId(), failure.getMessage());
        }
    }

    private void mailIfAway(AccountEntity account, Call call, Locale locale, String title, String body,
                            OffsetDateTime now) {
        if (!Boolean.TRUE.equals(account.getNotifyMail()) || account.getEmail() == null
                || account.getNotifyToken() == null) {
            return;
        }
        OffsetDateTime last = call.lastAction();
        if (last != null && last.plus(MAIL_ABSENCE).isAfter(now)) {
            return;
        }
        OffsetDateTime mailed = mailedAt.get(call.playerId());
        if (mailed != null && (last == null || !mailed.isBefore(last))) {
            // Письмо об этой цепочке ходов уже ушло: игрок с тех пор не возвращался.
            return;
        }
        String open = properties.clientUrl() + "/";
        String off = properties.clientUrl() + "/api/notify/unsubscribe/" + account.getNotifyToken();
        String text = messages.get(locale, "notify.mail.text", body, open, off);
        String html = messages.get(locale, "notify.mail.html", escape(body), open, off);
        if (Boolean.TRUE.equals(mail.sendNotice(account.getEmail(), title, text, html))) {
            mailedAt.put(call.playerId(), now);
        }
    }

    private String text(Locale locale, Call call) {
        String main = "waiting".equals(call.kind())
                ? messages.get(locale, "notify.push.waiting", call.turn())
                : messages.get(locale, "notify.push.advanced", call.turn());
        return Boolean.TRUE.equals(call.attacked()) ? main + " " + messages.get(locale, "notify.push.attacked") : main;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @PreDestroy
    void stop() {
        sender.shutdown();
    }
}
