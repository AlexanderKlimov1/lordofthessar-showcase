package com.sddnw.server.service;

/**
 * Главы партии — field-backlog, пункт 54: середину пути лечат подцели, у каждой свой конец.
 * Глава определяется СОСТОЯНИЕМ партии, а не номером хода, поэтому у разных партий и у разных
 * империй одной партии главы приходят в своё время. Идёт только вперёд: войну, кончившуюся миром,
 * не отменить, и «Первая война» остаётся пройденной.
 * <p>
 * Пять глав — решение хозяина проекта (08.10.2026). Глава перескакивает, если состояние ушло
 * дальше сразу (знакомство и война на одном ходу): объявляется та, в которую пришли.
 */
public final class ChapterRules {

    /** Номера глав — в строке игрока ({@code player.chapter}). */
    public static final int SETTLEMENT = 1;
    public static final int NEIGHBOURS = 2;
    public static final int WAR = 3;
    public static final int COUNCIL = 4;
    public static final int FINALE = 5;

    /** Коды для ключей словаря {@code chapter.<КОД>} — по номеру главы. */
    private static final String[] CODES = {"", "SETTLEMENT", "NEIGHBOURS", "WAR", "COUNCIL", "FINALE"};

    /** За сколько ходов до первого совета начинается «Борьба за совет». */
    public static final int COUNCIL_LEAD_TURNS = 10;
    /** За сколько ходов до итога по могуществу начинается «Финал». */
    public static final int MIGHT_LEAD_TURNS = 30;
    /** За сколько ходов до победы удержанием Тессара начинается «Финал». */
    public static final int WARDENHOLD_LEAD_TURNS = 10;
    /** Доля колоний галактики у одной империи, с которой покорение близко, — в процентах. */
    public static final int CONQUEST_SHARE_PERCENT = 50;

    private ChapterRules() {
    }

    /**
     * Что видно о партии и об империи — столько, сколько нужно правилу глав.
     *
     * @param met          империя с кем-то знакома
     * @param atWar        империя с кем-то воюет
     * @param councilNear  совет включён, живых империй хватает и первый совет близко (или прошёл)
     * @param finaleNear   кто-то близок к победе: удержание, итог по могуществу или покорение
     */
    public record State(boolean met, boolean atWar, boolean councilNear, boolean finaleNear) {
    }

    /** Глава, в которой империя теперь: не ниже прежней. */
    public static int chapter(int current, State state) {
        int reached = SETTLEMENT;
        if (state.met()) {
            reached = NEIGHBOURS;
        }
        if (state.atWar()) {
            reached = WAR;
        }
        if (state.met() && state.councilNear()) {
            reached = Math.max(reached, COUNCIL);
        }
        if (state.finaleNear()) {
            reached = FINALE;
        }
        return Math.max(current, reached);
    }

    /** Близок ли первый совет (или уже собирается) — по ходу партии. */
    public static boolean councilNear(boolean councilOn, int aliveEmpires, int turn) {
        return councilOn && aliveEmpires >= CouncilRules.MIN_EMPIRES
                && turn >= CouncilRules.FIRST_TURN - COUNCIL_LEAD_TURNS;
    }

    /**
     * Близок ли конец партии: держатель Тессара в {@link #WARDENHOLD_LEAD_TURNS} ходах от победы,
     * до итога по могуществу меньше {@link #MIGHT_LEAD_TURNS} ходов, или одна империя держит не
     * меньше половины колоний галактики (не раньше первого совета: в начале партии у каждого по
     * колонии, и «половина» значила бы ничего).
     */
    public static boolean finaleNear(Integer wardenholdTurnsLeft, boolean mightOn, int mightLimit, int turn,
                                     int biggestColonies, int allColonies, int aliveEmpires) {
        if (wardenholdTurnsLeft != null && wardenholdTurnsLeft <= WARDENHOLD_LEAD_TURNS) {
            return true;
        }
        if (mightOn && turn >= mightLimit - MIGHT_LEAD_TURNS) {
            return true;
        }
        return aliveEmpires >= 2 && allColonies > 0
                && biggestColonies * 100 >= allColonies * CONQUEST_SHARE_PERCENT
                && turn >= CouncilRules.FIRST_TURN;
    }

    public static String code(int chapter) {
        return CODES[Math.max(SETTLEMENT, Math.min(FINALE, chapter))];
    }
}
