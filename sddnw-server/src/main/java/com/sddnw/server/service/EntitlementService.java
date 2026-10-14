package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.AccountEntitlementEntity;
import com.sddnw.server.dto.EntitlementDto;
import com.sddnw.server.repository.history.AccountEntitlementRepository;
import com.sddnw.server.repository.history.AccountRepository;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Права учётных записей — backlog-promo, пункт 41.
 * <p>
 * Здесь только то, что не зависит от посредника: какие права у записи сейчас, выдача и отзыв
 * руками. Приём уведомлений об оплате (пункт 43) и проверка доступа на сервере (пункт 42) встанут
 * сюда же, когда решены путь оплаты (пункт 38) и граница «бесплатно / платно» (пункт 40): тогда
 * {@link #has} и станет той проверкой, что сейчас делает {@code requireRegistered}.
 */
@Service
public class EntitlementService {

    private static final Logger log = LoggerFactory.getLogger(EntitlementService.class);

    /**
     * Виды прав. Новый вид — строка здесь, а не новая колонка: прав будет больше.
     * <ul>
     *   <li>{@code FOUNDER} — основатель: пакет за 100 $ или пожертвование от 100 $ (08.10.2026,
     *       решение хозяина проекта; прежде это право звалось EARLY_ACCESS, миграция 135). Открывает
     *       страницы прибора — баланс рас и «как сделано» ({@link #FOUNDER});</li>
     *   <li>{@code EARLY_ACCESS} — ранний доступ: дешевле пакета и сверх обычного игрока не даёт ничего;</li>
     *   <li>{@code SUPPORTER} — поддержал игру.</li>
     * </ul>
     */
    public static final Set<String> KINDS = Set.of("FOUNDER", "EARLY_ACCESS", "SUPPORTER");

    /** Право основателя — field-backlog, пункты 47, 70, 101: оно открывает страницы прибора. */
    public static final String FOUNDER = "FOUNDER";

    public static final String SOURCE_ADMIN = "ADMIN";
    public static final String SOURCE_PURCHASE = "PURCHASE";

    private final AccountEntitlementRepository entitlements;
    private final AccountRepository accounts;
    private final AccountService accountService;

    public EntitlementService(AccountEntitlementRepository entitlements, AccountRepository accounts,
                              AccountService accountService) {
        this.entitlements = entitlements;
        this.accounts = accounts;
        this.accountService = accountService;
    }

    /** Есть ли у записи право этого вида сейчас. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public Boolean has(UUID accountId, String kind) {
        OffsetDateTime now = OffsetDateTime.now();
        return entitlements.findAllByAccountIdOrderByStartsAtDesc(accountId).stream()
                .anyMatch(row -> row.getKind().equals(kind) && row.activeAt(now));
    }

    /** Права записи со всей историей — администратору. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<EntitlementDto> list(String adminToken, UUID accountId) {
        accountService.requireAdmin(adminToken);
        OffsetDateTime now = OffsetDateTime.now();
        return entitlements.findAllByAccountIdOrderByStartsAtDesc(accountId).stream()
                .map(row -> dto(row, now))
                .toList();
    }

    /** Выдать право руками — для тестов, наград турниров и спорных возвратов. */
    @Transactional("historyTransactionManager")
    public EntitlementDto grant(String adminToken, UUID accountId, String kind, Integer days, String note) {
        accountService.requireAdmin(adminToken);
        if (!KINDS.contains(kind)) {
            throw new BadRequestException("entitlement.kind", kind);
        }
        accounts.findById(accountId).orElseThrow(() -> new NotFoundException("auth.accountNotFound", accountId));
        // Начало — с точностью до секунды НАЗАД (13.10.2026, трек техдолга, пункт 30): база хранит
        // время, округлённое до микросекунд, и право, выданное ровно «сейчас», при чтении следующим
        // же запросом бывало «в будущем» на доли миллисекунды — запись его не видела.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        AccountEntitlementEntity row = new AccountEntitlementEntity();
        row.setAccountId(accountId);
        row.setKind(kind);
        row.setSource(SOURCE_ADMIN);
        row.setStartsAt(now);
        row.setEndsAt(days == null ? null : now.plusDays(days));
        row.setNote(note == null || note.isBlank() ? null : note.strip());
        entitlements.save(row);
        log.info("Записи {} выдано право {} руками{}", accountId, kind, days == null ? "" : " на " + days + " дн.");
        return dto(row, now);
    }

    /** Отозвать право: строка остаётся, отмечается миг отзыва. */
    @Transactional("historyTransactionManager")
    public EntitlementDto revoke(String adminToken, UUID entitlementId) {
        accountService.requireAdmin(adminToken);
        AccountEntitlementEntity row = entitlements.findById(entitlementId)
                .orElseThrow(() -> new NotFoundException("entitlement.notFound", entitlementId));
        if (row.getRevokedAt() == null) {
            row.setRevokedAt(OffsetDateTime.now());
            entitlements.save(row);
            log.info("Право {} записи {} отозвано", row.getKind(), row.getAccountId());
        }
        return dto(row, OffsetDateTime.now());
    }

    private EntitlementDto dto(AccountEntitlementEntity row, OffsetDateTime now) {
        return new EntitlementDto(row.getId(), row.getKind(), row.getSource(), row.getStartsAt(),
                row.getEndsAt(), row.getPaymentId(), row.getRevokedAt(), row.getNote(), row.activeAt(now));
    }
}
