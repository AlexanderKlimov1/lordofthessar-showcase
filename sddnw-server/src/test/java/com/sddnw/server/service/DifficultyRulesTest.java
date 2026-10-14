package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;
import com.sddnw.server.domain.enums.AiSkill;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Уровни сложности (пункт 9) и подсказка уровня следующей партии (пункт 10). */
class DifficultyRulesTest {

    private static GameRecordEntity row(String outcome, int turn, Integer deficitTurn, String skill, boolean tutorial) {
        GameRecordEntity row = new GameRecordEntity();
        row.setOutcome(outcome);
        row.setTurn(turn);
        row.setDeficitTurn(deficitTurn);
        row.setAiSkill(skill);
        row.setTutorial(tutorial);
        return row;
    }

    @Test
    void easyWinSuggestsHarder() {
        DifficultyRules.Suggestion suggestion = DifficultyRules.suggest(
                List.of(row(GameRecordService.WON, 140, null, null, false)));
        assertThat(suggestion.last()).isEqualTo(AiSkill.NORMAL);
        assertThat(suggestion.suggested()).isEqualTo(AiSkill.HARD);
        assertThat(suggestion.reason()).isEqualTo(DifficultyRules.Reason.EASY_WIN);
    }

    @Test
    void hardWonVictoryIsTheFlow() {
        // Отставал от сильнейшего — победа трудная, это и есть поток: предлагать нечего.
        assertThat(DifficultyRules.suggest(List.of(row(GameRecordService.WON, 140, 80, null, false)))).isNull();
        // Поздняя победа — тоже.
        assertThat(DifficultyRules.suggest(List.of(row(GameRecordService.WON, 260, null, null, false)))).isNull();
    }

    @Test
    void earlyLossSuggestsEasier() {
        DifficultyRules.Suggestion suggestion = DifficultyRules.suggest(
                List.of(row(GameRecordService.CONCEDED, 90, null, "EASY", false)));
        assertThat(suggestion.suggested()).isEqualTo(AiSkill.BEGINNER);
        assertThat(suggestion.reason()).isEqualTo(DifficultyRules.Reason.EARLY_LOSS);
    }

    @Test
    void edgesOfTheLadderSuggestNothing() {
        assertThat(DifficultyRules.suggest(List.of(row(GameRecordService.WON, 100, null, "HARD", false)))).isNull();
        assertThat(DifficultyRules.suggest(List.of(row(GameRecordService.LOST, 60, null, "BEGINNER", false)))).isNull();
    }

    @Test
    void firstSeriousGameSuggestsEasy() {
        // Пункт 4: после гостевых и учебных партий (или вовсе без партий) — лёгкий уровень.
        for (List<GameRecordEntity> history : List.of(List.<GameRecordEntity>of(),
                List.of(row(GameRecordService.WON, 90, null, "NORMAL", true)))) {
            DifficultyRules.Suggestion suggestion = DifficultyRules.suggest(history);
            assertThat(suggestion.suggested()).isEqualTo(AiSkill.EASY);
            assertThat(suggestion.reason()).isEqualTo(DifficultyRules.Reason.FIRST_GAME);
            assertThat(suggestion.turn()).isNull();
        }
    }

    @Test
    void tutorialGamesAreSkipped() {
        DifficultyRules.Suggestion suggestion = DifficultyRules.suggest(List.of(
                row(GameRecordService.WON, 50, null, "BEGINNER", true),
                row(GameRecordService.LOST, 100, null, "HARD", false)));
        assertThat(suggestion.suggested()).isEqualTo(AiSkill.NORMAL);
    }

    @Test
    void normalLevelIsTheGameBeforeLevels() {
        // Обычный уровень — ровно прежняя игра: на нём мерит прибор балансировки.
        AiSkill normal = AiSkill.DEFAULT;
        assertThat(normal).isEqualTo(AiSkill.NORMAL);
        assertThat(normal.getBuild()).isEqualTo(AiSkill.BuildChoice.PAYBACK);
        assertThat(normal.getRecommendedResearch()).isTrue();
        assertThat(normal.getPurchasesPerTurn()).isEqualTo(1);
        assertThat(normal.getCoalitions()).isTrue();
        assertThat(normal.getOpeningBook()).isFalse();
        assertThat(AiSkill.of(null)).isEqualTo(AiSkill.NORMAL);
        assertThat(AiSkill.of("NOPE")).isEqualTo(AiSkill.NORMAL);
    }

    @Test
    void ladderNeverGetsWorseUpward() {
        // Выше по лестнице — не хуже ни по одному признаку: иначе «сложный» где-то играл бы глупее.
        List<AiSkill> ladder = Arrays.asList(AiSkill.values());
        for (int i = 1; i < ladder.size(); i++) {
            AiSkill lower = ladder.get(i - 1);
            AiSkill upper = ladder.get(i);
            assertThat(upper.getBuild().ordinal()).isLessThanOrEqualTo(lower.getBuild().ordinal());
            assertThat(upper.getPurchasesPerTurn()).isGreaterThanOrEqualTo(lower.getPurchasesPerTurn());
            assertThat(!lower.getRecommendedResearch() || upper.getRecommendedResearch()).isTrue();
            assertThat(!lower.getCoalitions() || upper.getCoalitions()).isTrue();
            assertThat(!lower.getOpeningBook() || upper.getOpeningBook()).isTrue();
        }
    }
}
