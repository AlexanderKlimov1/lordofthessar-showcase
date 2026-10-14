package com.sddnw.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.config.GameProperties;
import com.sddnw.server.domain.BuildingEffects;
import com.sddnw.server.domain.enums.BuildingEffectType;
import com.sddnw.server.domain.enums.MineralRichness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * «Достижения» MOO II — технологии, которые ничего не строят, а сразу действуют на всю империю
 * (п. 9, 13.10.2026). Числа сверены со StrategyWiki, разделы дерева; проверяется, что они
 * доезжают из НАСТОЯЩЕГО {@code tech.json} — опечатка в коде или типе молча оставила бы
 * технологию пустой, как она и была до переноса.
 */
class TechnologyEffectsTest {

    private final ResearchCatalog catalog = new ResearchCatalog(new GameProperties(8, 4, 1.5, "star-names.txt",
            "../resources/Technologies/tech.json",
            "../resources/Buildings/buildings.json",
            "../resources/Races/race-traits.json",
            "../resources/Ships/ship-components.json",
            "../resources/Leaders/leaders.json"), new ObjectMapper());

    private final PopulationCalculator calculator = new PopulationCalculator();

    @Test
    @DisplayName("Числа достижений — оригинала: валюта +50 % дохода, планировка +5 жителей, интеллект +1 учёному")
    void achievementsCarryOriginalNumbers() {
        assertThat(catalog.effectsOf(List.of("galactic-currency-exchange")).incomePercent()).isEqualTo(50);
        assertThat(catalog.effectsOf(List.of("advanced-city-planning")).maxPopulationBonus()).isEqualTo(5);
        assertThat(catalog.effectsOf(List.of("heightened-intelligence")).researchPerScientist()).isEqualTo(1);
        assertThat(catalog.effectsOf(List.of("biomorphic-fungi")).foodOnLifeless()).isEqualTo(1);
        assertThat(catalog.effectsOf(List.of("megafluxers")).amount(BuildingEffectType.SHIP_SPACE_PERCENT)).isEqualTo(25);
        assertThat(catalog.effectsOf(List.of("stealth-suit")).espionageTraining()).isEqualTo(10);
        // Технология без поля effects не даёт ничего — её действие живёт в своём правиле.
        assertThat(catalog.effectsOf(List.of("colony-ship"))).isEqualTo(BuildingEffects.NONE);
    }

    @Test
    @DisplayName("Подготовка разведки складывается: нейросканер, кибербезопасность и псионика по +10, телепаты +5")
    void espionageTrainingAddsUp() {
        assertThat(catalog.effectsOf(List.of("neural-scanner", "cyber-security-link", "psionics",
                "telepathic-training")).espionageTraining()).isEqualTo(35);
    }

    @Test
    @DisplayName("Роботизированный завод даёт 5/8/10/15/20 по недрам и пачкает планету")
    void roboticFactoryFollowsMineralsAndPollutes() {
        assertThat(calculator.roboticFactory(MineralRichness.ULTRA_POOR)).isEqualTo(5);
        assertThat(calculator.roboticFactory(MineralRichness.POOR)).isEqualTo(8);
        assertThat(calculator.roboticFactory(MineralRichness.AVERAGE)).isEqualTo(10);
        assertThat(calculator.roboticFactory(MineralRichness.RICH)).isEqualTo(15);
        assertThat(calculator.roboticFactory(MineralRichness.ULTRA_RICH)).isEqualTo(20);

        BuildingEffects factory = new BuildingEffects(Map.of(BuildingEffectType.ROBOTIC_FACTORY, 1), 3);
        // Рабочих нет, а завод на богатой планете даёт свои 15.
        assertThat(calculator.production(MineralRichness.RICH, 0, 0, factory)).isEqualTo(15);
        // И в чистую часть добычи это производство не входит: оно пачкает.
        assertThat(calculator.cleanProduction(0, factory)).isZero();
    }
}
