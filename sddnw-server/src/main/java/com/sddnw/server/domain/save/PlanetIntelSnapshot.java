package com.sddnw.server.domain.save;

/**
 * Память игрока о чужой колонии в слепке партии — п. 15, туман войны. Планета названа
 * номерами системы и орбиты, хозяин — слотом: идентификаторов слепок не хранит.
 */
public record PlanetIntelSnapshot(
        Integer systemIndex,
        Integer planetIndex,
        Integer ownerSlot,
        Integer population,
        String defences,
        Integer seenTurn
) {
}
