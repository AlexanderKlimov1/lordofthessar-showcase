package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Опыт экипажей — п. 8 (перенос MOO II ради космической академии). */
class CrewRulesTest {

    @Test
    @DisplayName("Звание растёт с опытом, прибавка — по званию, выше сверхэлиты не растёт")
    void levelsAndBonuses() {
        assertThat(CrewRules.level(0)).isZero();
        assertThat(CrewRules.bonus(0)).isZero();
        assertThat(CrewRules.level(CrewRules.ACADEMY_START_XP)).isEqualTo(1);
        assertThat(CrewRules.bonus(CrewRules.ACADEMY_START_XP)).isEqualTo(15);
        assertThat(CrewRules.level(49)).isEqualTo(1);
        assertThat(CrewRules.level(50)).isEqualTo(2);
        assertThat(CrewRules.level(1000)).isEqualTo(4);
        assertThat(CrewRules.gained(CrewRules.MAX_XP - 1, 10)).isEqualTo(CrewRules.MAX_XP);
    }

    @Test
    @DisplayName("При слиянии опыт усредняется по числу кораблей")
    void mergeAverages() {
        assertThat(CrewRules.merged(50, 3, 0, 1)).isEqualTo(37);
        assertThat(CrewRules.merged(0, 0, 20, 1)).isEqualTo(20);
    }
}
