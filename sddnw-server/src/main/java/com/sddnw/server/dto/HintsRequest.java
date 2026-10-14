package com.sddnw.server.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Подсказки записи целиком — field-backlog, пункты 30 и 130: нужны ли подсказки новичку и
 * какие отклонены насовсем. Пустой список — «вернуть все подсказки».
 */
public record HintsRequest(
        @NotNull Boolean noviceHints,
        @NotNull List<String> dismissedHints
) {
}
