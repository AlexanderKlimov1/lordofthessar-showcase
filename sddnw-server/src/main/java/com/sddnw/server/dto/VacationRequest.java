package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Отпуск — field-backlog, пункт 177: на сколько дней; ноль — вернуться сейчас. */
public record VacationRequest(@NotBlank String accessToken, @NotNull Integer days) {
}
