package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Баланс рас в открытую — field-backlog, пункты 70 и 101: что намерил прибор, без управления
 * прогонами. Все числа — из записей законченных прогонов ({@code balance_run}), а не вписанные руками:
 * число растёт само с каждым прогоном.
 *
 * @param runs           сколько прогонов закончено (замеров и оракулов)
 * @param games          сколько партий в них сыграно
 * @param turns          сколько ходов в этих партиях
 * @param lastFinishedAt когда закончился последний
 * @param minGames       с какого числа партий прогон показывается приговорами: крошечные прогоны
 *                       сквозной проверки шумят и вывода не дают
 * @param measure        последний законченный замер цен не меньше {@code minGames} партий; пусто — нет
 * @param oracle         последний законченный оракул сборок не меньше {@code minGames} партий; пусто — нет
 * @param previousAt     когда закончился предыдущий такой замер — от него считаются изменения цен
 * @param priceChanges   что поменялось в ценах сторон между предыдущим и последним замером
 */
public record OpenBalanceDto(Integer runs, Integer games, Long turns, OffsetDateTime lastFinishedAt,
                             Integer minGames, BalanceRunDto measure, BalanceRunDto oracle,
                             OffsetDateTime previousAt, List<PriceChangeDto> priceChanges) {

    /** Цена стороны расы: была, стала. Пусто «было» — стороны раньше не было. */
    public record PriceChangeDto(String code, Integer before, Integer after) {
    }
}
