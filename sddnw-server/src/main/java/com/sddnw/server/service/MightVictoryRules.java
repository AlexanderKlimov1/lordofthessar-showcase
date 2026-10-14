package com.sddnw.server.service;

import com.sddnw.server.domain.entity.PlayerEntity;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Итог по могуществу — п. 3, backlog-promo, пункт 2 (решение хозяина проекта).
 * <p>
 * <b>Зачем.</b> Главная болезнь жанра — затянутый финал: исход ясен за сотню ходов до
 * конца, а игроку осталось управлять тридцатью колониями, и партию бросают недоигранной.
 * Здесь у партии есть срок: не кончилась ничем к {@link #TURN_LIMIT}-му ходу — победа
 * отдаётся сильнейшей империи. Срок виден заранее, поэтому он не обрыв, а цель: отстающий
 * знает, сколько ходов у него на рывок, а ведущий — сколько ему держаться.
 * <p>
 * <b>Мерило — первая линия графика «Инфо»</b> ({@link EmpireMightRules}, тот же замер, что
 * пишет летопись): игрок весь срок видит на экране ровно то, по чему его рассудят, а
 * соседи-ИИ судят друг друга по той же формуле. Второй меры силы в игре быть не должно.
 * <p>
 * Судят только ЖИВЫЕ империи — с колониями: флот без колоний империей не считается и в
 * покорении, иначе разбитая империя с уцелевшей эскадрой могла бы выиграть по очкам.
 * Равенство мощи разрешает место игрока: партия обязана повторяться (см. {@code GameOrder}).
 */
public final class MightVictoryRules {

    /** На каком ходу подводится итог по умолчанию; решение хозяина проекта. */
    public static final int TURN_LIMIT = 300;

    /**
     * Сроки, которые партия может выбрать (09.10.2026, field-backlog, пункт 24): сотый — «партия под
     * вечер», которая помещается в один вечер; двухсотый и трёхсотый — обычные. Список закрытый: срок
     * не всякий, а проверенный.
     */
    public static final java.util.List<Integer> ALLOWED_TURNS = java.util.List.of(100, 200, 300);

    /**
     * Досрочный итог — field-backlog, пункт 80, лекарство от «снежного кома»: сильнейший, чья мощь не
     * меньше {@link #EARLY_RATIO}-кратной мощи второго, держит этот отрыв {@link #EARLY_HOLD_TURNS}
     * ходов подряд — итог подводится раньше срока (с тем же согласием людей). Не раньше трети срока:
     * в начале партии отрыв от соседа значит ничего. Реконструкция — числа по правилу «вдвое или
     * пополам» (пункт 89); отрыв называется всем заранее, скрытой подстройки нет (пункт 10).
     */
    public static final int EARLY_RATIO = 2;
    public static final int EARLY_HOLD_TURNS = 20;

    /** Срок итога этой партии. */
    public static int limit(com.sddnw.server.domain.entity.GameEntity game) {
        return game.getMightTurn() == null ? TURN_LIMIT : game.getMightTurn();
    }

    /** С какого хода досрочный итог возможен вообще. */
    public static int earlyFrom(int limit) {
        return limit / 3;
    }

    /**
     * Держит ли сильнейший огромный отрыв: его мощь не меньше {@link #EARLY_RATIO} мощи второго из
     * живых. Нулевая мощь второго — отрыв считается: соперник без мощи не соперник.
     */
    public static Boolean crushing(Map<UUID, Integer> might, PlayerEntity leader, Collection<PlayerEntity> players,
                                   Set<UUID> alive) {
        int mine = might.getOrDefault(leader.getId(), 0);
        int second = players.stream()
                .filter(player -> alive.contains(player.getId()) && !player.getId().equals(leader.getId()))
                .mapToInt(player -> might.getOrDefault(player.getId(), 0))
                .max()
                .orElse(0);
        return mine > 0 && mine >= second * EARLY_RATIO && alive.size() >= 2;
    }

    /** За сколько ходов до срока напоминать галактике: за полсотни, за десять и за один. */
    private static final Set<Integer> REMIND_BEFORE = Set.of(50, 10, 1);

    private MightVictoryRules() {
    }

    /** Подводится ли итог на этом ходу. */
    public static Boolean due(Integer turn, Integer limit) {
        return turn >= limit;
    }

    /** Напоминать ли о сроке на этом ходу; число — сколько ходов осталось. */
    public static Boolean remind(Integer turn, Integer limit) {
        return REMIND_BEFORE.contains(limit - turn);
    }

    /**
     * Сильнейшая из живых империй; пусто — живых нет.
     *
     * @param might мощь по игрокам — первая линия графика «Инфо»
     * @param alive игроки, у которых есть колонии
     */
    public static PlayerEntity winner(Map<UUID, Integer> might, Collection<PlayerEntity> players,
                                      Set<UUID> alive) {
        return players.stream()
                .filter(player -> alive.contains(player.getId()))
                .max(Comparator.comparingInt((PlayerEntity player) -> might.getOrDefault(player.getId(), 0))
                        // При равной мощи побеждает меньшее место: место сравнивается
                        // обратным порядком, и меньшее оказывается «большим» для max.
                        .thenComparing(PlayerEntity::getSlot, Comparator.reverseOrder()))
                .orElse(null);
    }
}
