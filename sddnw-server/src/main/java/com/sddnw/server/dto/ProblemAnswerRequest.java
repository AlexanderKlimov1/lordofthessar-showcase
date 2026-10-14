package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.ProblemAnswer;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Ответ хозяина на сообщение о проблеме — field-backlog, пункт 158.
 *
 * @param kind    исправлено или «не будем исправлять»
 * @param version версия исправления; пусто — текущая (та, что в меню)
 * @param text    причина «не будем исправлять» (обязательна) или приписка к «исправлено»
 */
public record ProblemAnswerRequest(
        @NotNull ProblemAnswer kind,
        @Size(max = 32) @Pattern(regexp = "[0-9A-Za-z.+-]*") String version,
        @Size(max = 1000) String text
) {
}
