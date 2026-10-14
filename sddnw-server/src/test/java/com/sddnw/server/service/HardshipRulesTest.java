package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Победа помнит, какой трудной была (field-backlog, пункт 185). */
class HardshipRulesTest {

    private final UUID self = UUID.randomUUID();
    private final UUID rival = UUID.randomUUID();

    @Test
    @DisplayName("Наибольшее отставание от сильнейшего соперника — ход и проценты")
    void deepestDeficit() {
        Map<UUID, Map<Integer, Integer>> might = Map.of(
                self, Map.of(50, 60, 120, 60, 200, 300),
                rival, Map.of(50, 80, 120, 100, 200, 100));
        HardshipRules.Deficit deficit = HardshipRules.deepest(might, self);
        assertThat(deficit).isEqualTo(new HardshipRules.Deficit(120, 40));
    }

    @Test
    @DisplayName("Лёгкая победа трудной не названа")
    void easyWinIsNotHard() {
        Map<UUID, Map<Integer, Integer>> might = Map.of(
                self, Map.of(10, 95, 20, 200),
                rival, Map.of(10, 100, 20, 100));
        assertThat(HardshipRules.deepest(might, self)).isNull();
    }
}
