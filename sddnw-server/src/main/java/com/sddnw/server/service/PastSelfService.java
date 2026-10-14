package com.sddnw.server.service;

import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.dto.PastSelfDto;
import com.sddnw.server.repository.EmpireHistoryRepository;
import com.sddnw.server.repository.PlayerRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Сравнение с собой прошлым посреди партии — field-backlog, пункт 115: «Инфо» говорит, как
 * игрок идёт против своей лучшей партии той же настройки. Мощь этой партии — из летописи своей
 * империи (своя линия знакома всегда), лучшая — из истории партий учётной записи.
 */
@Service
public class PastSelfService {

    private final GameAccess gameAccess;
    private final PlayerRepository players;
    private final EmpireHistoryRepository history;
    private final EmpireMightRules mightRules;
    private final GameRecordService records;

    public PastSelfService(GameAccess gameAccess, PlayerRepository players, EmpireHistoryRepository history,
                           EmpireMightRules mightRules, GameRecordService records) {
        this.gameAccess = gameAccess;
        this.players = players;
        this.history = history;
        this.mightRules = mightRules;
        this.records = records;
    }

    public PastSelfDto forPlayer(UUID gameId, String accessToken) {
        UUID me = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameAccess.requireGame(gameId);
        PlayerEntity player = players.findById(me).orElseThrow();
        return records.compare(player.getAccountId(), gameId,
                game.getGalaxySize() == null ? null : game.getGalaxySize().name(),
                players.findEmpiresByGameIdOrderBySlotAsc(gameId).size(), mightOf(gameId, me));
    }

    /** Мощь одной империи по ходам — та же мера, что график «Инфо». */
    public Map<Integer, Integer> mightOf(UUID gameId, UUID playerId) {
        Map<Integer, Integer> byTurn = new LinkedHashMap<>();
        for (EmpireHistoryEntity row : history.findAllByGameIdOrderByTurnAsc(gameId)) {
            if (row.getPlayerId().equals(playerId)) {
                byTurn.put(row.getTurn(), mightRules.might(row.getFleetPower(), row.getPopulationK(),
                        row.getProduction(), row.getResearch(), row.getTechnologies()));
            }
        }
        return byTurn;
    }
}
