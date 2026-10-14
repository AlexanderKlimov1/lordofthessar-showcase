package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.FunnelStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Шаги воронки новичка — field-backlog, пункт 2. */
public interface FunnelStepRepository extends JpaRepository<FunnelStepEntity, UUID> {

    /** Последний шаг посетителя: повтор того же шага подряд не пишется. */
    Optional<FunnelStepEntity> findFirstByVisitorOrderByCreatedAtDesc(String visitor);

    Long countByVisitor(String visitor);

    /** Шаги за срок, по времени — разбор воронки идёт по порядку пути. */
    List<FunnelStepEntity> findAllByCreatedAtAfterOrderByCreatedAtAsc(OffsetDateTime since);

    void deleteByAccountId(UUID accountId);
}
