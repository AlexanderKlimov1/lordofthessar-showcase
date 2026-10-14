package com.sddnw.server.dto;

/**
 * Записка к партии — field-backlog, пункт 110.
 *
 * @param note строка, которую игрок написал себе сам; пусто — записки нет
 */
public record GameNoteDto(
        String note
) {
}
