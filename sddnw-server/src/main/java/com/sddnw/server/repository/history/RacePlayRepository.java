package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.RacePlayEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Партии, сыгранные чужими расами, — field-backlog, пункт 99. */
public interface RacePlayRepository extends JpaRepository<RacePlayEntity, UUID> {

    List<RacePlayEntity> findAllByTemplateIdIn(Collection<UUID> templateIds);

    Optional<RacePlayEntity> findByAccountIdAndGameId(UUID accountId, UUID gameId);
}
