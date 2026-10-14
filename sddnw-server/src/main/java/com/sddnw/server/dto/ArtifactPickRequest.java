package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Выбор технологий из наследия ушедших — пункт 34. */
public record ArtifactPickRequest(@NotBlank String accessToken, @NotEmpty List<String> codes) {
}
