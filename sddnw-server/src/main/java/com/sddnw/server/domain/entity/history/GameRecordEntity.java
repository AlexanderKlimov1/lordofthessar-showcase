package com.sddnw.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Сыгранная партия в истории учётной записи — field-backlog, пункт 18.
 * <p>
 * Внутри партии игрок видит своё место, а между партиями — ничего, и на вопрос «я стал лучше?»
 * ответа нет. Строка пишется в конце партии (или при признанном поражении) в источник данных
 * ИСТОРИИ, рядом с учётной записью: партию потом удаляют, а история остаётся. Поэтому здесь не
 * ссылки, а сами значения — имя расы, размер галактики, число империй.
 */
@Entity
@Table(name = "game_record",
    indexes = {
        @Index(name = "ix_game_record_account", columnList = "account_id")
    })
public class GameRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Партия — только чтобы не записать её дважды; самой партии может уже не быть. */
    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "game_name", nullable = false, length = 128)
    private String gameName;

    @Column(name = "race_name", length = 64)
    private String raceName;

    @Column(name = "galaxy_size", length = 16)
    private String galaxySize;

    @Column(name = "empires", nullable = false)
    private Integer empires;

    /** WON, LOST или CONCEDED. */
    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    /** Путь к победе того, кто победил (CONQUEST, COUNCIL…); пусто — партия ещё идёт без игрока. */
    @Column(name = "victory_kind", length = 16)
    private String victoryKind;

    @Column(name = "turn", nullable = false)
    private Integer turn;

    /** Место по мощи на последнем замере (1 — первое). */
    @Column(name = "place", nullable = false)
    private Integer place;

    /** Наибольшее число колоний за партию. */
    @Column(name = "peak_colonies", nullable = false)
    private Integer peakColonies;

    /**
     * Свои проекты кораблей партии — field-backlog, пункт 98: собранные ИГРОКОМ (не игрой),
     * по которым строили, строкой «имя:построено» через «|», лучший первым. Пусто — своих не было.
     */
    @Column(name = "designs", length = 1000)
    private String designs;

    public String getDesigns() {
        return designs;
    }

    public void setDesigns(String designs) {
        this.designs = designs;
    }

    /** Наибольшее отставание победителя (пункт 185): ход и проценты; пусто — не было. */
    @Column(name = "deficit_turn")
    private Integer deficitTurn;

    @Column(name = "deficit_percent")
    private Integer deficitPercent;

    @Column(name = "finished_at", nullable = false)
    private OffsetDateTime finishedAt;

    /**
     * Мощь на контрольных ходах и в конце — field-backlog, пункт 115: по ним следующая партия
     * сравнивает игрока с ним самим. Пусто — партия до хода не дошла.
     */
    @Column(name = "might_50")
    private Integer might50;

    @Column(name = "might_100")
    private Integer might100;

    @Column(name = "might_150")
    private Integer might150;

    @Column(name = "might_end")
    private Integer mightEnd;

    /**
     * Чем партия была трудна — field-backlog, пункт 41 (звания), миграция 130. После удаления
     * партии пересчитать это нечем, поэтому выводится при записи. Пусто у строк, записанных до
     * миграции: такие партии в звания не идут.
     */
    /** Код готовой расы; пусто — раса своя. */
    @Column(name = "race_code", length = 32)
    private String raceCode;

    /** Была ли у империи хоть одна война — объявленная ею или ей. */
    @Column(name = "at_war")
    private Boolean atWar;

    /** Взяла ли империя Тессар у Стража — получила его клад. */
    @Column(name = "warden")
    private Boolean warden;

    /** Брала ли империя корабль абордажем. */
    @Column(name = "trophy")
    private Boolean trophy;

    /** Учебная партия: в звания не идёт. */
    @Column(name = "tutorial")
    private Boolean tutorial;

    public String getRaceCode() {
        return raceCode;
    }

    public void setRaceCode(String raceCode) {
        this.raceCode = raceCode;
    }

    public Boolean getAtWar() {
        return atWar;
    }

    public void setAtWar(Boolean atWar) {
        this.atWar = atWar;
    }

    public Boolean getWarden() {
        return warden;
    }

    public void setWarden(Boolean warden) {
        this.warden = warden;
    }

    public Boolean getTrophy() {
        return trophy;
    }

    public void setTrophy(Boolean trophy) {
        this.trophy = trophy;
    }

    public Boolean getTutorial() {
        return tutorial;
    }

    /**
     * Неделя галактики недели — field-backlog, пункт 146 ({@code WeeklyGalaxyRules}): по ней
     * итоги недели собираются по расам. Пусто — обычная партия.
     */
    @Column(name = "weekly_week")
    private Integer weeklyWeek;

    /** Уровень сложности партии — пункт 10: по нему подсказывается уровень следующей. Пусто — обычный. */
    @Column(name = "ai_skill", length = 16)
    private String aiSkill;

    /** Партию завела эта запись, и за столом были другие люди — звание хозяина стола (пункт 67). */
    @Column(name = "hosted")
    private Boolean hosted;

    /**
     * Кто позвал эту запись в партию — пункты 50 и 174: хозяин стола (код приглашения у партии один).
     * Пусто — пришёл сам.
     */
    @Column(name = "invited_by")
    private UUID invitedBy;

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public void setInvitedBy(UUID invitedBy) {
        this.invitedBy = invitedBy;
    }

    public Boolean getHosted() {
        return hosted;
    }

    public void setHosted(Boolean hosted) {
        this.hosted = hosted;
    }

    public Integer getWeeklyWeek() {
        return weeklyWeek;
    }

    public void setWeeklyWeek(Integer weeklyWeek) {
        this.weeklyWeek = weeklyWeek;
    }

    public void setTutorial(Boolean tutorial) {
        this.tutorial = tutorial;
    }

    public Integer mightAt(int turn) {
        return switch (turn) {
            case 50 -> might50;
            case 100 -> might100;
            case 150 -> might150;
            default -> null;
        };
    }

    public void setMightAt(int turn, Integer might) {
        switch (turn) {
            case 50 -> might50 = might;
            case 100 -> might100 = might;
            case 150 -> might150 = might;
            default -> { }
        }
    }

    public Integer getMightEnd() {
        return mightEnd;
    }

    public void setMightEnd(Integer mightEnd) {
        this.mightEnd = mightEnd;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
    }

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public String getGameName() {
        return gameName;
    }

    public void setGameName(String gameName) {
        this.gameName = gameName;
    }

    public String getRaceName() {
        return raceName;
    }

    public void setRaceName(String raceName) {
        this.raceName = raceName;
    }

    public String getGalaxySize() {
        return galaxySize;
    }

    public void setGalaxySize(String galaxySize) {
        this.galaxySize = galaxySize;
    }

    public Integer getEmpires() {
        return empires;
    }

    public void setEmpires(Integer empires) {
        this.empires = empires;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getVictoryKind() {
        return victoryKind;
    }

    public void setVictoryKind(String victoryKind) {
        this.victoryKind = victoryKind;
    }

    public Integer getTurn() {
        return turn;
    }

    public void setTurn(Integer turn) {
        this.turn = turn;
    }

    public Integer getPlace() {
        return place;
    }

    public void setPlace(Integer place) {
        this.place = place;
    }

    public Integer getPeakColonies() {
        return peakColonies;
    }

    public void setPeakColonies(Integer peakColonies) {
        this.peakColonies = peakColonies;
    }

    public Integer getDeficitTurn() {
        return deficitTurn;
    }

    public void setDeficitTurn(Integer deficitTurn) {
        this.deficitTurn = deficitTurn;
    }

    public Integer getDeficitPercent() {
        return deficitPercent;
    }

    public void setDeficitPercent(Integer deficitPercent) {
        this.deficitPercent = deficitPercent;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(OffsetDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getAiSkill() {
        return aiSkill;
    }

    public void setAiSkill(String aiSkill) {
        this.aiSkill = aiSkill;
    }
}
