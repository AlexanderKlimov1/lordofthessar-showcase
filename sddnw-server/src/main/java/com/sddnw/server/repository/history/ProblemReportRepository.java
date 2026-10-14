package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.ProblemReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Сообщения о проблемах — field-backlog, пункт 157: свежие сверху. */
public interface ProblemReportRepository extends JpaRepository<ProblemReportEntity, UUID> {

    List<ProblemReportEntity> findAllByOrderByCreatedAtDesc();

    Long countByResolvedFalse();

    /** Ответы хозяина, которых написавший ещё не видел, — пункт 158; старые первыми. */
    List<ProblemReportEntity> findAllByAccountIdAndAnsweredAtNotNullAndAnswerSeenFalseOrderByCreatedAtAsc(UUID accountId);

    void deleteByAccountId(UUID accountId);
}
