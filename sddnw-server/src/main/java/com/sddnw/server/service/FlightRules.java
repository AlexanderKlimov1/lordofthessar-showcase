package com.sddnw.server.service;

import com.sddnw.server.domain.enums.FuelTech;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Числовые правила перелёта флота — п. 8: дальность, время в пути и право менять курс.
 * <p>
 * Всё, что в MOO II меряется парсеками, живёт здесь: карта этой игры размечена в ГРЕ, и
 * переводом занимается одно место, а не каждый вызывающий. Правила три:
 * <ol>
 *   <li><b>Дальность.</b> Лететь можно не дальше, чем позволяют топливные элементы, и
 *       меряется дальность от ближайшей своей колонии — {@link FuelTech}. Топливо даёт
 *       империя всем кораблям сразу, а дополнительные баки — модуль КОРАБЛЯ, и его
 *       прибавка приходит сюда процентом (п. 8).</li>
 *   <li><b>Время в пути.</b> Расстояние, делённое на скорость самого медленного корабля
 *       флота: скорость двигателей в справочнике задана в парсеках за ход.</li>
 *   <li><b>Смена курса.</b> Улетевший флот приказов не слышит, пока империя не изучит
 *       Hyperspace Communications, — в оригинале это её единственное назначение,
 *       не считая командных очков.</li>
 * </ol>
 */
@Service
public class FlightRules {

    /**
     * Технология, позволяющая перенаправить флот в полёте — Physics, уровень
     * Hyper-Dimensional Physics (6000 ОИ). До неё отданный приказ отменить нельзя: флот
     * уходит в подпространство и связи с ним нет.
     */
    public static final String REDIRECT_TECH = "hyperspace-communications";

    /**
     * Дальность империи в парсеках: лучшее изученное топливо, без баков.
     * <p>
     * Карта меряется теми же парсеками (п. 4.2), поэтому переводить дальность больше не
     * во что: четыре парсека Standard Fuel Cells — это четыре парсека на карте.
     */
    public Integer rangeParsecs(Set<String> technologies) {
        return FuelTech.baseRangeParsecs(technologies);
    }

    /**
     * Дальность корабля или флота — п. 8: топливо империи плюс проценты его собственных
     * баков ({@code ShipEffectType.FUEL_RANGE_PERCENT}).
     * <p>
     * У флота процент берётся по САМОМУ КОРОТКОМУ кораблю — тем же правилом, что и
     * скорость по самому медленному: строй летит вместе, и корабль без баков держит при
     * себе весь флот.
     * <p>
     * Неограниченную дальность баки не трогают: {@link FuelTech#UNLIMITED_PARSECS} и так
     * накрывает самую большую галактику, а полтора таких числа не значили бы ничего.
     */
    public Integer rangeParsecs(Set<String> technologies, Integer rangePercent) {
        int base = FuelTech.baseRangeParsecs(technologies);
        if (base >= FuelTech.UNLIMITED_PARSECS) {
            return FuelTech.UNLIMITED_PARSECS;
        }
        return base + base * Math.max(0, rangePercent) / 100;
    }

    /** Расстояние между точками карты в парсеках. */
    public Double distanceParsecs(Double fromX, Double fromY, Double toX, Double toY) {
        return Math.hypot(toX - fromX, toY - fromY);
    }

    /**
     * Сколько ходов флот будет в пути.
     * <p>
     * Скорость флота — парсеки за ход по самому медленному кораблю, расстояние тоже в
     * парсеках: делить одно на другое больше нечем. Меньше одного хода перелёт не бывает:
     * ход — наименьшая единица времени в игре, и мгновенных перелётов в MOO II нет.
     */
    public Integer travelTurns(Double distanceParsecs, Integer speedParsecs) {
        int speed = Math.max(1, speedParsecs);
        return Math.max(1, (int) Math.ceil(distanceParsecs / speed));
    }

    /**
     * Прыжковые врата — Physics, перенос MOO II (13.10.2026): «a controlled wormhole between two
     * points, increasing the speed of ships moving through it by 3 parsecs a turn. Once
     * discovered, all colonies will automatically be equipped with one». Врата стоят у каждой
     * колонии, поэтому разгон получает флот, уходящий из системы со своей колонией, —
     * реконструкция «точки входа»: где кончается червоточина, оригинал не называет, а флот
     * идёт и к чужим звёздам.
     */
    public static final String JUMP_GATE_TECH = "jump-gate";

    /** Прибавка прыжковых врат к скорости, парсеков за ход — число оригинала. */
    public static final int JUMP_GATE_PARSECS = 3;

    /**
     * Звёздные врата — Physics, перенос MOO II: «instantaneous movement between any of your
     * colonies. All colonies are automatically equipped with one». Мгновенного перелёта у нас
     * нет — ход наименьшая единица времени, — поэтому от колонии к колонии флот идёт ОДИН ход
     * при любом расстоянии.
     */
    public static final String STAR_GATE_TECH = "star-gate";

