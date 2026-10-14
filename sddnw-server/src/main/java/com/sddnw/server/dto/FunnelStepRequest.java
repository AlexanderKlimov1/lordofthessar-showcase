package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Шаг воронки новичка — field-backlog, пункт 2.
 * <p>
 * Имя шага — короткий код из строчных букв, цифр и точек ({@code entry.shown},
 * {@code turn.end}): свободного текста сюда не пускаем, иначе журнал стал бы местом, куда
 * чужой скрипт пишет что угодно.
 *
 * @param visitor номер браузера — случайный, его заводит клиент
 * @param detail  подробность шага: коды событий итогов хода, номер шага обучения
 */
public record FunnelStepRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,40}") String visitor,
        @NotBlank @Pattern(regexp = "[a-z0-9._-]{1,64}") String step,
        UUID gameId,
        Integer turn,
        @Size(max = 255) @Pattern(regexp = "[A-Za-z0-9._:, -]*") String detail
) {
}
