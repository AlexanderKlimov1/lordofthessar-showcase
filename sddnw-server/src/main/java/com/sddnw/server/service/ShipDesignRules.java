package com.sddnw.server.service;

import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.ShipComponent;
import com.sddnw.server.domain.WeaponModification;
import com.sddnw.server.domain.ShipHull;
import com.sddnw.server.domain.ShipStats;
import com.sddnw.server.domain.enums.ShipComponentSlot;
import com.sddnw.server.domain.enums.ShipEffectType;
import com.sddnw.server.domain.enums.WeaponKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Числовые правила проекта корабля — п. 8. Всё, что считается по корпусу и компонентам,
 * живёт здесь: экран дизайна, стройка колонии и бой берут числа отсюда и своих формул
 * не заводят.
 * <p>
 * <b>Что взято из MOO II.</b> Шанс попасть по кораблю зависит от размера корпуса
 * (20 % по фрегату, 100 % по Leviathan) — отсюда уклонение {@link ShipHull#evasion()}.
 * Командные очки корпусов (1 у фрегата, 6 у Leviathan) — оттуда же.
 * <p>
 * <b>Что реконструировано.</b> Точных таблиц места, цены и урона игра не публиковала, а
 * StrategyWiki и зеркала вики отвечают отказом (403 и 402). Поэтому лестницы чисел
 * держатся на трёх величинах, которые в проекте уже были (Mass Driver 6, Laser 4,
 * Fusion Beam 10 урона; фрегат 30 места за 16 единиц), и на порядке технологий MOO II.
 * Все числа лежат в {@code ship-components.json} и правятся без пересборки.
 * <p>
 * <b>Правила расчёта.</b> Броня, щит, двигатель и приборы обслуживают весь корабль,
 * поэтому их место и цена растут вместе с корпусом; пушка занимает своё место на любом
 * корпусе. Из-за этого крупный корпус несёт пропорционально больше огня — как в MOO II,
 * где ради этого и строят дредноуты.
 */
@Service
public class ShipDesignRules {

    /**
     * Во сколько раз обволакивающий удар (ENV) сильнее обычного — п. 8.
     * <p>
     * <b>Реконструкция под наш щит.</b> В MOO II у корабля четыре стороны со своими
     * щитами, и такой удар приходится на все четыре разом, каждая получает полную силу.
     * Щит здесь один на корабль, поэтому то же самое выражено множителем.
     * <p>
     * Стоит оно ЗДЕСЬ, а не в правилах боя: множитель нужен обоим — и бою
     * ({@code BattleRules.envelopingFactor}), и силе проекта ({@code salvo}), — а одно
     * число в двух местах однажды разойдётся.
     */
    public static final int ENVELOPING_SIDES = 4;

    /**
     * Ядерный двигатель — Power, уровень 1 («Nuclear Fission»). Без него кораблю нечем
     * лететь, поэтому кораблестроения у империи нет вовсе — п. 8.
     */
    public static final String DRIVE_TECH = "nuclear-drive";

    /**
     * Топливные элементы — Chemistry, уровень 1 («Chemistry»). Без них корабль не отойдёт
     * от родной звезды — см. {@link com.sddnw.server.domain.enums.FuelTech}.
     */
    public static final String FUEL_TECH = "standard-fuel-cells";

    /**
     * Звёздная база — орбитальная верфь колонии. Код здания из
     * {@code resources/Buildings/buildings.json}.
     */
    public static final String STAR_BASE = "star-base";

    /**
     * Какого размера корпуса сходят со стапеля колонии без звёздной базы — п. 8.
     * <p>
     * В MOO II звёздная база и есть верфь: «a star dock capable of building ships larger
     * than destroyers». Фрегат и эсминец — два наименьших корпуса, их строит любая
     * колония; всё, что крупнее, требует базы на орбите.
     */
    public static final Integer HULL_SIZE_WITHOUT_STAR_BASE = 2;

    /**
     * Умеет ли империя строить корабли — п. 8.
     * <p>
     * Нужны базовые уровни двух разделов: Power даёт двигатель, Chemistry — топливо. Оба
     * уровня общие: изучив уровень, империя получает все его технологии разом, поэтому
     * достаточно спросить по одной из каждого. До них нет ни окна дизайна, ни корабля
     * в списке стройки колонии — строить было бы не из чего.
     */
    public Boolean shipbuildingAvailable(Set<String> technologies) {
        return technologies.contains(DRIVE_TECH) && technologies.contains(FUEL_TECH);
    }

    /**
     * Чего не хватает для кораблестроения — КОДАМИ технологий; пусто, если хватает. Название
     * подставляет служба на языке игрока: правило языка не знает.
     */
    public List<String> missingShipbuildingTechnologies(Set<String> technologies) {
        List<String> missing = new ArrayList<>(2);
        if (!technologies.contains(DRIVE_TECH)) {
            missing.add(DRIVE_TECH);
        }
        if (!technologies.contains(FUEL_TECH)) {
            missing.add(FUEL_TECH);
        }
        return missing;
    }

    /**
     * Наибольший размер корпуса, который поднимет эта колония, — п. 8: со звёздной базой
     * любой, без неё только два наименьших.
     */
    public Integer maxHullSize(Boolean hasStarBase) {
        return Boolean.TRUE.equals(hasStarBase) ? Integer.MAX_VALUE : HULL_SIZE_WITHOUT_STAR_BASE;
    }

    /**
     * Проект и его состав: компонент и сколько раз он взят. Пушек в проекте бывает
     * несколько одинаковых, поэтому состав — не множество, а пары с количеством.
     */
    public record Item(ShipComponent component, Integer count,
                       /** Модификации этого ствола — п. 8; у прочих гнёзд пусто. */
                       List<WeaponModification> modifications) {

        /** Состав без модификаций — так собираются гнёзда, где их не бывает. */
        public Item(ShipComponent component, Integer count) {
            this(component, count, List.of());
        }

        /** Место одного такого ствола на корпусе — с поправкой модификаций. */
        public Integer spaceOn(ShipHull hull) {
            return scaled(component.spaceOn(hull), WeaponModification::spacePercent);
        }

        /** Цена одного такого ствола — по тому же правилу, что и место. */
        public Integer costOn(ShipHull hull) {
            return scaled(component.costOn(hull), WeaponModification::costPercent);
        }

        /** Урон одного выстрела с поправкой модификаций. */
        public Integer damage() {
            return scaled(component.amount(ShipEffectType.WEAPON_DAMAGE),
                    WeaponModification::damagePercent);
        }

        /**
         * Выстрелов в залпе с поправкой модификаций — п. 8: разделяющаяся ракета (MIRV)
         * несёт четыре боеголовки, и каждая летит своей целью.
         */
        public Integer shots() {
            return scaled(component.amount(ShipEffectType.WEAPON_SHOTS),
                    WeaponModification::shotsPercent);
        }

        /** Насколько крепче сама ракета: настолько же труднее сбить её ближней обороной. */
        public Integer missileArmourPercent() {
            return modifications.stream()
                    .mapToInt(WeaponModification::missileArmourPercent)
                    .sum();
        }

        /** Насколько ракета быстрее обычной — перехватить её тем труднее. */
        public Integer missileSpeed() {
            return modifications.stream().mapToInt(WeaponModification::missileSpeed).sum();
        }

        /**
         * Дальность этого ствола в клетках — п. 8: тяжёлая установка бьёт вдвое дальше,
         * ближняя оборона — вдвое ближе.
         */
        public Integer rangeFrom(Integer base) {
            int total = 100 + modifications.stream()
                    .mapToInt(WeaponModification::rangePercent)
                    .sum();
            return Math.max(1, base * Math.max(0, total) / 100);
        }

        /**
         * Охватывает ли удар цель со всех сторон разом — ENV или сам ствол.
         * <p>
         * Второе — правило оригинала: плазменная пушка и Копьё новы обволакивают цель сами
         * собой, и модификации ENV им не предлагают (`ship-components.json`, признак
         * {@code envelops}).
         */
        public Boolean envelops() {
            return Boolean.TRUE.equals(component.envelops())
                    || modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.envelops()));
        }

        /** Не слабеет ли удар с расстоянием — NR. */
        public Boolean noRangePenalty() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.noRangePenalty()));
        }

        /** Срезает ли ракета уклонение цели вдвое — помехозащита ECCM. */
        public Boolean halvesEvasion() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.halvesEvasion()));
        }

        /** Бьёт ли ракета по двигателю цели, пробившись сквозь щит, — наведение EMG. */
        public Boolean hitsEngine() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.hitsEngine()));
        }

        /** Надбавка модификаций к меткости этого ствола, в процентных пунктах. */
        public Integer attackPercent() {
            return modifications.stream()
                    .mapToInt(WeaponModification::attackPercent)
                    .sum();
        }

        /** Пробивает ли выстрел броню насквозь. */
        public Boolean piercesArmour() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.piercesArmour()));
        }

        /** Держит ли щит такой выстрел. */
        public Boolean piercesShield() {
            return modifications.stream().anyMatch(m -> Boolean.TRUE.equals(m.piercesShield()));
        }

        /**
         * Проценты модификаций складываются, а не перемножаются: в MOO II их и берут по
         * одной-две, а сложение оставляет числа круглыми — тяжёлое скорострельное стоит
         * ровно вдвое с половиной.
         */
        private Integer scaled(Integer base, java.util.function.ToIntFunction<WeaponModification> percent) {
            int total = 100 + modifications.stream().mapToInt(percent).sum();
            return Math.max(1, base * Math.max(0, total) / 100);
        }
    }

    /**
     * Считает характеристики проекта.
     *
     * @param race расовые проценты атаки и защиты кораблей — п. 7; они применяются
     *             последними, поверх приборов
     */
    public ShipStats stats(ShipHull hull, List<Item> items, RaceEffects race) {
        int spacePercent = sum(items, ShipEffectType.SPACE_PERCENT);
        int space = hull.space() * (100 + spacePercent) / 100;

        int spaceUsed = 0;
        int cost = hull.cost();
        for (Item item : items) {
            // Место и цена берутся у состава, а не у компонента: модификация ствола
            // меняет и то и другое — п. 8.
            spaceUsed += item.spaceOn(hull) * item.count();
            cost += item.costOn(hull) * item.count();
        }
        // Феодализм строит корабли на треть дешевле — п. 14. Скидка идёт от готовой цены
        // корпуса с приборами, а не от корпуса: в MOO II дешевле обходится весь корабль,
        // и это касается не только боевых — колониальные и грузовые тоже.
        cost = Math.max(1, cost * (100 + race.shipCostPercent()) / 100);

        int structure = hull.structure() * (100 + sum(items, ShipEffectType.STRUCTURE_PERCENT)) / 100;

        // Броня и щиты покрывают весь корпус, поэтому очки множатся на его размер —
        // тем же множителем, что место и цена. Тяжёлая броня добавляется процентом сверху.
        int armour = sum(items, ShipEffectType.ARMOUR) * hull.systemFactor()
                * (100 + sum(items, ShipEffectType.ARMOUR_PERCENT)) / 100;
        // Щит оригинала (п. 8, 13.10.2026): класс срезает урон с каждого выстрела, а запас — класс
        // × 5 × множитель корпуса — поглощает остаток, пока не кончится.
        int shieldClass = sum(items, ShipEffectType.SHIELD);
        // Многофазные щиты (перенос MOO II): запас щита на половину больше.
        int shield = shieldClass * SHIELD_POOL_PER_CLASS * shieldFactor(hull)
                * (100 + sum(items, ShipEffectType.SHIELD_POOL_PERCENT)) / 100;

        int attack = salvo(items) * (100 + sum(items, ShipEffectType.ATTACK_PERCENT)
                + race.shipAttackPercent()) / 100;
        int defense = (hull.evasion() + sum(items, ShipEffectType.DEFENSE))
                * (100 + race.shipDefensePercent()) / 100;

        return new ShipStats(
                space,
                spaceUsed,
                cost,
                structure,
                armour,
                shield,
                sum(items, ShipEffectType.SPEED),
                sum(items, ShipEffectType.COMBAT_SPEED),
                attack,
                defense,
                sum(items, ShipEffectType.MISSILE_EVASION),
                marines(hull, items),
                hull.command(),
                // Дальность корабля — это дальность империи плюс проценты его баков (п. 8):
                // топливо в проект не ставится, а баки ставятся.
                sum(items, ShipEffectType.FUEL_RANGE_PERCENT),
                Math.min(MAX_ABSORB_PERCENT, sum(items, ShipEffectType.DAMAGE_ABSORB_PERCENT)),
                shieldClass,
                fighterSalvo(items) * (100 + sum(items, ShipEffectType.ATTACK_PERCENT)
                        + race.shipAttackPercent()) / 100,
                sum(items, ShipEffectType.TIME_WARP) > 0);
    }

    /**
     * Во сколько раз удар истребителей в силе корабля меньше полного — <i>реконструкция</i> (14.10.2026):
     * звено летит к цели кругами, его сбивают ближняя оборона и поле молний, а щит срезает урон с
     * каждого удара. Пополам — первый шаг по правилу «вдвое или пополам» (правила проекта, пункт 89).
     */
    static final int FIGHTER_POWER_DIVISOR = 4;

    /** Слабейший удар истребителя — лазер (4) +1, «normal damage +1» оригинала. */
    static final int FIGHTER_FLOOR_DAMAGE = 5;

    /**
     * Удар истребителей за круг для силы корабля — п. 8 (перенос MOO II, {@code BattleRules.fighterShots}):
     * с каждого отсека звено из четырёх машин, у перехватчика по 4 удара лучом, у тяжёлого по 2 (бомбы
     * в силу не входят, как и у стволов); урон удара — лучший луч самого корабля +1 («normal damage
     * +1»). В бою истребители бьют лучшим лучом империи, а технологий правила корабля не знают: луч на
     * борту — нижняя граница лучшего изученного, а без него берётся лазер ({@link #FIGHTER_FLOOR_DAMAGE}),
     * слабейший луч истребителя, — <i>реконструкция</i>: проекты игры ставят отсек, только когда такой
     * луч у империи есть.
     */
    private Integer fighterSalvo(List<Item> items) {
        int beam = 0;
        for (Item item : items) {
            if (item.component().slot() == ShipComponentSlot.WEAPON && item.component().weaponKind() == WeaponKind.BEAM) {
                beam = Math.max(beam, item.damage());
            }
        }
        if (beam == 0) {
            beam = FIGHTER_FLOOR_DAMAGE - 1;
        }
        int hits = 0;
        for (Item item : items) {
            int kind = item.component().amount(ShipEffectType.FIGHTER_BAY);
            int perCraft = kind == BattleRules.INTERCEPTORS ? 4 : kind == BattleRules.HEAVY_FIGHTERS ? 2 : 0;
            hits += perCraft * BattleRules.FIGHTERS_PER_BAY * item.count();
        }
        return hits * (beam + 1) / FIGHTER_POWER_DIVISOR;
    }

    /** Запас щита на единицу класса и множителя корпуса — число MOO II («class × 5 × size class»). */
    public static final int SHIELD_POOL_PER_CLASS = 5;

    /**
     * Множитель корпуса для запаса щита — п. 8. У кораблей оригинала: 1, 2, 3, 4, 5 и 7 от
     * фрегата до Левиафана («shields hull multiplier (5, 10, 15, 20, 25, 35)» — это 5 × множитель).
     * <p>
     * <b>Реконструкция для платформ и чудищ:</b> их множителей оригинал не публиковал, поэтому
     * они берутся по множителю систем корпуса той же лестницей: звёздная база — как линкор,
     * станция — как титан, крепость и планетарный щит — как Левиафан.
     */
    public static int shieldFactor(ShipHull hull) {
        if (!Boolean.TRUE.equals(hull.platform()) && !Boolean.TRUE.equals(hull.monster())
                && hull.sortOrder() != null && hull.sortOrder() >= 1 && hull.sortOrder() <= 6) {
            return SHIP_SHIELD_FACTORS[hull.sortOrder() - 1];
        }
        int systems = hull.systemFactor() == null ? 1 : hull.systemFactor();
        if (systems <= 1) {
            return 1;
        }
        if (systems <= 2) {
            return 2;
        }
        if (systems <= 4) {
            return 3;
        }
        if (systems <= 8) {
            return 4;
        }
        if (systems <= 16) {
            return 5;
        }
        return 7;
    }

    private static final int[] SHIP_SHIELD_FACTORS = {1, 2, 3, 4, 5, 7};

    /** Больше этого урона поглотители не гасят, сколько их ни ставь: корабль не бессмертен. */
    static final int MAX_ABSORB_PERCENT = 75;

    /**
     * Боевая сила одного корабля: чем он больнее бьёт, дольше живёт и труднее ловится, тем
     * больше весит в бою — п. 8.
     * <p>
     * Реконструкция: сравнивать флоты чем-то нужно, а быстрый бой ({@code
     * stub/SpaceBattleService}) поля не считает. Живучесть — прочность с бронёй и щитом;
     * деление держит числа в том же масштабе, в каком бой считал простые корабли; меньше
     * единицы сила не бывает — корабль на поле всегда что-то да значит.
     * <p>
     * <b>Защита входит тем же множителем, что и атака</b> — решение хозяина проекта, и
     * вот на чём оно стоит. Раньше её в силе не было вовсе: сила была «урон на живучесть»,
     * и сторона расы «пилоты» не меняла НИЧЕГО ни в мериле баланса, ни в исходе боя —
     * партии ИИ решаются только быстрым боём, а он сравнивает ровно эту силу. Измерено:
     * хорошие пилоты -0,24, отличные -0,17 по военному мерилу, то есть ноль. Уклонение
     * при этом в игре есть и работает — но лишь в тактическом бою, в кривой попадания,
     * которой партии ИИ не видят.
     * <p>
     * <b>Почему {@code 100 + защита}, а не голая защита.</b> Уклонение корпуса считается
     * как {@code 100 − шанс попадания}, и у Leviathan, звёздной крепости и наземных
     * батарей оно РАВНО НУЛЮ: по ним попадают всегда. Голый множитель обнулил бы их силу
     * вовсе, чего быть не должно — их защита это броня и щиты, а не верткость. Сотня и
     * есть та база «по мне всегда попадают», от которой уклонение считается прибавкой:
     * фрегат (уклонение 80) весит в 1,8 раза больше, чем весил бы неповоротливым, а
     * звёздная крепость — ровно столько же, сколько и раньше.
     */
    public Integer power(ShipStats stats) {
        int endurance = stats.structure() + stats.armour() + stats.shield();
        // Энергопоглотитель гасит долю урона по броне и корпусу (п. 8): корабль живёт дольше
        // ровно во столько раз, во сколько меньше урона до него доходит.
        int absorb = stats.damageAbsorbPercent() == null ? 0 : stats.damageAbsorbPercent();
        if (absorb > 0) {
            endurance = endurance * 100 / (100 - absorb);
        }
        long evasion = 100L + Math.max(0, stats.defense());
        // Истребители бьют вместе со стволами, а ускоритель времени даёт лишний ход в конце круга —
        // корабль стреляет дважды (п. 8, 14.10.2026). Без этого быстрый бой, которым дерётся ИИ, не
        // видел ни того, ни другого, и модули были для него потерянным местом.
        long attack = (long) stats.attack() + (stats.fighterAttack() == null ? 0 : stats.fighterAttack());
        if (Boolean.TRUE.equals(stats.timeWarp())) {
            attack *= 2;
        }
        return (int) Math.max(1, attack * endurance * evasion / 10_000L);
    }

    /**
     * Залп проекта: урон всех стволов за один заход, без приборов и расы. Урон берётся у
     * состава — модификация ствола его меняет (п. 8).
     */
    private Integer salvo(List<Item> items) {
        int salvo = 0;
        // Фокусировщик энергии прибавляет к лучам и снарядам (п. 8), но не к ракетам.
        int beamPercent = sum(items, ShipEffectType.BEAM_DAMAGE_PERCENT);
        // Обволакивающий удар входит в залп вчетверо: в бою он и приходится на цель
        // четыре раза (ENVELOPING_SIDES). Без этого сила проекта расходилась бы с боем —
        // а её читают ИИ, сравнение флотов и прибор балансировки.
        for (Item item : items) {
            // Бомбы в силу корабля не входят — пункт 25: по кораблям они не бьют.
            if (item.component().slot() == ShipComponentSlot.WEAPON
                    && item.component().weaponKind() != WeaponKind.BOMB) {
                int sides = Boolean.TRUE.equals(item.envelops()) ? ENVELOPING_SIDES : 1;
                int damage = item.component().weaponKind() == WeaponKind.BEAM
                        || item.component().weaponKind() == WeaponKind.PROJECTILE
                        ? item.damage() * (100 + beamPercent) / 100
                        : item.damage();
                salvo += damage * item.shots() * item.count() * sides;
            }
        }
        return salvo;
    }

    /**
     * Морская пехота на борту по классу корпуса (1..6) — п. 8, абордаж.
     * <p>
     * <b>Реконструкция.</b> MOO II чисел не публиковала, известно лишь, что пехоту несёт каждый
     * корабль, что её тем больше, чем крупнее корпус, и что десантные отсеки её удваивают. Ряд
     * растёт быстрее места — у фрегата команда на два бойца, у Левиафана на полсотни, — иначе
     * крупный корабль брали бы мелкие стаей.
     */
    private static final int[] MARINES_BY_HULL = {2, 5, 10, 15, 20, 50};

    /**
     * Сколько морской пехоты на корабле — п. 8, абордаж: по классу корпуса, а десантные
     * отсеки её удваивают ({@code MARINES_PERCENT}, правило оригинала). У платформы обороны
     * пехота — гарнизон, которым она отбивается от рейда: два бойца на множитель систем корпуса
     * (реконструкция — звёздной базе дюжина). У чудищ пехоты нет. Одно правило на окно
     * дизайна и бой.
     */
    public Integer marines(ShipHull hull, List<Item> items) {
        if (Boolean.TRUE.equals(hull.monster())) {
            return 0;
        }
        int base = Boolean.TRUE.equals(hull.platform())
                ? 2 * Math.max(1, hull.systemFactor())
                : MARINES_BY_HULL[Math.max(1, Math.min(6, hull.sortOrder())) - 1];
        return base * (100 + sum(items, ShipEffectType.MARINES_PERCENT)) / 100;
    }

    /**
     * Сколько урона по планете даёт проект за заход — пункт 25: бомбы, урон каждой на число
     * стволов. Ноль — бомб на корабле нет.
     */
    public Integer bombSalvo(List<Item> items) {
        int total = 0;
        for (Item item : items) {
            // Биологические бомбы урона не дают — они убивают жителей шансом (пункт 25).
            if (item.component().slot() == ShipComponentSlot.WEAPON
                    && item.component().weaponKind() == WeaponKind.BOMB
                    && item.component().amount(ShipEffectType.BIO_KILL_PERCENT) <= 0) {
                total += item.damage() * item.shots() * item.count();
            }
        }
        return total;
    }

    /**
     * Биологические бомбы проекта — пункт 25: по шансу на каждую бомбу (10 у спор смерти, 20 у
     * биотерминатора). Урона они не дают и в {@link #bombSalvo} не входят.
     */
    public List<Integer> bioBombs(List<Item> items) {
        List<Integer> bombs = new java.util.ArrayList<>();
        for (Item item : items) {
            int percent = item.component().amount(ShipEffectType.BIO_KILL_PERCENT);
            if (percent > 0) {
                for (int i = 0; i < item.count() * Math.max(1, item.shots()); i++) {
                    bombs.add(percent);
                }
            }
        }
        return bombs;
    }

    /** Сумма одного эффекта по всему проекту — с учётом количества компонентов. */
    private Integer sum(List<Item> items, ShipEffectType type) {
        int total = 0;
        for (Item item : items) {
            total += item.component().amount(type) * item.count();
        }
        return total;
    }
}
