package com.sddnw.server.domain.save;

/** Увиденная технология соперника в слепке партии — п. 15; соперник назван слотом. */
public record ObservedTechSnapshot(Integer otherSlot, String techCode, Integer observedTurn) {
}
