package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Сохранить набор справочников — field-backlog, пункт 42. Каждый текст — JSON того же вида, что
 * файл игры в {@code resources/} (здания, корабли, технологии, лидеры); пусто — справочник
 * остаётся обычным. Хотя бы один должен быть.
 */
public record SaveModSetRequest(
        @NotBlank @Size(max = 64) String name,
        String buildings,
        String ships,
        String technologies,
        String leaders
) {
}
