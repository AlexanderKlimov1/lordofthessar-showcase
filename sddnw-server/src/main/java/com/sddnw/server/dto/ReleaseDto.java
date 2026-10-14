package com.sddnw.server.dto;

/**
 * Номер версии игры — field-backlog, пункт 159: стоит в главном меню и уходит с каждым
 * сообщением о проблеме, иначе по сообщению не понять, какую игру видел игрок.
 */
public record ReleaseDto(
        String version
) {
}
