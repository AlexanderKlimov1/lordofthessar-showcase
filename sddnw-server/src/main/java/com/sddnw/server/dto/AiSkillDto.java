package com.sddnw.server.dto;

/**
 * Уровень сложности для окна новой игры и «Инфо» — field-backlog, пункты 9 и 87: что ИИ этого уровня
 * делает, теми же признаками, по которым он играет ({@code AiSkill}). Числа и признаки идут с
 * сервера, а не вписаны в словарь: при первой же настройке вписанные разошлись бы с правилом.
 *
 * @param code                код уровня
 * @param build               как выбирается здание: PAYBACK — по окупаемости, LIST — по списку,
 *                            SHUFFLED — вразнобой
 * @param recommendedResearch берёт ли рекомендованную технологию уровня дерева
 * @param purchasesPerTurn    сколько стройки выкупает за ход (0 — не выкупает)
 * @param coalitions          собирает ли коалицию против сильнейшего
 * @param openingBook         играет ли лучший измеренный дебют своей расы
 */
public record AiSkillDto(String code, String build, Boolean recommendedResearch, Integer purchasesPerTurn,
                         Boolean coalitions, Boolean openingBook) {
}
