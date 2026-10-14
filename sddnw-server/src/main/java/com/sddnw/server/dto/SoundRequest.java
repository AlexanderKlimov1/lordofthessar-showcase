package com.sddnw.server.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Громкость звука игрока: процент 0…100 у музыки и у звуковых эффектов, 0 — выключено.
 * <p>
 * Обе ступени одним запросом: переключатели стоят рядом и сохраняют настройку целиком, а два
 * адреса ради двух чисел значили бы две записи одной настройки. Что громкость в пределах,
 * проверяет служба ({@code AccountService.SOUND_MAX}), а не аннотация — предел живёт в одном
 * месте, как у размера текста.
 */
public record SoundRequest(
        @NotNull Integer musicVolume,
        @NotNull Integer sfxVolume
) {
}
