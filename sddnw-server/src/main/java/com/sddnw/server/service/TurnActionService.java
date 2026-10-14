package com.sddnw.server.service;

import com.sddnw.server.domain.entity.TurnActionEntity;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.TurnActionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Счёт действий живых игроков по ходам — field-backlog, пункт 13.
 * <p>
 * Зовёт его {@code GameAccess} — там, где отмечается присутствие: пропуск предъявляет каждое
 * действие над партией, и других дверей нет. Копится в памяти и ложится в базу раз в минуту —
 * писать строку на каждое нажатие значило бы спорить с самим действием за ту же базу. Скрипты
 * проекта (сквозной прогон, балансовые и учебные) не считаются: они ходят стандартной
 * библиотекой питона и узнаются по {@code User-Agent}, как и в счётчике отказов (пункт 77).
 * <p>
 * Разбор — {@code tools/turn_actions.py}: действия на ход у 30-го и 150-го хода и самые частые.
 */
@Service
public class TurnActionService {

    private static final String SCRIPT_AGENT = "Python-urllib";
    /** Опознаватель в адресе — UUID; действие называется адресом без них. */
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27}");

    private record Key(UUID gameId, UUID playerId, Integer turn, String action) {
    }

    private final Map<Key, AtomicInteger> pending = new ConcurrentHashMap<>();
    private final TurnActionRepository actions;
    private final GameRepository games;

    public TurnActionService(TurnActionRepository actions, GameRepository games) {
        this.actions = actions;
        this.games = games;
    }

    /** Одно действие игрока: метод и адрес запроса, из которого оно пришло. */
    public void count(UUID gameId, UUID playerId, String method, String uri, String userAgent) {
        if (gameId == null || userAgent == null || userAgent.startsWith(SCRIPT_AGENT)) {
            return;
        }
        Integer turn = games.findTurnById(gameId);
        if (turn == null) {
            return;
        }
        String action = (method + " " + ID.matcher(uri).replaceAll("{id}"));
        if (action.length() > 120) {
            action = action.substring(0, 120);
        }
        pending.computeIfAbsent(new Key(gameId, playerId, turn, action), key -> new AtomicInteger())
                .incrementAndGet();
    }

    /** Накопленное — в базу. Строка (партия, игрок, ход, действие) одна, счёт прибавляется. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<Key, Integer> taken = new HashMap<>();
        pending.forEach((key, value) -> {
            int times = value.getAndSet(0);
            if (times > 0) {
                taken.put(key, times);
            }
        });
        pending.entrySet().removeIf(entry -> entry.getValue().get() == 0);
        taken.forEach((key, times) -> {
            TurnActionEntity row = actions.findByGameIdAndPlayerIdAndTurnAndAction(
                    key.gameId(), key.playerId(), key.turn(), key.action()).orElseGet(() -> {
                        TurnActionEntity fresh = new TurnActionEntity();
                        fresh.setGameId(key.gameId());
                        fresh.setPlayerId(key.playerId());
                        fresh.setTurn(key.turn());
                        fresh.setAction(key.action());
                        fresh.setTimes(0);
                        return fresh;
                    });
            row.setTimes(row.getTimes() + times);
            actions.save(row);
        });
    }
}
