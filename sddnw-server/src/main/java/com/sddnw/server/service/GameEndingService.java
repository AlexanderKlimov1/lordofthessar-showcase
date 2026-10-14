package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameMilestoneEntity;
import com.sddnw.server.domain.entity.EmpireActivityEntity;
import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.enums.AccountRole;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.VictoryKind;
import com.sddnw.server.dto.CouncilDto;
import com.sddnw.server.dto.CouncilVoterDto;
import com.sddnw.server.dto.GameEndingDto;
import com.sddnw.server.repository.EmpireActivityRepository;
import com.sddnw.server.repository.EmpireHistoryRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сцена конца партии — field-backlog, пункт 33.
 * <p>
 * Конец партии сейчас был одной строкой отчёта хода («Победа: покорение галактики»), а его
 * вспоминают, решая, вернуться ли. Здесь собирается рассказ целиком: путь к победе, причина
 * конца словами и с числами (правило «потеря называет причину», backlog-promo, пункт 6),
 * лучший миг самого игрока и график мощи всех империй.
 * <p>
 * <b>Спрашивает учётная запись, а не пропуск игрока:</b> сцену открывают и из партии, и
 * из «Ваших партий» главного меню, где пропуска места может уже не быть (другая машина). Своё
 * место в партии узнаётся по записи — так же, как при возврате ({@code GameService.rejoin}).
 * <p>
 * <b>Ничего нового не считается и не хранится:</b> летопись империй ({@code empire_history})
 * и счётчики действий ({@code empire_activity}) пишутся всю партию и так. Пересчитывать
 * задним числом, кроме них, было бы нечем — поэтому и мерило мощи то же, что у «Инфо».
 */
@Service
public class GameEndingService {

    private final GameAccess gameAccess;
    private final PlayerRepository playerRepository;
    private final EmpireHistoryRepository historyRepository;
    private final EmpireActivityRepository activityRepository;
    private final EmpireMightRules empireMightRules;
    private final CouncilService councilService;
    private final Messages messages;
    private final MilestoneService milestones;
    /** История партий (пункт 115): лучшая своя партия той же настройки. */
    private final GameRecordService gameRecords;
    /** Новая партия тем же составом (пункт 149): собирается ли она ещё. */
    private final com.sddnw.server.repository.GameRepository gameRepository;
    private final com.sddnw.server.repository.ShipDesignRepository designRepository;
    private final RaceTraitCatalog raceTraitCatalog;

    /**
     * Название расы игрока на языке читателя — пункт 98: своя раса — тем именем, что дал игрок,
     * готовая — названием из справочника по коду (имени у неё в строке игрока нет).
     */
    private String raceNameOf(PlayerEntity player) {
        if (player.getRaceName() != null && !player.getRaceName().isBlank()) {
            return player.getRaceName();
        }
        return raceTraitCatalog.readyRaces().stream()
                .filter(race -> race.code().equals(player.getRaceCode()))
                .map(RaceTraitCatalog.ReadyRace::name)
                .findFirst()
                .orElse(null);
    }

