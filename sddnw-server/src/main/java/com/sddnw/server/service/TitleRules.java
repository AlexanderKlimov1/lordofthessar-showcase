package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;
import com.sddnw.server.domain.enums.VictoryKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Звания за трудное — field-backlog, пункт 41.
 * <p>
 * Мотивации «завершение» игра не давала ничего: собирать было нечего. Звание — <b>сведение о
 * трудном, а не плата за присутствие</b> (пункт 20): за число ходов, дни входа и просто сыгранные
 * партии званий нет. Считаются они при чтении из строк истории — второй правды о них нет, — и
 * только по партиям, сыгранным всерьёз: доигранным (признанное поражение не в счёт), не учебным и
 * не гостевым (там правила мягче), записанным после миграции 130 (у прежних строк нечем судить).
 * <p>
 * «На высокой сложности» — победа против сложного ИИ (пункт 9: уровни есть, и сложный играет лучшие
 * дебюты своей расы, а не получает прибавки). «Хозяин стола» (пункт 67) — партии, которые запись
 * завела и ДОИГРАЛА с другими людьми: собрать стол и довести его до конца трудно, а просто
 * заведённая партия — это присутствие.
 */
public final class TitleRules {

    /** Победа каждой готовой расой — сколько из них. */
    public static final String RACES = "RACES";
    /** Победа каждым путём. */
    public static final String PATHS = "PATHS";
    /** Победа без единой войны. */
    public static final String PEACE = "PEACE";
    /** Тессар взят у Стража. */
    public static final String WARDEN = "WARDEN";
    /** Корабль взят абордажем. */
    public static final String BOARDING = "BOARDING";
    /** Победа против сложного ИИ (пункт 9). */
    public static final String HARD = "HARD";
    /** Доигранные с людьми партии, которые запись завела сама (пункт 67). */
    public static final String HOST = "HOST";

    /** Сколько собранных и доигранных столов нужно хозяину. */
    public static final int HOST_GOAL = 5;

    /** Пути к победе, которые надо пройти: досрочная победа и признание поражения — не пути. */
    static final List<String> PATH_KINDS = List.of(VictoryKind.CONQUEST.name(), VictoryKind.COUNCIL.name(),
            VictoryKind.WARDENHOLD.name(), VictoryKind.MIGHT.name());

    private TitleRules() {
    }

    /**
     * Звание с ходом к нему.
     *
     * @param progress сколько пройдено (у одиночных званий — 0 или 1)
     * @param goal     сколько нужно
     */
    public record Title(String code, Boolean earned, Integer progress, Integer goal) {
    }

    /** Партия, сыгранная всерьёз: только она и говорит о трудном. */
    static boolean counts(GameRecordEntity row) {
        return row.getAtWar() != null && !Boolean.TRUE.equals(row.getTutorial())
                && !GameRecordService.CONCEDED.equals(row.getOutcome());
    }

    /**
     * Звания записи по её партиям.
     *
     * @param readyRaces сколько готовых рас в справочнике — у звания «каждой расой» цель от него
     */
    public static List<Title> titles(List<GameRecordEntity> rows, Integer readyRaces) {
        List<GameRecordEntity> serious = rows.stream().filter(TitleRules::counts).toList();
        List<GameRecordEntity> won = serious.stream()
                .filter(row -> GameRecordService.WON.equals(row.getOutcome())).toList();
        Set<String> races = won.stream().map(GameRecordEntity::getRaceCode).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> paths = won.stream().map(GameRecordEntity::getVictoryKind)
                .filter(PATH_KINDS::contains).collect(Collectors.toSet());
        boolean peace = won.stream().anyMatch(row -> !Boolean.TRUE.equals(row.getAtWar()));
        boolean warden = serious.stream().anyMatch(row -> Boolean.TRUE.equals(row.getWarden()));
        boolean boarding = serious.stream().anyMatch(row -> Boolean.TRUE.equals(row.getTrophy()));
        boolean hard = won.stream().anyMatch(row -> com.sddnw.server.domain.enums.AiSkill.HARD.name()
                .equals(row.getAiSkill()));
        int hosted = (int) serious.stream().filter(row -> Boolean.TRUE.equals(row.getHosted())).count();
        List<Title> titles = new ArrayList<>();
        titles.add(new Title(RACES, races.size() >= readyRaces, races.size(), readyRaces));
        titles.add(new Title(PATHS, paths.size() >= PATH_KINDS.size(), paths.size(), PATH_KINDS.size()));
        titles.add(single(PEACE, peace));
        titles.add(single(WARDEN, warden));
        titles.add(single(BOARDING, boarding));
        titles.add(single(HARD, hard));
        titles.add(new Title(HOST, hosted >= HOST_GOAL, Math.min(hosted, HOST_GOAL), HOST_GOAL));
        return titles;
    }

    /** Какие звания принесла именно эта партия: заработанные с ней, но не без неё. */
    public static List<String> earnedBy(List<GameRecordEntity> rows, java.util.UUID gameId, Integer readyRaces) {
        Set<String> before = titles(rows.stream().filter(row -> !row.getGameId().equals(gameId)).toList(), readyRaces)
                .stream().filter(title -> Boolean.TRUE.equals(title.earned())).map(Title::code)
                .collect(Collectors.toSet());
        return titles(rows, readyRaces).stream()
                .filter(title -> Boolean.TRUE.equals(title.earned()) && !before.contains(title.code()))
                .map(Title::code).toList();
    }

    private static Title single(String code, boolean earned) {
        return new Title(code, earned, earned ? 1 : 0, 1);
    }
}
