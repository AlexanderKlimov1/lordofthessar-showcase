package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * История своих партий — field-backlog, пункт 18, — с личными рекордами.
 *
 * @param games        сыгранные партии, свежие первыми
 * @param played       сколько партий доиграно или признано
 * @param wins         сколько из них выиграно
 * @param fastestWin   ход самой быстрой победы; пусто — побед ещё не было
 * @param mostColonies больше всего колоний разом в одной партии
 * @param hardestWin   наибольшее отставание в мощи, после которого всё же победили, %
 */
public record GameHistoryDto(
        List<Game> games,
        Integer played,
        Integer wins,
        Integer fastestWin,
        Integer mostColonies,
        Integer hardestWin,
        /** Звания за трудное (пункт 41) с ходом к ним — все, а не только заработанные. */
        List<com.sddnw.server.service.TitleRules.Title> titles,
        /** Рейтинг среди людей (пункт 19) со списком; пусто — своих партий мало или с людьми не играли. */
        RatingDto rating,
        /** Подарков за приглашение — спутников, с кем доиграли партию по приглашению (пункты 50, 174), 0…3. */
        Integer companions
) {

    /** Одна сыгранная партия. */
    public record Game(
            String gameName,
            String raceName,
            String galaxySize,
            Integer empires,
            String outcome,
            String victoryKind,
            Integer turn,
            Integer place,
            Integer peakColonies,
            Integer deficitTurn,
            Integer deficitPercent,
            OffsetDateTime finishedAt,
            /** Свои проекты кораблей партии (пункт 98), лучший первым; пусто — своих не было. */
            List<OwnDesignDto> designs
    ) {
    }
}
