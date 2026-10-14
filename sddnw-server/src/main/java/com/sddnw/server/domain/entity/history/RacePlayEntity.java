package com.sddnw.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Партия, сыгранная чужой расой, — field-backlog, пункт 99, миграция 130.
 * <p>
 * Созданное ценится выше, когда им пользуются другие. Строка заводится, когда игрок начинает
 * партию расой, взятой по ссылке, и указывает на расу АВТОРА ({@code templateId}); исход
 * дописывается в конце партии теми же руками, что пишут историю ({@code GameRecordService}).
 * Автор видит только числа — кто сыграл, ему не говорят.
 */
@Entity
@Table(name = "race_play",
        indexes = @Index(name = "idx_race_play_template", columnList = "template_id"),
        uniqueConstraints = @UniqueConstraint(name = "uq_race_play_account_game", columnNames = {"account_id", "game_id"}))
public class RacePlayEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    /** Исход партии ({@code GameRecordService.WON} и соседи); пусто — партия ещё не окончена. */
    @Column(name = "outcome", length = 16)
    private String outcome;

    @Column(name = "started_at", nullable = false)
    private OffsetDateTime startedAt;

    public UUID getId() {
        return id;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public void setTemplateId(UUID templateId) {
        this.templateId = templateId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }
}
