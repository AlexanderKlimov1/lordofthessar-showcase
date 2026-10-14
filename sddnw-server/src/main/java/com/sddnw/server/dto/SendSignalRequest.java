package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** Отправить короткий знак — пункт 183; {@code toPlayerId} пусто — всем людям партии. */
public record SendSignalRequest(@NotBlank String accessToken, UUID toPlayerId, @NotBlank String code,
                                /** Свободный текст при коде {@code TEXT} — пункт 16. */
                                @jakarta.validation.constraints.Size(max = 400) String text) {
}
