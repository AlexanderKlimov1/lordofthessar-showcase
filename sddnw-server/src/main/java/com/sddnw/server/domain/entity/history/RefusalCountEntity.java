package com.sddnw.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Сколько раз живые игроки получили отказ с этим ключом — field-backlog, пункт 77, миграция 117.
 * <p>
 * По Норману частая ошибка игрока — вина дизайна: отказ, который слышат часто, отмечает место,
 * где интерфейс позволил сделать то, чего нельзя, или не объяснил почему. Строка — ключ отказа,
 * число и когда был последний; ни игрока, ни партии здесь нет — нужен ответ «где», а не «кто».
 */
@Entity
@Table(name = "refusal_count")
public class RefusalCountEntity {

    /** Ключ отказа из {@code messages*.properties} — `colony.cannotQueue`, `fleet.outOfRange`… */
    @Id
    @Column(name = "message_key", nullable = false, length = 120)
    private String messageKey;

    @Column(name = "refusals", nullable = false)
    private Long refusals = 0L;

    @Column(name = "last_at", nullable = false)
    private OffsetDateTime lastAt;

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public Long getRefusals() {
        return refusals;
    }

    public void setRefusals(Long refusals) {
        this.refusals = refusals;
    }

    public OffsetDateTime getLastAt() {
        return lastAt;
    }

    public void setLastAt(OffsetDateTime lastAt) {
        this.lastAt = lastAt;
    }
}
