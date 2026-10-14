package com.sddnw.server.service;

import com.sddnw.server.domain.RaceEffects;
import org.springframework.stereotype.Service;

/**
 * Командные очки империи — п. 8, п. 14.
 * <p>
 * В MOO II флот держится не только на казне: у империи есть «командные единицы», их дают
 * колонии, а корабли их занимают — фрегат одну, Leviathan шесть (число хранится у корпуса
 * в справочнике). Флот сверх этого числа империя всё равно водит, но платит за каждый
 * лишний корабль из казны: у оригинала это и есть тормоз, не дающий построить сотню
 * дредноутов на трёх колониях.
 * <p>
 * <b>Что взято у оригинала, а что реконструировано.</b> Из MOO II взяты две вещи:
 * командные очки корабля (они уже лежат в справочнике корпусов) и правило военачальников
 * — «каждая колония увеличивает командные единицы на 2» (п. 7). Сколько даёт обычная
 * колония и во сколько обходится перебор, оригинал не публикует: здесь колония даёт
 * {@link #POINTS_PER_COLONY} очка, а лишнее очко стоит {@link #COST_PER_EXCESS_POINT}
 * кредит за ход. Числа подобраны так, чтобы прибавка военачальников была ровно
 * удвоением — тем самым, ради которого её и берут.
 */
@Service
public class CommandRules {

    /**
     * Сколько командных очков даёт колония. <i>Реконструкция:</i> два, чтобы прибавка
     * военачальников (+2 с колонии, число оригинала) выходила ровно вдвое.
     */
    public static final int POINTS_PER_COLONY = 2;

    /** Сколько очков добавляет колония военачальникам — п. 7, число MOO II. */
    public static final int WARLORD_POINTS_PER_COLONY = 2;

    /** Во сколько кредитов за ход обходится каждое очко сверх запаса. <i>Реконструкция.</i> */
    public static final int COST_PER_EXCESS_POINT = 1;

    /**
     * Запас командных очков империи — п. 8: его дают колонии.
     *
     * @param colonies сколько у империи колоний
     * @param race     раса империи: военачальники удваивают вклад каждой колонии
     */
    public Integer capacity(Integer colonies, RaceEffects race) {
        int perColony = POINTS_PER_COLONY
                + (Boolean.TRUE.equals(race.warlord()) ? WARLORD_POINTS_PER_COLONY : 0);
        int base = Math.max(0, colonies) * perColony;
        // Империум содержит флот в полтора раза больший — п. 14, число MOO II.
        return base * Math.max(0, 100 + race.commandPercent()) / 100;
    }

    /** Тахионная связь — Physics (перенос MOO II): «increases the command points of these bases by +1». */
    public static final String TACHYON_COMMUNICATIONS = "tachyon-communications";

    /** Подпространственная связь — «increases the command points given by a base by +2». */
    public static final String SUBSPACE_COMMUNICATIONS = "subspace-communications";

    /** Гиперпространственная связь — «increases the command points of star bases … by +3 points». */
    public static final String HYPERSPACE_COMMUNICATIONS = "hyperspace-communications";

    /**
     * Запас с прибавкой связи — п. 8 (перенос MOO II): каждая орбитальная база империи (звёздная
     * база, станция, крепость) даёт +1 с тахионной связью, +2 с подпространственной и +3 с
     * гиперпространственной (лучшая, не сумма). <i>Отступление</i>: сами базы командных очков у нас
     * не дают. Приказы кораблям в пути у базы — {@link FlightRules#canOrder}.
     */
    public Integer capacity(Integer colonies, RaceEffects race, Integer bases, java.util.Set<String> technologies) {
        int perBase = technologies.contains(HYPERSPACE_COMMUNICATIONS) ? 3
                : technologies.contains(SUBSPACE_COMMUNICATIONS) ? 2
                : technologies.contains(TACHYON_COMMUNICATIONS) ? 1 : 0;
        return capacity(colonies, race) + Math.max(0, bases) * perBase;
    }

    /**
     * Сколько казна платит за флот сверх запаса — п. 8.
     * <p>
     * Пока флот укладывается в запас, он бесплатен: в MOO II командные очки это не налог,
     * а предел. Перебор оплачивается каждый ход и целиком — отказаться от лишнего корабля
     * можно только списав его на экране флота.
     */
    public Integer upkeep(Integer used, Integer capacity) {
        return Math.max(0, used - capacity) * COST_PER_EXCESS_POINT;
    }
}
