package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Успехи игрока в итогах хода — field-backlog, пункт 88: правило одно с летописью. */
class MilestoneSuccessTest {

    @Test
    @DisplayName("Выигранный бой, захват и победа — успехи; потеря, война и знакомство — нет")
    void successFollowsMilestones() {
        assertThat(MilestoneService.success("turn.battle.won")).isTrue();
        assertThat(MilestoneService.success("turn.ai.invasionWon")).isTrue();
        assertThat(MilestoneService.success("turn.victory.won")).isTrue();
        assertThat(MilestoneService.success("turn.invasion.colonyLost")).isFalse();
        assertThat(MilestoneService.success("turn.diplomacy.warDeclared")).isFalse();
        assertThat(MilestoneService.success("turn.diplomacy.met")).isFalse();
        assertThat(MilestoneService.success("turn.victory.lost")).isFalse();
        assertThat(MilestoneService.success(null)).isFalse();
        // Обычное событие хода (не главный момент) — не успех, и не ошибка.
        assertThat(MilestoneService.success("turn.income")).isFalse();
    }
}
