package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.GameModEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Копии наборов справочников у партий — field-backlog, пункт 42. */
public interface GameModRepository extends JpaRepository<GameModEntity, UUID> {
}
