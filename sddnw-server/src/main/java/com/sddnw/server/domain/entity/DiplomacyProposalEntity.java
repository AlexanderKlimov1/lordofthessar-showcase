package com.sddnw.server.domain.entity;

import com.sddnw.server.domain.enums.DiplomacyAction;
import com.sddnw.server.domain.enums.DiplomacyTreaty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Предложение, которое ждёт ответа человека, — field-backlog, пункт 182.
 * <p>
 * Без него сетевая партия была одиночной, где некоторых ИИ зовут по именам: мир, договор,
 * обмен и дань, адресованные живому игроку, решало за него доверие — то самое правило, которым
 * отвечает ИИ. Теперь такая просьба становится строкой: адресат видит её в начале своего хода
 * и отвечает, а итог подводится ВНУТРИ посчитанного хода, как у совета с избирателем-человеком
 * ({@code CouncilService}, {@code game.council_open}) — ход считается на сервере целиком, и
 * ответ посреди него менял бы галактику между двумя фазами. Не ответил до конца следующего
 * хода — решает доверие, как прежде.
 * <p>
 * Строка живёт, пока предложение не решено: итог уходит в отчёт хода обеим сторонам, и
 * хранить решённое незачем. Ждущие предложения едут в слепке партии (у предлагающего).
 */
/*
 * Индекс объявлен ЗДЕСЬ, а не только в миграции: на H2, где схему строит Hibernate по
 * сущностям (режим балансового прогона), объявленного в changelog'е не существует вовсе.
 */
@Entity
@Table(name = "diplomacy_proposal",
    indexes = {
        @Index(name = "ix_diplomacy_proposal_game", columnList = "game_id"),
        @Index(name = "ix_diplomacy_proposal_to", columnList = "to_player_id")
    })
public class DiplomacyProposalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "from_player_id", nullable = false)
    private UUID fromPlayerId;

    @Column(name = "to_player_id", nullable = false)
    private UUID toPlayerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private DiplomacyAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "treaty", length = 32)
    private DiplomacyTreaty treaty;

    @Column(name = "offered_tech", length = 128)
    private String offeredTech;

    @Column(name = "requested_tech", length = 128)
    private String requestedTech;

    /** Ход, на котором предложено; срок ответа — конец следующего хода. */
    @Column(name = "turn", nullable = false)
    private Integer turn;

    /** Ответ адресата; пусто — ещё не ответил. */
    @Column(name = "accepted")
    private Boolean accepted;

    public UUID getId() {
        return id;
    }

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public UUID getFromPlayerId() {
        return fromPlayerId;
    }

    public void setFromPlayerId(UUID fromPlayerId) {
        this.fromPlayerId = fromPlayerId;
    }

    public UUID getToPlayerId() {
        return toPlayerId;
    }

    public void setToPlayerId(UUID toPlayerId) {
        this.toPlayerId = toPlayerId;
    }

    public DiplomacyAction getAction() {
        return action;
    }

    public void setAction(DiplomacyAction action) {
        this.action = action;
    }

    public DiplomacyTreaty getTreaty() {
        return treaty;
    }

    public void setTreaty(DiplomacyTreaty treaty) {
        this.treaty = treaty;
    }

    public String getOfferedTech() {
        return offeredTech;
    }

    public void setOfferedTech(String offeredTech) {
        this.offeredTech = offeredTech;
    }

    public String getRequestedTech() {
        return requestedTech;
    }

    public void setRequestedTech(String requestedTech) {
        this.requestedTech = requestedTech;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public Boolean getAccepted() {
        return accepted;
    }

    public void setAccepted(Boolean accepted) {
        this.accepted = accepted;
    }
}
