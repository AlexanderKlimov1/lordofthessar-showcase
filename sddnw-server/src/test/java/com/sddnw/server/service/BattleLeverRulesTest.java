package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Что изменить после поражения — field-backlog, пункт 103: рычаг выводится из правил боя, и
 * когда правила причины не называют, строки нет.
 */
class BattleLeverRulesTest {

    private static BattleLeverRules.Side side(int ships, int attack, int endurance, int shield, int shot, int power) {
        return new BattleLeverRules.Side(ships, attack, endurance, shield, shot, power);
    }

    @Test
    @DisplayName("Щит, гасящий сильнейший выстрел, называется первым")
    void shields() {
        MessageKey lever = BattleLeverRules.lever(side(5, 20, 100, 0, 4, 100), side(5, 20, 100, 6, 10, 300));
        assertEquals("turn.lever.shields", lever.key());
    }

    @Test
    @DisplayName("Корабль вдвое сильнее — корпус или оружие; вдвое больше кораблей — флот")
    void qualityAndNumbers() {
        assertEquals("turn.lever.quality",
                BattleLeverRules.lever(side(4, 10, 100, 0, 8, 400), side(4, 40, 300, 0, 8, 1600)).key());
        assertEquals("turn.lever.numbers",
                BattleLeverRules.lever(side(3, 10, 100, 0, 8, 300), side(6, 20, 200, 0, 8, 600)).key());
    }

    @Test
    @DisplayName("Близкие стороны — причины нет, и строки нет")
    void noLever() {
        assertNull(BattleLeverRules.lever(side(5, 20, 100, 0, 8, 500), side(5, 22, 110, 0, 8, 600)));
        assertNull(BattleLeverRules.lever(side(0, 0, 0, 0, 0, 0), side(5, 22, 110, 0, 8, 600)));
    }
}