    public GameEndingService(GameAccess gameAccess, PlayerRepository playerRepository,
                             EmpireHistoryRepository historyRepository,
                             EmpireActivityRepository activityRepository,
                             EmpireMightRules empireMightRules, CouncilService councilService,
                             Messages messages, MilestoneService milestones,
                             GameRecordService gameRecords,
                             com.sddnw.server.repository.GameRepository gameRepository,
                             com.sddnw.server.repository.ShipDesignRepository designRepository,
                             RaceTraitCatalog raceTraitCatalog) {
        this.raceTraitCatalog = raceTraitCatalog;
        this.gameRepository = gameRepository;
        this.designRepository = designRepository;
        this.gameRecords = gameRecords;
        this.milestones = milestones;
        this.gameAccess = gameAccess;
        this.playerRepository = playerRepository;
        this.historyRepository = historyRepository;
        this.activityRepository = activityRepository;
        this.empireMightRules = empireMightRules;
        this.councilService = councilService;
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public GameEndingDto ending(UUID gameId, AccountEntity account) {
        GameEntity game = gameAccess.requireGame(gameId);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        PlayerEntity self = players.stream()
                .filter(player -> account.getId().equals(player.getAccountId()))
                .findFirst()
                .orElseThrow(() -> new ForbiddenException("game.notYourGame"));
        // Признавший поражение (пункт 81) партию для себя закончил, даже если она идёт дальше
        // без него: итоги ему открыты сразу, а победителя у его конца может и не быть.
        boolean conceded = self.getConcededTurn() != null;
        // Продолженная после победы партия (пункт 131) — тоже выигранная: итоги той победы открыты.
        boolean finished = (game.getStatus() == GameStatus.FINISHED || Boolean.TRUE.equals(game.getPlayedOn()))
                && game.getWinnerPlayerId() != null;
        if (!finished && !conceded) {
            throw new ConflictException("ending.notFinished");
        }
        PlayerEntity winner = finished
                ? players.stream()
                        .filter(player -> player.getId().equals(game.getWinnerPlayerId()))
                        .findFirst()
                        .orElse(null)
                : null;

        // Летопись по империям, в порядке ходов (выборка уже отсортирована).
        Map<UUID, List<EmpireHistoryEntity>> rows = new LinkedHashMap<>();
        for (EmpireHistoryEntity row : historyRepository.findAllByGameIdOrderByTurnAsc(gameId)) {
            rows.computeIfAbsent(row.getPlayerId(), id -> new ArrayList<>()).add(row);
        }
        Map<UUID, Map<Integer, Integer>> might = new HashMap<>();
        Map<Integer, Integer> galaxyMight = new HashMap<>();
        for (Map.Entry<UUID, List<EmpireHistoryEntity>> entry : rows.entrySet()) {
            Map<Integer, Integer> byTurn = new LinkedHashMap<>();
            for (EmpireHistoryEntity row : entry.getValue()) {
                Integer value = empireMightRules.might(row.getFleetPower(), row.getPopulationK(),
                        row.getProduction(), row.getResearch(), row.getTechnologies());
                byTurn.put(row.getTurn(), value);
                galaxyMight.merge(row.getTurn(), value, Integer::sum);
            }
            might.put(entry.getKey(), byTurn);
        }

        List<GameEndingDto.EmpireLine> history = players.stream()
                .map(player -> new GameEndingDto.EmpireLine(
                        player.getId(),
                        messages.empire(player.getName()),
                        player.getColor(),
                        player.getId().equals(self.getId()),
                        winner != null && game.winners().contains(player.getId()),
                        might.getOrDefault(player.getId(), Map.of()).entrySet().stream()
                                .map(point -> new GameEndingDto.Point(point.getKey(), point.getValue()))
                                .toList()))
                .toList();

        // Мерило — последний замер партии, а у сдавшегося — последний ДО признания: что было
        // потом, делал уже ИИ.
        Integer lastTurn = galaxyMight.keySet().stream()
                .filter(turn -> !conceded || turn <= self.getConcededTurn())
                .max(Integer::compare).orElse(game.getTurn());
        Integer myMight = mightAt(might, self.getId(), lastTurn);
        Integer winnerMight = winner == null ? 0 : mightAt(might, winner.getId(), lastTurn);
        int place = 1;
        for (PlayerEntity other : players) {
            if (!other.getId().equals(self.getId()) && mightAt(might, other.getId(), lastTurn) > myMight) {
                place++;
            }
        }

        // Союзник, разделивший покорение (пункт 145), победил наравне с первым.
        Boolean won = !conceded && winner != null && game.winners().contains(self.getId());
        String winnerName = winner == null ? null : messages.empire(winner.getName());
        VictoryKind kind = conceded ? VictoryKind.CONCEDED : game.getVictoryKind();
        String reason = conceded && game.getVictoryKind() != VictoryKind.CONCEDED
                ? messages.get("ending.reason.CONCEDED.away", self.getConcededTurn(), myMight, place, players.size())
                : reason(game, players, self, winner, winnerName, won,
                        rows.getOrDefault(self.getId(), List.of()), myMight, winnerMight, place);

        return new GameEndingDto(
                game.getName(),
                conceded ? self.getConcededTurn() : game.getTurn(),
                kind.name(),
                messages.label(kind),
                winner == null ? null : winner.getId(),
                winnerName,
                winner == null ? null : winner.getColor(),
                won,
                reason,
                place,
                players.size(),
                achievements(gameId, self.getId(), rows.getOrDefault(self.getId(), List.of()),
                        might.getOrDefault(self.getId(), Map.of()), galaxyMight),
                history,
                chronicle(self.getId()),
                story(self.getId()),
                firsts(gameId, players, self, rows),
                hardship(won, might, self, players.size()),
                winnerPath(gameId, winner, winnerName, self, rows),
                gameRecords.compare(account.getId(), gameId,
                        game.getGalaxySize() == null ? null : game.getGalaxySize().name(), players.size(),
                        might.getOrDefault(self.getId(), Map.of())),
                account.getRole() == AccountRole.GUEST,
                rematch(game, players, account.getId()),
                raceNameOf(self),
                GameRecordService.craftedDesigns(designRepository.findAllByGameId(gameId), self.getId())
                        .entrySet().stream().limit(5)
                        .map(entry -> new com.sddnw.server.dto.OwnDesignDto(entry.getKey(), entry.getValue()))
                        .toList(),
                gameRecords.titlesEarnedBy(account.getId(), gameId),
                gameRecords.peersOf(account.getId(), gameId),
                gameRecords.ratingOf(account.getId(), gameId));
    }

    /**
     * Сыграть снова тем же составом — пункт 149: только когда за столом были другие люди. Завёл ли
     * её кто-то, видно по ссылке у оконченной партии; новая должна ещё собираться (лобби).
     */
    private GameEndingDto.Rematch rematch(GameEntity game, List<PlayerEntity> players, UUID accountId) {
        long humans = players.stream().filter(player -> player.getAccountId() != null).count();
        if (humans < 2) {
            return null;
        }
        if (game.getRematchGameId() == null) {
            return new GameEndingDto.Rematch(Boolean.FALSE, null, Boolean.FALSE);
        }
        GameEntity next = gameRepository.findById(game.getRematchGameId()).orElse(null);
        if (next == null || next.getStatus() != GameStatus.LOBBY) {
            return new GameEndingDto.Rematch(Boolean.FALSE, null, Boolean.FALSE);
        }
        List<PlayerEntity> seated = playerRepository.findEmpiresByGameIdOrderBySlotAsc(next.getId());
        String by = seated.stream().filter(player -> player.getId().equals(next.getHostPlayerId()))
                .map(player -> messages.empire(player.getName())).findFirst().orElse(null);
        return new GameEndingDto.Rematch(Boolean.TRUE, by,
                seated.stream().anyMatch(player -> accountId.equals(player.getAccountId())));
    }

    /**
     * Путь победителя — пункт 106: когда расселился, сколько колоний, флота и технологий было к
     * контрольным ходам, сколько войн и боёв. Всё — из летописи империй и счётчиков партии.
     */
    private GameEndingDto.WinnerPath winnerPath(UUID gameId, PlayerEntity winner, String winnerName,
                                                PlayerEntity self, Map<UUID, List<EmpireHistoryEntity>> rows) {
        if (winner == null) {
            return null;
        }
        List<EmpireHistoryEntity> path = rows.getOrDefault(winner.getId(), List.of());
        Integer firstColonyTurn = path.stream().filter(row -> row.getColonies() > 1)
                .map(EmpireHistoryEntity::getTurn).findFirst().orElse(null);
        List<GameEndingDto.Checkpoint> checkpoints = new ArrayList<>();
        for (Integer checkpoint : CheckpointRules.TURNS) {
            path.stream().filter(row -> row.getTurn() <= checkpoint)
                    .reduce((first, second) -> second)
                    .filter(row -> path.get(path.size() - 1).getTurn() >= checkpoint)
                    .ifPresent(row -> checkpoints.add(new GameEndingDto.Checkpoint(checkpoint, row.getColonies(),
                            row.getFleetPower(), row.getTechnologies())));
        }
        Map<String, Integer> times = new HashMap<>();
        for (EmpireActivityEntity row : activityRepository.findAllByGameIdOrderByPlayerIdAscCodeAsc(gameId)) {
            if (row.getPlayerId().equals(winner.getId())) {
                times.merge(row.getCode(), row.getTimes(), Integer::sum);
            }
        }
        return new GameEndingDto.WinnerPath(winnerName, winner.getColor(), winner.getId().equals(self.getId()),
                firstColonyTurn, checkpoints,
                times.getOrDefault(EmpireActivityService.WAR, 0),
                times.getOrDefault(EmpireActivityService.BATTLE, 0),
                times.getOrDefault(EmpireActivityService.CAPTURE, 0)
                        + times.getOrDefault(EmpireActivityService.MIND_CONTROL, 0));
    }

    /**
     * Победа помнит, какой трудной была, — пункт 185: «вы победили, уступая в мощи на 40 % на
     * 120-м ходу, против трёх империй». Мера — {@link HardshipRules} по той же летописи мощи, что
     * график сцены; лёгкую победу не раздуваем — отставания нет, и строки нет.
     */
    private String hardship(Boolean won, Map<UUID, Map<Integer, Integer>> might, PlayerEntity self, int empires) {
        if (!Boolean.TRUE.equals(won)) {
            return null;
        }
        HardshipRules.Deficit deficit = HardshipRules.deepest(might, self.getId());
        return deficit == null ? null
                : messages.get("ending.hardship", deficit.percent(), deficit.turn(), empires - 1);
    }

    /**
     * Каждый лучший в чём-то — пункт 116: первые места по летописи империй (наибольшее за партию,
     * у технологий — к концу) и по счётчикам партии. Числа — только записанные; правило отбора —
     * {@link FirstPlaceRules}. Соседей не прячем: партия кончилась, как и на графике мощи.
     */
    private List<GameEndingDto.FirstPlace> firsts(UUID gameId, List<PlayerEntity> players, PlayerEntity self,
                                                  Map<UUID, List<EmpireHistoryEntity>> rows) {
        Map<String, Map<UUID, Integer>> table = FirstPlaceRules.table();
        for (PlayerEntity player : players) {
            int population = 0;
            int production = 0;
            int research = 0;
            int fleet = 0;
            int technologies = 0;
            int colonies = 0;
            for (EmpireHistoryEntity row : rows.getOrDefault(player.getId(), List.of())) {
                population = Math.max(population, row.getPopulationK());
                production = Math.max(production, row.getProduction());
                research = Math.max(research, row.getResearch());
                fleet = Math.max(fleet, row.getFleetPower());
                technologies = Math.max(technologies, row.getTechnologies());
                colonies = Math.max(colonies, row.getColonies());
            }
            table.get("POPULATION").put(player.getId(), population);
            table.get("PRODUCTION").put(player.getId(), production);
            table.get("RESEARCH").put(player.getId(), research);
            table.get("FLEET").put(player.getId(), fleet);
            table.get("TECHNOLOGIES").put(player.getId(), technologies);
            table.get("COLONIES").put(player.getId(), colonies);
            for (String axis : List.of("COLONIZED", "BATTLES", "CAPTURED", "MONSTERS")) {
                table.get(axis).put(player.getId(), 0);
            }
        }
        for (EmpireActivityEntity row : activityRepository.findAllByGameIdOrderByPlayerIdAscCodeAsc(gameId)) {
            String axis = switch (row.getCode()) {
                case EmpireActivityService.COLONIZED -> "COLONIZED";
                case EmpireActivityService.BATTLE -> "BATTLES";
                case EmpireActivityService.CAPTURE, EmpireActivityService.MIND_CONTROL -> "CAPTURED";
                case EmpireActivityService.MONSTER -> "MONSTERS";
                default -> null;
            };
            if (axis != null && table.get(axis).containsKey(row.getPlayerId())) {
                table.get(axis).merge(row.getPlayerId(), row.getTimes(), Integer::sum);
            }
        }
        Map<UUID, PlayerEntity> byId = new HashMap<>();
        players.forEach(player -> byId.put(player.getId(), player));
        return FirstPlaceRules.firsts(table).stream()
                .map(place -> new GameEndingDto.FirstPlace(place.axis(),
                        place.holders().stream()
                                .map(id -> new GameEndingDto.Holder(messages.empire(byId.get(id).getName()),
                                        byId.get(id).getColor(), id.equals(self.getId())))
                                .toList(),
                        place.value(),
                        place.holders().contains(self.getId())))
                .toList();
    }

    /**
     * «Как это было» — пункт 143: сильнейшие строки всей хроники правилом {@link StoryRules},
     * на языке читателя. Берётся вся хроника, не только пики: перелом и падение соседа пиками
     * не считаются, а историю делают именно они.
     */
    private List<GameEndingDto.Moment> story(UUID playerId) {
        return StoryRules.story(milestones.chronicle(playerId)).stream()
                .map(row -> new GameEndingDto.Moment(row.getTurn(), row.getKind(),
                        messages.event(row.getMessageKey(), row.getArgs() == null ? List.of() : row.getArgs())))
                .toList();
    }

    /**
     * Летопись главных моментов игрока (пункт 34) — по ходам, на языке читателя: хранится
     * она ключом с подстановками, как отчёт хода, и переводится здесь, при чтении.
     */
    private List<GameEndingDto.Moment> chronicle(UUID playerId) {
        return milestones.chronicle(playerId).stream()
                // Сцене конца — только пики (пункт 34); прочее хроники живёт в «Инфо» (пункт 138).
                .filter(GameMilestoneEntity::isPeak)
                .map(row -> new GameEndingDto.Moment(row.getTurn(), row.getKind(),
                        messages.event(row.getMessageKey(), row.getArgs() == null ? List.of() : row.getArgs())))
                .toList();
    }

    private static Integer mightAt(Map<UUID, Map<Integer, Integer>> might, UUID playerId, Integer turn) {
        return might.getOrDefault(playerId, Map.of()).getOrDefault(turn, 0);
    }

    /**
     * Почему партия кончилась — словами и с числами. Ключ свой у каждого пути и у каждой
     * стороны: «вы удержали» и «он удержал» — разные фразы, а не одна с подстановкой.
     */
    private String reason(GameEntity game, List<PlayerEntity> players, PlayerEntity self,
                          PlayerEntity winner, String winnerName, Boolean won,
                          List<EmpireHistoryEntity> myRows, Integer myMight, Integer winnerMight,
                          int place) {
        VictoryKind kind = game.getVictoryKind();
        String side = won ? "won" : "lost";
        return switch (kind) {
            case CONQUEST -> {
                if (won) {
                    Integer colonies = myRows.isEmpty() ? 0 : myRows.get(myRows.size() - 1).getColonies();
                    yield messages.get("ending.reason.CONQUEST.won", game.getTurn(), colonies);
                }
                // Последний ход, когда у игрока была колония: с него и начинается его поражение.
                Integer lastHeld = myRows.stream()
                        .filter(row -> row.getColonies() > 0)
                        .map(EmpireHistoryEntity::getTurn)
                        .reduce((first, second) -> second)
                        .orElse(null);
                yield lastHeld == null || lastHeld >= game.getTurn()
                        ? messages.get("ending.reason.CONQUEST.lostNow", winnerName, game.getTurn())
                        : messages.get("ending.reason.CONQUEST.lost", winnerName, game.getTurn(), lastHeld + 1);
            }
            case WARDENHOLD -> {
                Integer since = game.getWardenholdSinceTurn() == null
                        ? game.getTurn() - WardenholdRules.HOLD_TURNS
                        : game.getWardenholdSinceTurn();
                yield won
                        ? messages.get("ending.reason.WARDENHOLD.won", WardenholdRules.HOLD_TURNS, since, game.getTurn())
                        : messages.get("ending.reason.WARDENHOLD.lost", winnerName, WardenholdRules.HOLD_TURNS,
                                since, myMight, winnerMight);
            }
            case COUNCIL -> {
                CouncilDto council = councilService.session(game, self, players);
                int votes = 0;
                int total = 0;
                int required = 0;
                if (council != null) {
                    total = council.totalVotes();
                    required = council.requiredVotes();
                    for (CouncilVoterDto voter : council.voters()) {
                        if (winner.getId().equals(voter.choicePlayerId())) {
                            votes += voter.weight();
                        }
                    }
                }
                yield won
                        ? messages.get("ending.reason.COUNCIL.won", votes, total, required)
                        : messages.get("ending.reason.COUNCIL.lost", winnerName, votes, total, required);
            }
            case CONCEDED -> messages.get("ending.reason.CONCEDED.lost", game.getTurn(), winnerName,
                    winnerMight, myMight);
            case DECLARED -> messages.get("ending.reason.DECLARED", game.getTurn(), myMight,
                    ConcessionService.DECLARE_RATIO);
            case MIGHT -> messages.get("ending.reason.MIGHT." + side, game.getTurn(), winnerName, winnerMight,
                    myMight, place, players.size());
        };
    }

    /**
     * Что вышло у игрока. Лучший миг — ход, когда его доля во всей мощи галактики была
     * наибольшей: абсолютная мощь растёт у всех к концу партии сама, и «лучшим» тогда
     * оказывался бы последний ход даже у того, кого разбили.
     */
    private GameEndingDto.Achievements achievements(UUID gameId, UUID playerId, List<EmpireHistoryEntity> rows,
                                                     Map<Integer, Integer> myMight,
                                                     Map<Integer, Integer> galaxyMight) {
        int bestTurn = 0;
        int bestShare = 0;
        for (Map.Entry<Integer, Integer> point : myMight.entrySet()) {
            int total = galaxyMight.getOrDefault(point.getKey(), 0);
            int share = total == 0 ? 0 : point.getValue() * 100 / total;
            if (share > bestShare) {
                bestShare = share;
                bestTurn = point.getKey();
            }
        }
        int peakColonies = 0;
        int peakPopulation = 0;
        int technologies = 0;
        for (EmpireHistoryEntity row : rows) {
            peakColonies = Math.max(peakColonies, row.getColonies());
            peakPopulation = Math.max(peakPopulation, row.getPopulationK());
            technologies = row.getTechnologies();
        }
        Map<String, Integer> times = new HashMap<>();
        for (EmpireActivityEntity row : activityRepository.findAllByGameIdOrderByPlayerIdAscCodeAsc(gameId)) {
            if (row.getPlayerId().equals(playerId)) {
                times.put(row.getCode(), row.getTimes());
            }
        }
        return new GameEndingDto.Achievements(
                bestTurn == 0 ? null : bestTurn,
                bestShare,
                peakColonies,
                peakPopulation,
                technologies,
                times.getOrDefault(EmpireActivityService.COLONIZED, 0),
                times.getOrDefault(EmpireActivityService.CAPTURE, 0)
                        + times.getOrDefault(EmpireActivityService.MIND_CONTROL, 0),
                times.getOrDefault(EmpireActivityService.COLONY_LOST, 0),
                times.getOrDefault(EmpireActivityService.BATTLE, 0),
                times.getOrDefault(EmpireActivityService.WAR, 0));
    }
}
