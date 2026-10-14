package com.sddnw.server.domain.entity;

import com.sddnw.server.domain.EventArgsConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.List;
import java.util.UUID;

/**
 * Решение, которое ИИ принял за ушедшего человека, — field-backlog, пункт 112.
 * <p>
 * Отчёт хода у игрока один, последний, а отлучка длится ходами: вернувшийся должен увидеть
 * всё, что за него решили, — что заложено, куда ушли флоты, какие встречи приняты, что выбрано
 * в науке. Поэтому решения копятся здесь, пока он не прочтёт их, и хранятся ключом словаря с
 * подстановками — переводятся при чтении, как отчёт хода. Индекс объявлен в сущности, а не только
 * в миграции: на H2 схему строит Hibernate (см. {@link PlayerEventEntity}).
 */
@Entity
@Table(name = "steward_decision",
    indexes = {
        @Index(name = "ix_steward_decision_player", columnList = "player_id, turn")
    })
public class StewardDecisionEntity {

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

    @Column(name = "message_key", nullable = false, length = 128)
    private String messageKey;

    @Convert(converter = EventArgsConverter.class)
    @Column(name = "args", length = 1024)
    private List<String> args;

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }
}
