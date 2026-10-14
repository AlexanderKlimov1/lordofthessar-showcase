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
 * Технология соперника, которую игрок ВИДЕЛ — п. 15, туман войны (решение хозяина проекта,
 * 03.10.2026, в отступление от оригинала): доклад о расе показывает не изученное соседом, а
 * только увиденное — стоявшее на его кораблях и в его колонии в бою или под сканерами флота.
 * Знание не теряется: однажды увиденную технологию сосед уже не «разучит».
 */
@Entity
@Table(name = "observed_tech",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_observed_tech", columnNames = {"player_id", "other_player_id", "tech_code"})
    },
    indexes = {
        @Index(name = "ix_observed_tech_game", columnList = "game_id")
    })
public class ObservedTechEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    /** Кто видел. */
    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    /** Чью технологию. */
    @Column(name = "other_player_id", nullable = false)
    private UUID otherPlayerId;

    @Column(name = "tech_code", nullable = false, length = 128)
    private String techCode;

    @Column(name = "observed_turn", nullable = false)
    private Integer observedTurn;

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

    public UUID getOtherPlayerId() {
        return otherPlayerId;
    }

    public void setOtherPlayerId(UUID otherPlayerId) {
        this.otherPlayerId = otherPlayerId;
    }

    public String getTechCode() {
        return techCode;
    }

    public void setTechCode(String techCode) {
        this.techCode = techCode;
    }

    public Integer getObservedTurn() {
        return observedTurn;
    }

    public void setObservedTurn(Integer observedTurn) {
        this.observedTurn = observedTurn;
    }
}
