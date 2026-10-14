package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.AccountEntitlementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Права учётных записей — backlog-promo, пункт 41. */
public interface AccountEntitlementRepository extends JpaRepository<AccountEntitlementEntity, UUID> {

    List<AccountEntitlementEntity> findAllByAccountIdOrderByStartsAtDesc(UUID accountId);

    /** Права нескольких записей разом — знак раннего доступа у состава (field-backlog, пункт 147). */
    List<AccountEntitlementEntity> findAllByAccountIdInAndKindIn(java.util.Collection<UUID> accountIds,
                                                                 java.util.Collection<String> kinds);

    void deleteByAccountId(UUID accountId);
}
