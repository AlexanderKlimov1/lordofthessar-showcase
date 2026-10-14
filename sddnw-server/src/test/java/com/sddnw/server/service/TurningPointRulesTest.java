package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Перелом назван — field-backlog, пункт 142: смена первого места засчитывается, удержавшись
 * три хода, и только среди знакомых.
 */
class TurningPointRulesTest {

    private static final UUID ME = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RIVAL = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private static Map<UUID, Integer> m(int me, int rival) {
        return Map.of(ME, me, RIVAL, rival, STRANGER, 10_000);
    }

    @Test
    @DisplayName("Обгон, удержанный три хода, — перелом; незнакомый сильнейший не в счёт")
    void becameFirst() {
        List<Map<UUID, Integer>> series = List.of(m(90, 100), m(110, 100), m(120, 100), m(130, 100));
        assertEquals(TurningPointRules.Turn.BECAME_FIRST, TurningPointRules.turn(series, ME, Set.of(RIVAL)));
        assertEquals(TurningPointRules.Turn.LOST_FIRST, TurningPointRules.turn(series, RIVAL, Set.of(ME)));
    }

    @Test
    @DisplayName("Дребезг первого места — не перелом")
    void flicker() {
        List<Map<UUID, Integer>> series = List.of(m(90, 100), m(110, 100), m(95, 100), m(130, 100));
        assertNull(TurningPointRules.turn(series, ME, Set.of(RIVAL)));
    }

    @Test
    @DisplayName("Без знакомых сравнивать не с кем; положение не менялось — перелома нет")
    void nothing() {
        List<Map<UUID, Integer>> series = List.of(m(110, 100), m(110, 100), m(120, 100), m(130, 100));
        assertNull(TurningPointRules.turn(series, ME, Set.of(RIVAL)));
        assertNull(TurningPointRules.turn(series, ME, Set.of()));
    }
}
