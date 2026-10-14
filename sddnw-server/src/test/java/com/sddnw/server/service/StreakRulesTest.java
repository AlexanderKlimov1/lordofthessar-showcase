package com.sddnw.server.service;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Полосы неудач — пункт 86: средний шанс тот же, что показан, а длинных полос промахов нет. */
class StreakRulesTest {

    @Test
    void averageMatchesShownChance() {
        for (int percent : new int[]{10, 25, 50, 75, 90}) {
            assertThat(StreakRules.averageFor(StreakRules.constant(percent)))
                    .isCloseTo(percent / 100.0, org.assertj.core.data.Offset.offset(0.001));
        }
    }

    @Test
    void longMissStreaksVanish() {
        Random random = new Random(86);
        int hits = 0;
        int misses = 0;
        int longest = 0;
        for (int i = 0; i < 100_000; i++) {
            boolean hit = StreakRules.roll(random, 75, misses);
            if (hit) {
                hits++;
                misses = 0;
            } else {
                misses++;
                longest = Math.max(longest, misses);
            }
        }
        assertThat(hits / 100_000.0).isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.01));
        // При 75 % и честном жребии полоса из трёх промахов случается; со сглаживанием — нет.
        assertThat(longest).isLessThanOrEqualTo(2);
    }
}
