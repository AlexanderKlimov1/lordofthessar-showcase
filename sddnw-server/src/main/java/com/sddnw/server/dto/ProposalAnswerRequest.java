package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Ответ человека на предложение — field-backlog, пункт 182. */
public record ProposalAnswerRequest(@NotBlank String accessToken, @NotNull Boolean accept) {
}
