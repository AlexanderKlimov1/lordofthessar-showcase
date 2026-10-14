package com.sddnw.server.domain.entity.history;

import jakarta.persistence.Column;
import com.sddnw.server.domain.enums.ProblemAnswer;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Сообщение о проблеме — field-backlog, пункт 157, миграция 098.
 * <p>
 * Несёт себя само: партию, ход, экран, версию клиента и последние ошибки запросов и консоли
 * ({@link #context}, JSON строкой — его читает человек, а не правило игры). Игрок дописывает
 * только строку своими словами ({@link #text}).
 */
@Entity
@Table(name = "problem_report", indexes = @Index(name = "idx_problem_report_created", columnList = "created_at"))
public class ProblemReportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "game_id")
    private UUID gameId;

    @Column(name = "turn")
    private Integer turn;

    @Column(name = "client_version", length = 64)
    private String clientVersion;

    @Column(name = "screen", length = 64)
    private String screen;

    @Column(name = "text", nullable = false, length = 2000)
    private String text;

    @Column(name = "context", columnDefinition = "text")
    private String context;

    @Column(name = "resolved", nullable = false)
    private Boolean resolved = Boolean.FALSE;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Ответ хозяина — field-backlog, пункт 158, миграция 101; пусто, пока не ответили. */
    @Enumerated(EnumType.STRING)
    @Column(name = "answer_kind", length = 16)
    private ProblemAnswer answerKind;

    /** Версия, в которой вышло исправление, — у ответа «исправлено». */
    @Column(name = "answer_version", length = 32)
    private String answerVersion;

    /** Слова хозяина: причина «не будем исправлять» или приписка к «исправлено». */
    @Column(name = "answer_text", length = 1000)
    private String answerText;

    @Column(name = "answered_at")
    private OffsetDateTime answeredAt;

    /** Написавший прочёл ответ: показывается один раз. */
    @Column(name = "answer_seen", nullable = false)
    private Boolean answerSeen = Boolean.FALSE;

    public UUID getId() {
        return id;
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

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getScreen() {
        return screen;
    }

    public void setScreen(String screen) {
        this.screen = screen;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public Boolean getResolved() {
        return resolved;
    }

    public void setResolved(Boolean resolved) {
        this.resolved = resolved;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public ProblemAnswer getAnswerKind() {
        return answerKind;
    }

    public void setAnswerKind(ProblemAnswer answerKind) {
        this.answerKind = answerKind;
    }

    public String getAnswerVersion() {
        return answerVersion;
    }

    public void setAnswerVersion(String answerVersion) {
        this.answerVersion = answerVersion;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public OffsetDateTime getAnsweredAt() {
        return answeredAt;
    }

    public void setAnsweredAt(OffsetDateTime answeredAt) {
        this.answeredAt = answeredAt;
    }

    public Boolean getAnswerSeen() {
        return answerSeen;
    }

    public void setAnswerSeen(Boolean answerSeen) {
        this.answerSeen = answerSeen;
    }
}
