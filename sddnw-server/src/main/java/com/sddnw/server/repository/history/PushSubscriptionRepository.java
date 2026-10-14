package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.PushSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Подписки браузеров на Web Push — field-backlog, пункт 22. */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionEntity, UUID> {

    List<PushSubscriptionEntity> findAllByAccountIdOrderByCreatedAtAsc(UUID accountId);

    Optional<PushSubscriptionEntity> findByEndpoint(String endpoint);

    long countByAccountId(UUID accountId);

    void deleteByAccountId(UUID accountId);
}
