package com.sddnw.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Наследие ушедших на выбор — backlog-promo, пункт 34.
 *
 * @param picks        сколько можно взять
 * @param deadlineTurn до конца какого хода ждут выбора; дальше берутся первые
 */
public record ArtifactChoiceDto(UUID id, String systemName, Integer picks, Integer deadlineTurn,
                                List<Option> options) {

    /** Технология-кандидат: название и описание на языке читателя, раздел и уровень. */
    public record Option(String code, String name, String description, String categoryCode, Integer levelOrder) {
    }
}
