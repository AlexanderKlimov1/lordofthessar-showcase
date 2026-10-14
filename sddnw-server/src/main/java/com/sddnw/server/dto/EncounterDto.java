package com.sddnw.server.dto;

import java.util.UUID;

/**
 * Встреча флотов в системе — п. 8, со стороны одного игрока.
 * <p>
 * Что видно игроку: где встретились, чей флот напротив, чья очередь решать и чем всё
 * кончилось, если уже кончилось.
 *
 * @param yourTurn          решение сейчас за этим игроком
 * @param firstMover        этот игрок ходит первым: его флот быстрее
 * @param yourShips         кораблей у него
 * @param opponentShips     кораблей у соперника
 * @param yourInitiative    инициатива его флота
 * @param opponentInitiative инициатива флота соперника
 * @param battleId          тактический бой, если игрок выбрал ручной, — п. 8
 * @param outcome           исход боя; пусто, пока встреча не закрыта
 */
public record EncounterDto(
        UUID id,
        UUID starSystemId,
        String systemName,
        Integer turn,
        String state,
        String stateLabel,
        Boolean yourTurn,
        Boolean firstMover,
        UUID opponentPlayerId,
        String opponentName,
        Integer yourShips,
        Integer opponentShips,
        Integer yourInitiative,
        Integer opponentInitiative,
        UUID battleId,
        String outcome,
        /**
         * Встреча у беззащитной колонии флота с бомбами — пункт 25: решение «бомбить или
         * уйти», как диалог оригинала «Select Combat at …: BOMBARD / CANCEL».
         */
        Boolean bombard,
        /**
         * Прогноз встречи — field-backlog, пункты 84 и 153: своя сила (флот и платформы обороны
         * своих колоний системы) — всегда точно.
         */
        Integer yourPower,
        /** Сила соперника — только когда все технологии на его кораблях увидены; пусто — не видна. */
        Integer opponentPower,
        /** Исход словами: LIKELY_WIN, UNCLEAR, LIKELY_LOSS или UNKNOWN ({@code EncounterForecastRules}). */
        String forecast,
        /** Сколько технологий на кораблях соперника ещё не увидено — то, что прогноз назвал неизвестным. */
        Integer unseenTechnologies
) {
}
