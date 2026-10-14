package com.sddnw.server.service;

import com.sddnw.server.domain.ShipStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сила корабля для быстрого боя — п. 8 (14.10.2026): истребители бьют вместе со стволами, а ускоритель
 * времени удваивает удар. ИИ дерётся только быстрым боём, и без этого оба модуля были для него
 * потерянным местом.
 */
class ShipPowerTest {

    private final ShipDesignRules rules = new ShipDesignRules();

    private static ShipStats stats(int attack, int fighters, boolean warp) {
        return new ShipStats(0, 0, 0, 100, 0, 0, 0, 0, attack, 0, 0, 0, 0, 0, 0, 0, fighters, warp);
    }

    @Test
    @DisplayName("Истребители прибавляются к удару, ускоритель времени его удваивает")
    void fightersAndTimeWarp() {
        int plain = rules.power(stats(40, 0, false));
        assertThat(rules.power(stats(40, 40, false))).isEqualTo(2 * plain);
        assertThat(rules.power(stats(40, 0, true))).isEqualTo(2 * plain);
        // Старые характеристики без новых полей считаются как прежде.
        assertThat(rules.power(new ShipStats(0, 0, 0, 100, 0, 0, 0, 0, 40, 0, 0, 0, 0, 0, 0, 0))).isEqualTo(plain);
    }
}
