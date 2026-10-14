package com.sddnw.server.service;

import com.sddnw.server.service.stub.TurnPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Схема конца хода в справочнике «Как устроено» (field-backlog, пункт 75) собирается из самих
 * фаз, а слова к ним лежат в словарях. Заведи новую фазу без слов — и схема покажет ключ вместо
 * названия; этот тест держит связь: у каждой фазы есть название и пояснение на обоих языках.
 */
class RulesBookKeysTest {

    @Test
    @DisplayName("У каждой фазы конца хода есть название и пояснение в обоих словарях")
    void everyPhaseIsDescribed() throws Exception {
        Properties en = load("/messages.properties");
        Properties ru = load("/messages_ru.properties");
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(TurnPhase.class));
        Set<BeanDefinition> found = scanner.findCandidateComponents("com.sddnw.server");
        assertThat(found).as("фазы конца хода").hasSizeGreaterThan(10);
        for (BeanDefinition definition : found) {
            String key = RulesBookService.phaseKey(Class.forName(definition.getBeanClassName()));
            for (String part : new String[]{".title", ".text"}) {
                assertThat(en.containsKey(key + part)).as("английский словарь: %s", key + part).isTrue();
                assertThat(ru.containsKey(key + part)).as("русский словарь: %s", key + part).isTrue();
            }
        }
    }

    @Test
    @DisplayName("У каждой статьи справочника есть заголовок и текст в обоих словарях")
    void everyArticleIsWritten() throws IOException {
        Properties en = load("/messages.properties");
        Properties ru = load("/messages_ru.properties");
        for (String code : new String[]{"victory", "council", "growth", "pollution", "buying", "battle",
                "ground", "trust", "command"}) {
            for (String part : new String[]{".title", ".text"}) {
                String key = "rules.article." + code + part;
                assertThat(en.containsKey(key)).as(key).isTrue();
                assertThat(ru.containsKey(key)).as(key).isTrue();
            }
        }
    }

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = RulesBookKeysTest.class.getResourceAsStream(resource)) {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
