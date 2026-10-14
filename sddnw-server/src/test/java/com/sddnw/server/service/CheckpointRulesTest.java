package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Контрольные ходы (field-backlog, пункты 106 и 115). */
class CheckpointRulesTest {

    @Test
    @DisplayName("Значение на ходу — последний замер не позже него; не дошли — пусто")
    void valueAtCheckpoint() {
        Map<Integer, Integer> byTurn = Map.of(48, 10, 52, 20, 99, 30);
        assertThat(CheckpointRules.at(byTurn, 50)).isEqualTo(10);
        assertThat(CheckpointRules.at(byTurn, 100)).isNull();
    }
}
