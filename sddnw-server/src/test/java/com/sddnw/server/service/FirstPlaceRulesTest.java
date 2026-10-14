package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Каждый лучший в чём-то (field-backlog, пункт 116): только настоящие первые места. */
class FirstPlaceRulesTest {

    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    @DisplayName("Ось, где у всех ноль, первого места не даёт; равные делят место")
    void realPlacesOnly() {
        Map<String, Map<UUID, Integer>> table = FirstPlaceRules.table();
        table.get("BATTLES").putAll(Map.of(first, 0, second, 0));
        table.get("COLONIZED").putAll(Map.of(first, 3, second, 3));
        table.get("RESEARCH").putAll(Map.of(first, 40, second, 55));
        List<FirstPlaceRules.Place> places = FirstPlaceRules.firsts(table);
        assertThat(places).extracting(FirstPlaceRules.Place::axis).containsExactly("RESEARCH", "COLONIZED");
        assertThat(places.get(0).holders()).containsExactly(second);
        assertThat(places.get(1).holders()).containsExactlyInAnyOrder(first, second);
    }
}
