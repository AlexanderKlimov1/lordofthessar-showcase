package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** «Сыграть с людьми» — field-backlog, пункт 65: имя за столом; остальное назначает сервер. */
public record QuickMatchRequest(@NotBlank @Size(max = 128) String playerName) {
}
