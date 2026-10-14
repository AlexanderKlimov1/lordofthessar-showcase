package com.sddnw.server.dto;

/**
 * Можно ли объявить победу — field-backlog, пункт 154: мощь своя и всех прочих живых империй
 * вместе, во сколько раз своей нужно быть больше и, если нельзя, — почему, словами на языке
 * запроса. Меню «Игра» гасит пункт по этому ответу ДО нажатия.
 */
public record VictoryClaimDto(Boolean allowed, Integer own, Integer others, Integer ratio, String reason) {
}
