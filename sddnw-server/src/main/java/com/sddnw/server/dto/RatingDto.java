package com.sddnw.server.dto;

import java.util.List;

/**
 * Рейтинг игроков — field-backlog, пункт 19: только партии с людьми, выводится из истории.
 *
 * @param value       рейтинг сейчас
 * @param games       сколько партий с людьми в нём
 * @param provisional предварительный: партий с людьми меньше пяти
 * @param delta       на сколько его сдвинула эта партия (сцена конца); пусто — не про партию
 * @param board       все, у кого есть рейтинг, сильнейшие первыми (экран истории); людей мало —
 *                    это список, а не таблица
 */
public record RatingDto(
        Integer value,
        Integer games,
        Boolean provisional,
        Integer delta,
        List<Line> board
) {

    /** Строка списка: имя записи, рейтинг, партий, вы ли это и знак раннего доступа (пункт 147). */
    public record Line(String name, Integer value, Integer games, Boolean you, Boolean earlyBird) {
    }
}
