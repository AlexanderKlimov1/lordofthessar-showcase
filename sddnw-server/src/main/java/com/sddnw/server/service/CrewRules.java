package com.sddnw.server.service;

/**
 * Опыт экипажей — п. 8 (13.10.2026, перенос MOO II ради космической академии).
 * <p>
 * В MOO II у экипажа есть звание — новобранцы, обученные, ветераны, элита, сверхэлита, — и звание
 * прибавляет кораблю меткости и уклонения. Академия поднимает начальное звание кораблей своей
 * колонии на одно («the crew starting level of ships built by a colony with a space academy is
 * increased by 1») и даёт стоящим в системе по очку опыта за ход («ships stationed in a system gain
 * 1 additional experience point each turn for every space academy»).
 * <p>
 * Корабли у нас хранятся числом по проектам, поэтому опыт — у СТРОКИ состава флота, средний на её
 * корабли; при слиянии строк он усредняется по числу кораблей.
 * <p>
 * <b>Реконструкция:</b> пороги званий, их прибавки и опыт за бой справка оригинала не называет.
 * Прибавка взята шагами шкалы меткости (+15 … +75), пороги — так, чтобы ветераном становились за
 * несколько боёв, а у академии без боёв — за полсотни ходов.
 */
public final class CrewRules {

    /** Опыт, с которого начинается звание: новобранцы, обученные, ветераны, элита, сверхэлита. */
    static final int[] LEVEL_XP = {0, 20, 50, 100, 150};

    /** Прибавка звания к меткости и к защите корабля — те же единицы, что у компьютера и уклонения. */
    static final int[] LEVEL_BONUS = {0, 15, 30, 50, 75};

    /** Опыт уцелевшим кораблям за бой. */
    public static final int BATTLE_XP = 10;

    /** Опыт за ход стоянки в системе за каждую свою академию там — число оригинала. */
    public static final int ACADEMY_XP_PER_TURN = 1;

    /** Сколько опыта у корабля, построенного колонией с академией, — начало звания «обученные». */
    public static final int ACADEMY_START_XP = LEVEL_XP[1];

    /** Больше этого опыт не копится: дальше сверхэлиты расти некуда. */
    public static final int MAX_XP = LEVEL_XP[LEVEL_XP.length - 1];

    private CrewRules() {
    }

    /** Звание экипажа по опыту: 0 — новобранцы … 4 — сверхэлита. */
    public static int level(Integer experience) {
        int xp = experience == null ? 0 : experience;
        int level = 0;
        for (int i = 0; i < LEVEL_XP.length; i++) {
            if (xp >= LEVEL_XP[i]) {
                level = i;
            }
        }
        return level;
    }

    /** Прибавка звания к меткости и защите. */
    public static int bonus(Integer experience) {
        return LEVEL_BONUS[level(experience)];
    }

    /** Опыт слившихся кораблей — среднее по их числу. */
    public static int merged(int experience, int ships, int otherExperience, int otherShips) {
        int all = ships + otherShips;
        return all <= 0 ? 0 : (int) (((long) experience * ships + (long) otherExperience * otherShips) / all);
    }

    /** Опыт после прибавки, не выше сверхэлиты. */
    public static int gained(Integer experience, int xp) {
        return Math.min(MAX_XP, (experience == null ? 0 : experience) + Math.max(0, xp));
    }
}
