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
 * Главный момент партии одного игрока — field-backlog, пункт 34.
 * <p>
 * Отчёт хода живёт один ход, а сцене конца партии нужны пики всей партии: первое знакомство,
 * первая выигранная битва, взятые и потерянные колонии, клад Стражей, совет. Пересчитать их
 * задним числом нечем, поэтому момент пишется строкой, когда случается ({@code MilestoneService}),
 * — ключом словаря с подстановками, как событие игрока: читатель партии бывает на любом языке.
 * <p>
 * Индекс объявлен и здесь, а не только в миграции: на H2 схему строит Hibernate (см.
 * {@link PlayerEventEntity}).
 */
@Entity
@Table(name = "game_milestone",
    indexes = {
        @Index(name = "ix_game_milestone_game", columnList = "game_id")
    })
public class GameMilestoneEntity {

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

    /** Род момента ({@code MilestoneKind}): по нему решается «только первый» и подпись в летописи. */
    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "message_key", nullable = false, length = 128)
    private String messageKey;

    @Convert(converter = EventArgsConverter.class)
    @Column(name = "args", length = 1024)
    private List<String> args;

    /**
     * Пик ли это — field-backlog, пункт 138. Хроника партии хранит ВСЕ заметные события (каждое
     * знакомство, каждую войну, договоры и гибель империй), а сцена конца показывает только пики:
     * первое знакомство, первую битву, взятые колонии. Пусто — строка снята до хроники и была
     * пиком: тогда в летопись писались только они.
     */
    @Column(name = "peak")
    private Boolean peak;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public Boolean getPeak() {
        return peak;
    }

    public void setPeak(Boolean peak) {
        this.peak = peak;
    }

    /** Пик ли это: пусто — строка из времён до хроники, тогда писались одни пики. */
    public boolean isPeak() {
        return !Boolean.FALSE.equals(peak);
    }

    public List<String> getArgs() {
        return args;
    }

    public void setArgs(List<String> args) {
        this.args = args;
    }
}
