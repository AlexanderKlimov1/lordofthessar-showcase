package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.BattleSide;

import java.util.List;
import java.util.UUID;

/**
 * Корабль на поле боя — п. 8, для сцены боя.
 * <p>
 * Экран рисует корабль рисунком его корпуса (`shipArt` на клиенте, выбор по `hullCode`), а
 * размер берёт из корпуса: фрегат мал, Leviathan занимает пол-экрана.
 *
 * @param hullSize   размер корпуса, 1..6: им и меряется рисунок корабля на поле
 * @param structure  прочность сейчас; ноль — корабль уничтожен
 * @param initiative очередь хода: чем больше, тем раньше корабль ходит в круге
 * @param speed      сколько клеток корабль проходит за свой ход
 * @param moveLeft   сколько ему осталось пройти в этом ходу
 * @param weaponRange насколько далеко бьёт самый дальнобойный его ствол: с модификациями
 *                    (HV, PD) дальность у каждого корабля своя
 * @param yours      корабль этого игрока: только им он и может ходить
 * @param platform   корпус — орбитальная платформа (п. 8, п. 11): звёздная база и её
 *                   старшие сёстры стоят на поле сооружением, а не кораблём
 * @param defense    защита от лучей: та же величина, что в окне дизайна
 * @param missileEvasion уклонение от ракет в процентах — его даёт постановщик помех
 * @param weapons    чем корабль вооружён — п. 8: список для нижней полосы и окна осмотра
 * @param specials   особые модули корабля названиями
 * @param engineName двигатель, {@code null} — его нет вовсе (платформы неподвижны)
 * @param armourName броня корабля
 * @param shieldName щит; {@code null} — щита нет
 * @param computerName прибор наведения; {@code null} — его нет
 */
public record BattleShipDto(
        UUID id,
        UUID ownerPlayerId,
        String ownerName,
        BattleSide side,
        String designName,
        String hullName,
        /**
         * Код корпуса справочника (`frigate`, `star-base`, `dragon-body`) — по нему сцена
         * выбирает РИСУНОК: у каждой платформы и каждого чудища свой. Подпись для этого не
         * годится — она на языке читателя и переводится.
         */
        String hullCode,
        Integer hullSize,
        Integer ordinal,
        Integer x,
        Integer y,
        Integer structure,
        Integer maxStructure,
        Integer armour,
        Integer maxArmour,
        Integer shield,
        /** Полный запас щита — п. 8: сколько он восстановит, если его не трогать. */
        Integer maxShield,
        /** Класс щита — п. 8: столько он срезает с каждого выстрела, не тратя запаса. */
        Integer shieldClass,
        Integer attack,
        Integer initiative,
        Integer speed,
        Integer moveLeft,
        Integer weaponRange,
        Boolean fired,
        Boolean destroyed,
        Boolean yours,
        Boolean platform,
        /**
         * На поле не корабль, а КОСМИЧЕСКОЕ ЧУДИЩЕ — п. 11.1: сцена рисует его существом,
         * а не прямоугольником корпуса. Признак приходит с сервера, а не угадывается по
         * названию проекта: имя хранится в базе и переименуется.
         */
        Boolean monster,
        Integer defense,
        Integer missileEvasion,
        List<BattleWeaponDto> weapons,
        List<String> specials,
        /** Двигатель разбит — п. 8: корабль неподвижен и с поля боя уже не уйдёт. */
        Boolean engineWrecked,
        /** Щит разбит: между кругами ему восстанавливаться нечем — п. 8. */
        Boolean shieldWrecked,
        /** Прицельный компьютер сожжён: корабль целится голым прицелом — п. 8. */
        Boolean computerWrecked,
        String engineName,
        String armourName,
        String shieldName,
        String computerName,
        /**
         * Шанс противоракет в упор, в процентах (пункт 30); ноль — их на корабле нет. По нему
         * сцена знает, достаёт ли корабль до залпа в полёте, даже без единого луча.
         */
        Integer antiMissile,
        /** Морская пехота на борту — п. 8, абордаж. */
        Integer marines,
        /** Корабль взят абордажем и воюет за захватчика — п. 8. */
        Boolean captured,
        /** С какого расстояния корабль идёт на абордаж: 1 — вплотную, больше — телепортом. */
        Integer boardingRange,
        /** Обездвижен (двигатель разбит или держат тяговые лучи) — его можно брать вплотную. */
        Boolean immobile,
        /** Получил приказ отходить и уйдёт в свой следующий ход — п. 8. */
        Boolean retreating,
        /** Ушёл с поля отходом — п. 8: на поле его больше нет, но он и не погиб. */
        Boolean retreated,
        /**
         * Проект собран ИГРОКОМ, а не игрой — field-backlog, пункт 98: созданное ценится, когда
         * его видно, и карточка боя называет его «ваш проект». Признак — {@code ship_design.auto}:
         * шесть ячеек, платформы и гражданские корабли собирает игра; трофей — чужая работа.
         */
        Boolean crafted,
        /** Заморожен стазисным полем — п. 8: не ходит, не стреляет, атаковать его нельзя. */
        Boolean frozen,
        /** Замаскирован — п. 8: защищён от лучей и ракет, пока не выстрелит. */
        Boolean cloaked
) {
}
