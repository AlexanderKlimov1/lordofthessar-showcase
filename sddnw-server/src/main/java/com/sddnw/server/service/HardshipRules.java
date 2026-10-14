package com.sddnw.server.service;

import java.util.Map;
import java.util.UUID;

/**
 * Победа помнит, какой трудной она была — field-backlog, пункт 185.
 * <p>
 * Fiero растёт с преодолённым, и его надо назвать числом: «вы победили, уступая в мощи на 40 %
 * на 120-м ходу». Мера — летопись мощи ({@code EmpireMightRules}, та же, что график «Инфо»): ход,
 * на котором отставание победителя от сильнейшего соперника было наибольшим. Лёгкую победу не
 * раздуваем: отставание меньше {@link #NOTABLE_PERCENT} — не трудность, и строки нет вовсе.
 */
public final class HardshipRules {

    /** С какого отставания победу стоит назвать трудной. */
    public static final int NOTABLE_PERCENT = 10;

    private HardshipRules() {
    }

    /** Наибольшее отставание: ход и проценты; пусто — не отставал заметно ни разу. */
    public record Deficit(Integer turn, Integer percent) {
    }

    /**
     * @param might мощь каждой империи по ходам
     * @param self  чья победа
     */
    public static Deficit deepest(Map<UUID, Map<Integer, Integer>> might, UUID self) {
        Map<Integer, Integer> mine = might.getOrDefault(self, Map.of());
        Deficit worst = null;
        for (Map.Entry<Integer, Integer> point : mine.entrySet()) {
            int strongest = 0;
            for (Map.Entry<UUID, Map<Integer, Integer>> other : might.entrySet()) {
                if (!other.getKey().equals(self)) {
                    strongest = Math.max(strongest, other.getValue().getOrDefault(point.getKey(), 0));
                }
            }
            if (strongest <= 0 || point.getValue() >= strongest) {
                continue;
            }
            int percent = (int) Math.round(100.0 * (strongest - point.getValue()) / strongest);
            if (percent >= NOTABLE_PERCENT && (worst == null || percent > worst.percent())) {
                worst = new Deficit(point.getKey(), percent);
            }
        }
        return worst;
    }
}
