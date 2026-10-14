package com.sddnw.server.service;

import com.sddnw.server.domain.ShipComponent;
import com.sddnw.server.domain.ShipHull;
import com.sddnw.server.domain.ShipStats;
import com.sddnw.server.domain.enums.BattleSide;
import com.sddnw.server.domain.enums.ShipComponentSlot;
import com.sddnw.server.domain.enums.ShipEffectType;
import com.sddnw.server.domain.enums.WeaponKind;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Числовые правила тактического боя — п. 8, сцена боя MOO II.
 * <p>
 * <b>Что взято из оригинала.</b> Шанс попасть по кораблю задаёт размер его корпуса:
 * 20 % по фрегату, 30 по эсминцу, 40 по крейсеру, 50 по дредноуту, 80 по титану и 100 по
 * Leviathan ({@link ShipHull#hitChancePercent()}). Прицельный компьютер поднимает шанс,
 * постановщик помех роняет. Урон снимается щитом, потом бронёй, и только остаток бьёт
 * по прочности корпуса — порядок MOO II.
 * <p>
 * <b>Что реконструировано.</b> Поле в MOO II шестиугольное, здесь клетчатое: соседняя
 * клетка по диагонали считается такой же соседней, как по прямой (расстояние Чебышёва).
 * Дальности у оружия в справочнике нет вовсе — она одна на все стволы
 * ({@link #WEAPON_RANGE}), иначе стрелять было бы можно через всё поле и движение
 * потеряло бы смысл. Инициатива корабля считается от скорости и прицельного компьютера:
 * в MOO II очередь задают двигатели и приборы, а других чисел про очередь игра не
 * публиковала.
 * <p>
 * <b>Чего в правилах пока нет, хотя в оригинале есть.</b> Урон и размеры оружия взяты у
 * MOO II (`ship-components.json`), а два его свойства — нет: торпеда в оригинале бьёт
 * ЧЕРЕЗ ХОД (зато её нельзя сбить, и это у нас есть), а ионная импульсная пушка минует
 * броню с корпусом и попадает прямо в системы. И то и другое — точки расширения: первое
 * ложится в саму стрельбу ({@code TacticalBattleService.fire}), второе — в разбор
 * попадания рядом с поломкой систем ({@link #rollSystemHit}). Пока оба оружия бьют как
 * обычные, и в их урон это не заложено: числа остаются оригинальскими.
 */
@Service
public class BattleRules {

    /**
     * Ширина поля боя в клетках — п. 8.
     * <p>
     * <b>Размер поля — реконструкция по трём числам оригинала</b>, потому что саму сетку
     * MOO II не публикует: корабли там проходят «12–14 клеток за ход» (и augmented
     * engines добавляют «+5 клеток»), луч теряет силу «до 35 % на 21–23 клетках», а экран
     * боя показывает часть поля в пропорции 8:5. Отсюда 32 на 20: ход корабля — треть
     * поля, вся таблица ослабления луча помещается, а пропорция совпадает с экранной.
     * <p>
     * Прежние 20×12 были придуманы до того, как в игре появилась боевая скорость
     * оригинала: с ней фрегат пересекал такое поле целиком за один ход.
     */
    public static final int FIELD_WIDTH = 32;

    /** Высота поля боя в клетках — п. 8, см. {@link #FIELD_WIDTH}. */
    public static final int FIELD_HEIGHT = 20;

    /**
     * Дальность залпа в клетках — одна на все стволы.
     * <p>
     * Двадцать четыре: столько считает таблица ослабления луча MOO II — от полного урона
     * вплотную до 35 % на 21–23 клетках. Дальше выстрел не летит вовсе: у оригинала этот
     * край тоже последний.
     */
    public static final int WEAPON_RANGE = 24;

    /** Меньше этого шанс попасть не падает: даже юркий фрегат ловит случайный залп. */
    private static final int MIN_HIT_PERCENT = 5;

    /**
     * Сколько очков перевеса меткости над защитой удваивают отношение шансов — MOO II,
     * знаменатель 16 в формуле попадания.
     */
    public static final int HIT_RATING_STEP = 16;

    /** Половина: столько даёт кривая попадания при равных меткости и защите. */
    private static final double EVEN_ODDS_PERCENT = 50.0;

    /**
     * На сколько процентов слабеет луч с каждыми тремя клетками пути — п. 8.
     * <p>
     * Числа оригинала: вплотную (0–2 клетки) луч бьёт в полную силу, дальше теряет по
     * десятой доле за каждые три клетки — 90 % на 3–5, 80 % на 6–8 и так далее, — и ниже
     * {@link #MIN_DAMAGE_PERCENT} не опускается.
     */
    public static final int DISSIPATION_PERCENT_PER_STEP = 10;

    /** Клеток в одной ступени ослабления. */
    public static final int DISSIPATION_STEP_CELLS = 3;

    /** Слабее этого луч не становится: 35 % оригинала на 21–23 клетках. */
    public static final int MIN_DAMAGE_PERCENT = 35;

    /** Код модификации ближней обороны: ею сбивают чужие ракеты — п. 8. */
    private static final String POINT_DEFENCE = "point-defense";

    /** Сколько процентов перехвата даёт один ствол ближней обороны — реконструкция. */
    private static final int POINT_DEFENCE_PERCENT = 20;

    /** Выше этого перехват не поднимается: оборона не оставляет совсем без ракет. */
    private static final int POINT_DEFENCE_MAX_PERCENT = 80;

    /** Сколько процентов перехвата снимает каждая единица скорости ракеты. */
    private static final int MISSILE_SPEED_PERCENT = 5;

    /**
     * Какую долю брони и корпуса кибернетическая раса чинит между кругами боя — п. 7.
     * <p>
     * Число MOO II: «корабли такой расы чинят 10% брони и структуры за ход сражения».
     * <b>Выбитые системы она не чинит</b> — это точка расширения: поломка систем в игре
     * теперь есть ({@link #SYSTEM_HIT_PERCENT}), а починки их посреди боя пока нет.
     */
    public static final int CYBERNETIC_REPAIR_PERCENT = 10;

    /**
     * Какая доля попаданий ПО КОРПУСУ выбивает бортовую систему — п. 8.
     * <p>
     * Правило MOO II: щит и броня держат удар, а всё, что прошло сквозь них, рвёт корабль
     * изнутри — гасит пушку, разбивает двигатель, сжигает прицел. Самого числа игра не
     * публиковала, и оно <b>реконструировано по длине боя</b>: корабль доживает до гибели
     * за пять-десять попаданий по корпусу, и при трети таких попаданий он теряет одну-две
     * системы — то есть доживает искалеченным, как в оригинале, а не целым до последнего
     * очка прочности.
     */
    public static final int SYSTEM_HIT_PERCENT = 30;

    /**
     * Вероятность того, что разбитый двигатель взорвёт корабль, — п. 8.
     * <p>
     * Число — решение хозяина проекта: у реактора, которому разбили привод, четверть
     * шансов уйти вместе с кораблём. Взрыв бьёт соседей ({@link #blastDamage}), поэтому
     * стоять кучей возле подбитого опасно и своим.
     */
    public static final int ENGINE_BLAST_PERCENT = 25;

    /** Сила взрыва: доля ПОЛНОЙ прочности корпуса — большой корабль рвёт сильнее. */
    private static final int BLAST_DAMAGE_PERCENT = 50;

    /** Взрыв достаёт соседей на клетку, а у крупных корпусов — на две. */
    private static final int BLAST_RADIUS = 1;
    private static final int BIG_BLAST_RADIUS = 2;

    /** С какой прочности корпус считается крупным: дредноут и выше. */
    private static final int BIG_HULL_STRUCTURE = 160;

    /**
     * Доля погибших кораблей, взрывающихся в БЫСТРОМ бою, — п. 8.
     * <p>
     * Перевод того же правила на бой без поля: там нет ни систем, ни клеток, зато есть
     * потери. Число выведено, а не назначено: попадание по корпусу выбивает систему в
     * {@link #SYSTEM_HIT_PERCENT} случаев, двигатель — примерно одна система из пяти, а
     * взрывается он в {@link #ENGINE_BLAST_PERCENT} случаев; за пять-десять попаданий,
     * которые корабль переживает до гибели, это и даёт около десятой доли.
     */
    public static final int FAST_BLAST_PERCENT = 10;

    /**
     * Множители зерна боя — {@link #battleSeed}.
     * <p>
     * Взаимно простые числа, и это единственное требование к ним: они разносят бои одной
     * партии по разным зёрнам, а не значат в игре чего-то своего.
     */
    private static final long SEED_GAME = 1009L;

    /** Множитель хода в зерне боя — {@link #battleSeed}. */
    private static final long SEED_TURN = 101L;

    /** Множитель места напавшего в зерне боя — {@link #battleSeed}. */
    private static final long SEED_SLOT = 11L;

    /** Бортовая система: двигатель. Выбит — корабль неподвижен и с поля не уйдёт. */
    public static final String ENGINE_SYSTEM = "ENGINE";

    /** Бортовая система: щит. Выбит — держать удар больше нечем. */
    public static final String SHIELD_SYSTEM = "SHIELD";

    /** Бортовая система: прицельный компьютер. Выбит — прицел падает до голого. */
    public static final String COMPUTER_SYSTEM = "COMPUTER";

    /**
     * Сколько чинится за круг: доля от целого, но не меньше единицы, пока чинить есть что.
     * <p>
     * Не меньше единицы потому, что у фрегата десятая доля брони — это ноль, и правило
     * оригинала на мелких кораблях не работало бы вовсе.
     */
    public Integer repaired(Integer now, Integer full) {
        return repaired(now, full, CYBERNETIC_REPAIR_PERCENT);
    }

    /**
     * То же, но с названной долей — ремонтный модуль (п. 8, 13.10.2026): «repairs up to 20% of
     * a ship's armor or structure per combat turn». Чинит вдвое быстрее кибернетики, и оба
     * правила складываются ({@code TacticalBattleService.repair}).
     */
    public Integer repaired(Integer now, Integer full, Integer percent) {
        if (now >= full) {
            return full;
        }
        if (percent <= 0) {
            return now;
        }
        return Math.min(full, now + Math.max(1, full * percent / 100));
    }

    /**
     * Сколько действия этого вида дают уцелевшие системы корабля — п. 8: сумма по составу.
     * Модули, выбитые в бою, в состав уже не входят ({@link #partsLeft}), поэтому разбитый
     * фокусировщик или поглотитель перестаёт действовать сам собой.
     */
    public Integer special(List<ShipDesignRules.Item> items, ShipEffectType type) {
        int total = 0;
        for (ShipDesignRules.Item item : items) {
            total += item.component().amount(type) * item.count();
        }
        return total;
    }

    /**
     * «Лучевое» оружие в смысле оригинала — п. 8: лучи и снаряды (масс-драйвер и пушка Гаусса
     * у MOO II числятся лучами, «machine-gun» weapons). Ракеты и торпеды — нет. На такое
     * оружие действуют фокусировщик энергии, прицелы дальнего боя и «Ахилл».
     */
    public Boolean beamLike(Shot shot) {
        return shot.kind() == WeaponKind.BEAM || shot.kind() == WeaponKind.PROJECTILE;
    }

    /**
     * Урон луча с фокусировщиком энергии — п. 8: «+50%» к урону лучевого оружия; к ракетам
     * не прибавляет.
     */
    public Integer focused(Shot shot, Integer damage, Integer beamPercent) {
        return beamPercent > 0 && Boolean.TRUE.equals(beamLike(shot))
                ? damage * (100 + beamPercent) / 100
                : damage;
    }

    /**
     * Инициатива корабля — п. 8: по ней строится очередь хода.
     * <p>
     * Реконструкция: в MOO II очередь задают двигатели и приборы корабля. Боевая скорость
     * взята с весом двадцать пять (то есть сто на клетку хода), прицельный компьютер
     * добавляется процентами — так быстрый разведчик всегда ходит раньше тяжёлого корабля,
     * а из двух одинаково быстрых первым стреляет тот, у кого лучше прицел.
     * <p>
     * <b>Боевая, а не скорость полёта</b> (13.10.2026, баг хозяина проекта: «фрегат всегда
     * ходит первым и уходит от чудищ»). Прежде здесь стояла скорость полёта по карте, а у
     * чудища её нет вовсе — плавники дают только боевую (14, быстрее ядерного двигателя). Его
     * инициатива была нулём, и любой корабль ходил раньше: фрегат успевал уйти с поля, пока
     * амёба не шевельнулась. В оригинале чудища часто убивают фрегат первым ходом.
     */
    public Integer initiative(ShipStats stats, List<ShipDesignRules.Item> items) {
        return stats.combatSpeed() * INITIATIVE_PER_COMBAT_SPEED + attackPercent(items);
    }

    /** Вес единицы боевой скорости в инициативе: сто на клетку хода (клетка — четыре единицы). */
    static final int INITIATIVE_PER_COMBAT_SPEED = 25;

    /** Прибавка приборов к прицелу в процентах: компьютер, сканер, анализатор. */
    public Integer attackPercent(List<ShipDesignRules.Item> items) {
        int percent = 0;
        for (ShipDesignRules.Item item : items) {
            percent += item.component().amount(ShipEffectType.ATTACK_PERCENT) * item.count();
        }
        return percent;
    }

    /**
     * Шанс попасть по цели в процентах — п. 8, формула попадания MOO II.
     * <p>
     * Оригинал считает попадание не сложением процентов, а кривой:
     * <pre>100 / (1 + 2^(−(BA − BD) / 16))</pre>
     * где BA — меткость стреляющего (прицельный компьютер, модификации ствола, раса), а
     * BD — защита цели (помехи, стабилизаторы). Шестнадцать очков перевеса удваивают
     * отношение шансов, поэтому прибавка в четверть сотни очков не значит «плюс 25 % к
     * попаданию»: у меткого корабля она добавляет немного, у неметкого — почти вдвое.
     * Сложением процентов, как было раньше, модификации ствола выходили втрое сильнее,
     * чем в оригинале.
     * <p>
     * <b>Что реконструировано.</b> Сама кривая даёт ровно половину при равных BA и BD, а
     * размера цели не знает — в MOO II размер сидит в её защите. У нас размер корпуса
     * хранится готовым шансом попадания (20 % по фрегату, 100 % по Leviathan, справочник
     * корпусов), поэтому кривая берётся множителем к нему: при равных меткости и защите
     * выходит ровно табличный шанс корпуса, а перевес двигает его по той же кривой
     * оригинала. Ниже {@link #MIN_HIT_PERCENT} шанс не опускается и выше ста не
     * поднимается.
     *
     * @param targetHull    корпус цели: его табличный шанс и есть точка равновесия
     * @param attackRating  меткость стреляющего (BA)
     * @param targetDefense защита цели <b>сверх</b> собственной юркости корпуса (BD):
     *                      помехи, стабилизаторы и раса. Уклонение самого корпуса сюда не
     *                      входит — оно уже сидит в табличном шансе MOO II, и вычитать его
     *                      второй раз значило бы сделать неуязвимым любой фрегат
     */
    public Integer hitChancePercent(ShipHull targetHull, Integer attackRating, Integer targetDefense) {
        double curve = 100.0 / (1.0 + Math.pow(2.0,
                -(attackRating - targetDefense) / (double) HIT_RATING_STEP));
        int chance = (int) Math.round(targetHull.hitChancePercent() * curve / EVEN_ODDS_PERCENT);
        return Math.max(MIN_HIT_PERCENT, Math.min(100, chance));
    }

    /**
     * Надбавка к защите сверх собственной юркости корпуса: помехи, стабилизаторы и раса.
     * <p>
     * Защита проекта ({@link ShipStats#defense()}) считается от уклонения корпуса, а оно
     * в бою уже учтено шансом попасть по корпусу такого размера. Поэтому в бой идёт
     * только то, что защита даёт сверх голого корпуса.
     */
    public Integer defenceBonus(ShipHull hull, ShipStats stats) {
        return Math.max(0, stats.defense() - hull.evasion());
    }

    /**
     * Один выстрел залпа — п. 8.
     * <p>
     * Выстрел несёт с собой всё, что решает его судьбу: урон, надбавку модификаций к
     * меткости <b>этого ствола</b> и то, проходит ли он броню и щит. Раньше выстрел был
     * просто числом урона, и модификации оружия рассказать о себе было нечем.
     *
     * @param kind          вид оружия: по нему сцена рисует выстрел, а ракеты ещё и
     *                      спотыкаются о постановщик помех
     * @param attackPercent надбавка модификаций к меткости, в процентных пунктах
     */
    public record Shot(WeaponKind kind,
                       Integer damage,
                       Integer attackPercent,
                       Boolean piercesArmour,
                       Boolean piercesShield,
                       /** Помехозащита ракеты (ECCM): уклонение цели считается вполовину. */
                       Boolean halvesEvasion,
                       /** Насколько крепче ракета (ARM): настолько же труднее её сбить. */
                       Integer missileArmourPercent,
                       /** Насколько ракета быстрее (FST): перехватить её труднее. */
                       Integer missileSpeed,
                       /** Наведение по излучению (EMG): попадание выбивает двигатель цели. */
                       Boolean hitsEngine,
                       /** Дальность этого ствола в клетках: HV бьёт дальше, PD ближе. */
                       Integer range,
                       /** Обволакивающий удар (ENV): цель получает его всеми сторонами разом. */
                       Boolean envelops,
                       /** Расстояние не ослабляет удар (NR). */
                       Boolean noRangePenalty,
                       /**
                        * Клеток за круг у ракеты в полёте ({@link #missileFlightSpeed}); у
                        * луча и снаряда — ноль: они долетают в тот же миг.
                        */
                       Integer flightSpeed,
                       /**
                        * Сколько очков урона сквозь щит убивают одного пехотинца на борту цели —
                        * нейтронный бластер (п. 8, абордаж); ноль — пехоту ствол не трогает.
                        */
                       Integer marineKill) {

        /** Выстрел без убийства пехоты — прежняя запись, так её читают и старые залпы. */
        public Shot(WeaponKind kind, Integer damage, Integer attackPercent,
                    Boolean piercesArmour, Boolean piercesShield, Boolean halvesEvasion,
                    Integer missileArmourPercent, Integer missileSpeed, Boolean hitsEngine,
                    Integer range, Boolean envelops, Boolean noRangePenalty, Integer flightSpeed) {
            this(kind, damage, attackPercent, piercesArmour, piercesShield, halvesEvasion,
                    missileArmourPercent, missileSpeed, hitsEngine, range, envelops, noRangePenalty,
                    flightSpeed, 0);
        }

        /** Выстрел без переделок — так собираются простые стволы в проверках. */
        public Shot(WeaponKind kind, Integer damage, Integer attackPercent,
                    Boolean piercesArmour, Boolean piercesShield) {
            this(kind, damage, attackPercent, piercesArmour, piercesShield,
                    Boolean.FALSE, 0, 0, Boolean.FALSE, WEAPON_RANGE,
                    Boolean.FALSE, Boolean.FALSE,
                    kind == WeaponKind.MISSILE ? MISSILE_BASE_SPEED : 0);
        }
    }

    /**
     * Выстрелы одного залпа: у каждого ствола свой урон, и стволов бывает много.
     * <p>
     * Залп разбивается на отдельные выстрелы потому, что попадание разыгрывается для
     * каждого, а щит цели гасит каждый выстрел по отдельности — как в MOO II, где щит
     * держит удар, а не суммарный урон.
     */
    public List<Shot> shots(List<ShipDesignRules.Item> items) {
        return shots(items, engineCombatSpeed(items));
    }

    /**
     * Боевая скорость двигателя из состава — по ней считается скорость ракет (пункт 30).
     * Берётся сам двигатель, без расовых прибавок: ракета летит своим ходом.
     */
    public Integer engineCombatSpeed(List<ShipDesignRules.Item> items) {
        int combatSpeed = 0;
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() == ShipComponentSlot.ENGINE) {
                combatSpeed = Math.max(combatSpeed, item.component().amount(ShipEffectType.COMBAT_SPEED));
            }
        }
        return combatSpeed;
    }

    /**
     * Те же выстрелы, но скорость ракет считается от названной боевой скорости корабля, а не
     * от двигателя в самом списке: в списке бывает один ствол (строка оружия полосы боя) или
     * уцелевшее после поломки — а ракета, уже снаряжённая, от выбитого двигателя корабля не
     * замедляется.
     */
    public List<Shot> shots(List<ShipDesignRules.Item> items, Integer combatSpeed) {
        List<Shot> shots = new ArrayList<>();
        for (ShipDesignRules.Item item : items) {
            ShipComponent component = item.component();
            if (component.slot() != ShipComponentSlot.WEAPON || component.weaponKind() == WeaponKind.BOMB) {
                // Бомба по кораблям не бьёт — пункт 25: её цель только планета.
                continue;
            }
            // Число выстрелов берётся у состава: разделяющаяся ракета (MIRV) несёт четыре
            // боеголовки — п. 8.
            int count = item.shots() * item.count();
            // Вида может не быть у пушки из старого справочника — считаем её снарядом:
            // не рисовать выстрел вовсе хуже, чем нарисовать привычным.
            WeaponKind kind = component.weaponKind() == null
                    ? WeaponKind.PROJECTILE : component.weaponKind();
            for (int shot = 0; shot < count; shot++) {
                shots.add(new Shot(kind, item.damage(), item.attackPercent(),
                        item.piercesArmour(), item.piercesShield(),
                        item.halvesEvasion(), item.missileArmourPercent(),
                        item.missileSpeed(), item.hitsEngine(),
                        item.rangeFrom(WEAPON_RANGE), item.envelops(),
                        item.noRangePenalty(),
                        kind == WeaponKind.MISSILE
                                ? missileFlightSpeed(combatSpeed, item.missileSpeed())
                                : 0,
                        component.amount(ShipEffectType.KILLS_MARINES)));
            }
        }
        return shots;
    }

    /**
     * Те же выстрелы, но разложенные по виду оружия — п. 8.
     * <p>
     * Числа боя от вида не зависят: урон, попадание и щит считаются одинаково. Разложение
     * нужно сцене — по нему луч, снаряды и ракеты рисуются по-разному, и видно, кто в кого
     * стреляет и чем. Порядок видов сохраняется: сперва то, что стоит раньше в проекте.
     */
    public Map<WeaponKind, List<Shot>> shotsByKind(List<ShipDesignRules.Item> items) {
        return shotsByKind(items, engineCombatSpeed(items));
    }

    /** То же, но со скоростью ракет от названной боевой скорости — см. {@link #shots(List, Integer)}. */
    public Map<WeaponKind, List<Shot>> shotsByKind(List<ShipDesignRules.Item> items, Integer combatSpeed) {
        Map<WeaponKind, List<Shot>> shots = new LinkedHashMap<>();
        for (Shot shot : shots(items, combatSpeed)) {
            shots.computeIfAbsent(shot.kind(), key -> new ArrayList<>()).add(shot);
        }
        return shots;
    }

    /**
     * Шанс попасть этим выстрелом — п. 8.
     * <p>
     * Меткость модификации самого ствола (ближняя оборона и скорострельное бьют метче,
     * автоматический огонь — хуже) идёт в BA, а уклонение цели от ракет — в BD: считает
     * всё та же кривая попадания MOO II, {@link #hitChancePercent}. Уклонение касается
     * только ракет — постановщик помех сбивает наведение, лучу и снаряду сбивать нечего.
     */
    public Integer shotHitChancePercent(ShipHull targetHull, Integer attackRating,
                                        Integer targetDefense, Shot shot,
                                        Integer missileEvasion) {
        int defence = targetDefense;
        if (shot.kind() == WeaponKind.MISSILE) {
            // Помехозащита (ECCM) режет чужое уклонение вдвое — п. 8.
            int evasion = Math.max(0, missileEvasion);
            defence += Boolean.TRUE.equals(shot.halvesEvasion()) ? evasion / 2 : evasion;
        }
        return hitChancePercent(targetHull, attackRating + shot.attackPercent(), defence);
    }

    /**
     * Сколько у корабля стволов ближней обороны — п. 8: ими и сбивают чужие ракеты.
     * <p>
     * Считаются стволы с модификацией «ближняя оборона»: в MOO II она для того и нужна —
     * «такими стволами сбивают ракеты и истребители».
     */
    public Integer pointDefenceGuns(List<ShipDesignRules.Item> items) {
        int guns = 0;
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() == ShipComponentSlot.WEAPON
                    && item.modifications().stream()
                            .anyMatch(modification -> POINT_DEFENCE.equals(modification.code()))) {
                guns += item.count();
            }
        }
        return guns;
    }

    /**
     * Докуда достают стволы ближней обороны корабля — по самому дальнобойному из них
     * (модификация ближней обороны вдвое укорачивает ствол, п. 8). Нет таких стволов — ноль.
     */
    public Integer pointDefenceRange(List<ShipDesignRules.Item> items) {
        int range = 0;
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() == ShipComponentSlot.WEAPON
                    && item.modifications().stream()
                            .anyMatch(modification -> POINT_DEFENCE.equals(modification.code()))) {
                range = Math.max(range, item.rangeFrom(WEAPON_RANGE));
            }
        }
        return range;
    }

    /**
     * Ближайшая к кораблю клетка на отрезке пути ракеты и расстояние до неё — пункт 30.
     * <p>
     * Путь проверяется по клеткам, которые ракета проходит по прямой, — тем же шагом, каким
     * она и летит ({@link #flightStep}): защитник сбивает ракету там, где она пролетает мимо
     * него, а не только в начале и конце отрезка.
     *
     * @return {x, y, расстояние}
     */
    public int[] closestOnPath(Integer x, Integer y, Integer fromX, Integer fromY,
                               Integer toX, Integer toY) {
        // На клетчатом поле до многих клеток пути расстояние одно (диагональ — шаг), и тогда
        // берётся ближайшая по прямой: ракету сбивают напротив защитника, а не на краю
        // полосы равных расстояний.
        int length = distance(fromX, fromY, toX, toY);
        int[] best = {fromX, fromY, distance(x, y, fromX, fromY)};
        int bestStraight = (x - fromX) * (x - fromX) + (y - fromY) * (y - fromY);
        for (int step = 1; step <= length; step++) {
            int cx = fromX + Math.round((float) (toX - fromX) * step / length);
            int cy = fromY + Math.round((float) (toY - fromY) * step / length);
            int d = distance(x, y, cx, cy);
            int straight = (x - cx) * (x - cx) + (y - cy) * (y - cy);
            if (d < best[2] || (d == best[2] && straight < bestStraight)) {
                best = new int[]{cx, cy, d};
                bestStraight = straight;
            }
        }
        return best;
    }

    /** Во сколько раз скорость ракеты переходит в её защиту от выстрела — число MOO II. */
    private static final int MISSILE_DEFENCE_PER_SPEED = 5;

    /**
     * Шанс сбить ракету прицельным выстрелом, в процентах, — backlog-promo, пункт 30.
     * <p>
     * Правило MOO II: защита ракеты от огня — её боевая скорость, умноженная на пять, и
     * считается она той же кривой попадания, что и выстрел по кораблю ({@link #hitChancePercent}
     * без размера цели): быструю ракету сбить труднее. Крепкая ракета (ARM) делит шанс на
     * столько же, на сколько крепче, — как и у перехвата ближней обороной. Пределы те же,
     * что у попадания: совсем без надежды и наверняка не бывает.
     */
    public Integer missileShotChancePercent(Integer attackRating, Integer missileSpeed,
                                            Integer missileArmourPercent) {
        int defence = Math.max(0, missileSpeed) * MISSILE_DEFENCE_PER_SPEED;
        double chance = 100.0 / (1 + Math.pow(2, -(attackRating - defence) / (double) HIT_RATING_STEP));
        chance = chance * 100 / (100 + Math.max(0, missileArmourPercent == null ? 0 : missileArmourPercent));
        return Math.max(MIN_HIT_PERCENT, Math.min(95, (int) Math.round(chance)));
    }

    /**
     * Строки оружия с ближней обороной — номера в том же счёте, что у {@link #partsLeft}:
     * место ствола среди оружия проекта. Ими ИИ бьёт по ракетам в полёте (backlog-promo,
     * пункт 30); ракетная строка сюда не входит — ракетой ракету не сбить.
     */
    public Set<Integer> pointDefenceRows(List<ShipDesignRules.Item> items) {
        Set<Integer> rows = new java.util.TreeSet<>();
        int row = 0;
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() != ShipComponentSlot.WEAPON) {
                continue;
            }
            if (item.component().weaponKind() != WeaponKind.MISSILE
                    && item.modifications().stream()
                            .anyMatch(modification -> POINT_DEFENCE.equals(modification.code()))) {
                rows.add(row);
            }
            row++;
        }
        return rows;
    }

    /** С какого расстояния берут на абордаж: только вплотную, с соседней клетки. */
    public static final int BOARDING_RANGE = 1;

    /** Сколько морской пехоты на корабле — правило одно с окном дизайна ({@link ShipDesignRules#marines}). */
    public Integer marines(ShipHull hull, List<ShipDesignRules.Item> items) {
        return DESIGN.marines(hull, items);
    }

    private static final ShipDesignRules DESIGN = new ShipDesignRules();

    /** Класс корпуса 1..6: фрегат … Левиафан — столько тяговых лучей его и держит. */
    public int sizeClass(ShipHull hull) {
        return Math.max(1, Math.min(6, hull.sortOrder()));
    }

    /** Дальность телепорта корабля в клетках; ноль — телепорта нет (п. 8, абордаж). */
    public Integer transporterRange(List<ShipDesignRules.Item> items) {
        int best = 0;
        for (ShipDesignRules.Item item : items) {
            best = Math.max(best, item.component().amount(ShipEffectType.TRANSPORTER));
        }
        return best;
    }

    /** С какого расстояния корабль идёт на абордаж: вплотную или телепортом. */
    public Integer boardingRange(List<ShipDesignRules.Item> items) {
        return Math.max(BOARDING_RANGE, transporterRange(items));
    }

    /**
     * Докуда достаёт тяговый луч, в клетках — п. 8. <b>Реконструкция:</b> оригинал числа не
     * называет; взято расстояние, на котором луч ещё бьёт почти в полную силу.
     */
    public static final int TRACTOR_RANGE = 8;

    /** Сколько тяговых лучей стоит на корабле. */
    public Integer tractorBeams(List<ShipDesignRules.Item> items) {
        int total = 0;
        for (ShipDesignRules.Item item : items) {
            total += item.component().amount(ShipEffectType.TRACTOR) * item.count();
        }
        return total;
    }

    /**
     * Сколько клеток корабль проходит под тяговыми лучами — п. 8: луч на класс корпуса
     * обездвиживает, меньше лучей — замедляют долей («greatly reduces the target's combat
     * speed»).
     */
    public Integer tractoredSpeed(Integer speed, Integer beams, ShipHull hull) {
        int size = sizeClass(hull);
        if (beams <= 0) {
            return speed;
        }
        return Math.max(0, speed * (size - Math.min(size, beams)) / size);
    }

    /** Пехоты в одном штурмовом шаттле — реконструкция. */
    public static final int SHUTTLE_MARINES = 2;

    /** Сколько шаттлов уходит с корабля за залп. */
    public Integer shuttles(List<ShipDesignRules.Item> items) {
        int total = 0;
        for (ShipDesignRules.Item item : items) {
            total += item.component().amount(ShipEffectType.SHUTTLE_BAY) * item.count();
        }
        return total;
    }

    /**
     * Скорость штурмового шаттла, клеток за круг, — число оригинала: «2 + (2 × the number of
     * parsecs their empire's drive can travel per turn)».
     */
    public Integer shuttleSpeed(List<ShipDesignRules.Item> items) {
        int parsecs = 0;
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() == ShipComponentSlot.ENGINE) {
                parsecs = Math.max(parsecs, item.component().amount(ShipEffectType.SPEED));
            }
        }
        return 2 + 2 * parsecs;
    }

    /**
     * Шаттлы залпа — как ракеты в полёте: их сбивает ближняя оборона, а броня у шаттла
     * крепче ракетной (у оригинала прочность 3 + броня), отсюда вдвое труднее сбить.
     */
    public List<Shot> shuttleShots(int count, int speed) {
        List<Shot> shots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            shots.add(new Shot(WeaponKind.MISSILE, 0, 0, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
                    100, 0, Boolean.FALSE, speed * MISSILE_FUEL_TURNS, Boolean.FALSE, Boolean.FALSE,
                    speed, 0));
        }
        return shots;
    }

    /** Перехватчики — род звена отсека истребителей (п. 8). */
    public static final int INTERCEPTORS = 1;

    /** Бомбардировщики — род звена (п. 8). */
    public static final int BOMBERS = 2;

    /** Тяжёлые истребители — род звена (п. 8). */
    public static final int HEAVY_FIGHTERS = 3;

    /** Наибольший урон пульсара на класс корпуса цели — число MOO II («1-24 points per size class»). */
    public static final int PULSAR_DAMAGE_PER_CLASS = 24;

    /** Наибольший урон гиродестабилизатора на класс корпуса цели — число MOO II («1-4»). */
    public static final int GYRO_DAMAGE_PER_CLASS = 4;

    /** Плазменная сеть: урон за круг от 5 до 25, тает на 5 за круг — числа MOO II. */
    public static final int PLASMA_WEB_MIN = 5;
    public static final int PLASMA_WEB_MAX = 25;
    public static final int PLASMA_WEB_DECAY = 5;

    /** Урон «по размеру»: от 1 до {@code perClass} на каждый класс корпуса цели. */
    public int sizedDamage(java.util.random.RandomGenerator random, int perClass, int sizeClass) {
        return (1 + random.nextInt(perClass)) * Math.max(1, sizeClass);
    }

    /** Машин в звене — число MOO II: «launches four Interceptors». */
    public static final int FIGHTERS_PER_BAY = 4;

    /**
     * Сколько кругов звено держится в полёте — реконструкция (п. 8): истребитель MOO II не ракета,
     * он идёт за целью, пока не ударит, и через всё поле (32 клетки) за два ракетных круга со
     * скоростью 10 не долетал вовсе. Четыре круга — поле насквозь даже у перехватчика без
     * двигателя лучше ядерного.
     */
    public static final int FIGHTER_FUEL_TURNS = 4;

    /**
     * Через сколько кругов звено снова на борту — <i>реконструкция</i> (14.10.2026): MOO II говорит лишь, что
     * звено «can make 4 shots before needing to return and recharge», а сроков не публикует (неизвестно даже,
     * пополняет ли отсек звено вовсе). Прежде звено взлетало каждый круг, и фрегат с отсеком в 5 клеток бил
     * три таких же без него; с возвратом через 2 круга бой решало первое же звено. Четыре круга — шаг
     * «вдвое» (правила проекта, пункт 89): за обычный бой отсек выпускает одно-два звена.
     */
    public static final int FIGHTER_REARM_ROUNDS = 4;

    /** Прибавка истребителя к меткости — число MOO II: «Beam Attack is a base +50». */
    public static final int FIGHTER_ATTACK = 50;

    /**
     * Скорость звена, клеток за круг, — числа оригинала: перехватчики «8 + (2 x the number of
     * parsecs their empire's drive can travel per turn)», бомбардировщики и тяжёлые «4 + (4 x …)».
     */
    public Integer fighterSpeed(List<ShipDesignRules.Item> items, int kind) {
        int parsecs = 0;
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() == ShipComponentSlot.ENGINE) {
                parsecs = Math.max(parsecs, item.component().amount(ShipEffectType.SPEED));
            }
        }
        return kind == INTERCEPTORS ? 8 + 2 * parsecs : 4 + 4 * parsecs;
    }

    /**
     * Удары одного звена — таблица оригинала: перехватчик 4 атаки по лучу, бомбардировщик 1 атака
     * бомбой, тяжёлый истребитель 2 атаки лучом и бомбой; машин в звене четыре. Луч у истребителя
     * — лучший с модификацией ближней обороны, «normal damage +1 (Mass Drivers being the
     * exception)»; бомба — лучшая из изученных. Летят звеном как ракеты: их сбивает ближняя
     * оборона, и тем труднее, чем прочнее машина ({@link #fighterSturdyPercent}).
     *
     * @param beam урон луча истребителя; ноль — подходящего луча нет
     * @param bomb урон бомбы; ноль — бомб нет
     * @param armour множитель лучшей брони империи (титан 1, тритан 2 … — {@code ARMOUR} брони / 4)
     */
    public List<Shot> fighterShots(int kind, int beam, int bomb, int speed, int armour) {
        int beams = kind == INTERCEPTORS ? 4 : kind == HEAVY_FIGHTERS ? 2 : 0;
        int bombs = kind == BOMBERS ? 1 : kind == HEAVY_FIGHTERS ? 2 : 0;
        int sturdy = fighterSturdyPercent(kind, armour);
        List<Shot> shots = new ArrayList<>();
        for (int craft = 0; craft < FIGHTERS_PER_BAY; craft++) {
            for (int i = 0; beam > 0 && i < beams; i++) {
                shots.add(new Shot(WeaponKind.MISSILE, beam, 0, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
                        sturdy, 0, Boolean.FALSE, speed * MISSILE_FUEL_TURNS, Boolean.FALSE, Boolean.TRUE,
                        speed, 0));
            }
            for (int i = 0; bomb > 0 && i < bombs; i++) {
                shots.add(new Shot(WeaponKind.MISSILE, bomb, 0, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE,
                        sturdy, 0, Boolean.FALSE, speed * MISSILE_FUEL_TURNS, Boolean.FALSE, Boolean.TRUE,
                        speed, 0));
            }
        }
        return shots;
    }

    /** Удары звена при титановой броне — прежняя запись, так её читают проверки. */
    public List<Shot> fighterShots(int kind, int beam, int bomb, int speed) {
        return fighterShots(kind, beam, bomb, speed, 1);
    }

    /**
     * Прочность машины звена — MOO II 1.50: у перехватчика «3 + (armor multiplier) in structure» (4–13).
     * Тяжёлым и бомбардировщикам классика даёт 5 очков против 2 у перехватчика — <i>реконструкция</i>: у них
     * 6 + множитель (14.10.2026).
     */
    public int fighterHitPoints(int kind, int armour) {
        return (kind == INTERCEPTORS ? 3 : 6) + Math.max(1, armour);
    }

    /** Прочность, при которой машину сбивает одно попадание ближней обороны: перехватчик с титановой бронёй. */
    static final int FIGHTER_REFERENCE_HP = 4;

    /**
     * Насколько труднее сбить машину звена, в процентах, — тем же счётом, что крепость ракеты (ARM): каждое
     * очко прочности сверх {@link #FIGHTER_REFERENCE_HP} — плюс четверть. <i>Реконструкция</i>: ближняя
     * оборона у нас бьёт ракету числом попаданий, а не уроном, и прочность переводится в шанс.
     */
    public int fighterSturdyPercent(int kind, int armour) {
        return Math.max(0, fighterHitPoints(kind, armour) - FIGHTER_REFERENCE_HP) * 100 / FIGHTER_REFERENCE_HP;
    }

    /** На сколько процентов слабеют противоракеты за каждую клетку до залпа — число MOO II. */
    public static final int ANTI_MISSILE_STEP_PERCENT = 3;

    /**
     * Шанс противоракет в упор, в процентах, — модуль {@code anti-missile-rockets}
     * (backlog-promo, пункт 30); ноль — противоракет на корабле нет. Берётся сильнейший
     * модуль, а не сумма: особый модуль ставится один ({@code ship.specialOnce}).
     */
    public Integer antiMissilePercent(List<ShipDesignRules.Item> items) {
        int best = 0;
        for (ShipDesignRules.Item item : items) {
            best = Math.max(best, item.component().amount(ShipEffectType.ANTI_MISSILE));
        }
        return best;
    }

    /**
     * Шанс противоракеты сбить одну ракету залпа на этом расстоянии — правило MOO II
     * дословно: «60% chance to destroy enemy missiles, decreasing by 3% with each square from
     * launched position». Меткость корабля и скорость ракеты сюда не входят: у оригинала их
     * нет, противоракета — своя ракета со своим наведением. Ноль — не достают: при 60 % это
     * дальше двадцати клеток.
     */
    public Integer antiMissileChancePercent(Integer basePercent, Integer distance) {
        return Math.max(0, Math.min(95, basePercent - ANTI_MISSILE_STEP_PERCENT * distance));
    }

    /**
     * Сколько ракет залпа корабль ждёт сбить выстрелом по нему — в сотых долях ракеты.
     * По этому числу ИИ решает, стоит ли тратить ход на залп в полёте, а не на корабль
     * (backlog-promo, пункт 30). Сначала по ракетам бьют противоракеты, остаток — стволы.
     */
    public Integer expectedDownsCentis(Integer antiMissileChance, List<Integer> gunChances, Integer rockets) {
        double left = rockets;
        double downed = 0;
        if (antiMissileChance > 0) {
            double hit = left * antiMissileChance / 100.0;
            downed += hit;
            left -= hit;
        }
        for (Integer chance : gunChances) {
            if (left <= 0) {
                break;
            }
            double hit = Math.min(1.0, left) * chance / 100.0;
            downed += hit;
            left -= hit;
        }
        return (int) Math.round(downed * 100);
    }

    /**
     * Шанс сбить чужую ракету ближней обороной, в процентах, — п. 8.
     * <p>
     * <b>Реконструкция.</b> В MOO II ракета летит по полю, и стволы ближней обороны бьют
     * по ней в пути: крепкая ракета (ARM) держит вдвое больше попаданий, быстрая (FST)
     * реже попадает под огонь. Ракета у нас летит (backlog-promo, пункт 30), и бросок
     * разыгрывается на каждом отрезке её пути каждым чужим кораблём, мимо которого она
     * проходит в пределах его ближней обороны, а сама цель бросает на подлёте. Каждый ствол ближней обороны даёт
     * {@link #POINT_DEFENCE_PERCENT} процентов, броня ракеты делит их на столько же,
     * на сколько крепче сама ракета, а скорость отнимает по
     * {@link #MISSILE_SPEED_PERCENT} за единицу. Выше {@link #POINT_DEFENCE_MAX_PERCENT}
     * шанс не поднимается: совсем без ракет оборона не оставляет.
     */
    public Integer interceptChancePercent(Integer guns, Shot shot) {
        if (shot.kind() != WeaponKind.MISSILE || guns <= 0) {
            return 0;
        }
        int chance = Math.min(POINT_DEFENCE_MAX_PERCENT, guns * POINT_DEFENCE_PERCENT);
        chance = chance * 100 / (100 + Math.max(0, shot.missileArmourPercent()));
        chance -= Math.max(0, shot.missileSpeed()) * MISSILE_SPEED_PERCENT;
        return Math.max(0, chance);
    }

    /**
     * Клеток поля за ход — п. 8: <b>боевая скорость двигателя как есть</b>.
     * <p>
     * Число оригинала идёт в бой напрямую: у ядерного двигателя двенадцать клеток, и поле
     * под него и размечено (32 клетки — почти три хода). Делить её больше не на что —
     * прежний делитель был поправкой на поле вчетверо мельче настоящего.
     * <p>
     * У проекта без двигателя из справочника (старые партии) остаётся его звёздная
     * скорость: меньше одной клетки за ход не ходит никто.
     */
    public Integer cellsPerTurn(ShipStats stats) {
        int combat = stats.combatSpeed() == null ? 0 : stats.combatSpeed();
        return combat > 0 ? combat : Math.max(1, stats.speed());
    }

    /**
     * Урон выстрела, дошедший до цели через расстояние, — п. 8.
     * <p>
     * Правило MOO II: луч рассеивается в пути и с каждыми тремя клетками теряет по
     * десятой доле силы — 100 % вплотную, 90 % на 3–5 клетках, 80 % на 6–8 и так далее до
     * 35 % на 21–23. Снаряды и ракеты долетают целыми: рассеиваться в них нечему.
     */
    public Integer damageAtRange(Shot shot, Integer distance) {
        if (shot.kind() != WeaponKind.BEAM || Boolean.TRUE.equals(shot.noRangePenalty())) {
            return shot.damage();
        }
        int steps = Math.max(0, distance) / DISSIPATION_STEP_CELLS;
        int percent = Math.max(MIN_DAMAGE_PERCENT, 100 - steps * DISSIPATION_PERCENT_PER_STEP);
        return Math.max(1, shot.damage() * percent / 100);
    }

    /**
     * Урон одного выстрела, дошедший до корабля: щит гасит удар целиком, если тот слабее
     * щита. Так тяжёлый ствол пробивает щит, а рой мелких об него разбивается — правило
     * MOO II, ради которого там и ставят большие пушки.
     */
    public Integer damageThroughShield(Integer shotDamage, Integer shield) {
        return Math.max(0, shotDamage - shield);
    }

    /**
     * Удар по щиту оригинала — п. 8 (13.10.2026): класс срезает урон с выстрела, не тратя запаса
     * («negates N points of damage from each weapon... it does not even weaken the shield»), а
     * остаток поглощает запас, пока не кончится («absorbs... before failing»). Упавший щит
     * держит только класс — до конца круга, пока запас не восстановится.
     *
     * @param shieldClass класс щита; ноль — генератор разбит или его нет
     * @param pool        запас щита сейчас
     * @return урон, дошедший до брони, и запас после удара
     */
    public ShieldHit shieldHit(Integer damage, Integer shieldClass, Integer pool) {
        int left = Math.max(0, damage - Math.max(0, shieldClass));
        int absorbed = Math.min(Math.max(0, pool), left);
        return new ShieldHit(left - absorbed, Math.max(0, pool) - absorbed);
    }

    /** Итог удара по щиту: что прошло к броне и сколько запаса осталось. */
    public record ShieldHit(Integer through, Integer pool) {
    }

    /**
     * Сколько запаса щита восстанавливается за круг — п. 8, число MOO II: «Regenerates 30% of its
     * maximum strength per combat turn». Щитовой конденсатор утраивает это («triples the shield
     * recharge rate»).
     */
    public static final int SHIELD_RECHARGE_PERCENT = 30;

    /** Запас щита после восстановления круга; {@code multiplier} — 3 у щитового конденсатора. */
    public Integer recharged(Integer pool, Integer max, Integer multiplier) {
        int gain = max * SHIELD_RECHARGE_PERCENT * Math.max(1, multiplier) / 100;
        return Math.min(max, Math.max(0, pool) + Math.max(gain, max > 0 ? 1 : 0));
    }

    /**
     * Доходит ли этот ствол до цели — п. 8: дальность у каждого своя, потому что тяжёлая
     * установка бьёт вдвое дальше, а ближняя оборона вдвое ближе.
     */
    public Boolean shotReaches(Shot shot, Integer distance) {
        return distance <= rangeOf(shot);
    }

    /**
     * Скорость ракеты при ядерном двигателе — клеток за круг (backlog-promo, пункт 30).
     * <p>
     * Ракета в MOO II — самоходный снаряд, а не мгновенный выстрел: она летит по полю своей
     * боевой скоростью, «базовая 10, каждый двигатель после ядерного +2, быстрая (FST) ещё
     * +4» — так что быстрая ракета на межфазных двигателях идёт 24 клетки за ход. До
     * 03.10.2026 ракета у нас доставала через всё поле в тот же миг — и корабль с ракетами
     * не двигался в бою вовсе: всякая цель была «в досягаемости».
     */
    public static final int MISSILE_BASE_SPEED = 10;

    /** Прибавка скорости ракеты за каждую ступень двигателя после ядерного. */
    private static final int MISSILE_SPEED_PER_DRIVE = 2;

    /**
     * Боевая скорость ядерного двигателя и шаг лестницы двигателей — по ним ступень
     * двигателя выводится из его боевой скорости ({@code ship-components.json}: 12, 16 … 32).
     */
    private static final int NUCLEAR_COMBAT_SPEED = 12;
    private static final int DRIVE_COMBAT_STEP = 4;

    /**
     * На сколько кругов у ракеты топлива — правило MOO II: «missiles can only fly for 2
     * turns before they run out of fuel». Не догнала цель за два круга — пропала, поэтому
     * быстрый корабль от медленной ракеты уходит.
     */
    public static final int MISSILE_FUEL_TURNS = 2;

    /**
     * Скорость ракеты в клетках за круг — п. 8, backlog-promo, пункт 30.
     * <p>
     * В оригинале скорость ракеты даёт лучший изученный двигатель ИМПЕРИИ; у нас берётся
     * двигатель самого корабля — проекты собираются из лучшего изученного
     * ({@code ShipDesignService.ensureAutoDesigns}), и разница бывает только у старых
     * проектов. Это реконструкция и упрощение, и правится оно здесь. У платформы двигателя
     * нет — её ракеты идут базовой скоростью.
     */
    public Integer missileFlightSpeed(Integer combatSpeed, Integer fastBonus) {
        int drives = Math.max(0, (combatSpeed == null ? 0 : combatSpeed) - NUCLEAR_COMBAT_SPEED)
                / DRIVE_COMBAT_STEP;
        return MISSILE_BASE_SPEED + drives * MISSILE_SPEED_PER_DRIVE
                + Math.max(0, fastBonus == null ? 0 : fastBonus);
    }

    /**
     * Дальность одного ствола в клетках: у ракеты — сколько она пролетит на своём топливе
     * (скорость на {@link #MISSILE_FUEL_TURNS} круга), у прочих — своя.
     */
    public Integer rangeOf(Shot shot) {
        if (shot.kind() == WeaponKind.MISSILE) {
            return Math.max(1, shot.flightSpeed()) * MISSILE_FUEL_TURNS;
        }
        return shot.range() == null ? WEAPON_RANGE : shot.range();
    }

    /**
     * Куда ракета долетает за один круг — п. 8, backlog-promo, пункт 30.
     * <p>
     * Ближе своей скорости — прямо к цели; дальше — на скорость по прямой к цели. Клетка
     * считается от доли пути по каждой оси, а не шагом «по диагонали, пока можно»: так
     * ракета идёт к цели прямой, а не ломаной.
     *
     * @return клетка, где ракета окажется; совпала с целью — ракета долетела
     */
    public int[] flightStep(Integer fromX, Integer fromY, Integer toX, Integer toY, Integer cells) {
        int distance = distance(fromX, fromY, toX, toY);
        if (distance <= cells) {
            return new int[]{toX, toY};
        }
        int x = fromX + Math.round((float) (toX - fromX) * cells / distance);
        int y = fromY + Math.round((float) (toY - fromY) * cells / distance);
        return new int[]{x, y};
    }

    /**
     * На какое расстояние кораблю выгодно подойти к цели — backlog-promo, пункт 30.
     * <p>
     * Дальнее всего, на котором залп ещё бьёт в полную силу: луч слабеет каждые
     * {@link #DISSIPATION_STEP_CELLS} клетки, и лучевой корабль, остановившийся на краю
     * досягаемости, бил бы на треть силы. Ракета считается «в полную силу» только в
     * пределах ПОЛОВИНЫ своего пути за круг: дальше её успевают перехватить или от неё уйти,
     * а ближняя оборона бьёт по ней на всём пути, и чем путь короче, тем меньше чужих
     * защитников он минует. Снаряды не слабеют — корабль с одними снарядами бьёт с предела.
     * <p>
     * Прежде ИИ подходил ровно до предела самого дальнобойного ствола, и бой стоял: эскадры
     * перестреливались с краёв поля, а корабль с ракетами не трогался с места вовсе.
     */
    public Integer preferredDistance(List<Shot> shots) {
        return preferredDistance(shots, Boolean.TRUE);
    }

    /**
     * То же, но с оглядкой на цель (пункт 30, 03.10.2026): если сбивать ракеты ей НЕЧЕМ — ни
     * ближней обороны, ни противоракет, — ракета бьёт в полную силу на всём своём пути, и
     * ракетчику выгодно стоять на пределе, подальше от чужих лучей. Подходить на полпути за
     * круг стоит только к тому, кто умеет перехватывать: там короткий путь — это меньше
     * бросков перехвата. Заодно в бою появляются залпы в полёте, и перехват становится виден.
     */
    public Integer preferredDistance(List<Shot> shots, Boolean targetIntercepts) {
        if (shots.isEmpty()) {
            return 1;
        }
        int best = 0;
        int full = volleyAt(shots, 0, targetIntercepts);
        for (int distance = 1; distance <= Math.max(FIELD_WIDTH, FIELD_HEIGHT); distance++) {
            if (volleyAt(shots, distance, targetIntercepts) < full) {
                break;
            }
            best = distance;
        }
        return Math.max(1, best);
    }

    /** Урон залпа на этом расстоянии, если попадёт всё, — для выбора дистанции. */
    private int volleyAt(List<Shot> shots, int distance, Boolean targetIntercepts) {
        int total = 0;
        for (Shot shot : shots) {
            boolean reaches = shot.kind() == WeaponKind.MISSILE
                    ? distance <= (Boolean.TRUE.equals(targetIntercepts)
                            ? Math.max(1, shot.flightSpeed() / 2)
                            : rangeOf(shot))
                    : shotReaches(shot, distance);
            if (reaches) {
                total += damageAtRange(shot, distance);
            }
        }
        return total;
    }

    /**
     * Во сколько раз обволакивающий удар (ENV) сильнее обычного — п. 8.
     * <p>
     * Само число живёт в {@link ShipDesignRules#ENVELOPING_SIDES} — там же, где считается
     * сила проекта: множитель нужен обоим, и одно число в двух местах однажды разойдётся.
     * Прошедший щит урон учитывается вчетверо.
     */
    public Integer envelopingFactor(Shot shot) {
        return Boolean.TRUE.equals(shot.envelops()) ? ShipDesignRules.ENVELOPING_SIDES : 1;
    }

    /** Расстояние между клетками поля: диагональ считается за один шаг. */
    public Integer distance(Integer fromX, Integer fromY, Integer toX, Integer toY) {
        return Math.max(Math.abs(fromX - toX), Math.abs(fromY - toY));
    }

    /** Достанет ли залп до цели с этого места. */
    public Boolean inRange(Integer fromX, Integer fromY, Integer toX, Integer toY) {
        return distance(fromX, fromY, toX, toY) <= WEAPON_RANGE;
    }

    /**
     * Насколько далеко бьёт корабль — по самому дальнобойному своему стволу (п. 8).
     * <p>
     * Дальность перестала быть одной на всех: тяжёлая установка (HV) бьёт вдвое дальше,
     * ближняя оборона (PD) — вдвое ближе. Подходить к цели корабль должен на выстрел
     * хоть чем-нибудь, а долетит ли до неё каждый конкретный ствол, решает
     * {@link #shotReaches(Shot, Integer)} уже в залпе.
     */
    public Integer reach(List<Shot> shots) {
        return shots.stream()
                .mapToInt(this::rangeOf)
                .max()
                .orElse(WEAPON_RANGE);
    }

    /** Достанет ли до цели хоть один ствол корабля. */
    public Boolean inReach(List<Shot> shots, Integer fromX, Integer fromY,
                           Integer toX, Integer toY) {
        return distance(fromX, fromY, toX, toY) <= reach(shots);
    }

    /** Клетка на поле, а не за его краем. */
    public Boolean onField(Integer x, Integer y) {
        return x >= 0 && x < FIELD_WIDTH && y >= 0 && y < FIELD_HEIGHT;
    }

    /**
     * Куда корабль встаёт перед боем — п. 8.
     * <p>
     * Нападающий входит в бой слева, обороняющийся справа, оба строем в столбец по центру поля:
     * так же расставляет флоты MOO II. Строй растягивается от середины, чтобы бой начинали
     * с одинакового расстояния при любом числе кораблей.
     * <p>
     * Строй длиннее поля переходит во второй столбец — за спину первому. Иначе лишние
     * корабли вставали бы на последнюю клетку друг на друга: две единицы в одной клетке
     * поле не различает, и ходить им потом некуда.
     *
     * @param index номер корабля в своём строю, начиная с нуля
     * @param total сколько кораблей в строю — по нему строй и центрируется
     */
    public int[] startingCell(BattleSide side, int index, int total) {
        int column = index / FIELD_HEIGHT;
        int inColumn = index % FIELD_HEIGHT;
        int rows = Math.max(1, Math.min(FIELD_HEIGHT, total - column * FIELD_HEIGHT));
        int first = Math.max(0, (FIELD_HEIGHT - rows) / 2);
        int y = Math.min(FIELD_HEIGHT - 1, first + inColumn);
        int x = side == BattleSide.ATTACKER ? 1 + column : FIELD_WIDTH - 2 - column;
        return new int[]{x, y};
    }

    /** Разыгрывает попадание: {@code true} — выстрел дошёл до цели. */
    public Boolean rollHit(RandomGenerator random, Integer hitChancePercent) {
        return random.nextInt(100) < hitChancePercent;
    }

    /**
     * Что на корабле ещё цело — п. 8: по этому списку и бросается жребий поломки.
     * <p>
     * Система здесь — это <b>гнездо</b>, а не вид: четыре масс-драйвера это четыре
     * системы, и выбивает их по одной. Оттого у крупного корабля с полным трюмом стволов
     * двигатель под ударом реже, чем у фрегата с единственной пушкой, — и это правильно:
     * рвётся то, чего в корабле больше.
     * <p>
     * Брони и корпуса в списке нет: их держит сам корпус, ломать их отдельно нечего.
     * Особые модули не ломаются пока тоже — точка расширения: их действие считается на
     * проекте целиком, а не на корабле.
     */
    public List<String> liveSystems(List<ShipDesignRules.Item> items, List<String> damaged) {
        List<String> live = new ArrayList<>();
        for (ShipDesignRules.Item item : items) {
            switch (item.component().slot()) {
                case WEAPON -> {
                    for (int mount = 0; mount < item.count(); mount++) {
                        live.add(item.component().code());
                    }
                }
                case ENGINE -> live.add(ENGINE_SYSTEM);
                case SHIELD -> live.add(SHIELD_SYSTEM);
                case COMPUTER -> live.add(COMPUTER_SYSTEM);
                default -> {
                    // Броня и особые модули не ломаются — см. javadoc.
                }
            }
        }
        // Уже выбитое вычитается ПО ОДНОМУ: у корабля четыре одинаковых ствола, и
        // разбитый первый не уносит с собой три уцелевших.
        List<String> left = new ArrayList<>(live);
        for (String broken : damaged) {
            left.remove(broken);
        }
        return left;
    }

    /**
     * Состав корабля БЕЗ выбитых систем — п. 8: по нему считаются залп и прицел.
     * <p>
     * Разбитая пушка не стреляет, сожжённый компьютер не целится. Двигатель и щит из
     * состава не убираются: скорость и щит корабль держит своими полями боя, и убрать их
     * отсюда значило бы посчитать поломку дважды.
     */
    public List<ShipDesignRules.Item> partsLeft(List<ShipDesignRules.Item> items,
                                                List<String> damaged) {
        return partsLeft(items, damaged, null);
    }

    /**
     * Тот же состав, но из оружия — только выбранные строки: так стреляет корабль, у которого
     * игрок включил не все стволы, как в полосе боя оригинала (п. 8).
     * <p>
     * Номер строки — место ствола среди оружия ПРОЕКТА, то есть тот же номер, что у строки
     * в полосе боя (`BattleWeaponDto`). Выбитые гнёзда списываются так же, как без выбора:
     * по порядку проекта, — иначе выключенная строка могла бы «забрать» чужую поломку.
     *
     * @param rows выбранные строки оружия; {@code null} — стреляет всё
     */
    public List<ShipDesignRules.Item> partsLeft(List<ShipDesignRules.Item> items,
                                                List<String> damaged, Set<Integer> rows) {
        if (damaged.isEmpty() && rows == null) {
            return items;
        }
        List<String> broken = new ArrayList<>(damaged);
        List<ShipDesignRules.Item> left = new ArrayList<>(items.size());
        int row = 0;
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() == ShipComponentSlot.COMPUTER
                    && broken.remove(COMPUTER_SYSTEM)) {
                continue;
            }
            if (item.component().slot() != ShipComponentSlot.WEAPON) {
                left.add(item);
                continue;
            }
            int mounts = item.count();
            while (mounts > 0 && broken.remove(item.component().code())) {
                mounts--;
            }
            if (mounts > 0 && (rows == null || rows.contains(row))) {
                left.add(new ShipDesignRules.Item(item.component(), mounts, item.modifications()));
            }
            row++;
        }
        return left;
    }

    /** Попадание по корпусу выбило систему? */
    public Boolean rollSystemHit(RandomGenerator random) {
        return rollSystemHit(random, 1);
    }

    /**
     * То же, но с множителем — прицел «Ахилл» (п. 8): «triples the chance to damage weapons
     * and other essential systems». Выше сотни шанс не поднимается.
     */
    public Boolean rollSystemHit(RandomGenerator random, Integer odds) {
        return rollHit(random, Math.min(100, SYSTEM_HIT_PERCENT * Math.max(1, odds)));
    }

    /** Разбитый двигатель взорвал корабль? */
    public Boolean rollEngineBlast(RandomGenerator random) {
        return rollHit(random, ENGINE_BLAST_PERCENT);
    }

    /** Какую систему выбило: жребий по всему, что ещё цело; пусто — ломать нечего. */
    public String pickSystem(RandomGenerator random, List<String> live) {
        return live.isEmpty() ? null : live.get(random.nextInt(live.size()));
    }

    /** Сила взрыва корабля: половина его полной прочности — по всем, кто рядом. */
    public Integer blastDamage(ShipHull hull) {
        return Math.max(1, hull.structure() * BLAST_DAMAGE_PERCENT / 100);
    }

    /** На сколько клеток достаёт взрыв: у дредноута и выше — на две. */
    public Integer blastRadius(ShipHull hull) {
        return hull.structure() >= BIG_HULL_STRUCTURE ? BIG_BLAST_RADIUS : BLAST_RADIUS;
    }

    /**
     * Сколько кораблей победителя унесли с собой взрывы погибших — быстрый бой (п. 8).
     * <p>
     * Жребий бросается на каждого погибшего, а не берётся долей: партия обязана
     * повторяться от прогона к прогону, и случайность у неё выводится из зерна партии.
     */
    public Integer fastBlastLosses(RandomGenerator random, Integer deaths) {
        int blasts = 0;
        for (int death = 0; death < Math.max(0, deaths); death++) {
            if (Boolean.TRUE.equals(rollHit(random, FAST_BLAST_PERCENT))) {
                blasts++;
            }
        }
        return blasts;
    }

    /**
     * Зерно одного боя — п. 8: партия, ход и места обеих сторон, и НИ ОДНОГО
     * идентификатора строки.
     * <p>
     * <b>Почему правило стоит здесь, одно на оба боя.</b> Бой в игре считается двумя
     * путями — тактической сценой и быстрым «авто» ({@code SpaceBattleService}), — и жребий
     * бросают оба. Пока формула жила в каждом своём месте, тактическая сцена держала
     * правило, а «авто» его нарушало: зерно там собиралось с
     * {@code starSystemId.getMostSignificantBits()}. Идентификатор строки — случайный UUID,
     * выданный Hibernate при записи, и во ВТОРОМ прогоне той же партии он другой. Значит
     * взрывы погибших ({@link #fastBlastLosses}) выпадали по-разному, потери победителя
     * расходились, а за ними расходилась и вся партия. Измерено 25.09.2026: из восьми
     * партий по 150 ходов (SMALL, шесть империй) четыре разошлись сами с собой, самая ранняя
     * на 74-м ходу — и всякий раз первой расходилась сила флота. Бьёт это прежде всего по
     * прибору балансировки: империи ИИ дерутся ТОЛЬКО быстрым боём, и парные прогоны
     * сравнивали не расы, а удачу взрывов.
     * <p>
     * <b>Почему система в зерно не входит вовсе.</b> Различать бои в один ход нужно, и
     * различают их места сторон: двух боёв одной пары в один ход не бывает — флот у империи
     * в системе один, и встреча заводится на пару флотов. Название звезды сюда просилось
     * (оно выводится из зерна партии и потому повторимо), но за ним пришлось бы идти в базу
     * изнутри посчитанного хода, а мест сторон хватает.
     *
     * @param gameSeed      зерно партии: вся случайность игры выводится из него
     * @param turn          ход боя
     * @param attackerSlot  место напавшего в партии
     * @param defenderSlot  место обороняющегося
     */
    public static Long battleSeed(Long gameSeed, Integer turn,
                                  Integer attackerSlot, Integer defenderSlot) {
        return (gameSeed == null ? 0L : gameSeed) * SEED_GAME
                + turn * SEED_TURN
                + attackerSlot * SEED_SLOT
                + defenderSlot;
    }
}
