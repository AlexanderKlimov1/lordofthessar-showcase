package com.sddnw.server.dto;

import java.util.List;

/**
 * Справочник «Как устроено» — field-backlog, пункты 75 и 200: порядок конца хода и статьи о
 * правилах, на языке читателя.
 *
 * @param phases   фазы конца хода в порядке, в каком их считает сервер
 * @param articles статьи о правилах с числами из классов правил
 */
public record RulesBookDto(
        List<Phase> phases,
        List<Article> articles
) {

    /**
     * Фаза конца хода.
     *
     * @param code  имя класса фазы — им же подписано событие TURN_PHASE (пункт 100)
     * @param order номер в порядке хода: меньше — раньше
     * @param title название словами игрока
     * @param text  что в ней происходит одной-двумя фразами
     */
    public record Phase(String code, Integer order, String title, String text) {
    }

    /**
     * Статья о правиле.
     *
     * @param code      опознаватель статьи (victory, council…)
     * @param title     заголовок
     * @param text      правило с числами
     * @param deviation чем правило этой игры отличается от оригинала; пусто — ничем
     */
    public record Article(String code, String title, String text, String deviation) {
    }
}
