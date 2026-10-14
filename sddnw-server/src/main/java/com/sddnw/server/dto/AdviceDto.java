package com.sddnw.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Что выбрал бы советник — field-backlog, пункт 23. Советник называет, решает игрок: ни цель
 * науки, ни стройка от этого ответа не меняются.
 *
 * @param research что изучать и почему; пусто — изучать нечего
 * @param colonies совет каждой колонии, у которой он есть
 */
public record AdviceDto(Research research, List<Colony> colonies) {

    /**
     * @param optionCode технология, которую советник взял бы
     * @param wantedName нужда, к которой она лишь шаг (название на языке читателя); пусто — она сама и есть нужда
     * @param reason     почему — одной фразой
     * @param chosen     игрок уже исследует именно это
     */
    public record Research(String categoryCode, Integer levelOrder, String optionCode, String optionName,
                           String wantedName, String reason, Boolean chosen) {
    }

    /**
     * @param idle         колония свободна — стоит на товарах или без стройки
     * @param paybackTurns за сколько ходов здание окупится на этой колонии
     */
    public record Colony(UUID planetId, String planetName, String projectCode, String projectName,
                         Integer paybackTurns, Boolean idle, String reason) {
    }
}
