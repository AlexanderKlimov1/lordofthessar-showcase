package com.sddnw.server.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Копия набора справочников у партии — field-backlog, пункт 42, миграция 142.
 * <p>
 * Партия хранит СВОЮ копию, а не ссылку на набор основателя: набор в учётной записи можно
 * поправить или удалить, а правила начатой партии посреди игры меняться не должны. Пустое поле —
 * этот справочник в партии обычный, из файлов {@code resources/}.
 */
@Entity
@Table(name = "game_mod")
public class GameModEntity {

    @Id
    @Column(name = "game_id", nullable = false)
    private UUID gameId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "buildings", columnDefinition = "text")
    private String buildings;

    @Column(name = "ships", columnDefinition = "text")
    private String ships;

    @Column(name = "technologies", columnDefinition = "text")
    private String technologies;

    @Column(name = "leaders", columnDefinition = "text")
    private String leaders;

    public UUID getGameId() {
        return gameId;
    }

    public void setGameId(UUID gameId) {
        this.gameId = gameId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBuildings() {
        return buildings;
    }

    public void setBuildings(String buildings) {
        this.buildings = buildings;
    }

    public String getShips() {
        return ships;
    }

    public void setShips(String ships) {
        this.ships = ships;
    }

    public String getTechnologies() {
        return technologies;
    }

    public void setTechnologies(String technologies) {
        this.technologies = technologies;
    }

    public String getLeaders() {
        return leaders;
    }

    public void setLeaders(String leaders) {
        this.leaders = leaders;
    }
}
