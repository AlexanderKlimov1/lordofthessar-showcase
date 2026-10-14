package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.ModSetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Наборы справочников основателя — field-backlog, пункт 42: по названию. */
public interface ModSetRepository extends JpaRepository<ModSetEntity, UUID> {

    List<ModSetEntity> findAllByAccountIdOrderByNameAsc(UUID accountId);

    void deleteByAccountId(UUID accountId);
}
