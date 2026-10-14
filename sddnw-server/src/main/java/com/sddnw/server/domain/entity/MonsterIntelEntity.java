package com.sddnw.server.domain.entity;

import com.sddnw.server.domain.enums.SpaceMonster;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * Что игрок помнит о стороже системы — п. 11.1, п. 15, туман войны: те же правила, что у
 * чужой колонии ({@link PlanetIntelEntity}). Под глазами чудище видно правдой, без них —
 * таким, каким его застали; пустой код — в последний раз сторожа там не было.
 */
@Entity
@Table(name = "monster_intel",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_monster_intel", columnNames = {"player_id", "star_system_id"})
    },
    indexes = {
        @Index(name = "ix_monster_intel_game", columnList = "game_id")
    })
public class MonsterIntelEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "star_system_id", nullable = false)
    private UUID starSystemId;

    @Enumerated(EnumType.STRING)
    @Column(name = "monster_code", length = 64)
    private SpaceMonster monster;

    @Column(name = "strength", nullable = false)
    private Integer strength;

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

    public UUID getStarSystemId() {
        return starSystemId;
    }

    public void setStarSystemId(UUID starSystemId) {
        this.starSystemId = starSystemId;
    }

    public SpaceMonster getMonster() {
        return monster;
    }

    public void setMonster(SpaceMonster monster) {
        this.monster = monster;
    }

    public Integer getStrength() {
        return strength;
    }

    public void setStrength(Integer strength) {
        this.strength = strength;
    }

    public Integer getSeenTurn() {
        return seenTurn;
    }

    public void setSeenTurn(Integer seenTurn) {
        this.seenTurn = seenTurn;
    }
}
