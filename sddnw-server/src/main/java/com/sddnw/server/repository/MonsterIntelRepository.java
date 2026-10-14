package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.MonsterIntelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Память игроков о сторожах систем — п. 11.1, п. 15. */
public interface MonsterIntelRepository extends JpaRepository<MonsterIntelEntity, UUID> {

    List<MonsterIntelEntity> findAllByGameId(UUID gameId);

    List<MonsterIntelEntity> findAllByPlayerId(UUID playerId);
}
