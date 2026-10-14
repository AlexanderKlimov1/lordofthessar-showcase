package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.TurnActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Действия живых игроков по ходам — field-backlog, пункт 13. */
public interface TurnActionRepository extends JpaRepository<TurnActionEntity, UUID> {

    Optional<TurnActionEntity> findByGameIdAndPlayerIdAndTurnAndAction(UUID gameId, UUID playerId,
                                                                      Integer turn, String action);
}
