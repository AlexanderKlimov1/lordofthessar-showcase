package com.sddnw.server.service;

import com.sddnw.server.domain.entity.PlayerLeaderEntity;
import com.sddnw.server.domain.enums.LeaderState;
import com.sddnw.server.repository.PlayerLeaderRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Послужной список лидера — field-backlog, пункт 140: бои флота под его командой.
 * <p>
 * Отдельной службой, а не методом {@code LeaderService}: бои решаются в {@code EncounterService}
 * и {@code TacticalBattleService}, а {@code LeaderService} зависит от исследований — кольцо
 * зависимостей, на котором Spring однажды уже не поднял контекст (см. {@link LeaderBonusService}).
 * Здесь только репозиторий. Бой — редкое событие, и выборка на него допустима, как у высадки.
 */
@Service
public class LeaderRecordService {

    private final PlayerLeaderRepository leaders;

    public LeaderRecordService(PlayerLeaderRepository leaders) {
        this.leaders = leaders;
    }

    /**
     * Флот отвоевал: его корабельный лидер запоминает исход. Ничья ({@code won == null}) и флот
     * без лидера не пишутся.
     */
    public void battle(UUID ownerPlayerId, UUID fleetId, Boolean won) {
        if (ownerPlayerId == null || fleetId == null || won == null) {
            return;
        }
        for (PlayerLeaderEntity row : leaders.findAllByPlayerId(ownerPlayerId)) {
            if (row.getState() == LeaderState.HIRED && fleetId.equals(row.getFleetId())) {
                if (won) {
                    row.setBattlesWon(row.getBattlesWon() + 1);
                } else {
                    row.setBattlesLost(row.getBattlesLost() + 1);
                }
                leaders.save(row);
            }
        }
    }
}
