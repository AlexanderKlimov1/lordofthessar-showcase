package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;
import com.sddnw.server.domain.enums.AiSkill;

import java.util.List;

/**
 * Сложность следующей партии по итогу прошлой — field-backlog, пункт 10.
 * <p>
 * Коридор потока движется вместе с умением, но двигает его ИГРОК: правило только ПРЕДЛАГАЕТ уровень
 * в окне новой игры и только МЕЖДУ партиями. Подстройка ИИ посреди партии, замеченная игроком,
 * читается кражей победы; единственное выравнивание внутри партии — открытый перекос бед к лидеру.
 * <p>
 * Итог берётся из истории партий (пункт 18) — последняя всерьёз сыгранная партия (не учебная, не
 * по гостевым правилам). Пороги — реконструкция, числа в одном месте:
 * <ul>
 *   <li><b>легко</b> — победа не позже {@link #EASY_WIN_TURN} хода и ни разу не отставал от
 *       сильнейшего на заметную долю ({@link HardshipRules#NOTABLE_PERCENT}, глубина отставания
 *       уже пишется в историю для званий) — предложить уровень выше;</li>
 *   <li><b>рано проиграл</b> — поражение или признанное поражение не позже {@link #EARLY_LOSS_TURN}
 *       хода — предложить ниже.</li>
 * </ul>
 * Всё прочее — ровная партия, и предлагать нечего: «победил с трудом» и есть поток.
 * <p>
 * <b>Первая партия по обычным правилам</b> (field-backlog, пункт 4): всерьёз сыгранных партий в
 * истории нет — были только гостевые и учебные или не было никаких. Гость привык к богатому старту
 * и науке вдвое, и первая обычная партия отнимает всё это разом; лёгкий уровень смягчает скачок, не
 * трогая правил, — сами правила остаются настоящими, и об их отличии говорит памятка первых ходов.
 */
public final class DifficultyRules {

    /** Победа до этого хода без отставания — лёгкая. */
    public static final int EASY_WIN_TURN = 200;

    /** Поражение до этого хода — раннее. */
    public static final int EARLY_LOSS_TURN = 150;

    /** Причина подсказки: легко победил или рано проиграл. */
    public enum Reason { EASY_WIN, EARLY_LOSS, FIRST_GAME }

    /**
     * Подсказка: уровень прошлой партии, предложенный и почему.
     *
     * @param last      уровень прошлой партии
     * @param suggested предлагаемый уровень
     * @param reason    почему
     * @param turn      ход, на котором прошлая партия кончилась; пусто у первой партии
     */
    public record Suggestion(AiSkill last, AiSkill suggested, Reason reason, Integer turn) {
    }

    private DifficultyRules() {
    }

    /** Подсказка по истории, свежей партией первой; {@code null} — предлагать нечего. */
    public static Suggestion suggest(List<GameRecordEntity> newestFirst) {
        GameRecordEntity last = newestFirst.stream()
                .filter(row -> !Boolean.TRUE.equals(row.getTutorial()))
                .findFirst()
                .orElse(null);
        if (last == null) {
            return new Suggestion(AiSkill.DEFAULT, AiSkill.EASY, Reason.FIRST_GAME, null);
        }
        AiSkill skill = AiSkill.of(last.getAiSkill());
        Integer turn = last.getTurn();
        if (GameRecordService.WON.equals(last.getOutcome()) && turn != null && turn <= EASY_WIN_TURN
                && last.getDeficitTurn() == null && skill != AiSkill.HARD) {
            return new Suggestion(skill, skill.harder(), Reason.EASY_WIN, turn);
        }
        boolean lost = GameRecordService.LOST.equals(last.getOutcome())
                || GameRecordService.CONCEDED.equals(last.getOutcome());
        if (lost && turn != null && turn <= EARLY_LOSS_TURN && skill != AiSkill.BEGINNER) {
            return new Suggestion(skill, skill.easier(), Reason.EARLY_LOSS, turn);
        }
        return null;
    }
}
