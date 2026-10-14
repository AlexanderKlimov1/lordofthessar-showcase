package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.enums.AccountRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Учётные записи игроков — п. 3.1. */
public interface AccountRepository extends JpaRepository<AccountEntity, UUID> {

    /**
     * Запись по логину без оглядки на регистр: «Admin» и «admin» — один и тот же человек,
     * и заводить их двоих нельзя.
     */
    Optional<AccountEntity> findByLoginIgnoreCase(String login);

    Optional<AccountEntity> findByEmailIgnoreCase(String email);

    /** Запись по ссылке из письма — п. 3.1. */
    Optional<AccountEntity> findByConfirmToken(String confirmToken);

    /** Отписка от писем о ходе ключом из письма — field-backlog, пункт 22. */
    Optional<AccountEntity> findByNotifyToken(String notifyToken);

    /** Запись по ссылке сброса пароля — backlog-promo, пункт 45. */
    Optional<AccountEntity> findByResetToken(String resetToken);

    Boolean existsByRole(AccountRole role);
}
