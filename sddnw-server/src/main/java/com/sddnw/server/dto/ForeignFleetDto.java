package com.sddnw.server.dto;

import java.util.UUID;

/**
 * Чужой флот, который игрок видит на карте — п. 8, п. 15, туман войны.
 * <p>
 * В оригинале сканеры показывают флоты противника в своём радиусе, а у летящего — «планету,
 * куда он летит, и срок прибытия» (StrategyWiki, Engineering). Состав флота со стороны не
 * виден: только число кораблей и есть ли среди них вооружённые — его осматривают в бою.
 *
 * @param starSystemId   где стоит; у летящего — откуда вылетел
 * @param targetSystemId куда летит; пусто — стоит
 * @param armed          есть ли в нём корабли с оружием
 */
public record ForeignFleetDto(
        UUID id,
        UUID ownerPlayerId,
        Integer ships,
        Boolean armed,
        UUID starSystemId,
        UUID targetSystemId,
        Integer departureTurn,
        Integer arrivalTurn
) {
}
