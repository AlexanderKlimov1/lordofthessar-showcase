package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.GameFeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Отзывы после партии — field-backlog, пункты 46 и 187. */
public interface GameFeedbackRepository extends JpaRepository<GameFeedbackEntity, UUID> {

    Boolean existsByAccountIdAndGameId(UUID accountId, UUID gameId);

    /** Последний ответ или отказ записи: от него отсчитываются партии до следующего вопроса. */
    Optional<GameFeedbackEntity> findFirstByAccountIdOrderByCreatedAtDesc(UUID accountId);

    /** Для администратора — свежие сверху; отказы (без оценки) не нужны и не берутся. */
    List<GameFeedbackEntity> findAllByRatingIsNotNullOrderByCreatedAtDesc();
}
