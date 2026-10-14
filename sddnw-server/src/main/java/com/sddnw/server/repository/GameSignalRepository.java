package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.GameSignalEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Короткие знаки между людьми партии — field-backlog, пункт 183. */
public interface GameSignalRepository extends JpaRepository<GameSignalEntity, UUID> {

    /** Последние знаки, которые видит игрок: ему, всем или от него самого. */
    @Query("select s from GameSignalEntity s where s.gameId = :gameId and (s.toPlayerId is null"
            + " or s.toPlayerId = :playerId or s.fromPlayerId = :playerId) order by s.createdAt desc")
    List<GameSignalEntity> recentFor(@Param("gameId") UUID gameId, @Param("playerId") UUID playerId, Pageable page);
}
