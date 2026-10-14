package com.sddnw.server.service;

import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.enums.VictoryKind;
import com.sddnw.server.domain.entity.DiplomacyRelationEntity;
import com.sddnw.server.dto.VictoryClaimDto;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.EmpireHistoryRepository;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * «Признать поражение» — field-backlog, пункт 81.
 * <p>
 * Игроку без надежды оставалось дотягивать партию или бросить её, а брошенная партия
 * запоминается хуже законченной. Признание — законченная: империя человека выходит из партии,
 * а сам он получает сцену конца (пункт 33) с тем, что у него получилось.
 * <p>
 * <b>Против одного ИИ партия на этом кончается</b> ({@link VictoryKind#CONCEDED}): победа отдаётся
 * сильнейшей из оставшихся империй той же мерой, что итог по могуществу, — по последней
 * строке летописи ({@link EmpireMightRules}). Посреди хода спросить мощь у хода нечем, а
 * летопись пишется каждый ход и есть всегда.
 * <p>
 * <b>С другими людьми партия идёт дальше</b>: род игрока становится AI, и его империю ведёт ИИ
 * — как ушедшего, но навсегда: в отличие от ухода (backlog-promo, пункт 11) признание не
 * отменяется возвратом ({@code GameService.rejoin} отказывает). Ждать его больше некому —
 * часы хода ({@code GameService.tick}) досчитают ход сами, если остальные уже закончили.
 */
@Service
public class ConcessionService {

    private static final Logger log = LoggerFactory.getLogger(ConcessionService.class);

    private final GameRepository gameRepository;
    private final GameAccess gameAccess;
    private final PlayerRepository playerRepository;
    private final PlayerRoster playerRoster;
    private final EmpireHistoryRepository historyRepository;
    private final EmpireMightRules empireMightRules;
    private final MilestoneService milestones;
    /** История своих партий (пункт 18): признавший поражение записывается сразу. */
    private final GameRecordService gameRecords;
    private final DiplomacyRelationRepository relationRepository;
    private final Messages messages;

    /**
     * Во сколько раз мощь объявляющего обязана превзойти мощь ВСЕХ прочих живых империй вместе —
     * field-backlog, пункт 154.
     * <p>
     * Число из замера пункта 150 ({@code tools/balance_analyze.py --decided}, 44 партии ИИ со
     * всеми путями к победе): там ни одна империя ни на одном ходу не набрала даже ОДНОЙ доли от
     * суммы остальных — наибольшее 0,74. Втрое — вчетверо за пределом всего, что мерилось:
     * объявить победу можно лишь там, где исход и правда решён, а не там, где лидер просто
     * впереди. Партии людей этот порог проверят заново, когда их наберётся.
     */
    public static final int DECLARE_RATIO = 3;

    public ConcessionService(GameRepository gameRepository, GameAccess gameAccess,
                             PlayerRepository playerRepository, PlayerRoster playerRoster,
                             EmpireHistoryRepository historyRepository, EmpireMightRules empireMightRules,
                             MilestoneService milestones, GameRecordService gameRecords,
                             DiplomacyRelationRepository relationRepository, Messages messages) {
        this.relationRepository = relationRepository;
        this.messages = messages;
        this.gameRecords = gameRecords;
        this.gameRepository = gameRepository;
        this.gameAccess = gameAccess;
        this.playerRepository = playerRepository;
        this.playerRoster = playerRoster;
        this.historyRepository = historyRepository;
        this.empireMightRules = empireMightRules;
        this.milestones = milestones;
    }

    /** Признать поражение; возвращает, кончилась ли этим партия. */
    @Transactional
    public Boolean concede(UUID gameId, String accessToken) {
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        gameAccess.requireInProgress(game);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        PlayerEntity player = players.stream()
                .filter(candidate -> candidate.getId().equals(playerId))
                .findFirst()
                .orElseThrow();
        if (player.getPlayerType() != PlayerType.HUMAN || player.getConcededTurn() != null) {
            throw new ConflictException("concede.notHuman");
        }

        // Момент летописи пишется, пока игрок ещё человек: ИИ летопись не ведётся.
        milestones.note(gameId, player, game.getTurn(), MilestoneService.CONCEDED,
                new MessageKey("milestone.conceded"));
        player.setConcededTurn(game.getTurn());
        player.setPlayerType(PlayerType.AI);
        if (player.getAiPersonality() == null) {
            playerRoster.giveCharacter(player, game, player.getSlot());
        }
        playerRepository.save(player);
        gameRecords.record(game, players, List.of(player), null, null, game.getTurn(), GameRecordService.CONCEDED);

        boolean othersHuman = players.stream()
                .anyMatch(other -> other != player && other.getPlayerType() == PlayerType.HUMAN);
        if (othersHuman) {
            log.info("Игра {}: игрок {} признал поражение на ходу {}, его империю ведёт ИИ",
                    game.getName(), player.getName(), game.getTurn());
            return Boolean.FALSE;
        }

        PlayerEntity winner = strongestOther(game, players, player);
        game.setStatus(GameStatus.FINISHED);
        game.setWinnerPlayerId(winner.getId());
        game.setVictoryKind(VictoryKind.CONCEDED);
        game.setCouncilOpen(Boolean.FALSE);
        game.setFinishedAt(OffsetDateTime.now());
        gameRepository.save(game);
        log.info("Игра {} окончена на ходу {}: {} признал поражение, победила империя {}",
                game.getName(), game.getTurn(), player.getName(), winner.getName());
        return Boolean.TRUE;
    }

    /**
     * Можно ли объявить победу сейчас — field-backlog, пункт 154; правило одно на меню и на
     * само объявление ({@link #declareVictory}). Меню спрашивает ДО нажатия и гасит пункт с
     * причиной (пункт 76: ограничение вместо отказа), а не узнаёт её отказом.
     */
    @Transactional(readOnly = true)
    public VictoryClaimDto victoryClaim(UUID gameId, String accessToken) {
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameRepository.findById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        Claim claim = claim(game, playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId), playerId);
        return new VictoryClaimDto(claim.refusal() == null, claim.own(), claim.others(), DECLARE_RATIO,
                claim.refusal() == null ? null : messages.get(claim.refusal().key(), claim.refusal().args()));
    }

    /**
     * Объявить победу — field-backlog, пункт 154: хвост партии, где исход уже известен, не
     * обязан доигрываться. Партия кончается победой объявившего с пометкой «досрочно»
     * ({@link VictoryKind#DECLARED}).
     */
    @Transactional
    public Boolean declareVictory(UUID gameId, String accessToken) {
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        gameAccess.requireInProgress(game);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        Claim claim = claim(game, players, playerId);
        if (claim.refusal() != null) {
            throw new ConflictException(claim.refusal().key(), claim.refusal().args());
        }
        PlayerEntity player = claim.player();
        game.setStatus(GameStatus.FINISHED);
        game.setWinnerPlayerId(player.getId());
        game.setVictoryKind(VictoryKind.DECLARED);
        game.setCouncilOpen(Boolean.FALSE);
        game.setFinishedAt(OffsetDateTime.now());
        gameRepository.save(game);
        gameRecords.record(game, players, List.of(player), player.getId(), VictoryKind.DECLARED.name(),
                game.getTurn(), null);
        log.info("Игра {} окончена на ходу {}: {} объявил победу, мощь {} против {} у остальных",
                game.getName(), game.getTurn(), player.getName(), claim.own(), claim.others());
        return Boolean.TRUE;
    }

    /** Разбор права объявить победу: мощь своя и всех прочих живых и, если нельзя, — почему. */
    private Claim claim(GameEntity game, List<PlayerEntity> players, UUID playerId) {
        PlayerEntity player = players.stream()
                .filter(candidate -> candidate.getId().equals(playerId))
                .findFirst()
                .orElseThrow();
        if (game.getStatus() != GameStatus.IN_PROGRESS
                || player.getPlayerType() != PlayerType.HUMAN || player.getConcededTurn() != null) {
            return new Claim(player, 0, 0, new MessageKey("declare.notHuman"));
        }
        // С другими людьми за столом победу одного объявить нельзя: согласие всех людей —
        // точка расширения (голосование за столом), а до неё решают ходы.
        boolean othersHuman = players.stream().anyMatch(other -> other != player
                && other.getPlayerType() == PlayerType.HUMAN && other.getConcededTurn() == null);
        if (othersHuman) {
            return new Claim(player, 0, 0, new MessageKey("declare.humans"));
        }
        // Последний замер летописи: она пишется в конце каждого хода по всем империям, и
        // выбывшая империя стоит там с нулём населения.
        List<EmpireHistoryEntity> history = historyRepository.findAllByGameIdOrderByTurnAsc(game.getId());
        if (history.isEmpty()) {
            return new Claim(player, 0, 0, new MessageKey("declare.notYet", 0, 0, DECLARE_RATIO));
        }
        Integer lastTurn = history.get(history.size() - 1).getTurn();
        Map<UUID, Integer> might = new HashMap<>();
        Set<UUID> alive = new HashSet<>();
        for (EmpireHistoryEntity row : history) {
            if (!row.getTurn().equals(lastTurn)) {
                continue;
            }
            might.put(row.getPlayerId(), empireMightRules.might(row.getFleetPower(), row.getPopulationK(),
                    row.getProduction(), row.getResearch(), row.getTechnologies()));
            if (row.getPopulationK() > 0) {
                alive.add(row.getPlayerId());
            }
        }
        // Победу над тем, кого не знаешь, не объявляют: его мощь игроку не видна, и решать за
        // него, что исход известен, нечем. Незнакомые живые — отказ с их числом.
        Set<UUID> known = relationRepository.findAllByPlayerId(player.getId()).stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                .map(DiplomacyRelationEntity::getOtherPlayerId)
                .collect(Collectors.toSet());
        long unknown = players.stream()
                .filter(other -> other != player && alive.contains(other.getId()) && !known.contains(other.getId()))
                .count();
        int own = might.getOrDefault(player.getId(), 0);
        int others = players.stream()
                .filter(other -> other != player && alive.contains(other.getId()))
                .mapToInt(other -> might.getOrDefault(other.getId(), 0))
                .sum();
        if (unknown > 0) {
            return new Claim(player, own, others, new MessageKey("declare.unknown", unknown));
        }
        // Нулевая своя мощь победы не даёт, даже если у остальных тоже ноль: «втрое больше
        // ничего» — это не перевес.
        if (own <= 0 || own < DECLARE_RATIO * others) {
            return new Claim(player, own, others, new MessageKey("declare.notYet", own, others, DECLARE_RATIO));
        }
        return new Claim(player, own, others, null);
    }

    /** Итог разбора: кто, мощь своя и прочих вместе и причина отказа (пусто — можно). */
    private record Claim(PlayerEntity player, Integer own, Integer others, MessageKey refusal) {
    }

    /** Сильнейшая из остальных империй по последнему замеру летописи; замеров нет — первая по месту. */
    private PlayerEntity strongestOther(GameEntity game, List<PlayerEntity> players, PlayerEntity self) {
        Map<UUID, Integer> might = new HashMap<>();
        for (EmpireHistoryEntity row : historyRepository.findAllByGameIdOrderByTurnAsc(game.getId())) {
            // Выборка идёт по ходам, поэтому каждая следующая строка игрока — свежее прежней.
            might.put(row.getPlayerId(), empireMightRules.might(row.getFleetPower(), row.getPopulationK(),
                    row.getProduction(), row.getResearch(), row.getTechnologies()));
        }
        return players.stream()
                .filter(other -> !other.getId().equals(self.getId()))
                .max(Comparator.comparing((PlayerEntity other) -> might.getOrDefault(other.getId(), 0))
                        .thenComparing(other -> -other.getSlot()))
                .orElse(self);
    }
}
