package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Звания за трудное — пункт 41: что засчитывается и что нет. */
class TitleRulesTest {

    private static GameRecordEntity row(String outcome, String kind, String race, Boolean war, Boolean tutorial) {
        GameRecordEntity row = new GameRecordEntity();
        row.setGameId(UUID.randomUUID());
        row.setOutcome(outcome);
        row.setVictoryKind(kind);
        row.setRaceCode(race);
        row.setAtWar(war);
        row.setWarden(Boolean.FALSE);
        row.setTrophy(Boolean.FALSE);
        row.setTutorial(tutorial);
        return row;
    }

    private static TitleRules.Title title(List<TitleRules.Title> titles, String code) {
        return titles.stream().filter(one -> one.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void peacefulWinCountsOnlyInASeriousGame() {
        List<TitleRules.Title> tutorial = TitleRules.titles(
                List.of(row(GameRecordService.WON, "CONQUEST", "HUMANS", Boolean.FALSE, Boolean.TRUE)), 13);
        assertThat(title(tutorial, TitleRules.PEACE).earned()).isFalse();

        List<TitleRules.Title> serious = TitleRules.titles(
                List.of(row(GameRecordService.WON, "CONQUEST", "HUMANS", Boolean.FALSE, Boolean.FALSE)), 13);
        assertThat(title(serious, TitleRules.PEACE).earned()).isTrue();
        assertThat(title(serious, TitleRules.RACES).progress()).isEqualTo(1);
        assertThat(title(serious, TitleRules.PATHS).progress()).isEqualTo(1);
    }

    @Test
    void lossesAndOldRowsGiveNothing() {
        GameRecordEntity old = row(GameRecordService.WON, "COUNCIL", "PSILONS", null, null);
        List<TitleRules.Title> titles = TitleRules.titles(List.of(
                row(GameRecordService.LOST, "CONQUEST", "HUMANS", Boolean.FALSE, Boolean.FALSE), old), 13);
        assertThat(titles).allMatch(one -> !one.earned() && one.progress() == 0);
    }

    @Test
    void earnedByNamesOnlyWhatThisGameBrought() {
        GameRecordEntity first = row(GameRecordService.WON, "CONQUEST", "HUMANS", Boolean.TRUE, Boolean.FALSE);
        GameRecordEntity second = row(GameRecordService.WON, "MIGHT", "PSILONS", Boolean.FALSE, Boolean.FALSE);
        assertThat(TitleRules.earnedBy(List.of(first, second), second.getGameId(), 13))
                .containsExactly(TitleRules.PEACE);
        assertThat(TitleRules.earnedBy(List.of(first, second), first.getGameId(), 13)).isEmpty();
    }

    @Test
    void hardWinAndHostedTables() {
        GameRecordEntity hard = row(GameRecordService.WON, "CONQUEST", "HUMANS", Boolean.TRUE, Boolean.FALSE);
        hard.setAiSkill("HARD");
        GameRecordEntity normal = row(GameRecordService.WON, "CONQUEST", "HUMANS", Boolean.TRUE, Boolean.FALSE);
        assertThat(title(TitleRules.titles(List.of(normal), 13), TitleRules.HARD).earned()).isFalse();
        assertThat(title(TitleRules.titles(List.of(hard), 13), TitleRules.HARD).earned()).isTrue();

        java.util.List<GameRecordEntity> rows = new java.util.ArrayList<>();
        for (int i = 0; i < TitleRules.HOST_GOAL; i++) {
            GameRecordEntity table = row(GameRecordService.LOST, "CONQUEST", "HUMANS", Boolean.TRUE, Boolean.FALSE);
            table.setHosted(Boolean.TRUE);
            rows.add(table);
        }
        // Признанное поражение — стол не доигран — не в счёт.
        rows.get(0).setOutcome(GameRecordService.CONCEDED);
        TitleRules.Title host = title(TitleRules.titles(rows, 13), TitleRules.HOST);
        assertThat(host.earned()).isFalse();
        assertThat(host.progress()).isEqualTo(TitleRules.HOST_GOAL - 1);
        rows.get(0).setOutcome(GameRecordService.LOST);
        assertThat(title(TitleRules.titles(rows, 13), TitleRules.HOST).earned()).isTrue();
    }
}
