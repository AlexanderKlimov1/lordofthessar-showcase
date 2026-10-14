package com.sddnw.server.service;

import com.sddnw.server.domain.Building;
import com.sddnw.server.domain.BuildingEffects;
import com.sddnw.server.domain.LocalizedText;
import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.enums.BuildingEffectType;
import com.sddnw.server.domain.enums.RaceEffectType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Столица империи — п. 10.1: «у империи есть столица» — состояние, а не отметка, и
 * проверяется оно по колониям контекста.
 */
class CapitolRulesTest {

    private static final Building CAPITOL = new Building(CapitolRules.CODE, LocalizedText.of("Capitol"),
            LocalizedText.of(""), 200, 0, null, Map.of(BuildingEffectType.CAPITOL, 1));

    private static final RaceEffects FEUDAL = new RaceEffects(BuildingEffects.NONE,
            Map.of(RaceEffectType.CAPITOL_LOSS_MORALE, -50, RaceEffectType.MORALE_WITHOUT_BARRACKS, -20));
    private static final RaceEffects UNIFICATION = new RaceEffects(BuildingEffects.NONE,
            Map.of(RaceEffectType.NO_MORALE, 1));

    private final UUID empire = UUID.randomUUID();
    private final UUID rival = UUID.randomUUID();
    private final PlanetEntity home = colony("Home I", empire);
    private final PlanetEntity second = colony("Second II", empire);

    @Test
    void homeworldWithCapitolKeepsTheEmpireWhole() {
        ColonyService.ColonyContext context = context(FEUDAL, home);
        assertTrue(context.hasCapitol(empire));
        assertFalse(context.capitolLost(empire));
        assertEquals(0, context.morale(second).percent() + 20, "только штраф за казармы");
    }

    @Test
    void capturedCapitolIsLostForEveryColony() {
        ColonyService.ColonyContext context = context(FEUDAL, home);
        home.setOwnerPlayerId(rival);
        assertFalse(context.hasCapitol(empire));
        assertTrue(context.capitolLost(empire));
        // −20 за казармы и −50 за столицу: штраф ложится на ВСЕ колонии империи.
        assertEquals(-70, context.morale(second).percent());
    }

    @Test
    void unificationHasNothingToLose() {
        ColonyService.ColonyContext context = context(UNIFICATION);
        assertFalse(context.capitolLost(empire));
    }

    @Test
    void secondCapitolIsNotOrderedTwice() {
        ColonyService.ColonyContext context = context(FEUDAL);
        home.setProjectCode(CapitolRules.CODE);
        assertTrue(context.capitolOrderedElsewhere(second));
        assertFalse(context.capitolOrderedElsewhere(home));
    }

    private ColonyService.ColonyContext context(RaceEffects race, PlanetEntity... withCapitol) {
        Map<UUID, List<Building>> buildings = new HashMap<>();
        for (PlanetEntity planet : withCapitol) {
            buildings.put(planet.getId(), new ArrayList<>(List.of(CAPITOL)));
        }
        return new ColonyService.ColonyContext(Map.of(), buildings, Map.of(empire, race), Map.of(),
                Map.of(CapitolRules.CODE, CAPITOL), Map.of(), Map.of(), Map.of(),
                Map.of(empire, List.of(home, second)));
    }

    private static PlanetEntity colony(String name, UUID owner) {
        PlanetEntity planet = new PlanetEntity();
        planet.setId(UUID.randomUUID());
        planet.setName(name);
        planet.setOwnerPlayerId(owner);
        planet.setPopulation(4);
        return planet;
    }
}
