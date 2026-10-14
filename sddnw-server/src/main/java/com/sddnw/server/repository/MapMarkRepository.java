package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.MapMarkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MapMarkRepository extends JpaRepository<MapMarkEntity, UUID> {

    List<MapMarkEntity> findAllByStudentPlayerIdOrderByTurnAsc(UUID studentPlayerId);

    Optional<MapMarkEntity> findByStudentPlayerIdAndSystemId(UUID studentPlayerId, UUID systemId);

    void deleteAllByStudentPlayerId(UUID studentPlayerId);
}
