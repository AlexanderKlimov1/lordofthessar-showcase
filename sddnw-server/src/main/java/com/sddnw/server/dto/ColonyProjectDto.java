package com.sddnw.server.dto;

/**
 * Проект, доступный колонии для постройки — п. 10.
 *
 * @param special    особый проект MOO II — дома или товары: он не заканчивается, зданием
 *                   не становится и на планете ничего не занимает
 * @param sellValue  сколько кредитов даст казне продажа этой постройки — п. 10; пусто у
 *                   особых проектов: продавать в них нечего
 * @param repeatable можно ли поставить проект в очередь ещё раз, когда он уже строится, —
 *                   п. 10: здание на планете одно, а кораблей, шпионов и грузовиков
 *                   строят сколько угодно. Правило живёт на сервере
 *                   ({@link com.sddnw.server.domain.ColonyProject#repeatable}), а экран
 *                   по нему гасит кнопку «в очередь»
 */
public record ColonyProjectDto(
        String code,
        String name,
        String description,
        Integer cost,
        Integer upkeep,
        Boolean special,
        Integer sellValue,
        Boolean repeatable,
        /**
         * За сколько ходов здание окупится НА ЭТОЙ колонии — field-backlog, пункт 122: то же число,
         * по которому выбирают ИИ и автострой ({@code AiEmpireService.paybackTurns}). Пусто — у
         * здания нет окупаемости в производстве, и тогда говорит {@link #purpose}.
         */
        Integer paybackTurns,
        /**
         * Ради чего строят здание без окупаемости: DEFENCE, FOOD, INCOME, GROWTH, POLLUTION,
         * MORALE, GOVERNANCE или OTHER. Число не выдумывается — называется назначение.
         */
        String purpose
) {
}
