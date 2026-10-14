package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.StewardDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Решения ИИ за ушедшего игрока — field-backlog, пункт 112. */
public interface StewardDecisionRepository extends JpaRepository<StewardDecisionEntity, UUID> {

    List<StewardDecisionEntity> findAllByPlayerIdOrderByTurnAsc(UUID playerId);

    void deleteAllByPlayerId(UUID playerId);
}