    /**
     * Срок перелёта с вратами — п. 8: звёздные врата между двумя своими колониями дают один ход,
     * прыжковые прибавляют скорость при уходе от своей колонии, иначе — обычный срок.
     *
     * @param fromColony в системе вылета есть населённая колония владельца флота
     * @param toColony   в системе цели есть населённая колония владельца флота
     */
    public Integer travelTurns(Double distanceParsecs, Integer speedParsecs, Set<String> technologies,
                               Boolean fromColony, Boolean toColony) {
        if (Boolean.TRUE.equals(fromColony) && Boolean.TRUE.equals(toColony)
                && technologies.contains(STAR_GATE_TECH)) {
            return 1;
        }
        int speed = speedParsecs;
        if (Boolean.TRUE.equals(fromColony) && technologies.contains(JUMP_GATE_TECH)) {
            speed += JUMP_GATE_PARSECS;
        }
        return travelTurns(distanceParsecs, speed);
    }

    /**
     * Срок перелёта к системе под чужим варп-перехватчиком — п. 8 (перенос MOO II: «slows all enemy
     * movement to 1 parsec per turn» в радиусе {@code radius} парсеков): последние парсеки пути
     * флот идёт по одному за ход, прочее — своей скоростью. Звёздные врата в поле перехватчика не
     * работают. <i>Реконструкция</i>: поле ловит флот, летящий В его систему; пролёта мимо у нас нет —
     * флот летит прямо к цели.
     */
    public Integer travelTurns(Double distanceParsecs, Integer speedParsecs, Set<String> technologies,
                               Boolean fromColony, Boolean toColony, Integer interdictorRadius) {
        if (interdictorRadius == null || interdictorRadius <= 0) {
            return travelTurns(distanceParsecs, speedParsecs, technologies, fromColony, toColony);
        }
        double slow = Math.min(distanceParsecs, interdictorRadius);
        int speed = speedParsecs;
        if (Boolean.TRUE.equals(fromColony) && technologies.contains(JUMP_GATE_TECH)) {
            speed += JUMP_GATE_PARSECS;
        }
        int before = distanceParsecs - slow <= 0 ? 0
                : (int) Math.ceil((distanceParsecs - slow) / Math.max(1, speed));
        return Math.max(1, before + (int) Math.ceil(slow));
    }

    /** Империя слышит корабли в полёте ВЕЗДЕ — гиперпространственная связь. */
    public Boolean canRedirect(Set<String> technologies) {
        return technologies.contains(REDIRECT_TECH);
    }

    /**
     * Сколько парсеков от своей орбитальной базы флот в пути ещё слышит приказ без связи —
     * решение хозяина проекта (14.10.2026): «рядом с базой» значит строго меньше парсека. Отлетев на
     * парсек, флот без связи уже не развернуть.
     */
    public static final double NEAR_BASE_PARSECS = 1.0;

    /** Тахионная связь — Physics, MOO II: «ships within 3 parsecs from a base can receive your orders». */
    public static final int TACHYON_ORDER_PARSECS = 3;

    /** Подпространственная связь — «any ship within 6 parsecs of a star system with a Star Base». */
    public static final int SUBSPACE_ORDER_PARSECS = 6;

    /**
     * Слышит ли флот в пути приказ — п. 8 (перенос MOO II): с гиперпространственной связью — всегда;
     * иначе по расстоянию от его текущей точки до ближайшей своей орбитальной базы (звёздная база,
     * станция, крепость): с подпространственной связью до 6 пк, с тахионной до 3, без связи — меньше
     * парсека. {@code nearestBaseParsecs} — {@code null}, когда баз нет вовсе.
     */
    public Boolean canOrder(Set<String> technologies, Double nearestBaseParsecs) {
        if (Boolean.TRUE.equals(canRedirect(technologies))) {
            return Boolean.TRUE;
        }
        if (nearestBaseParsecs == null) {
            return Boolean.FALSE;
        }
        if (technologies.contains(CommandRules.SUBSPACE_COMMUNICATIONS)) {
            return nearestBaseParsecs <= SUBSPACE_ORDER_PARSECS;
        }
        if (technologies.contains(CommandRules.TACHYON_COMMUNICATIONS)) {
            return nearestBaseParsecs <= TACHYON_ORDER_PARSECS;
        }
        return nearestBaseParsecs < NEAR_BASE_PARSECS;
    }

    /**
     * Где флот в пути на ходу {@code turn} — по доле пройденных ходов от системы вылета к цели, то же
     * правило, что у сканеров ({@code IntelService.seesFleet}): координат у флота в пути нет.
     */
    public static double[] point(double fromX, double fromY, double toX, double toY,
                                 int departureTurn, int arrivalTurn, int turn) {
        int total = Math.max(1, arrivalTurn - departureTurn);
        double passed = Math.max(0, Math.min(1, (double) (turn - departureTurn) / total));
        return new double[]{fromX + (toX - fromX) * passed, fromY + (toY - fromY) * passed};
    }
}
