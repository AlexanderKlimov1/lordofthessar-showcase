package com.sddnw.server.domain.entity.history;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Ключ сервера — field-backlog, пункт 22, миграция 130: пара VAPID для Web Push. Живёт в базе, а
 * не в файле: новый ключ после переезда сервера молча обесценил бы все подписки браузеров.
 */
@Entity
@Table(name = "server_secret")
public class ServerSecretEntity {

    @Id
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "secret", nullable = false, length = 512)
    private String secret;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }
}
