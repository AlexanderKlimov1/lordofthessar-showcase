package com.sddnw.server.service;

import com.sddnw.server.dto.BalanceTelemetryDto;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Когда партия решена — field-backlog, пункты 150 и 155: «ход решённости» — с какого хода будущий
 * победитель (первый по мощи на последнем ходу) держит первое место до конца, а «хвост» — ходы после
 * него. Тот же счёт, что у {@code tools/balance_analyze.py --decided}, только на сервере: пульт
 * балансировки показывает его у каждого прогона рядом с приговорами ценам.
 * <p>
 * <b>Правило хозяина проекта</b> (пункт 155): партия, решённая задолго до конца, — изъян баланса.
 * Правка, которая сдвигает ход решённости раньше, — шаг назад, даже если цены стали точнее.
 */
public final class DecidedRules {

    /**
     * @param decidedTurn с какого хода победитель первый до конца
     * @param lastTurn    последний ход партии
     */
    public record Decided(Integer decidedTurn, Integer lastTurn) {

        /** Доля партии до решённости, в процентах. */
        public Integer percent() {
            return lastTurn <= 0 ? 100 : Math.round(100f * decidedTurn / lastTurn);
        }

        /** Хвост — ходы после решённости. */
        public Integer tail() {
            return Math.max(0, lastTurn - decidedTurn);
        }
    }

    private DecidedRules() {
    }

    /** Решённость одной партии по её летописи; пусто — летописи нет. */
    public static Decided of(BalanceTelemetryDto telemetry) {
        List<BalanceTelemetryDto.EmpireTelemetryDto> empires = telemetry.empires().stream()
                .filter(empire -> !empire.history().isEmpty())
                .toList();
        if (empires.isEmpty()) {
            return null;
        }
        int rows = empires.stream().mapToInt(empire -> empire.history().size()).min().orElse(0);
        if (rows == 0) {
            return null;
        }
        // Первый по мощи на каждом ходу; равенство решает место — партия обязана повторяться.
        List<Integer> leaders = new ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            int at = row;
            leaders.add(empires.stream()
                    .max(java.util.Comparator
                            .comparingInt((BalanceTelemetryDto.EmpireTelemetryDto empire) ->
                                    nonNull(empire.history().get(at).might()))
                            .thenComparing(BalanceTelemetryDto.EmpireTelemetryDto::slot,
                                    java.util.Comparator.reverseOrder()))
                    .map(BalanceTelemetryDto.EmpireTelemetryDto::slot)
                    .orElseThrow());
        }
        int winner = leaders.get(rows - 1);
        int from = rows - 1;
        while (from > 0 && leaders.get(from - 1) == winner) {
            from--;
        }
        int firstTurn = empires.get(0).history().get(0).turn();
        int lastTurn = empires.get(0).history().get(rows - 1).turn();
        int decidedTurn = empires.get(0).history().get(from).turn();
        return new Decided(decidedTurn - firstTurn + 1, lastTurn - firstTurn + 1);
    }

    /** Медиана — партии бывают решены и на первом ходу, и на последнем, и среднее тут врёт. */
    public static Integer median(Collection<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        return sorted.isEmpty() ? null : sorted.get(sorted.size() / 2);
    }

    private static int nonNull(Integer value) {
        return value == null ? 0 : value;
    }
}
