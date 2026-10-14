package com.sddnw.server.service;

import com.sddnw.server.domain.BuildingEffects;
import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.enums.BuildingEffectType;
import com.sddnw.server.domain.enums.RaceEffectType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Мораль колонии — п. 10.1: числа и примеры взяты со StrategyWiki («Strength through Joy»).
 */
class MoraleRulesTest {

    private static final RaceEffects DICTATORSHIP = government(Map.of(
            RaceEffectType.MORALE_WITHOUT_BARRACKS, -20, RaceEffectType.PSIONICS_MORALE, 10));
    private static final RaceEffects IMPERIUM = government(Map.of(
            RaceEffectType.MORALE_WITH_BARRACKS, 20, RaceEffectType.PSIONICS_MORALE, 10));
    private static final RaceEffects UNIFICATION = government(Map.of(RaceEffectType.NO_MORALE, 1));
    private static final RaceEffects DEMOCRACY = RaceEffects.NONE;

    private static final BuildingEffects BARRACKS = effects(Map.of(BuildingEffectType.BARRACKS, 1));
    private static final BuildingEffects HOLO = effects(Map.of(BuildingEffectType.MORALE_PERCENT, 20));

    @Test
    void dictatorshipColonyWithoutBarracksLosesAFifth() {
        assertEquals(-20, morale(DICTATORSHIP, BuildingEffects.NONE, Set.of()).percent());
        assertEquals(0, morale(DICTATORSHIP, BARRACKS, Set.of()).percent());
    }

    @Test
    void imperiumGainsAFifthWithBarracksAndLosesNothingWithout() {
        assertEquals(20, morale(IMPERIUM, BARRACKS, Set.of()).percent());
        assertEquals(0, morale(IMPERIUM, BuildingEffects.NONE, Set.of()).percent());
    }

    @Test
    void democracyNeedsNoBarracks() {
        assertEquals(0, morale(DEMOCRACY, BuildingEffects.NONE, Set.of()).percent());
        assertEquals(20, morale(DEMOCRACY, HOLO, Set.of()).percent());
    }

    @Test
    void unificationIgnoresMoraleEntirely() {
        MoraleRules.Morale morale = MoraleRules.morale(UNIFICATION, HOLO,
                Set.of(MoraleRules.VIRTUAL_REALITY_TECH), 30, Boolean.TRUE, Boolean.TRUE);
        assertEquals(0, morale.percent());
        assertTrue(morale.ignored());
        assertEquals(BuildingEffects.NONE, morale.effects());
    }

    @Test
    void achievementsAndLeaderAddUp() {
        Set<String> techs = Set.of(MoraleRules.VIRTUAL_REALITY_TECH, MoraleRules.PSIONICS_TECH);
        // Казармы гасят штраф, сеть +20, псионика +10 у диктатуры, лидер +15.
        assertEquals(45, MoraleRules.morale(DICTATORSHIP, BARRACKS, techs, 15, Boolean.FALSE, Boolean.FALSE).percent());
        // Демократии псионика морали не даёт.
        assertEquals(20, MoraleRules.morale(DEMOCRACY, BuildingEffects.NONE, techs, 0, Boolean.FALSE, Boolean.FALSE).percent());
    }

    @Test
    void multiracialColonyLosesAFifthUntilAlienManagementCenter() {
        assertEquals(-20, MoraleRules.morale(DEMOCRACY, BuildingEffects.NONE, Set.of(), 0, Boolean.TRUE, Boolean.FALSE).percent());
        BuildingEffects center = effects(Map.of(BuildingEffectType.ALIEN_MANAGEMENT, 1));
        assertEquals(0, MoraleRules.morale(DEMOCRACY, center, Set.of(), 0, Boolean.TRUE, Boolean.FALSE).percent());
    }

    /**
     * Пример первоисточника: 3 учёных, 3 рабочих и 6 фермеров на земном мире. Голосимулятор
     * даёт 11 очков науки и 11 производства вместо 9 (10,8 у оригинала округляется вверх, у
     * нас вниз — 10) и 14 еды вместо 12. Здесь проверяется, что мораль дошла до выработки
     * процентом, а не какой-то своей формулой.
     */
    @Test
    void moraleIsAPercentToColonistOutput() {
        BuildingEffects morale = morale(DEMOCRACY, HOLO, Set.of()).effects();
        PopulationCalculator calculator = new PopulationCalculator();
        assertEquals(14, calculator.food(com.sddnw.server.domain.enums.PlanetClimate.TERRAN, 6, morale));
        assertEquals(10, calculator.research(3, morale));
        assertEquals(20, morale.incomePercent());
    }

    @Test
    void lostCapitolCostsEveryColonyByGovernment() {
        RaceEffects feudal = government(Map.of(RaceEffectType.MORALE_WITHOUT_BARRACKS, -20,
                RaceEffectType.CAPITOL_LOSS_MORALE, -50));
        RaceEffects democracy = government(Map.of(RaceEffectType.CAPITOL_LOSS_MORALE, -20));
        assertEquals(-50, MoraleRules.morale(feudal, BARRACKS, Set.of(), 0, Boolean.FALSE, Boolean.TRUE).percent());
        assertEquals(-70, MoraleRules.morale(feudal, BuildingEffects.NONE, Set.of(), 0, Boolean.FALSE, Boolean.TRUE).percent());
        assertEquals(-20, MoraleRules.morale(democracy, BuildingEffects.NONE, Set.of(), 0, Boolean.FALSE, Boolean.TRUE).percent());
        assertEquals(0, MoraleRules.morale(democracy, BuildingEffects.NONE, Set.of(), 0, Boolean.FALSE, Boolean.FALSE).percent());
    }

    @Test
    void unificationHasNoCapitol() {
        assertTrue(!UNIFICATION.hasCapitol());
        assertTrue(DEMOCRACY.hasCapitol());
    }

    private static MoraleRules.Morale morale(RaceEffects government, BuildingEffects buildings, Set<String> techs) {
        return MoraleRules.morale(government, buildings, techs, 0, Boolean.FALSE, Boolean.FALSE);
    }

    private static RaceEffects government(Map<RaceEffectType, Integer> amounts) {
        return new RaceEffects(BuildingEffects.NONE, amounts);
    }

    private static BuildingEffects effects(Map<BuildingEffectType, Integer> amounts) {
        return new BuildingEffects(amounts, 0);
    }
}
