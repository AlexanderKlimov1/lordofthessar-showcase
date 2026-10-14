package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.sddnw.server.service.EncounterForecastRules.Forecast.LIKELY_LOSS;
import static com.sddnw.server.service.EncounterForecastRules.Forecast.LIKELY_WIN;
import static com.sddnw.server.service.EncounterForecastRules.Forecast.UNCLEAR;
import static com.sddnw.server.service.EncounterForecastRules.Forecast.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Прогноз встречи — field-backlog, пункты 84 и 153: вероятный исход только при перевесе,
 * близкие силы — «неясно», невидимая сила — «неизвестно».
 */
class EncounterForecastRulesTest {

    @Test
    @DisplayName("Перевес в четверть и больше — вероятный исход, ближе — неясно")
    void margin() {
        assertEquals(LIKELY_WIN, EncounterForecastRules.forecast(125, 100));
        assertEquals(UNCLEAR, EncounterForecastRules.forecast(124, 100), "перевес меньше четверти ничего не обещает");
        assertEquals(UNCLEAR, EncounterForecastRules.forecast(100, 100));
        assertEquals(LIKELY_LOSS, EncounterForecastRules.forecast(100, 125));
    }

    @Test
    @DisplayName("Невидимая сила соперника не угадывается")
    void unknown() {
        assertEquals(UNKNOWN, EncounterForecastRules.forecast(500, null));
    }

    @Test
    @DisplayName("Против пустой стороны — победа, пустой против пустой — неясно")
    void empty() {
        assertEquals(LIKELY_WIN, EncounterForecastRules.forecast(10, 0));
        assertEquals(UNCLEAR, EncounterForecastRules.forecast(0, 0));
        assertEquals(LIKELY_LOSS, EncounterForecastRules.forecast(0, 10));
    }
}
