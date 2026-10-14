package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Свой набор справочников основателя — field-backlog, пункт 42: какие справочники он меняет.
 * Тексты наборов в список не идут — они велики, а экрану кабинета нужны только названия.
 */
public record ModSetDto(
        UUID id,
        String name,
        Boolean buildings,
        Boolean ships,
        Boolean technologies,
        Boolean leaders,
        OffsetDateTime updatedAt
) {
}
