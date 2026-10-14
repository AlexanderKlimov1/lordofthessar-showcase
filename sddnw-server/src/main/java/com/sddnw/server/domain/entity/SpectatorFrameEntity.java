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
 * Кадр партии для зрителя — field-backlog, пункт 59: галактика без тумана войны на начало хода.
 * <p>
 * Зритель отстаёт от партии на {@code SpectatorService.DELAY_TURNS} ходов, чтобы не стать
 * разведчиком для кого-то за столом, а прошлое состояние партии пересчитать нечем — поэтому
 * кадр пишется строкой в начале каждого хода и живёт, пока не станет старше отставания.
 * Хранится готовым ответом (JSON): кадр нужен только зрителю и только целиком.
 * <p>
 * Индекс объявлен и здесь, а не только в миграции: на H2 схему строит Hibernate (см.
 * {@link PlayerEventEntity}).
 */
@Entity
@Table(name = "spectator_frame",
    indexes = {
        @Index(name = "ix_spectator_frame_game", columnList = "game_id")
    })
public class SpectatorFrameEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "turn", nullable = false)
    private Integer turn;

    @Column(name = "frame", nullable = false, columnDefinition = "text")
    private String frame;

    protected SpectatorFrameEntity() {
    }

    public SpectatorFrameEntity(UUID gameId, Integer turn, String frame) {
        this.gameId = gameId;
        this.turn = turn;
        this.frame = frame;
    }

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public Integer getTurn() {
        return turn;
    }

    public String getFrame() {
        return frame;
    }
}
