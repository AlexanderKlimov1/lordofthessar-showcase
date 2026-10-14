package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.ShipTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Библиотека проектов кораблей учётной записи — field-backlog, пункт 96. */
public interface ShipTemplateRepository extends JpaRepository<ShipTemplateEntity, UUID> {

    List<ShipTemplateEntity> findAllByAccountIdOrderByNameAsc(UUID accountId);

    long countByAccountId(UUID accountId);

    void deleteByAccountId(UUID accountId);
}
