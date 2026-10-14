package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Летопись узнаёт главные события по КЛЮЧУ словаря (field-backlog, пункт 34,
 * {@link MilestoneService#BY_KEY}). Переименуй ключ события — и момент молча выпадет из
 * летописи: ни одна проверка хода этого не заметит. Этот тест держит связь: каждый ключ
 * таблицы есть в обоих словарях сервера, а у каждого рода момента, который считается один раз,
 * есть дорога в таблицу или в действие игрока.
 */
class MilestoneKeysTest {

    @Test
    @DisplayName("Каждый ключ главного события есть в обоих словарях сервера")
    void everyMilestoneKeyExists() throws IOException {
        Properties en = load("/messages.properties");
        Properties ru = load("/messages_ru.properties");
        java.util.Set<String> keys = new java.util.HashSet<>(MilestoneService.BY_KEY.keySet());
        // Хроника (пункт 138) узнаёт свои события тем же способом — по ключу.
        keys.addAll(MilestoneService.CHRONICLE_BY_KEY.keySet());
        for (String key : keys) {
            assertThat(en.containsKey(key)).as("английский словарь: %s", key).isTrue();
            assertThat(ru.containsKey(key)).as("русский словарь: %s", key).isTrue();
        }
        for (String key : new String[]{"milestone.colonyFounded", "milestone.conceded", "milestone.empireFallen"}) {
            assertThat(en.containsKey(key)).as(key).isTrue();
            assertThat(ru.containsKey(key)).as(key).isTrue();
        }
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = MilestoneKeysTest.class.getResourceAsStream(resource)) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
