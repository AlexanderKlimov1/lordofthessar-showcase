package com.sddnw.server.service;

import com.sddnw.server.repository.history.AccountEntitlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Знак «с нами с раннего доступа» — field-backlog, пункт 147: группа с положительной
 * отличительностью зовёт своих.
 * <p>
 * Знак — <b>за время, а не за сумму</b>: его получает и основатель ({@code FOUNDER}), и купивший
 * ранний доступ ({@code EARLY_ACCESS}), поэтому по нему не прочесть, кто сколько заплатил
 * (правило плана продвижения «чего не делаем никогда»). В игре он не даёт ничего. И остаётся,
 * когда срок права вышел: с нами с раннего доступа — это то, что было, а не то, что оплачено сейчас.
 * Своей колонки нет: признак выводится из прав записи (backlog-promo, пункт 41).
 */
@Service
public class EarlyBirdService {

    /** Права, которые говорят «пришёл рано». */
    public static final List<String> KINDS = List.of(EntitlementService.FOUNDER, "EARLY_ACCESS");

    private final AccountEntitlementRepository entitlements;

    public EarlyBirdService(AccountEntitlementRepository entitlements) {
        this.entitlements = entitlements;
    }

    /** Кто из этих записей с нами с раннего доступа — одной выборкой на состав. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public Set<UUID> among(Collection<UUID> accountIds) {
        List<UUID> ids = accountIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        return entitlements.findAllByAccountIdInAndKindIn(ids, KINDS).stream()
                .map(row -> row.getAccountId()).collect(Collectors.toSet());
    }
}
