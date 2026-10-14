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
 * Свой набор справочников основателя — field-backlog, пункт 42, миграция 142.
 * <p>
 * Лежит в учётной записи, как свои расы и проекты кораблей: набор — творчество игрока, он
 * переживает партию. Каждое поле — текст JSON того же вида, что файл в {@code resources/}
 * (здания, корабли, технологии, лидеры); пустое — справочник не меняется. Проверяется набор
 * при сохранении тем же разбором, каким игра читает свои файлы.
 */
@Entity
@Table(name = "mod_set", indexes = @Index(name = "idx_mod_set_account", columnList = "account_id"))
public class ModSetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

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

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public void setAccountId(UUID accountId) {
        this.accountId = accountId;
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

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
