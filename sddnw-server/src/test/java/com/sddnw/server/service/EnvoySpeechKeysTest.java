package com.sddnw.server.service;

import com.sddnw.server.domain.enums.AiPersonality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Речь посла (field-backlog, пункт 139): у каждого характера правителя есть реплика на каждый
 * исход переговоров на обоих языках, а каждый ключ ответа дипломатии сводится к исходу. Новый
 * характер или новый ответ без реплики — посол заговорил бы ключом словаря.
 */
class EnvoySpeechKeysTest {

    private static final String[] OUTCOMES = {"war", "peace", "treaty", "broken", "tribute", "exchange", "gift", "refusal"};

    @Test
    @DisplayName("У каждого характера есть реплика на каждый исход в обоих словарях")
    void everyCharacterSpeaks() throws IOException {
        Properties en = load("/messages.properties");
        Properties ru = load("/messages_ru.properties");
        for (AiPersonality personality : AiPersonality.values()) {
            for (String outcome : OUTCOMES) {
                String key = "envoy." + personality.name() + "." + outcome;
                assertThat(en.containsKey(key)).as(key).isTrue();
                assertThat(ru.containsKey(key)).as(key).isTrue();
            }
        }
    }

    @Test
    @DisplayName("Каждый ответ дипломатии сводится к исходу")
    void everyAnswerHasOutcome() throws IOException {
        Properties en = load("/messages.properties");
        for (String key : en.stringPropertyNames()) {
            if (key.startsWith("diplomacy.answer.") || key.startsWith("diplomacy.refusal.")) {
                assertThat(DiplomacyService.outcome(key)).as(key).isNotNull();
            }
        }
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = EnvoySpeechKeysTest.class.getResourceAsStream(resource)) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
