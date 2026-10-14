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
 * Сколько раз за ход живой игрок сделал одно действие — field-backlog, пункт 13.
 * <p>
 * Каждое действие, которое игрок делает из обязанности, — утечка из потока в скуку; догоняет ли
 * автоматизация рост империи, видно только числом: сравнить 30-й ход со 150-м и автоматизировать
 * самое частое. Действие — изменяющий запрос с пропуском игрока, названный адресом без
 * опознавателей ({@code POST /api/games/{id}/planets/{id}/queue}). Строка на (партию, игрока,
 * ход, действие) — копится в памяти и ложится раз в минуту ({@code TurnActionService}).
 */
@Entity
@Table(name = "turn_action",
    indexes = @Index(name = "ix_turn_action_game", columnList = "game_id"))
public class TurnActionEntity {

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

    @Column(name = "action", nullable = false, length = 120)
    private String action;

    @Column(name = "times", nullable = false)
    private Integer times;

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

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Integer getTimes() {
        return times;
    }

    public void setTimes(Integer times) {
        this.times = times;
    }
}
