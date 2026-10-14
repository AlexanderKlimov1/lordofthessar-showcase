package com.sddnw.server.service;

import com.sddnw.server.domain.enums.MineralRichness;
import com.sddnw.server.domain.enums.PlanetClimate;
import com.sddnw.server.domain.enums.PlanetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Переделка планет — п. 4.1.2.1: переходы, цены и искусственные планеты оригинала MOO II. */
class TerraformRulesTest {

    @Test
    @DisplayName("Переходы терраформирования — оригинала: бесплодная ветвится, океан, засушливая и болото ведут к земной")
    void transitionsFollowTheOriginal() {
        assertThat(TerraformRules.terraformed(PlanetClimate.BARREN, true)).contains(PlanetClimate.TUNDRA);
        assertThat(TerraformRules.terraformed(PlanetClimate.BARREN, false)).contains(PlanetClimate.DESERT);
        assertThat(TerraformRules.terraformed(PlanetClimate.DESERT, false)).contains(PlanetClimate.ARID);
        assertThat(TerraformRules.terraformed(PlanetClimate.TUNDRA, false)).contains(PlanetClimate.SWAMP);
        assertThat(TerraformRules.terraformed(PlanetClimate.OCEAN, false)).contains(PlanetClimate.TERRAN);
        assertThat(TerraformRules.terraformed(PlanetClimate.ARID, false)).contains(PlanetClimate.TERRAN);
        assertThat(TerraformRules.terraformed(PlanetClimate.SWAMP, false)).contains(PlanetClimate.TERRAN);
        // Земную терраформированием не улучшить — для неё превращение в Гею.
        assertThat(TerraformRules.terraformed(PlanetClimate.TERRAN, false)).isEmpty();
        assertThat(TerraformRules.terraformed(PlanetClimate.TOXIC, false)).isEmpty();
    }

    @Test
    @DisplayName("Терраформирование дорожает: 250, 500, 750 — и дальше не растёт")
    void costRisesThreeSteps() {
        assertThat(TerraformRules.terraformCost(0)).isEqualTo(250);
        assertThat(TerraformRules.terraformCost(1)).isEqualTo(500);
        assertThat(TerraformRules.terraformCost(2)).isEqualTo(750);
        assertThat(TerraformRules.terraformCost(5)).isEqualTo(750);
    }

    @Test
    @DisplayName("Искусственная планета: из газового гиганта огромная, из пояса большая, недра обычные")
    void artificialPlanets() {
        assertThat(TerraformRules.convertible(PlanetClimate.GAS_GIANT)).isTrue();
        assertThat(TerraformRules.convertible(PlanetClimate.ASTEROID_BELT)).isTrue();
        assertThat(TerraformRules.convertible(PlanetClimate.BARREN)).isFalse();
        assertThat(TerraformRules.artificialSize(PlanetClimate.GAS_GIANT)).isEqualTo(PlanetSize.HUGE);
        assertThat(TerraformRules.artificialSize(PlanetClimate.ASTEROID_BELT)).isEqualTo(PlanetSize.LARGE);
        assertThat(TerraformRules.artificialMinerals()).isEqualTo(MineralRichness.AVERAGE);
    }

    @Test
    @DisplayName("Жребий «тундра или пустыня» повторим: та же партия и та же планета — тот же ответ")
    void tundraRollRepeats() {
        assertThat(TerraformRules.tundraRoll(42L, "Rana II")).isEqualTo(TerraformRules.tundraRoll(42L, "Rana II"));
    }
}
