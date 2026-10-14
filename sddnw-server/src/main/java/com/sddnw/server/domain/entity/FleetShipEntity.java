package com.sddnw.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Корабли одного проекта во флоте — п. 8.
 * <p>
 * Флот перестал быть просто счётчиком: он знает, из чего состоит. Общее число кораблей
 * осталось в {@link FleetEntity#getShips()} — по нему считают бой, разведка и слепок
 * партии, — а эти строки говорят, какие именно корабли за ним стоят. Сумма строк равна
 * числу во флоте: обе величины меняются вместе, в {@code FleetService}.
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
@Table(name = "fleet_ship",
    indexes = {
        @Index(name = "ix_fleet_ship_fleet", columnList = "fleet_id")
    })
public class FleetShipEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "fleet_id", nullable = false)
    private UUID fleetId;

    /** Проект корабля; он мог давно устареть — корабли по нему всё равно летают. */
    @Column(name = "design_id", nullable = false)
    private UUID designId;

    @Column(name = "ships", nullable = false)
    private Integer ships = 0;

    /**
     * Сколько жителей везут эти корабли — п. 4.1, п. 12: поселенцы колониального корабля
     * и десант транспорта. У боевых кораблей ноль.
     * <p>
     * Хранится на составе флота, а не на проекте: транспорт может прийти полупустым,
     * если часть десанта уже высадили, и его груз — свойство самого корабля, а не чертежа.
     */
    @Column(name = "colonists", nullable = false)
    private Integer colonists = 0;

    /**
     * Опыт экипажей этих кораблей — п. 8 ({@code CrewRules}): средний на строку, при слиянии
     * усредняется по числу кораблей.
     */
    @Column(name = "experience", nullable = false)
    private Integer experience = 0;

    public Integer getExperience() {
        return experience == null ? 0 : experience;
    }

    public void setExperience(Integer experience) {
        this.experience = experience == null ? 0 : experience;
    }

    /** Версия строки: состав трогают и стройка в конце хода, и перелёт, и бой. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getFleetId() {
        return fleetId;
    }

    public void setFleetId(UUID fleetId) {
        this.fleetId = fleetId;
    }

    public UUID getDesignId() {
        return designId;
    }

    public void setDesignId(UUID designId) {
        this.designId = designId;
    }

    public Integer getShips() {
        return ships;
    }

    public void setShips(Integer ships) {
        this.ships = ships;
    }

    public Integer getColonists() {
        return colonists;
    }

    public void setColonists(Integer colonists) {
        this.colonists = colonists;
    }

    /**
     * Строка теряет {@code gone} кораблей, и груз уходит вместе с ними — своей долей
     * (03.10.2026). Прежде бой, мины и отделение отряда меняли только число кораблей:
     * уцелевший транспорт вёз десант погибших, а отделившийся улетал пустым.
     *
     * @return сколько груза ушло с этими кораблями
     */
    public int lose(int gone) {
        int was = ships;
        int left = Math.max(0, was - gone);
        int kept = was <= 0 ? 0 : (int) ((long) colonists * left / was);
        int carried = colonists - kept;
        ships = left;
        colonists = kept;
        return carried;
    }

    /** Принимает корабли и груз другой строки того же проекта — слияние флотов. */
    public void absorb(FleetShipEntity other) {
        experience = com.sddnw.server.service.CrewRules.merged(getExperience(), ships,
                other.getExperience(), other.getShips());
        ships += other.getShips();
        colonists += other.getColonists();
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
