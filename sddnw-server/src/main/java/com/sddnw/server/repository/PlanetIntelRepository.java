package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.PlanetIntelEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Память игроков о чужих колониях — п. 15, туман войны. */
public interface PlanetIntelRepository extends JpaRepository<PlanetIntelEntity, UUID> {

    /**
     * Вся память партии одной выборкой: фаза памяти и фаза ИИ спрашивают её каждый ход, а
     * выборка «на игрока» изнутри посчитанного хода сбрасывает в базу весь ход.
     */
    List<PlanetIntelEntity> findAllByGameIdOrderByPlayerIdAscPlanetIdAsc(UUID gameId);

    List<PlanetIntelEntity> findAllByPlayerId(UUID playerId);
}
