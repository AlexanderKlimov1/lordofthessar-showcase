package com.sddnw.server.service;

import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.GameMilestoneEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.dto.GameSummaryDto;
import com.sddnw.server.repository.EmpireHistoryRepository;
import com.sddnw.server.repository.GameMilestoneRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * «Ваши партии» напоминают контекст — field-backlog, пункт 109.
 * <p>
 * Строка списка была названием и размером галактики, и по ней нельзя было решить, куда
 * вернуться. Отложенная партия с ясной незавершённостью тянет назад, а партия, о которой
 * ничего не сказано, забывается, — поэтому строка говорит главное: ждут ли ВАШЕГО хода, своё
 * место и последний главный момент летописи («потеряна колония Вега»).
 * <p>
 * Новых чисел здесь нет: место — по той же мощи, что первая линия «Инфо» (последний замер
 * летописи), момент — из летописи (пункт 34). Запросов по два на партию списка — а партий в
 * нём горсть, и выборка идёт вне посчитанного хода.
 */
@Service
public class MyGamesContextService {

    private final EmpireHistoryRepository historyRepository;
    private final GameMilestoneRepository milestoneRepository;
    private final EmpireMightRules empireMightRules;
    private final Messages messages;

    public MyGamesContextService(EmpireHistoryRepository historyRepository,
                                 GameMilestoneRepository milestoneRepository,
                                 EmpireMightRules empireMightRules, Messages messages) {
        this.historyRepository = historyRepository;
        this.milestoneRepository = milestoneRepository;
        this.empireMightRules = empireMightRules;
        this.messages = messages;
    }

    public GameSummaryDto.MineContextDto context(GameEntity game, PlayerEntity seat, List<PlayerEntity> players) {
        long empires = players.stream().filter(player -> player.getPlayerType() != PlayerType.MONSTER).count();
        Integer place = null;
        List<EmpireHistoryEntity> rows = historyRepository.findAllByGameIdAndTurn(game.getId(), game.getTurn() - 1);
        if (!rows.isEmpty()) {
            Map<UUID, Integer> might = new HashMap<>();
            for (EmpireHistoryEntity row : rows) {
                might.put(row.getPlayerId(), empireMightRules.might(row.getFleetPower(), row.getPopulationK(),
                        row.getProduction(), row.getResearch(), row.getTechnologies()));
            }
            Integer mine = might.getOrDefault(seat.getId(), 0);
            place = 1 + (int) might.entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(seat.getId()) && entry.getValue() > mine)
                    .count();
        }
        List<GameMilestoneEntity> moments = milestoneRepository.findAllByPlayerIdOrderByTurnAsc(seat.getId());
        GameMilestoneEntity last = moments.isEmpty() ? null : moments.get(moments.size() - 1);
        Boolean yourMove = game.getStatus() == GameStatus.IN_PROGRESS
                && seat.getPlayerType() == PlayerType.HUMAN
                && seat.getEndedTurn() < game.getTurn();
        return new GameSummaryDto.MineContextDto(
                place,
                (int) empires,
                yourMove,
                last == null ? null : messages.event(last.getMessageKey(), last.getArgs() == null ? List.of() : last.getArgs()),
                last == null ? null : last.getTurn(),
                seat.getLastActionAt());
    }
}
