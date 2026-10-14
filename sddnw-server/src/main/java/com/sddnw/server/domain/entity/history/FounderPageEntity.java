package com.sddnw.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Страница пакета основателя — миграция 100.
 * <p>
 * Одна строка на сервер ({@link #MAIN}): страницу правят и сохраняют целиком. Текст на обоих
 * языках лежит JSON строкой ({@link #content}) — его читает экран, а не правило игры.
 */
@Entity
@Table(name = "founder_page")
public class FounderPageEntity {

    /** Опознаватель единственной строки. */
    public static final String MAIN = "main";

    @Id
    @Column(name = "id", nullable = false, length = 16)
    private String id;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
