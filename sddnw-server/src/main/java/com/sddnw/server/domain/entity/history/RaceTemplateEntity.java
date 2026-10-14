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
 * Своя раса в учётной записи — field-backlog, пункты 26 и 176, миграция 128.
 * <p>
 * Раса собиралась заново к каждой партии, и вложение пропадало. Теперь она живёт в записи
 * составом — кодами сторон — и <b>со снимком цен на миг сохранения</b>: цены двигает
 * балансировка, и раса, законная вчера, сегодня может стоить иначе. Снимок не правило, а
 * память: по нему экран показывает, что подешевело, что подорожало и сколько очков у расы
 * прибавилось или убыло, — вместо молчаливого отказа при входе в партию.
 */
@Entity
@Table(name = "race_template", indexes = @Index(name = "idx_race_template_account", columnList = "account_id"))
public class RaceTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** Стороны расы — JSON-список кодов справочника. */
    @Column(name = "traits", nullable = false, length = 1000)
    private String traits;

    /** Цены сторон на миг сохранения — JSON «код — очки». */
    @Column(name = "prices", nullable = false, length = 2000)
    private String prices;

    /** Потрачено на миг сохранения — с надбавками связок. */
    @Column(name = "spent", nullable = false)
    private Integer spent;

    /** Бюджет очков выбора на миг сохранения. */
    @Column(name = "budget", nullable = false)
    private Integer budget;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /**
     * Чья это раса — field-backlog, пункт 99: раса, взятая по ссылке, помнит расу автора, и
     * партии, сыгранные ею, засчитываются автору. Пусто — собрана здесь же.
     */
    @Column(name = "origin_id")
    private UUID originId;

    public UUID getOriginId() {
        return originId;
    }

    public void setOriginId(UUID originId) {
        this.originId = originId;
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

    public String getTraits() {
        return traits;
    }

    public void setTraits(String traits) {
        this.traits = traits;
    }

    public String getPrices() {
        return prices;
    }

    public void setPrices(String prices) {
        this.prices = prices;
    }

    public Integer getSpent() {
        return spent;
    }

    public void setSpent(Integer spent) {
        this.spent = spent;
    }

    public Integer getBudget() {
        return budget;
    }

    public void setBudget(Integer budget) {
        this.budget = budget;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
