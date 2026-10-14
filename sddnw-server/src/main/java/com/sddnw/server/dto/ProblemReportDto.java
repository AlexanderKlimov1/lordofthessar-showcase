package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.ProblemAnswer;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Сообщение о проблеме в списке хозяина — field-backlog, пункт 157. */
public record ProblemReportDto(
        UUID id,
        UUID accountId,
        String accountName,
        String accountRole,
        UUID gameId,
        Integer turn,
        String clientVersion,
        String screen,
        String text,
        String context,
        Boolean resolved,
        OffsetDateTime createdAt,
        ProblemAnswer answerKind,
        String answerVersion,
        String answerText,
        OffsetDateTime answeredAt,
        Boolean answerSeen
) {
}
