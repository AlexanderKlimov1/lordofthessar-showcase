package com.sddnw.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Партия глазами зрителя — field-backlog, пункт 59: вся галактика без тумана войны, но на
 * {@code turn}, а не на нынешнем ходу партии ({@code gameTurn}).
 * <p>
 * Свой ответ, а не карта игрока ({@code GalaxyMapDto}): та собрана из глаз одной империи — «ваши»
 * флоты, разведанное, туман, — а у зрителя своей империи нет. Здесь только то, что видно со
 * стороны: чьи звёзды, где флоты, кто сколько весит.
 */
public record SpectatorFrameDto(
        UUID gameId,
        String name,
        Integer turn,
        Integer gameTurn,
        Integer widthParsecs,
        Integer heightParsecs,
        List<EmpireDto> empires,
        List<StarDto> stars,
        List<FleetDto> fleets) {

    /**
     * Империя со стороны: мощь — первая линия графика «Инфо» ({@code EmpireMightRules}) на
     * последнем замере летописи.
     */
    public record EmpireDto(Integer slot, String name, String raceName, String color, Boolean ai,
                            Integer colonies, Integer populationK, Integer might, Boolean alive) {
    }

    /** Звезда: чьи у неё колонии ({@code owners} — места империй) и кто её сторожит. */
    public record StarDto(UUID id, String name, Double x, Double y, String starColor, Boolean special,
                          List<Integer> owners, String monster) {
    }

    /**
     * Флот: чей, где стоит или откуда и куда летит, сколько кораблей и есть ли оружие. Летящий —
     * с {@code originId}, {@code targetId} и ходами вылета и прибытия: зритель рисует его на пути.
     */
    public record FleetDto(Integer slot, UUID systemId, UUID originId, UUID targetId,
                           Integer departureTurn, Integer arrivalTurn, Integer ships, Boolean armed) {
    }
}
