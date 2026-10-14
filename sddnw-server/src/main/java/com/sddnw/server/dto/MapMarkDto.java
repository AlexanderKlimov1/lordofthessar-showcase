package com.sddnw.server.dto;

import java.util.UUID;

/**
 * Метка наставника на карте ученика — field-backlog, пункт 184. {@code code} — знак
 * ({@code MentorService.CODES}), подпись к нему переводит клиент; {@code author} — имя империи
 * наставника.
 */
public record MapMarkDto(UUID systemId, String code, String author, Integer turn) {
}
