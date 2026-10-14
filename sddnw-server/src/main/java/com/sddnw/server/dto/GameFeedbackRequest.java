package com.sddnw.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Отзыв после партии — field-backlog, пункты 46 и 187. Пустая оценка значит «не хочу отвечать»:
 * отказ запоминается, и вопрос вернётся не скоро.
 */
public record GameFeedbackRequest(
        @NotNull UUID gameId,
        @Min(1) @Max(5) Integer rating,
        /** Что понравилось больше всего: HARD_WIN, DISCOVERY, BUILDING, PEOPLE; пусто — не сказал. */
        @Size(max = 16) String liked,
        @Size(max = 500) String comment
) {
}
