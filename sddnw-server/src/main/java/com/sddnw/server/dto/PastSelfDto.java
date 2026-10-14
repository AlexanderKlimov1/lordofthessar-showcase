package com.sddnw.server.dto;

/**
 * Сравнение с собой прошлым — field-backlog, пункт 115.
 *
 * @param checkpoint контрольный ход (50, 100, 150)
 * @param mine       мощь в этой партии на нём
 * @param best       мощь в лучшей своей партии той же настройки на том же ходу
 * @param percent    насколько выше (плюс) или ниже (минус) лучшей, %
 */
public record PastSelfDto(
        Integer checkpoint,
        Integer mine,
        Integer best,
        Integer percent
) {
}
