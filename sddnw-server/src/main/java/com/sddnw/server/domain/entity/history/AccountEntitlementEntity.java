package com.sddnw.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Право учётной записи — backlog-promo, пункт 41: что запись купила или получила.
 * <p>
 * Роль для этого не годится: роль не истекает, а подписка истекает, и прав бывает несколько
 * разом (ранний доступ, позже подписка, «издание сторонника», дополнения). Поэтому — строка на
 * право: вид, откуда взялось, с какого и до какого мига (пусто — навсегда), идентификатор
 * платежа посредника. Отзыв (возврат, спор по платежу) строку не стирает, а ставит ей
 * {@code revokedAt}: история прав — это и история денег.
 */
@Entity
@Table(name = "account_entitlement",
    indexes = @Index(name = "ix_account_entitlement_account", columnList = "account_id"))
public class AccountEntitlementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Вид права: {@code FOUNDER}, {@code EARLY_ACCESS}, {@code SUPPORTER} ({@code EntitlementService.KINDS}). */
    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    /** Откуда: {@code PURCHASE} — оплата через посредника, {@code ADMIN} — выдано руками. */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    /** До какого мига действует; пусто — навсегда. */
    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    /** Идентификатор платежа у посредника — по нему уведомления идемпотентны (пункт 43). */
    @Column(name = "payment_id", length = 128)
    private String paymentId;

    /** Когда отозвано (возврат, спор, ошибка); пусто — не отзывалось. */
    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    /** Пояснение выдавшего: за что, по какой просьбе. */
    @Column(name = "note", length = 256)
    private String note;

    /** Действует ли право сейчас: начато, не кончилось и не отозвано. */
    public boolean activeAt(OffsetDateTime now) {
        return revokedAt == null && !startsAt.isAfter(now) && (endsAt == null || endsAt.isAfter(now));
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public OffsetDateTime getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(OffsetDateTime startsAt) {
        this.startsAt = startsAt;
    }

    public OffsetDateTime getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(OffsetDateTime endsAt) {
        this.endsAt = endsAt;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public OffsetDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(OffsetDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
