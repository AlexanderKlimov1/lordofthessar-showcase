package com.sddnw.server.dto;

import java.util.UUID;

/**
 * Что можно отменить до конца хода — пункт 74. {@code kind} — SELL, BUY или SCRAP; {@code what} —
 * подпись на языке читателя (здание, стройка или корабли), {@code where} — колония или система.
 */
public record TurnUndoDto(UUID id, String kind, String what, String where, Integer credits) {
}
