package com.sddnw.server.service;

import com.sddnw.server.domain.enums.GalaxyFeature;
import com.sddnw.server.domain.enums.GalaxySize;
import com.sddnw.server.dto.CreateGameRequest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;

/**
 * Галактика недели — field-backlog, пункт 146: одно зерно на неделю для всех, бесплатно. Каждый
 * играет ту же галактику против того же ИИ, одиночной партией; итоги — по расам, а не по людям
 * (пункт 118: людей друг против друга в таблицу не ставим).
 * <p>
 * Условия одни на всех — решение хозяина проекта 08.10.2026: малая галактика, четыре империи,
 * обычные правила со всеми путями к победе и особенность галактики недели (пункт 94); расу игрок
 * выбирает сам. Неделя — ISO, по UTC: смена в полночь понедельника одна для всех часовых поясов.
 */
public final class WeeklyGalaxyRules {

    public static final GalaxySize GALAXY = GalaxySize.SMALL;
    public static final int EMPIRES = 4;

    private WeeklyGalaxyRules() {
    }

    /** Номер недели вида ГГГГНН (202641) — ключ партий и итогов недели. */
    public static Integer week(LocalDate day) {
        return day.get(IsoFields.WEEK_BASED_YEAR) * 100 + day.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    public static Integer currentWeek() {
        return week(LocalDate.now(ZoneOffset.UTC));
    }

    /** Первый день недели — для подписи «неделя с 6 октября». */
    public static LocalDate monday(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * Зерно недели: из её номера, перемешанное, чтобы соседние недели не давали соседних
     * галактик. Повторимость партии по зерну проверена прогонами (этап 0).
     */
    public static Long seed(Integer week) {
        long mixed = week * 0x9E3779B97F4A7C15L;
        mixed ^= (mixed >>> 31);
        return mixed * 0xBF58476D1CE4E5B9L;
    }

    /** Особенность галактики недели — жребием от её зерна (пункт 94). */
    public static String feature(Integer week) {
        return GalaxyFeature.resolve(GalaxyFeature.RANDOM, seed(week));
    }

    /** Запрос партии недели: условия назначает сервер, расу и имена — игрок. */
    public static CreateGameRequest restrict(CreateGameRequest request, Integer week) {
        return new CreateGameRequest(
                request.name(),
                GALAXY,
                request.playerName(),
                request.homeStarName(),
                request.raceCode(),
                request.raceName(),
                request.raceTraits(),
                seed(week),
                Boolean.TRUE,
                Boolean.FALSE,
                EMPIRES,
                Boolean.TRUE,
                Boolean.TRUE,
                request.banner(),
                // Удержание Тессара и итог по могуществу — все пути к победе, как в обычной партии.
                Boolean.TRUE,
                Boolean.TRUE,
                // Без срока хода: партия одиночная, торопить некого.
                null,
                Boolean.FALSE,
                Boolean.FALSE,
                request.raceTemplateId(),
                Boolean.FALSE,
                feature(week),
                Boolean.TRUE);
    }
}
