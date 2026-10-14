package com.sddnw.server.service;

import com.sddnw.server.domain.Building;
import com.sddnw.server.domain.BuildingEffects;
import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.enums.BuildingEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Мораль колонии — п. 10.1, правило MOO II («Strength through Joy», StrategyWiki).
 * <p>
 * <b>Мораль — это не выработка, а процент ко всему, что дают жители:</b> к еде фермеров, к
 * производству рабочих, к науке учёных и к налогу. Поэтому своей колонки у неё нет вовсе, а в
 * колонию она приходит четырьмя процентами ({@link #effects}) — той же строкой, что и проценты
 * строя: «C_total is formed by government, morale and colony leader … for other governments
 * morale just adds to C_total» (Calculations). Твёрдые прибавки зданий под мораль не попадают,
 * как и под процент строя: «morale is applied after building, planetary, and racial bonuses to
 * each worker but before flat building bonuses».
 * <p>
 * Что её двигает, взято у первоисточника дословно:
 * <ul>
 *   <li><b>диктатура и феодализм</b> (и конфедерация) — колония без казарм теряет 20 %: «newly
 *       founded colonies of Dictatorship or Feudal empires struggle at −20% morale until they
 *       build a Marine Barracks». Родной мир получает казармы готовыми, поэтому стартует ровно;</li>
 *   <li><b>Империум</b> — штрафа за отсутствие казарм нет, а с казармами +20 %;</li>
 *   <li><b>объединение и галактическое единство</b> мораль не знают вовсе — ни к добру, ни к
 *       худу: всё прочее для них ноль;</li>
 *   <li>голосимулятор +20 %, Купол Элизия +30 % — здания колонии;</li>
 *   <li>Сеть виртуальной реальности +20 % — достижение, сразу всем колониям империи;</li>
 *   <li>псионика +10 % — только диктатуре, феодализму и их развитым строям;</li>
 *   <li>духовный лидер — своей прибавкой всем колониям системы (п. 6);</li>
 *   <li>колония двух рас −20 %, пока на ней живут подданные, если не построен центр управления
 *       чужими;</li>
 *   <li>империя без столицы — мораль всех колоний падает по строю: −50 % феодализму, −35 %
 *       диктатуре, −20 % демократии, пока столицу не отстроят ({@link CapitolRules}).</li>
 * </ul>
 */
public final class MoraleRules {

    /** Сеть виртуальной реальности: достижение, мораль всех колоний +20 %. */
    public static final String VIRTUAL_REALITY_TECH = "virtual-reality-network";
    public static final int VIRTUAL_REALITY_PERCENT = 20;

    /** Псионика: прибавку к морали дают строи, у которых она есть ({@code PSIONICS_MORALE}). */
    public static final String PSIONICS_TECH = "psionics";

    /** Колония двух рас без центра управления чужими: «the 20% morale penalty from multi-racial colonies». */
    public static final int MULTIRACIAL_PERCENT = -20;

    /** Код способности духовного лидера в {@code leaders.json}. */
    public static final String SPIRITUAL_ABILITY = "SPIRITUAL";

    private MoraleRules() {
    }

    /** Откуда взялось слагаемое морали: экран показывает их строками подсказки. */
    public enum Source {
        /** Строй: штраф без казарм или прибавка Империума с ними. */
        GOVERNMENT,
        /** Здания колонии: голосимулятор и Купол Элизия. */
        BUILDINGS,
        /** Сеть виртуальной реальности. */
        VIRTUAL_REALITY,
        /** Псионика. */
        PSIONICS,
        /** Духовный лидер системы. */
        LEADER,
        /** Подданные другой расы. */
        ALIENS,
        /** Империя потеряла столицу. */
        CAPITOL
    }

    public record Part(Source source, Integer percent) {
    }

    /**
     * Мораль колонии и её слагаемые.
     *
     * @param percent сумма слагаемых; у строя без морали — ноль
     * @param ignored строй морали не знает (объединение): экран говорит это словами, а не нулём
     */
    public record Morale(Integer percent, List<Part> parts, Boolean ignored) {

        public static final Morale NONE = new Morale(0, List.of(), Boolean.FALSE);

        /**
         * Мораль четырьмя процентами: к выработке фермера, рабочего, учёного и к налогу.
         * Колония складывает их с процентами строя и не различает, откуда пришла прибавка.
         */
        public BuildingEffects effects() {
            if (percent == 0) {
                return BuildingEffects.NONE;
            }
            return new BuildingEffects(Map.of(
                    BuildingEffectType.FOOD_PERCENT, percent,
                    BuildingEffectType.PRODUCTION_PERCENT, percent,
                    BuildingEffectType.RESEARCH_PERCENT, percent,
                    BuildingEffectType.INCOME_PERCENT, percent), 0);
        }
    }

    /**
     * Мораль колонии.
     *
     * @param government   раса владельца: строй с его правилами морали
     * @param buildings    действие построек колонии (без расы — у неё своих слагаемых нет)
     * @param technologies изученное владельцем
     * @param leader       процент духовного лидера системы; ноль — его нет
     * @param multiracial  на колонии живут подданные другой расы
     * @param capitolLost  у империи нет столицы, хотя строю она положена ({@link CapitolRules})
     */
    public static Morale morale(RaceEffects government, BuildingEffects buildings,
                                Set<String> technologies, Integer leader, Boolean multiracial,
                                Boolean capitolLost) {
        if (Boolean.TRUE.equals(government.noMorale())) {
            return new Morale(0, List.of(), Boolean.TRUE);
        }
        List<Part> parts = new ArrayList<>();
        Integer barracksPart = Boolean.TRUE.equals(buildings.barracks())
                ? government.moraleWithBarracks()
                : government.moraleWithoutBarracks();
        add(parts, Source.GOVERNMENT, barracksPart);
        add(parts, Source.BUILDINGS, buildings.moralePercent());
        if (technologies.contains(VIRTUAL_REALITY_TECH)) {
            add(parts, Source.VIRTUAL_REALITY, VIRTUAL_REALITY_PERCENT);
        }
        if (technologies.contains(PSIONICS_TECH)) {
            add(parts, Source.PSIONICS, government.psionicsMorale());
        }
        add(parts, Source.LEADER, leader);
        if (Boolean.TRUE.equals(multiracial) && !Boolean.TRUE.equals(buildings.alienManagement())) {
            add(parts, Source.ALIENS, MULTIRACIAL_PERCENT);
        }
        if (Boolean.TRUE.equals(capitolLost)) {
            add(parts, Source.CAPITOL, government.capitolLossMorale());
        }
        int total = parts.stream().mapToInt(Part::percent).sum();
        return new Morale(total, List.copyOf(parts), Boolean.FALSE);
    }

    /**
     * Здание, которое этому строю не даёт ничего, — п. 10.1: досуг у объединения. Строить его
     * игроку никто не запрещает (не запрещает и оригинал), но выбирать его за игрока —
     * автострою и ИИ — значит тратить стройку и содержание впустую.
     */
    public static Boolean useless(Building building, RaceEffects government) {
        return Boolean.TRUE.equals(government.noMorale())
                && building != null
                && building.effects().keySet().stream()
                        .allMatch(type -> type == BuildingEffectType.MORALE_PERCENT)
                && building.effects().containsKey(BuildingEffectType.MORALE_PERCENT);
    }

    private static void add(List<Part> parts, Source source, Integer percent) {
        if (percent != null && percent != 0) {
            parts.add(new Part(source, percent));
        }
    }
}
