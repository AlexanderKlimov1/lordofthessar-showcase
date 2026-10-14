package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.history.GameRecordEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.dto.LiveStatsDto;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.history.AccountRepository;
import com.sddnw.server.repository.history.GameRecordRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Признаки живой игры — field-backlog, пункт 45 (09.10.2026: пока видны только администратору и
 * модератору — решение хозяина проекта). Сколько партий доиграно за неделю, сколько людей сейчас в
 * сетевых партиях и чем кончились последние партии.
 * <p>
 * <b>Числа только настоящие:</b> учебные партии, служебные партии сквозного прогона ({@link #HARNESS})
 * и строки удалённых записей (временные записи прогона) в счёт не идут; балансовые партии в историю
 * не пишутся вовсе — людей в них нет. «Сейчас» — человек действовал в сетевой партии (двое людей и
 * больше) последние {@link #ONLINE_MINUTES} минут. Порог показа ({@link #PUBLIC_THRESHOLD}) сказан
 * рядом: «двое онлайн» хуже, чем ничего, и, когда блок выйдет к игрокам, ниже порога его не будет.
 */
@Service
public class LiveStatsService {

    /** Сколько минут после последнего действия человек считается «сейчас в партии». */
    public static final int ONLINE_MINUTES = 15;
    /** Ниже скольких людей сейчас блок игрокам не показывали бы вовсе (пункт 45). */
    public static final int PUBLIC_THRESHOLD = 5;
    /** Сколько последних партий показывать. */
    private static final int LAST_GAMES = 6;
    /** Имена служебных партий сквозного прогона — те же, что у его уборки ({@code HARNESS_NAMES}). */
    private static final List<String> HARNESS = List.of("Регресс", "Наблюдение", "Расселение", "Тактика",
            "Баланс прогон", "Прогон", "Дипломатия", "Шпионаж", "Лидеры", "Сохранение", "Загруженная",
            "Совет", "Находки", "Часы");

    private final GameRecordRepository records;
    private final AccountRepository accounts;
    private final GameRepository games;
    private final PlayerRepository players;
    private final PresenceService presence;

    public LiveStatsService(GameRecordRepository records, AccountRepository accounts, GameRepository games,
                            PlayerRepository players, PresenceService presence) {
        this.records = records;
        this.accounts = accounts;
        this.games = games;
        this.players = players;
        this.presence = presence;
    }

    @Transactional(value = "historyTransactionManager", readOnly = true)
    public LiveStatsDto live() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime weekAgo = now.minusDays(7);
        List<GameRecordEntity> rows = records.findAll().stream()
                .filter(row -> !Boolean.TRUE.equals(row.getTutorial()))
                .filter(row -> HARNESS.stream().noneMatch(prefix -> row.getGameName().startsWith(prefix)))
                .toList();
        Set<UUID> alive = new HashSet<>();
        accounts.findAllById(rows.stream().map(GameRecordEntity::getAccountId).collect(Collectors.toSet()))
                .forEach(account -> alive.add(account.getId()));
        rows = rows.stream().filter(row -> alive.contains(row.getAccountId())).toList();

        Integer weekGames = (int) rows.stream()
                .filter(row -> row.getFinishedAt().isAfter(weekAgo))
                .map(GameRecordEntity::getGameId)
                .distinct()
                .count();

        // Последние партии: строка победившего человека, а без неё — лучшего по месту.
        Map<UUID, GameRecordEntity> byGame = new LinkedHashMap<>();
        rows.stream()
                .sorted(Comparator.comparing(GameRecordEntity::getFinishedAt).reversed())
                .forEach(row -> byGame.merge(row.getGameId(), row, (kept, other) ->
                        other.getPlace() < kept.getPlace() ? other : kept));
        Map<UUID, Long> humans = rows.stream()
                .collect(Collectors.groupingBy(GameRecordEntity::getGameId, Collectors.counting()));
        List<LiveStatsDto.LastGame> last = byGame.values().stream()
                .limit(LAST_GAMES)
                .map(row -> new LiveStatsDto.LastGame(row.getRaceName(), row.getOutcome(), row.getVictoryKind(),
                        row.getTurn(), row.getEmpires(), humans.get(row.getGameId()).intValue(), row.getFinishedAt()))
                .toList();

        return new LiveStatsDto(weekGames, onlineInNetworkGames(now), PUBLIC_THRESHOLD, ONLINE_MINUTES, last);
    }

    /** Люди, действовавшие недавно в идущих партиях, где за столом двое людей и больше. */
    private Integer onlineInNetworkGames(OffsetDateTime now) {
        List<GameEntity> running = games.findAllByStatusOrderByCreatedAtDesc(GameStatus.IN_PROGRESS,
                PageRequest.of(0, 1000));
        List<PlayerEntity> seated = players.findAllByGameIdInOrderBySlotAsc(
                running.stream().map(GameEntity::getId).toList());
        Map<UUID, List<PlayerEntity>> byGame = seated.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .collect(Collectors.groupingBy(player -> player.getGame().getId()));
        return (int) byGame.values().stream()
                .filter(table -> table.size() >= 2)
                .flatMap(List::stream)
                .filter(player -> {
                    OffsetDateTime last = presence.lastAction(player.getId());
                    return last != null && Duration.between(last, now).toMinutes() < ONLINE_MINUTES;
                })
                .count();
    }
}
