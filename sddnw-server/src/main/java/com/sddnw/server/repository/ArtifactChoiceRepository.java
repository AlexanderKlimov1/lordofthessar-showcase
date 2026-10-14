package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.ArtifactChoiceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Наследие ушедших, ждущее выбора человека, — backlog-promo, пункт 34. */
public interface ArtifactChoiceRepository extends JpaRepository<ArtifactChoiceEntity, UUID> {

    List<ArtifactChoiceEntity> findAllByPlayerIdOrderByTurnAsc(UUID playerId);

    /** Ждущие в партии — по ходу и месту: партия обязана повторяться. */
    List<ArtifactChoiceEntity> findAllByGameIdOrderByTurnAsc(UUID gameId);
}
