package com.sddnw.server.dto;

/**
 * Меньше движения — field-backlog, пункт 170: да, нет или пусто — «как в системе».
 */
public record MotionRequest(
        Boolean reduceMotion
) {
}
