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
 * Шаг воронки новичка — field-backlog, пункт 2, миграция 097: где был человек, когда ушёл.
 * <p>
 * Строка — одно событие пути: экран входа, гостевой старт, ход партии, шаг обучения, итоги
 * хода. Последняя строка посетителя и есть ответ «где ушёл». Личных данных здесь нет:
 * посетитель — случайный номер браузера, запись — её идентификатор.
 */
@Entity
@Table(name = "funnel_step", indexes = {
        @Index(name = "idx_funnel_step_visitor", columnList = "visitor, created_at"),
        @Index(name = "idx_funnel_step_created", columnList = "created_at")})
public class FunnelStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    /** Случайный номер браузера: до входа записи нет, а уход с экрана входа — первый вопрос. */
    @Column(name = "visitor", nullable = false, length = 40)
    private String visitor;

    /** Чья запись, если человек уже вошёл; пусто — до входа. */
    @Column(name = "account_id")
    private UUID accountId;

    /** Роль записи на миг шага: гость и игрок уходят по разным причинам. */
    @Column(name = "role", length = 16)
    private String role;

    @Column(name = "step", nullable = false, length = 64)
    private String step;

    @Column(name = "game_id")
    private UUID gameId;

    @Column(name = "turn")
    private Integer turn;

    /** Подробность шага: коды событий итогов хода, номер шага обучения. */
    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public String getVisitor() {
        return visitor;
    }

    public void setVisitor(String visitor) {
        this.visitor = visitor;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStep() {
        return step;
    }

    public void setStep(String step) {
        this.step = step;
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

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
