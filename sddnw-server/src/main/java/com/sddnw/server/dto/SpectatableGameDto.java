package com.sddnw.server.dto;

import java.util.UUID;

/**
 * Партия, которую можно смотреть, — строка списка зрителя (field-backlog, пункт 59).
 *
 * @param viewTurn   ход, который зритель увидит сейчас; пусто — партия ещё молода, кадров нет
 * @param yours      спрашивающий сам сидит за этим столом — смотреть свою партию со стороны нельзя
 */
public record SpectatableGameDto(UUID id, String name, String galaxySize, Integer turn, Integer empires,
                                 Integer humans, Integer viewTurn, Boolean yours) {
}
