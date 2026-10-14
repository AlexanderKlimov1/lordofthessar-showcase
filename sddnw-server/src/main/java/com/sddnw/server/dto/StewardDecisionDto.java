package com.sddnw.server.dto;

/**
 * Решение, которое ИИ принял за ушедшего игрока, — field-backlog, пункт 112: ход и текст на
 * языке читателя.
 */
public record StewardDecisionDto(Integer turn, String text) {
}
