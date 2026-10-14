package com.sddnw.server.service;

import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.PlayerLeaderEntity;
import com.sddnw.server.domain.entity.PlayerTechnologyEntity;
import com.sddnw.server.domain.enums.LeaderState;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlayerLeaderRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Гибель корабельного лидера и спасательные капсулы — п. 6 (13.10.2026, правило MOO II).
 */
class LeaderCasualtyTest {

    private final UUID owner = UUID.randomUUID();
    private final FleetEntity fleet = fleet();
    private final PlayerLeaderRepository leaders = mock(PlayerLeaderRepository.class);
    private final PlayerTechnologyRepository technologies = mock(PlayerTechnologyRepository.class);
    private final GameRepository games = mock(GameRepository.class);
    private final PlayerRepository players = mock(PlayerRepository.class);
    private final PlayerEventService events = mock(PlayerEventService.class);
    private final LeaderCasualtyService service =
            new LeaderCasualtyService(leaders, technologies, games, events, players);

    @Test
    @DisplayName("Флот разбит целиком — лидер погибает наверняка, даже с капсулами")
    void wipedFleetKillsLeader() {
        PlayerLeaderEntity admiral = aboard();
        knows(LeaderCasualtyService.SURVIVAL_PODS);
        service.afterLosses(fleet, 4, 4, 1);
        assertThat(admiral.getState()).isEqualTo(LeaderState.KILLED);
        assertThat(admiral.getFleetId()).isNull();
    }

    @Test
    @DisplayName("С капсулами и уцелевшим кораблём лидер не гибнет никогда")
    void podsSaveLeaderWhileFleetLives() {
        PlayerLeaderEntity admiral = aboard();
        knows(LeaderCasualtyService.SURVIVAL_PODS);
        // Погибли три корабля из четырёх: без капсул это три шанса из четырёх.
        service.afterLosses(fleet, 4, 3, 1);
        assertThat(admiral.getState()).isEqualTo(LeaderState.HIRED);
    }

    @Test
    @DisplayName("Без потерь лидеру ничего не грозит")
    void noLossNoRisk() {
        PlayerLeaderEntity admiral = aboard();
        knows();
        service.afterLosses(fleet, 4, 0, 1);
        assertThat(admiral.getState()).isEqualTo(LeaderState.HIRED);
    }

    private PlayerLeaderEntity aboard() {
        PlayerLeaderEntity row = new PlayerLeaderEntity();
        row.setPlayerId(owner);
        row.setLeaderCode("kirsus");
        row.setState(LeaderState.HIRED);
        row.setFleetId(fleet.getId());
        when(leaders.findAllByPlayerId(owner)).thenReturn(List.of(row));
        when(games.findById(any())).thenReturn(Optional.empty());
        return row;
    }

    private void knows(String... codes) {
        List<PlayerTechnologyEntity> rows = new java.util.ArrayList<>();
        for (String code : codes) {
            PlayerTechnologyEntity row = mock(PlayerTechnologyEntity.class);
            when(row.getOptionCode()).thenReturn(code);
            rows.add(row);
        }
        when(technologies.findAllByPlayerIdIn(any())).thenReturn(rows);
    }

    private FleetEntity fleet() {
        FleetEntity entity = new FleetEntity();
        entity.setId(UUID.randomUUID());
        entity.setGameId(UUID.randomUUID());
        entity.setOwnerPlayerId(owner);
        entity.setShips(4);
        return entity;
    }
}
