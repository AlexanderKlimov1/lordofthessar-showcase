package com.sddnw.server.dto;

/**
 * Свой проект корабля в итогах — field-backlog, пункт 98: собранный игроком (не игрой) и сколько
 * по нему построено. Созданное ценится, когда его видно.
 */
public record OwnDesignDto(String name, Integer built) {
}
