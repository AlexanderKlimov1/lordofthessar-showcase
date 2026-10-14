package com.sddnw.server.dto;

import java.time.OffsetDateTime;

/**
 * Запись «что нового» — field-backlog, пункты 159 и 161: текст на языке читателя.
 *
 * @param version версия, с которой изменение вышло
 * @param kind    раздел: {@code loss} — что потеряно и что делать, {@code changed} — правила,
 *                {@code prices} — цены сторон рас, {@code new} — новое, {@code fixed} — исправлено
 */
public record NoticeDto(
        String id,
        OffsetDateTime date,
        String version,
        String kind,
        String text
) {
}
