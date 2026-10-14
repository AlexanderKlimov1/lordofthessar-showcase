package com.sddnw.server.service;

import java.util.List;
import java.util.Map;

/**
 * Контрольные ходы партии — field-backlog, пункты 106 и 115: 50-й, 100-й и 150-й.
 * <p>
 * По ним сравнивают путь победителя («к сотому ходу у него было девять колоний») и себя с
 * собой прошлым («к сотому ходу мощь выше, чем в лучшей партии, на 20 %»). Значение на
 * контрольном ходу — последний замер летописи не позже него: замер пишется в конце каждого
 * хода, но партия, поднятая из старого сохранения, может начаться с пропуском.
 */
public final class CheckpointRules {

    public static final List<Integer> TURNS = List.of(50, 100, 150);

    private CheckpointRules() {
    }

    /** Значение на ходу или последний замер до него; пусто — партия до него не дошла. */
    public static Integer at(Map<Integer, Integer> byTurn, int turn) {
        Integer last = byTurn.keySet().stream().max(Integer::compare).orElse(null);
        if (last == null || last < turn) {
            return null;
        }
        return byTurn.entrySet().stream()
                .filter(entry -> entry.getKey() <= turn)
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }
}
