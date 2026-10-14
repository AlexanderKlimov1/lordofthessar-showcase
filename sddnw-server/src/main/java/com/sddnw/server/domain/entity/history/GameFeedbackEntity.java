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
 * Отзыв после партии — field-backlog, пункты 46 и 187: оценка, что понравилось больше всего и
 * необязательная строка.
 * <p>
 * Живёт в источнике данных ИСТОРИИ, рядом с историей партий (пункт 18): партию потом удаляют, а
 * отзыв остаётся. Поэтому здесь не ссылки, а значения из строки истории — исход, путь к победе,
 * галактика и число империй: по ним видно, на какой партии и каким путём игра нравится больше.
 * <b>Отказ отвечать — тоже строка</b>, с пустой оценкой: так он запоминается, и следующий вопрос
 * приходит не раньше, чем через {@code GameFeedbackService.ASK_EVERY} партий.
 */
@Entity
@Table(name = "game_feedback",
    uniqueConstraints = @UniqueConstraint(name = "uq_game_feedback", columnNames = {"account_id", "game_id"}),
    indexes = @Index(name = "ix_game_feedback_account", columnList = "account_id"))
public class GameFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    /** От 1 до 5; пусто — игрок отказался отвечать. */
    @Column(name = "rating")
    private Integer rating;

    /** Что понравилось больше всего (пункт 187): HARD_WIN, DISCOVERY, BUILDING, PEOPLE. */
    @Column(name = "liked", length = 16)
    private String liked;

    @Column(name = "comment", length = 500)
    private String comment;

    @Column(name = "outcome", length = 16)
    private String outcome;

    @Column(name = "victory_kind", length = 16)
    private String victoryKind;

    @Column(name = "galaxy_size", length = 16)
    private String galaxySize;

    @Column(name = "empires")
    private Integer empires;

    @Column(name = "turn")
    private Integer turn;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getLiked() {
        return liked;
    }

    public void setLiked(String liked) {
        this.liked = liked;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getVictoryKind() {
        return victoryKind;
    }

    public void setVictoryKind(String victoryKind) {
        this.victoryKind = victoryKind;
    }

    public String getGalaxySize() {
        return galaxySize;
    }

    public void setGalaxySize(String galaxySize) {
        this.galaxySize = galaxySize;
    }

    public Integer getEmpires() {
        return empires;
    }

    public void setEmpires(Integer empires) {
        this.empires = empires;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
