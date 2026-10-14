package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Имя флота — field-backlog, пункт 141. Пустое имя снимает его.
 *
 * @param accessToken пропуск игрока
 * @param name        имя, данное игроком
 */
public record FleetRenameRequest(
        @NotBlank String accessToken,
        String name
) {
}
