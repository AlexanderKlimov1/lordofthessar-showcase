package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.ProblemAnswer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Ответ на своё сообщение о проблеме глазами написавшего — field-backlog, пункт 158:
 * «ваше сообщение от 12.10 исправлено в версии 0.9.3, спасибо».
 *
 * @param excerpt начало его же сообщения — чтобы узнать, о чём речь, через неделю
 */
public record ProblemAnswerDto(
        UUID id,
        OffsetDateTime reportedAt,
        String excerpt,
        ProblemAnswer kind,
        String version,
        String text
) {
}
