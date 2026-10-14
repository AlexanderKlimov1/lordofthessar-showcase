package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Шаг воронки для разбора — field-backlog, пункт 2; отдаётся только администратору. */
public record FunnelStepDto(
        String visitor,
        UUID accountId,
        String role,
        String step,
        UUID gameId,
        Integer turn,
        String detail,
        OffsetDateTime at
) {
}
