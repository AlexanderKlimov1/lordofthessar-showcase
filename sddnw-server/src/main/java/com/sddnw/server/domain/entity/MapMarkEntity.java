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
 * Метка наставника на карте ученика — field-backlog, пункт 184: «сюда — колониальный корабль».
 * Знак — КОД ({@code MentorService.CODES}), а не текст: реплики между людьми в этой игре только
 * кодами, перевод — словарём читателя (пункт 183). На одну систему у ученика — одна метка.
 * <p>
 * Индекс объявлен и здесь, а не только в миграции: на H2 схему строит Hibernate.
 */
@Entity
@Table(name = "map_mark",
    indexes = {
        @Index(name = "ix_map_mark_game", columnList = "game_id")
    })
public class MapMarkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "author_player_id", nullable = false)
    private UUID authorPlayerId;

    @Column(name = "student_player_id", nullable = false)
    private UUID studentPlayerId;

    @Column(name = "system_id", nullable = false)
    private UUID systemId;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "turn", nullable = false)
    private Integer turn;

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public UUID getAuthorPlayerId() {
        return authorPlayerId;
    }

    public void setAuthorPlayerId(UUID authorPlayerId) {
        this.authorPlayerId = authorPlayerId;
    }

    public UUID getStudentPlayerId() {
        return studentPlayerId;
    }

    public void setStudentPlayerId(UUID studentPlayerId) {
        this.studentPlayerId = studentPlayerId;
    }

    public UUID getSystemId() {
        return systemId;
    }

    public void setSystemId(UUID systemId) {
        this.systemId = systemId;
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
}
