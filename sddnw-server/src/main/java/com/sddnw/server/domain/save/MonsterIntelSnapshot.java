package com.sddnw.server.domain.save;

import com.sddnw.server.domain.enums.SpaceMonster;

/**
 * Память о стороже системы в слепке партии — п. 11.1, п. 15; система названа номером.
 * Пустой сторож — в последний раз его там не было.
 */
public record MonsterIntelSnapshot(Integer systemIndex, SpaceMonster monster, Integer strength, Integer seenTurn) {
}
