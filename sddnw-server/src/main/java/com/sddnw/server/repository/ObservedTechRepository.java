package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.ObservedTechEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Увиденные технологии соперников — п. 15, туман войны. */
public interface ObservedTechRepository extends JpaRepository<ObservedTechEntity, UUID> {

    /** Всё увиденное в партии одной выборкой: бой и фаза памяти спрашивают его разом. */
    List<ObservedTechEntity> findAllByGameId(UUID gameId);

    List<ObservedTechEntity> findAllByPlayerIdAndOtherPlayerId(UUID playerId, UUID otherPlayerId);
}
