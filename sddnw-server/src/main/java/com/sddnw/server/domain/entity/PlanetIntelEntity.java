package com.sddnw.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * Что игрок помнит о чужой колонии — п. 15, туман войны.
 * <p>
 * В оригинале чужая колония видна такой, какой её застали в последний раз: хозяин и
 * население, а оборона — пока в системе свои глаза (решение хозяина проекта, 03.10.2026).
 * Строка переписывается в конце хода, когда у игрока в системе колония, застава, флот или
 * радиус сканера; без глаз карта и решения ИИ читают её, а не живое состояние. Строки нет —
 * игрок о колонии не знает: на его карте планета свободна.
 * <p>
 * Хранится строкой на пару «игрок — планета», а не полем планеты: у каждого игрока своя
 * память, и устаревает она у всех по-разному.
 */
@Entity
@Table(name = "planet_intel",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_planet_intel_player_planet", columnNames = {"player_id", "planet_id"})
    },
    indexes = {
        @Index(name = "ix_planet_intel_game", columnList = "game_id")
    })
public class PlanetIntelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "planet_id", nullable = false)
    private UUID planetId;

    /** Чья колония — на тот ход, когда её видели. */
    @Column(name = "owner_player_id", nullable = false)
    private UUID ownerPlayerId;

    /** Сколько жителей было тогда. */
    @Column(name = "population", nullable = false)
    private Integer population;

    /** Коды оборонных построек через запятую; пусто — обороны не было. */
    @Column(name = "defences", nullable = false, length = 512)
    private String defences = "";

    /** Ход, на котором колонию видели в последний раз. */
    @Column(name = "seen_turn", nullable = false)
    private Integer seenTurn;

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

    public UUID getPlanetId() {
        return planetId;
    }

    public void setPlanetId(UUID planetId) {
        this.planetId = planetId;
    }

    public UUID getOwnerPlayerId() {
        return ownerPlayerId;
    }

    public void setOwnerPlayerId(UUID ownerPlayerId) {
        this.ownerPlayerId = ownerPlayerId;
    }

    public Integer getPopulation() {
        return population;
    }

    public void setPopulation(Integer population) {
        this.population = population;
    }

    public String getDefences() {
        return defences;
    }

    public void setDefences(String defences) {
        this.defences = defences;
    }

    public Integer getSeenTurn() {
        return seenTurn;
    }

    public void setSeenTurn(Integer seenTurn) {
        this.seenTurn = seenTurn;
    }
}
