package com.sddnw.server.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Галактика недели — field-backlog, пункт 146: условия недели и итоги по расам.
 *
 * @param week     номер недели ГГГГНН
 * @param from     понедельник недели (UTC)
 * @param until    воскресенье — последний день, когда партия этой недели заводится
 * @param feature  особенность галактики недели (пункт 94)
 * @param started  сколько партий недели начато
 * @param races    итоги по расам, лучшие первыми: доигранные партии и победы; расы, собранные
 *                 игроками, идут одной строкой без кода — таблица про расы, а не про людей
 */
public record WeeklyGalaxyDto(Integer week, LocalDate from, LocalDate until, String galaxySize, Integer empires,
                              String feature, Long started, List<RaceResult> races) {

    public record RaceResult(String raceCode, Integer games, Integer wins) {
    }
}
