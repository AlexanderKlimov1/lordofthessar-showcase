package com.sddnw.server.domain.entity;

import com.sddnw.server.domain.EventArgsConverter;
import com.sddnw.server.domain.enums.BattleSide;
import jakarta.persistence.Convert;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.List;
import java.util.UUID;

/**
 * Корабль на поле боя — п. 8.
 * <p>
 * До боя корабли живут числом во флоте ({@code fleet_ship}: сколько единиц какого
 * проекта). В бою каждый становится отдельной единицей со своим местом на поле,
 * прочностью и очередью хода: иначе ни ходить, ни гибнуть по одному они не смогут.
 * Уцелевшие возвращаются во флот числом, погибшие списываются.
 * <p>
 * Прочность, броня и щит хранятся текущие: они убывают за бой. Всё остальное — залп,
 * скорость, дальность — берётся из проекта, потому что от боя не зависит и меняться
 * не должно.
 */
/*
 * Индексы и уникальности объявлены ЗДЕСЬ, а не только в миграции.
 *
 * Объявленные в changelog'е, они существуют лишь там, где changelog прошёл. На H2, где
 * схему строит Hibernate по сущностям (режим балансового прогона), их не было вовсе — и
 * та же партия в пятьсот ходов шла 236 секунд вместо 159: каждая выборка внутри хода
 * перебирала таблицу целиком. Вторая причина проще: глядя на сущность, видно, по каким
 * полям её ищут, а лишний индекс заметен рядом с полем, а не в файле миграции
 * трёхмесячной давности.
 *
 * В Postgres они уже созданы, и Hibernate их не трогает: ddl-auto: validate сверяет
 * таблицы и колонки, но не индексы.
 */
@Entity
@Table(name = "battle_ship",
    indexes = {
        @Index(name = "ix_battle_ship_battle", columnList = "battle_id")
    })
