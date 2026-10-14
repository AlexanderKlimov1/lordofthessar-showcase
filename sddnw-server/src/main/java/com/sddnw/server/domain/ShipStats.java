package com.sddnw.server.domain;

/**
 * Характеристики проекта корабля — п. 8. То, что показывает окно дизайна и по чему
 * считается бой.
 *
 * @param space     полезное место корпуса с учётом боевых отсеков
 * @param spaceUsed сколько места занято компонентами
 * @param cost      во что обходится корабль колонии, единиц производства
 * @param structure прочность корпуса: сколько урона он держит после брони
 * @param armour    броневые очки — снимаются раньше прочности
 * @param shield    ЗАПАС щита — п. 8, правило MOO II: сколько урона щит поглощает, прежде чем
 *                  упасть до конца круга (класс × 5 × множитель корпуса); восстанавливается
 *                  на 30 % за круг
 * @param speed     скорость в ГРЕ за ход
 * @param combatSpeed боевая скорость двигателя — п. 8, число оригинала; поле боя делит её
 *                  на четыре и получает клетки за ход
 * @param attack    залп с учётом приборов и расы
 * @param defense   уклонение корпуса с расой — от лучей и снарядов
 * @param missileEvasion уклонение от ракет сверх обычного: постановщик помех — п. 8
 * @param troops    морская пехота на борту — п. 12
 * @param command   командных очков забирает корабль у империи
 * @param rangePercent прибавка к дальности полёта в процентах — дополнительные баки (п. 8);
 *                  сама дальность считается от топлива империи, поэтому здесь только процент
 * @param damageAbsorbPercent какую долю урона гасит энергопоглотитель — п. 8; ноль — его нет
 * @param shieldClass класс щита — п. 8: столько урона он срезает с КАЖДОГО выстрела, не тратя
 *                    запаса («completely negates N points of damage from each weapon»)
 */
public record ShipStats(
        Integer space,
        Integer spaceUsed,
        Integer cost,
        Integer structure,
        Integer armour,
        Integer shield,
        Integer speed,
        Integer combatSpeed,
        Integer attack,
        Integer defense,
        Integer missileEvasion,
        Integer troops,
        Integer command,
        Integer rangePercent,
        Integer damageAbsorbPercent,
        Integer shieldClass,
        /**
         * Удар истребителей за круг в мерах залпа, уже уполовиненный — п. 8 ({@code
         * ShipDesignRules.fighterSalvo}); в {@link #attack} не входит: окно дизайна показывает залп
         * стволов, а истребители — отдельная сила, которую считает {@code power}.
         */
        Integer fighterAttack,
        /** Стоит ускоритель времени — лишний ход в конце круга (п. 8); {@code power} удваивает удар. */
        Boolean timeWarp
) {

    /** Характеристики без истребителей и ускорителя — для проверок и чудищ. */
    public ShipStats(Integer space, Integer spaceUsed, Integer cost, Integer structure, Integer armour,
                     Integer shield, Integer speed, Integer combatSpeed, Integer attack, Integer defense,
                     Integer missileEvasion, Integer troops, Integer command, Integer rangePercent,
                     Integer damageAbsorbPercent, Integer shieldClass) {
        this(space, spaceUsed, cost, structure, armour, shield, speed, combatSpeed, attack, defense,
                missileEvasion, troops, command, rangePercent, damageAbsorbPercent, shieldClass, 0, Boolean.FALSE);
    }

    /** Свободное место в корпусе; отрицательное — проект не помещается. */
    public Integer spaceLeft() {
        return space - spaceUsed;
    }

    /** Помещается ли проект в корпус. */
    public Boolean fits() {
        return spaceUsed <= space;
    }
}
