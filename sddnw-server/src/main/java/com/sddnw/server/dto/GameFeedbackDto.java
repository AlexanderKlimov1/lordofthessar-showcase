package com.sddnw.server.dto;

import java.time.OffsetDateTime;

/**
 * Отзыв для администратора — field-backlog, пункты 46 и 187: без имени игрока, с тем, на какой
 * партии он оставлен, — исход, путь к победе, галактика, число империй, ход конца.
 */
public record GameFeedbackDto(Integer rating, String liked, String comment, String outcome,
                              String victoryKind, String galaxySize, Integer empires, Integer turn,
                              OffsetDateTime createdAt) {
}
