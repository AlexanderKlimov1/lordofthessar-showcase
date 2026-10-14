package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.GameMilestoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Летопись главных моментов — field-backlog, пункт 34. */
public interface GameMilestoneRepository extends JpaRepository<GameMilestoneEntity, UUID> {

    /** Летопись партии по порядку: ход, затем игрок и род — порядок задан ради повторимости. */
    List<GameMilestoneEntity> findAllByGameIdOrderByTurnAscPlayerIdAscKindAsc(UUID gameId);

    List<GameMilestoneEntity> findAllByPlayerIdOrderByTurnAsc(UUID playerId);

    Boolean existsByPlayerIdAndKind(UUID playerId, String kind);
}
