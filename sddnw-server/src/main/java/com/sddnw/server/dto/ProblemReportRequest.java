package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Сообщение о проблеме — field-backlog, пункт 157.
 *
 * @param text    строка своими словами — без неё сообщение о сбое не скажет, что игрок делал
 * @param context что собрал клиент сам: последние ошибки запросов и консоли, JSON строкой
 */
public record ProblemReportRequest(
        @NotBlank @Size(max = 2000) String text,
        UUID gameId,
        Integer turn,
        @Size(max = 64) String clientVersion,
        @Size(max = 64) String screen,
        @Size(max = 20000) String context
) {
}
