package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.RaceTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Свои расы учётной записи — field-backlog, пункты 26 и 176. */
public interface RaceTemplateRepository extends JpaRepository<RaceTemplateEntity, UUID> {

    List<RaceTemplateEntity> findAllByAccountIdOrderByNameAsc(UUID accountId);

    long countByAccountId(UUID accountId);

    void deleteByAccountId(UUID accountId);
}
