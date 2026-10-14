package com.sddnw.server.service;

import java.util.random.RandomGenerator;

/**
 * Полосы неудач — field-backlog, пункт 86 (решение хозяина проекта 08.10.2026): две неудачи подряд
 * при 75 % кажутся подстроенными, хотя случаются в каждой шестнадцатой паре бросков.
 * <p>
 * Псевдослучайное распределение: шанс n-й попытки после последнего успеха — {@code C × n}, и
 * постоянная {@code C} подобрана так, что СРЕДНИЙ шанс равен показанному. Длинных полос промахов
 * нет, зато нет и длинных полос попаданий подряд: средний выход тот же, разброс уже. Показанный
 * игроку процент — средний: правило «показан тот шанс, что бросается» (backlog-promo, пункт 37)
 * уточнено так, и сказано об этом в справочнике правил.
 * <p>
 * Одинаково для всех империй и из того же жребия боя, что и прежде, — партия повторяется по
 * зерну (GameOrder). Где бросков нет вовсе (задания агентов решают очки против контрразведки),
 * сглаживать нечего.
 */
public final class StreakRules {

    /** Постоянные C по проценту 0…100 — считаются один раз. */
    private static final double[] C = new double[101];

    static {
        for (int percent = 1; percent < 100; percent++) {
            C[percent] = constantFor(percent / 100.0);
        }
        C[100] = 1;
    }

    private StreakRules() {
    }

    /**
     * Бросок с учётом полосы: {@code misses} — сколько промахов подряд уже было у этого стрелка.
     * Ноль и сто процентов не сглаживаются: там нечего выравнивать.
     */
    public static Boolean roll(RandomGenerator random, Integer percent, Integer misses) {
        if (percent <= 0) {
            return Boolean.FALSE;
        }
        if (percent >= 100) {
            return Boolean.TRUE;
        }
        double chance = Math.min(1.0, C[percent] * (misses + 1));
        return random.nextDouble() < chance;
    }

    /** Средний шанс при постоянной C: единица, делённая на ожидаемое число попыток до успеха. */
    static double averageFor(double c) {
        double expected = 0;
        double notYet = 1;
        for (int n = 1; notYet > 1e-12; n++) {
            double p = Math.min(1.0, c * n);
            expected += n * notYet * p;
            notYet *= 1 - p;
        }
        return 1 / expected;
    }

    /** C для нужного среднего — делением отрезка: средний растёт вместе с C. */
    static double constantFor(double wanted) {
        double low = 0;
        double high = wanted;
        for (int i = 0; i < 60; i++) {
            double middle = (low + high) / 2;
            if (averageFor(middle) < wanted) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (low + high) / 2;
    }

    public static double constant(int percent) {
        return C[Math.max(0, Math.min(100, percent))];
    }
}
