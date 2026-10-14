package com.sddnw.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Карта галактики: прямоугольное поле в парсеках и звёздные системы — п. 3 / п. 11.3.
 * <p>
 * Парсеки те же, что у дальности топлива и скорости кораблей (п. 8): множителя между
 * картой и полётом больше нет — четыре парсека Standard Fuel Cells и есть четыре парсека
 * на карте.
 */
public record GalaxyMapDto(
        UUID gameId,
        String galaxySize,
        Integer widthParsecs,
        Integer heightParsecs,
        Double minStarDistanceParsecs,
        List<StarSystemDto> systems,
        /** Чужие флоты, которые игрок видит (п. 15, туман войны): под глазами и сканерами. */
        List<ForeignFleetDto> foreignFleets,
        /**
         * Клад особой звезды названным наполовину — field-backlog, пункт 135: «первому, кто
         * поселится у звезды, — столько-то технологий Стражей», без названий самих технологий
         * (какие — скажет сцена наследия). Пусто — клад уже взят или особой звезды нет.
         */
        Integer specialStarReward,
        /**
         * Метки наставника на карте этого игрока — field-backlog, пункт 184; пусто — наставника нет
         * или он ничего не отметил.
         */
        List<MapMarkDto> mentorMarks
) {

    /** Та же карта с метками наставника. */
    public GalaxyMapDto withMentorMarks(List<MapMarkDto> marks) {
        return new GalaxyMapDto(gameId, galaxySize, widthParsecs, heightParsecs, minStarDistanceParsecs,
                systems, foreignFleets, specialStarReward, marks.isEmpty() ? null : marks);
    }
}
