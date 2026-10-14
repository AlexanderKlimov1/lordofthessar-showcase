package com.sddnw.server.dto;

/**
 * Доля среди похожих — field-backlog, пункт 117: сравнение с другими людьми долей распределения,
 * а не местом в мире. Наружу уходят только доли — ни имён, ни чисел чужих партий.
 *
 * @param games         сколько похожих партий других игроков (та же галактика, число империй и правила)
 * @param coloniesAbove у какой доли из них колоний было меньше, чем у вас, %
 * @param fasterThan    у какой доли похожих побед победа пришла позже вашей, %; пусто — не победа или побед мало
 * @param guest         сравнение среди гостевых партий (пункт 118)
 */
public record PeerShareDto(
        Integer games,
        Integer coloniesAbove,
        Integer fasterThan,
        Boolean guest
) {
}