public class BattleShipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "battle_id", nullable = false)
    private UUID battleId;

    @Column(name = "owner_player_id", nullable = false)
    private UUID ownerPlayerId;

    /** Проект корабля: из него берутся залп, скорость и живучесть. */
    @Column(name = "design_id", nullable = false)
    private UUID designId;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 16)
    private BattleSide side;

    /** Номер корабля в своём строю: по нему он и подписан на поле — «Охотник 2». */
    @Column(name = "ordinal", nullable = false)
    private Integer ordinal;

    @Column(name = "x", nullable = false)
    private Integer x;

    @Column(name = "y", nullable = false)
    private Integer y;

    /** Прочность корпуса сейчас; ноль — корабль уничтожен. */
    @Column(name = "structure", nullable = false)
    private Integer structure;

    /** Броня сейчас: её снимают раньше прочности. */
    @Column(name = "armour", nullable = false)
    private Integer armour;

    /** Щит: держит удар раньше брони и восстанавливается между кругами. */
    @Column(name = "shield", nullable = false)
    private Integer shield;

    /** Инициатива: по ней строится очередь хода кораблей — п. 8. */
    @Column(name = "initiative", nullable = false)
    private Integer initiative;

    /** Сколько клеток корабль уже прошёл в этом своём ходу. */
    @Column(name = "moved", nullable = false)
    private Integer moved = 0;

    /** Стрелял ли в этом своём ходу: залп у корабля один. */
    @Column(name = "fired", nullable = false)
    private Boolean fired = Boolean.FALSE;

    /** Уничтожен: остаётся в записи боя ради подсчёта потерь, но с поля уходит. */
    @Column(name = "destroyed", nullable = false)
    private Boolean destroyed = Boolean.FALSE;

    /**
     * Выбитые бортовые системы — п. 8: то, что разбито внутри корабля.
     * <p>
     * Попадание, прошедшее сквозь щит и броню, рвёт корабль изнутри: гаснет ствол,
     * разбивается двигатель, сгорает прицел. Гнездо здесь считается отдельно от вида —
     * четыре одинаковых ствола это четыре записи, и выбивает их по одной.
     * <p>
     * Список, а не колонка на систему: систем у разных корпусов разное число, а бой
     * живёт один ход. Хранится он тем же преобразователем, что и подстановки событий.
     * Поломка держится один бой: в следующий корабль выходит целым — чинить его в MOO II
     * не нужно, там тоже ломается снаряжение вылета, а не корабль навсегда.
     */
    @Convert(converter = EventArgsConverter.class)
    @Column(name = "damaged_systems", length = 512)
    private List<String> damagedSystems;

    /**
     * Морская пехота на борту — п. 8, абордаж (BattleRules.marines). Уходит на абордаж вся
     * разом: погибшие не возвращаются, уцелевшие остаются экипажем взятого корабля.
     */
    @Column(name = "marines", nullable = false)
    private Integer marines = 0;

    /**
     * Корабль взят абордажем и воюет за захватчика — п. 8. Владелец и сторона у него уже
     * новые; отметка нужна концу боя: для прежнего хозяина это потеря, а захватчику —
     * трофей, который встаёт в его флот.
     */
    @Column(name = "captured", nullable = false)
    private Boolean captured = Boolean.FALSE;

    /**
     * Сколько тяговых лучей держит корабль в этом круге — п. 8: луч на класс корпуса
     * обездвиживает его. Сбрасывается на смене круга.
     */
    @Column(name = "tractored", nullable = false)
    private Integer tractored = 0;

    /**
     * Промахов подряд у этого стрелка — field-backlog, пункт 86 ({@code StreakRules}): чем длиннее
     * полоса, тем выше шанс следующего выстрела; попадание сбрасывает счёт.
     */
    @Column(name = "miss_streak", nullable = false)
    private Integer missStreak = 0;

    public Integer getMissStreak() {
        return missStreak;
    }

    public void setMissStreak(Integer missStreak) {
        this.missStreak = missStreak;
    }

    /**
     * Приказ отходить — п. 8 (06.10.2026, решение хозяина проекта): корабль развернулся к
     * своему краю и уйдёт в гиперпространство в СВОЙ следующий ход, если доживёт до него.
     * До того он на поле и под огнём.
     */
    @Column(name = "retreating")
    private Boolean retreating;

    /** Ушёл с поля отходом: не погиб и не потерян, но в бою его больше нет. */
    @Column(name = "retreated")
    private Boolean retreated;

    /**
     * Круг, в который лучи молчат после двойного залпа гиперконденсаторов — п. 8 (13.10.2026,
     * перенос MOO II): «fire twice in a combat turn, but the ship then has to wait 1 combat turn
     * without firing». Пусто — лучи не молчат.
     */
    @Column(name = "beams_rest_round")
    private Integer beamsRestRound;

    /** То же для ракет — скоростные ракетные стойки (п. 8). */
    @Column(name = "missiles_rest_round")
    private Integer missilesRestRound;

    /**
     * С какого круга отсеки истребителей снова выпускают звено — п. 8 (14.10.2026): звено отбивает свои
     * удары и возвращается на носитель перевооружаться. Пусто — звено дома.
     */
    @Column(name = "fighters_back_round")
    private Integer fightersBackRound;

    public Integer getFightersBackRound() {
        return fightersBackRound;
    }

    public void setFightersBackRound(Integer fightersBackRound) {
        this.fightersBackRound = fightersBackRound;
    }

    /**
     * Круг последнего выстрела — п. 8 (маскировка MOO II): стреляющий корабль раскрывается и
     * прячется снова, только пропустив круг без выстрелов. Пусто — не стрелял.
     */
    @Column(name = "last_fired_round")
    private Integer lastFiredRound;

    /**
     * До какого круга корабль заморожен стазисным полем — п. 8: «cannot move, fire or be
     * attacked». Пусто — не заморожен.
     */
    @Column(name = "frozen_until_round")
    private Integer frozenUntilRound;

    /**
     * Круг, в котором корабль уже взял лишний ход ускорителя времени (п. 8, перенос MOO II):
     * «an additional turn at the end of every combat turn». Пусто — не брал ни разу.
     */
    @Column(name = "warp_round")
    private Integer warpRound;

    /** Прибавка звания экипажа к меткости и защите (п. 8, {@code CrewRules}); ставится при расстановке. */
    @Column(name = "crew_bonus", nullable = false)
    private Integer crewBonus = 0;

    public Integer getCrewBonus() {
        return crewBonus == null ? 0 : crewBonus;
    }

    public void setCrewBonus(Integer crewBonus) {
        this.crewBonus = crewBonus;
    }

    /**
     * Плазменная сеть на корабле — п. 8: столько урона она даст в начале следующего круга, а потом
     * ослабнет на 5. Ноль — сети нет.
     */
    @Column(name = "plasma_web", nullable = false)
    private Integer plasmaWeb = 0;

    public Integer getWarpRound() {
        return warpRound;
    }

    public void setWarpRound(Integer warpRound) {
        this.warpRound = warpRound;
    }

    public Integer getPlasmaWeb() {
        return plasmaWeb == null ? 0 : plasmaWeb;
    }

    public void setPlasmaWeb(Integer plasmaWeb) {
        this.plasmaWeb = plasmaWeb;
    }

    public Integer getLastFiredRound() {
        return lastFiredRound;
    }

    public void setLastFiredRound(Integer lastFiredRound) {
        this.lastFiredRound = lastFiredRound;
    }

    public Integer getFrozenUntilRound() {
        return frozenUntilRound;
    }

    public void setFrozenUntilRound(Integer frozenUntilRound) {
        this.frozenUntilRound = frozenUntilRound;
    }

    public Integer getBeamsRestRound() {
        return beamsRestRound;
    }

    public void setBeamsRestRound(Integer beamsRestRound) {
        this.beamsRestRound = beamsRestRound;
    }

    public Integer getMissilesRestRound() {
        return missilesRestRound;
    }

    public void setMissilesRestRound(Integer missilesRestRound) {
        this.missilesRestRound = missilesRestRound;
    }

    public Boolean getRetreating() {
        return retreating;
    }

    public void setRetreating(Boolean retreating) {
        this.retreating = retreating;
    }

    public Boolean getRetreated() {
        return retreated;
    }

    public void setRetreated(Boolean retreated) {
        this.retreated = retreated;
    }

    public Integer getTractored() {
        return tractored;
    }

    public void setTractored(Integer tractored) {
        this.tractored = tractored;
    }

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Integer getMarines() {
        return marines;
    }

    public void setMarines(Integer marines) {
        this.marines = marines;
    }

    public Boolean getCaptured() {
        return captured;
    }

    public void setCaptured(Boolean captured) {
        this.captured = captured;
    }

    public List<String> getDamagedSystems() {
        return damagedSystems == null ? List.of() : damagedSystems;
    }

    public void setDamagedSystems(List<String> damagedSystems) {
        // КОПИЕЙ, а не тем же списком: у колонки свой преобразователь, и Hibernate
        // сравнивает поле с тем самым списком, который отдал при загрузке, — правку на
        // месте он просто не увидит (те же грабли, что у очереди стройки).
        this.damagedSystems = damagedSystems == null || damagedSystems.isEmpty()
                ? null
                : List.copyOf(damagedSystems);
    }

    /** Выбита ли эта система — по ней и решается, стреляет ли ствол и ходит ли корабль. */
    public Boolean systemDamaged(String system) {
        return getDamagedSystems().contains(system);
    }

    /** Отмечает систему выбитой; одно и то же гнездо дважды не ломается. */
    public void damageSystem(String system) {
        List<String> broken = new java.util.ArrayList<>(getDamagedSystems());
        broken.add(system);
        setDamagedSystems(broken);
    }

    /**
     * На поле ли корабль — им и меряется, кончился ли бой. Ушедший отходом на поле больше
     * не стоит: по нему не стреляют, он не ходит и сторону на поле не держит.
     */
    public Boolean alive() {
        return !Boolean.TRUE.equals(destroyed) && !Boolean.TRUE.equals(retreated);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getBattleId() {
        return battleId;
    }

    public void setBattleId(UUID battleId) {
        this.battleId = battleId;
    }

    public UUID getOwnerPlayerId() {
        return ownerPlayerId;
    }

    public void setOwnerPlayerId(UUID ownerPlayerId) {
        this.ownerPlayerId = ownerPlayerId;
    }

    public UUID getDesignId() {
        return designId;
    }

    public void setDesignId(UUID designId) {
        this.designId = designId;
    }

    public BattleSide getSide() {
        return side;
    }

    public void setSide(BattleSide side) {
        this.side = side;
    }

    public Integer getOrdinal() {
        return ordinal;
    }

    public void setOrdinal(Integer ordinal) {
        this.ordinal = ordinal;
    }

    public Integer getX() {
        return x;
    }

    public void setX(Integer x) {
        this.x = x;
    }

    public Integer getY() {
        return y;
    }

    public void setY(Integer y) {
        this.y = y;
    }

    public Integer getStructure() {
        return structure;
    }

    public void setStructure(Integer structure) {
        this.structure = structure;
    }

    public Integer getArmour() {
        return armour;
    }

    public void setArmour(Integer armour) {
        this.armour = armour;
    }

    public Integer getShield() {
        return shield;
    }

    public void setShield(Integer shield) {
        this.shield = shield;
    }

    public Integer getInitiative() {
        return initiative;
    }

    public void setInitiative(Integer initiative) {
        this.initiative = initiative;
    }

    public Integer getMoved() {
        return moved;
    }

    public void setMoved(Integer moved) {
        this.moved = moved;
    }

    public Boolean getFired() {
        return fired;
    }

    public void setFired(Boolean fired) {
        this.fired = fired;
    }

    public Boolean getDestroyed() {
        return destroyed;
    }

    public void setDestroyed(Boolean destroyed) {
        this.destroyed = destroyed;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
