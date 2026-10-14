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
 * Проект корабля в библиотеке учётной записи — field-backlog, пункт 96, миграция 127.
 * <p>
 * Собранный игроком линкор, которым он гордится, в следующей партии собирать заново — вложение
 * пропадает. Поэтому проект живёт в записи, рядом с шаблонами стройки, и хранится СОСТАВОМ —
 * корпус, компоненты, модификации кодами справочника, — а не строкой партии: в новой партии его
 * собирает окно дизайна заново и проверяет тем же правилом, что любой проект.
 */
@Entity
@Table(name = "ship_template", indexes = @Index(name = "idx_ship_template_account", columnList = "account_id"))
public class ShipTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "hull_code", nullable = false, length = 64)
    private String hullCode;

    /** Состав одной строкой JSON: [{code, count, modifications}] — строк в нём полдесятка. */
    @Column(name = "components", nullable = false, length = 2000)
    private String components;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Снимок справочника на миг сохранения — field-backlog, пункт 176: JSON «код — числа части».
     * Пусто у проектов, сохранённых до снимков.
     */
    @Column(name = "specs", length = 8000)
    private String specs;

    public String getSpecs() {
        return specs;
    }

    public void setSpecs(String specs) {
        this.specs = specs;
    }

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

    public String getHullCode() {
        return hullCode;
    }

    public void setHullCode(String hullCode) {
        this.hullCode = hullCode;
    }

    public String getComponents() {
        return components;
    }

    public void setComponents(String components) {
        this.components = components;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
