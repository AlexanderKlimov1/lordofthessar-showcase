package com.sddnw.server.service;

/**
 * Из чего сложилась сила стороны и что изменить после поражения — field-backlog, пункты 102
 * и 103.
 * <p>
 * «Сила 300 против 900» говорит, что проиграли, но не что менять, — и игрок записывает причину
 * вовне: «ИИ сильнее, игра нечестная». Поэтому сила раскладывается на те же слагаемые, из
 * которых её считает {@link ShipDesignRules#power}: корабли, залп, прочность (корпус, броня,
 * щит) — и к потере добавляется ОДНА строка о рычаге, выведенная из правил боя, без
 * советника. Правила не дают ответа — строки нет: выдуманный совет хуже молчания и подрывает
 * доверие к остальным.
 * <p>
 * Рычаги проверяются по порядку, первый сработавший и называется:
 * <ol>
 *   <li><b>щит</b> — сильнейший выстрел проигравшего не пробивает щит победителя
 *       ({@link BattleRules#damageThroughShield}: щит гасит удар целиком, если тот слабее), —
 *       нужен ствол тяжелее или бронебойный;</li>
 *   <li><b>корабль</b> — корабль победителя вдвое сильнее корабля проигравшего: нужен корпус
 *       крупнее или оружие лучше;</li>
 *   <li><b>число</b> — у победителя вдвое больше кораблей: нужен флот больше;</li>
 *   <li><b>прочность</b> и <b>залп</b> — вдвое перевеса в одном из множителей силы.</li>
 * </ol>
 * Порог «вдвое» — реконструкция: он отделяет причину от разброса, и правится только здесь.
 */
public final class BattleLeverRules {

    /** Во сколько раз слагаемое победителя должно превосходить, чтобы стать названной причиной. */
    public static final int DECISIVE_RATIO = 2;

    /**
     * Сторона боя слагаемыми силы.
     *
     * @param ships     кораблей и платформ, вышедших в бой
     * @param attack    сумма залпов
     * @param endurance сумма прочности: корпус, броня и щит
     * @param shield    сильнейший щит стороны — им гасится каждый чужой выстрел
     * @param bestShot  сильнейший одиночный выстрел стороны
     * @param power     сила стороны той же мерой, что решала бой
     */
    public record Side(Integer ships, Integer attack, Integer endurance, Integer shield,
                       Integer bestShot, Integer power) {
    }

    private BattleLeverRules() {
    }

    /** Рычаг проигравшего; пусто — правила причины не называют. */
    public static MessageKey lever(Side loser, Side winner) {
        if (loser.ships() <= 0 || winner.ships() <= 0) {
            return null;
        }
        if (winner.shield() > 0 && loser.bestShot() > 0 && loser.bestShot() <= winner.shield()) {
            return new MessageKey("turn.lever.shields", winner.shield(), loser.bestShot());
        }
        int loserShip = Math.max(1, loser.power() / loser.ships());
        int winnerShip = winner.power() / winner.ships();
        if (winnerShip >= DECISIVE_RATIO * loserShip) {
            return new MessageKey("turn.lever.quality", winnerShip, loserShip);
        }
        if (winner.ships() >= DECISIVE_RATIO * loser.ships()) {
            return new MessageKey("turn.lever.numbers", winner.ships(), loser.ships());
        }
        if (winner.endurance() >= DECISIVE_RATIO * Math.max(1, loser.endurance())) {
            return new MessageKey("turn.lever.endurance", winner.endurance(), loser.endurance());
        }
        if (winner.attack() >= DECISIVE_RATIO * Math.max(1, loser.attack())) {
            return new MessageKey("turn.lever.attack", winner.attack(), loser.attack());
        }
        return null;
    }

    /** Слагаемые обеих сторон одной строкой отчёта: сперва своя, потом чужая. */
    public static MessageKey parts(Side yours, Side theirs) {
        return new MessageKey("turn.battle.parts",
                yours.ships(), yours.attack(), yours.endurance(), yours.shield(),
                theirs.ships(), theirs.attack(), theirs.endurance(), theirs.shield());
    }
}
