package com.sddnw.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Наследие ушедших, ждущее выбора человека, — backlog-promo, пункт 34: три технологии на выбор,
 * взять одну (при удачном броске — две). Разведка случается внутри посчитанного хода, где спросить
 * некого, поэтому кандидаты ждут здесь до конца следующего хода; не выбрал — берутся первые.
 * Строка живёт до выбора. В слепок партии не идёт: сохранение снимается в конце хода, а не
 * выбранное к загрузке — это подарок, который сгорит, а не правило, которое сломается.
 */
/*
 * Индекс объявлен ЗДЕСЬ, а не только в миграции: на H2, где схему строит Hibernate по
 * сущностям (режим балансового прогона), объявленного в changelog'е не существует вовсе.
 */
@Entity
@Table(name = "artifact_choice", indexes = {@Index(name = "ix_artifact_choice_game", columnList = "game_id")})
public class ArtifactChoiceEntity {

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

    /** Коды технологий-кандидатов через запятую, в порядке жребия. */
    @Column(name = "options", nullable = false, length = 512)
    private String options;

    /** Сколько можно взять: одну, при удачном броске — две. */
    @Column(name = "picks", nullable = false)
    private Integer picks;

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

    public List<String> optionCodes() {
        return Arrays.stream(options.split(",")).filter(code -> !code.isBlank()).toList();
    }

    public void setOptions(List<String> codes) {
        this.options = String.join(",", codes);
    }

    public Integer getPicks() {
        return picks;
    }

    public void setPicks(Integer picks) {
        this.picks = picks;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }
}
