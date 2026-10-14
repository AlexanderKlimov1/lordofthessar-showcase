package com.sddnw.server.domain.entity;

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
 * Короткий знак между людьми партии — field-backlog, пункт 183: готовая реплика КОДОМ
 * ({@code SignalService.CODES}), которую словарь читателя переводит на его язык. Свободного текста
 * нет нарочно: нечего модерировать, и русский с англичанином понимают друг друга.
 */
/*
 * Индекс объявлен ЗДЕСЬ, а не только в миграции: на H2, где схему строит Hibernate по
 * сущностям (режим балансового прогона), объявленного в changelog'е не существует вовсе.
 */
@Entity
@Table(name = "game_signal", indexes = {@Index(name = "ix_game_signal_game", columnList = "game_id")})
public class GameSignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "from_player_id", nullable = false)
    private UUID fromPlayerId;

    /** Кому; пусто — всем людям партии. */
    @Column(name = "to_player_id")
    private UUID toPlayerId;

    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "turn", nullable = false)
    private Integer turn;

    /**
     * Свободный текст — field-backlog, пункт 16 (решение хозяина проекта 08.10.2026): у строки с кодом
     * {@code TEXT} это сама реплика; у готовых знаков пусто — их переводит словарь читателя.
     */
    @Column(name = "text", length = 400)
    private String text;

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public UUID getFromPlayerId() {
        return fromPlayerId;
    }

    public void setFromPlayerId(UUID fromPlayerId) {
        this.fromPlayerId = fromPlayerId;
    }

    public UUID getToPlayerId() {
        return toPlayerId;
    }

    public void setToPlayerId(UUID toPlayerId) {
        this.toPlayerId = toPlayerId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
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
