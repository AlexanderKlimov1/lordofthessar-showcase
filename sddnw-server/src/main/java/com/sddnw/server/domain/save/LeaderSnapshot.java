package com.sddnw.server.domain.save;

/**
 * Лидер в слепке партии — п. 6, трек техдолга, пункт 28.
 * <p>
 * До этого слепка поднятое сохранение теряло ВСЕХ лидеров — нанятых, назначенных, с опытом и
 * званием — и с ними их прибавки: сохранение здесь не зеркало базы, а отдельный список полей
 * (грабля чудищ, правила проекта). Хозяин назван местом, система — номером в списке систем, флот —
 * номером в списке флотов слепка: при загрузке всё это заводится заново.
 *
 * @param ownerSlot   место игрока
 * @param code        код лидера в справочнике
 * @param kind        род: COLONY или SHIP
 * @param state       OFFERED, HIRED, DISMISSED, KILLED
 * @param systemIndex где служит губернатор; {@code null} — в резерве
 * @param fleetIndex  где служит корабельный лидер; {@code null} — в резерве
 */
public record LeaderSnapshot(
        Integer ownerSlot,
        String code,
        String kind,
        String state,
        Integer experience,
        Integer offeredTurn,
        Integer hiredTurn,
        Integer hireCost,
        Integer salary,
        Integer systemIndex,
        Integer fleetIndex,
        Integer arrivesTurn,
        Integer battlesWon,
        Integer battlesLost,
        Integer termStartTurn,
        Integer termStartPopulation
) {
}
