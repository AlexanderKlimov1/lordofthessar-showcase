package com.sddnw.server.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Номера версий «что нового» — field-backlog, пункт 159. Запись новее текущей версии
 * отвергается при старте, поэтому сравнение обязано идти по числам: строкой «0.10» меньше
 * «0.9», и десятая выкладка объявила бы себя старее девятой.
 */
class NoticeVersionTest {

    @Test
    @DisplayName("Версии сравниваются по числам, а не строкой")
    void numericOrder() {
        assertThat(NoticeService.compareVersions("0.10.0", "0.9.3")).isPositive();
        assertThat(NoticeService.compareVersions("0.9.3", "0.10")).isNegative();
        assertThat(NoticeService.compareVersions("0.1", "0.1.0")).isZero();
        assertThat(NoticeService.compareVersions("1.0.0", "0.99.99")).isPositive();
    }
}
