package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Короткий знак — field-backlog, пункт 183. Текста нет: клиент переводит {@code code} своим
 * словарём, на языке читателя.
 *
 * @param toPlayerId кому; пусто — всем
 */
public record GameSignalDto(UUID id, UUID fromPlayerId, String fromName, UUID toPlayerId, String code,
                            Integer turn, OffsetDateTime createdAt,
                            /** Свободный текст при коде {@code TEXT} — пункт 16. */
                            String text) {
}
