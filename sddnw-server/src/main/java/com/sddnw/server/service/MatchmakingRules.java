package com.sddnw.server.service;

import com.sddnw.server.dto.CreateGameRequest;
import com.sddnw.server.domain.enums.GalaxySize;

import java.time.Duration;

/**
 * Правила стола «сыграть с людьми» — field-backlog, пункт 65.
 * <p>
 * Подбор сводит незнакомых, поэтому стол настраивает сервер: малая галактика на четверых — партия,
 * которую двое людей способны доиграть (атомарная сеть, пункт 63), — все пути к победе и срок хода,
 * без которого один задумавшийся держит всех. Числа — решение по пункту, а не замер: ждать
 * дольше пяти минут человек, пришедший «сыграть сейчас», не станет, и стол уйдёт вместе с ним.
 */
public final class MatchmakingRules {

    /** Сколько стол ждёт людей, прежде чем стартовать сам. */
    public static final Duration WAIT = Duration.ofMinutes(5);

    /**
     * Когда двое уже за столом, остальных ждут недолго: партия людей состоялась, и держать двоих
     * пять минут ради третьего — терять обоих.
     */
    public static final Duration GRACE = Duration.ofSeconds(30);

    /** Сам стол стартует не меньше чем с двумя людьми: иначе это одиночная партия. */
    public static final int MIN_HUMANS = 2;

    /** Империй за столом, считая людей; свободные места добирает ИИ. */
    public static final int EMPIRES = 4;

    /** Срок хода в секундах — из разрешённых окном новой игры ({@code TurnClockRules}). */
    public static final int TURN_SECONDS = 180;

    private MatchmakingRules() {
    }

    /** Настройки стола подбора; название — по имени первого, как у любой партии. */
    static CreateGameRequest request(String playerName) {
        return new CreateGameRequest(null, GalaxySize.SMALL, playerName, null, null, null, null, null,
                Boolean.TRUE, Boolean.FALSE, EMPIRES, Boolean.TRUE, Boolean.TRUE, null,
                Boolean.TRUE, Boolean.TRUE, TURN_SECONDS, null, null, null, null, null, null);
    }
}
