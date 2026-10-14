package com.sddnw.server.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Перелом назван — field-backlog, пункт 142.
 * <p>
 * Поворот — сердце сюжета партии, а прежде он проходил молча: игрок обгонял сильнейшего
 * соседа и узнавал об этом, только открыв «Инфо». Здесь два перелома: <b>вы стали сильнейшей</b>
 * империей среди знакомых и <b>вы потеряли первое место</b>. Мера — та же мощь
 * ({@link EmpireMightRules}), что рисует первую линию графика «Инфо», и сравниваются только
 * знакомые: о незнакомом соседе игрок ничего не знает, и обгон его был бы разведкой даром.
 * <p>
 * <b>Порог против дребезга.</b> На равных силах первое место переходит из рук в руки каждый ход,
 * и событие каждый ход обесценилось бы. Поэтому перелом засчитывается, когда новое положение
 * удержалось {@link #HOLD_TURNS} хода подряд, а до того было иным. Число — реконструкция и
 * правится только здесь.
 */
public final class TurningPointRules {

    /** Сколько ходов подряд должно удержаться новое положение. */
    public static final int HOLD_TURNS = 3;

    /** Что случилось. */
    public enum Turn {
        BECAME_FIRST,
        LOST_FIRST
    }

    private TurningPointRules() {
    }

    /**
     * Перелом игрока; пусто — его нет.
     *
     * @param mightByTurn мощь империй по ходам, от СТАРОГО к новому: {@link #HOLD_TURNS} + 1 замер
     * @param player      чей перелом
     * @param known       с кем игрок знаком — только они и сравниваются
     */
    public static Turn turn(List<Map<UUID, Integer>> mightByTurn, UUID player, Set<UUID> known) {
        if (known.isEmpty() || mightByTurn.size() < HOLD_TURNS + 1) {
            return null;
        }
        boolean before = first(mightByTurn.get(mightByTurn.size() - HOLD_TURNS - 1), player, known);
        boolean held = true;
        boolean now = first(mightByTurn.get(mightByTurn.size() - 1), player, known);
        for (int i = mightByTurn.size() - HOLD_TURNS; i < mightByTurn.size(); i++) {
            held &= first(mightByTurn.get(i), player, known) == now;
        }
        if (!held || before == now) {
            return null;
        }
        return now ? Turn.BECAME_FIRST : Turn.LOST_FIRST;
    }

    /** Сильнее ли игрок всех знакомых на этом замере: ничья первого места не даёт. */
    static boolean first(Map<UUID, Integer> might, UUID player, Set<UUID> known) {
        Integer mine = might.get(player);
        if (mine == null) {
            return false;
        }
        return known.stream()
                .filter(other -> !other.equals(player))
                .map(might::get)
                .allMatch(other -> other == null || mine > other);
    }
}
