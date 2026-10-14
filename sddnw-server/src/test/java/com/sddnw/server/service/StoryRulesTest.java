package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameMilestoneEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** «Как это было» (field-backlog, пункт 143): отбор правилом, рассказ по порядку ходов. */
class StoryRulesTest {

    private static GameMilestoneEntity row(int turn, String kind) {
        GameMilestoneEntity row = new GameMilestoneEntity();
        row.setTurn(turn);
        row.setKind(kind);
        return row;
    }

    @Test
    @DisplayName("Берутся сильнейшие повороты, не больше семи, и идут по ходам")
    void strongestInTurnOrder() {
        List<GameMilestoneEntity> chronicle = new ArrayList<>();
        for (int turn = 1; turn <= 10; turn++) {
            chronicle.add(row(turn, MilestoneService.CONTACT));
        }
        chronicle.add(row(40, MilestoneService.WAR));
        chronicle.add(row(60, MilestoneService.COLONY_LOST));
        chronicle.add(row(80, MilestoneService.LEAD));
        chronicle.add(row(90, MilestoneService.END));
        List<GameMilestoneEntity> story = StoryRules.story(chronicle);
        assertThat(story).hasSizeLessThanOrEqualTo(StoryRules.MAX_LINES);
        assertThat(story).extracting(GameMilestoneEntity::getKind)
                .contains(MilestoneService.WAR, MilestoneService.COLONY_LOST, MilestoneService.LEAD)
                .doesNotContain(MilestoneService.END);
        assertThat(story).extracting(GameMilestoneEntity::getTurn).isSorted();
    }

    @Test
    @DisplayName("Одного рода — не больше двух строк, и раньше — сильнее")
    void notAListOfOneKind() {
        List<GameMilestoneEntity> chronicle = new ArrayList<>();
        for (int turn = 10; turn <= 100; turn += 10) {
            chronicle.add(row(turn, MilestoneService.WAR));
        }
        List<GameMilestoneEntity> story = StoryRules.story(chronicle);
        assertThat(story).extracting(GameMilestoneEntity::getTurn).containsExactly(10, 20);
    }
}
