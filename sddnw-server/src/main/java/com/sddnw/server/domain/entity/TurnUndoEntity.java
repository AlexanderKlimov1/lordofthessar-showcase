package com.sddnw.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Действие, которое можно отменить до конца хода, — field-backlog, пункт 74: продажа постройки,
 * выкуп стройки, списание кораблей. «Вы уверены?» после десятого раза нажимают не читая; защищает
 * отмена. Строка помнит, ЧТО вернуть ({@code payload}, JSON), и живёт только свой ход
 * ({@code turn}): после конца хода продажа и выкуп уже сработали в производстве.
 * <p>
 * Индекс объявлен и здесь, а не только в миграции: на H2 схему строит Hibernate.
 */
@Entity
@Table(name = "turn_undo",
    indexes = {
        @Index(name = "ix_turn_undo_player", columnList = "player_id")
    })
public class TurnUndoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "turn", nullable = false)
    private Integer turn;

    /** SELL, BUY или SCRAP. */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    protected TurnUndoEntity() {
    }

    public TurnUndoEntity(UUID gameId, UUID playerId, Integer turn, String kind, String payload) {
        this.gameId = gameId;
        this.playerId = playerId;
        this.turn = turn;
        this.kind = kind;
        this.payload = payload;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public Integer getTurn() {
        return turn;
    }

    public String getKind() {
        return kind;
    }

    public String getPayload() {
        return payload;
    }
}
