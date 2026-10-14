package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;
import com.sddnw.server.dto.WeeklyGalaxyDto;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.history.GameRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Галактика недели — field-backlog, пункт 146 ({@link WeeklyGalaxyRules}): условия недели и итоги
 * по расам из истории партий. Людей в таблицу друг против друга не ставим (пункт 118): «на этой
 * неделе Фелиры взяли больше всех побед» сплачивает, «вы 412-й» — нет.
 */
@Service
public class WeeklyGalaxyService {

    private final GameRepository games;
    private final GameRecordRepository records;

    public WeeklyGalaxyService(GameRepository games, GameRecordRepository records) {
        this.games = games;
        this.records = records;
    }

    @Transactional(value = "historyTransactionManager", readOnly = true)
    public WeeklyGalaxyDto current() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Integer week = WeeklyGalaxyRules.week(today);
        LocalDate monday = WeeklyGalaxyRules.monday(today);
        Map<String, int[]> byRace = new LinkedHashMap<>();
        for (GameRecordEntity row : records.findAllByWeeklyWeek(week)) {
            // Своя раса — одной строкой: код у неё пуст, а имя придумано игроком и в таблице лишнее.
            String race = row.getRaceCode() == null ? "" : row.getRaceCode();
            int[] tally = byRace.computeIfAbsent(race, code -> new int[2]);
            tally[0]++;
            if (GameRecordService.WON.equals(row.getOutcome())) {
                tally[1]++;
            }
        }
        List<WeeklyGalaxyDto.RaceResult> races = new ArrayList<>();
        byRace.forEach((race, tally) -> races.add(
                new WeeklyGalaxyDto.RaceResult(race.isEmpty() ? null : race, tally[0], tally[1])));
        races.sort(Comparator.comparing(WeeklyGalaxyDto.RaceResult::wins, Comparator.reverseOrder())
                .thenComparing(WeeklyGalaxyDto.RaceResult::games, Comparator.reverseOrder())
                .thenComparing(result -> result.raceCode() == null ? "~" : result.raceCode()));
        return new WeeklyGalaxyDto(week, monday, monday.plusDays(6), WeeklyGalaxyRules.GALAXY.name(),
                WeeklyGalaxyRules.EMPIRES, WeeklyGalaxyRules.feature(week), games.countByWeeklyWeek(week), races);
    }
}
