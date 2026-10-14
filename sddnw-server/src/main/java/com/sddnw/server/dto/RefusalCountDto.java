package com.sddnw.server.dto;

import java.time.OffsetDateTime;

/**
 * Отказ в списке частых — field-backlog, пункт 77: ключ, его текст на языке читателя (с
 * местами подстановок, как они лежат в словаре), сколько раз и когда последний.
 */
public record RefusalCountDto(
        String key,
        String text,
        Long refusals,
        OffsetDateTime lastAt
) {
}
