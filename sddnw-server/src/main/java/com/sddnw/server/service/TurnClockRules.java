package com.sddnw.server.service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Часы хода в партии людей — backlog-promo, пункт 11.
 * <p>
 * Ход считается, когда закончили все люди, и один медленный игрок держит остальных, а
 * проигрывающий обычно уходит — и партия умирает для всех. Правил три, и все три — решение
 * хозяина проекта (02.10.2026):
 * <ul>
 *   <li><b>срок хода</b> — настройка партии ({@link #ALLOWED_SECONDS}, по умолчанию без
 *       срока). Истёк — ход считается сам: опоздавший пропускает свои решения этого хода,
 *       колонии строят по очереди, наука идёт к выбранной цели;</li>
 *   <li><b>ушедший</b> — тот, кто нажал «покинуть партию», пропустил {@link #MISSED_LIMIT}
 *       срока подряд или {@link #IDLE} не сделал ни одного настоящего действия. Его империю
 *       ведёт ИИ, пока он не вернётся: любое его действие возвращает ему место;</li>
 *   <li><b>всё это — только когда кто-то ждёт</b>: хотя бы один присутствующий человек уже
 *       закончил ход. Без этой оговорки одиночная партия против ИИ шла бы сама, пока
 *       игрок отошёл от стола, а ждать в ней некому — и торопить некого.</li>
 * </ul>
 */
public final class TurnClockRules {

    /**
     * Сроки хода, которые предлагает окно новой игры: 1,5, 2, 3, 5 и 10 минут — и медленные партии
     * (field-backlog, пункт 64, решение хозяина проекта 08.10.2026): «вечерняя» — ход в четыре часа,
     * «по переписке» — ход в сутки. Людям не нужно быть за столом одновременно, а без уведомлений о
     * ходе (пункт 22) такая партия умирает — они уже есть.
     */
    public static final List<Integer> ALLOWED_SECONDS = List.of(90, 120, 180, 300, 600, 4 * 3600, 24 * 3600);

    /**
     * С какого срока партия медленная (пункт 64): ушедшим в ней считают по ПРОПУЩЕННЫМ срокам, а
     * не по трём минутам без действия — иначе любой игрок «уходил» бы через три минуты после своего
     * хода, и его империю вёл бы ИИ до вечера.
     */
    public static final Integer SLOW_FROM_SECONDS = 3600;

    /** Столько без единого настоящего действия — и игрок считается ушедшим. */
    public static final Duration IDLE = Duration.ofMinutes(3);

    /** Столько сроков подряд без конца хода — и игрок считается ушедшим. */
    public static final Integer MISSED_LIMIT = 3;

    private TurnClockRules() {
    }

    /** Годится ли срок хода для партии: пусто — без срока. */
    public static Boolean allowed(Integer seconds) {
        return seconds == null || ALLOWED_SECONDS.contains(seconds);
    }

    /** Когда истекает ход; пусто — у партии нет срока или ход ещё не начинался. */
    public static OffsetDateTime deadline(OffsetDateTime turnStartedAt, Integer seconds) {
        if (turnStartedAt == null || seconds == null) {
            return null;
        }
        return turnStartedAt.plusSeconds(seconds);
    }

    /** Истёк ли срок хода к этому мгновению. */
    public static Boolean expired(OffsetDateTime turnStartedAt, Integer seconds, OffsetDateTime now) {
        OffsetDateTime deadline = deadline(turnStartedAt, seconds);
        return deadline != null && !now.isBefore(deadline);
    }

    /**
     * Когда ИИ возьмёт империю игрока, если тот так ничего и не сделает, — field-backlog,
     * пункт 127: по безделью ({@link #IDLE} после последнего действия), а если срок хода
     * станет для него последним пропущенным ({@link #MISSED_LIMIT}) — на этом сроке, когда он
     * раньше. Одно правило на часы и на предупреждение: посчитанное дважды, оно разошлось бы,
     * и строка «через минуту» врала бы.
     */
    public static OffsetDateTime takeoverAt(OffsetDateTime lastAction, Integer missed,
                                            OffsetDateTime turnStartedAt, Integer seconds) {
        OffsetDateTime deadline = deadline(turnStartedAt, seconds);
        if (Boolean.TRUE.equals(slow(seconds))) {
            // Медленная партия (пункт 64): только сроки; пока до последнего пропущенного далеко — не грозит.
            return deadline != null && missed + 1 >= MISSED_LIMIT ? deadline : null;
        }
        OffsetDateTime idle = lastAction.plus(IDLE);
        if (deadline != null && missed + 1 >= MISSED_LIMIT && deadline.isBefore(idle)) {
            return deadline;
        }
        return idle;
    }

    /**
     * Ушёл ли игрок по безделью: последнее настоящее действие — не позже чем {@link #IDLE} назад. В
     * медленной партии безделья не бывает (пункт 64): там ушедшего называют только пропущенные сроки.
     */
    public static Boolean idle(OffsetDateTime lastAction, OffsetDateTime now, Integer seconds) {
        return !Boolean.TRUE.equals(slow(seconds)) && !lastAction.plus(IDLE).isAfter(now);
    }

    /** Медленная ли партия — ход в часы, а не в минуты (пункт 64). */
    public static Boolean slow(Integer seconds) {
        return seconds != null && seconds >= SLOW_FROM_SECONDS;
    }
}
