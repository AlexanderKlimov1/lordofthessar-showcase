package com.sddnw.server.dto;

import java.util.UUID;

/**
 * Прогресс к каждому включённому пути к победе — field-backlog, пункты 51 и 11.
 * <p>
 * Градиент цели работает, только когда расстояние до неё видно. Свои числа — всегда, чужие —
 * сквозь туман войны: про знакомые империи (их население и мощь и так видны в «Инфо»), а
 * незнакомые названы только числом. Выключенный путь приходит пустым.
 *
 * @param council   совет: голоса — население
 * @param conquest  покорение: колонии остаются у одной империи
 * @param thessar   удержание особой звезды
 * @param might     итог по могуществу на последнем ходу
 * @param milestone ближайшая значимая веха одной строкой на языке читателя; пусто — вехи нет
 */
public record VictoryProgressDto(
        Council council,
        Conquest conquest,
        Thessar thessar,
        Might might,
        String milestone,
        /**
         * Финиш близко — field-backlog, пункт 56: чья победа (своя или чужая) в считанных ходах, строками
         * по одной на путь, с тем, что можно сделать. Пусто — до финиша далеко.
         */
        java.util.List<Finish> finish
) {

    /**
     * @param kind  путь к победе: THESSAR, COUNCIL, MIGHT, CONQUEST
     * @param yours ваша это победа или чужая
     * @param text  строка на языке читателя
     */
    public record Finish(String kind, Boolean yours, String text) {
    }

    /**
     * @param yourVotes    ваши голоса (население)
     * @param knownVotes   голоса ваши и знакомых империй
     * @param neededPercent сколько процентов всех голосов нужно — две трети
     * @param nextTurn     ход ближайшего созыва; пусто — совет не соберётся (мало живых империй)
     * @param unknownEmpires незнакомых империй: их голосов не видно
     */
    public record Council(Integer yourVotes, Integer knownVotes, Integer neededPercent, Integer nextTurn,
                          Integer unknownEmpires) {
    }

    /**
     * @param yourColonies    ваших колоний
     * @param knownRivals     знакомых соперников, у которых ещё есть колонии
     * @param unknownEmpires  незнакомых империй
     */
    public record Conquest(Integer yourColonies, Integer knownRivals, Integer unknownEmpires) {
    }

    /**
     * @param holderPlayerId кто держит звезду; пусто — никто
     * @param turnsLeft      сколько ходов держать до победы
     * @param holdTurns      сколько ходов держать всего
     */
    public record Thessar(UUID holderPlayerId, Integer turnsLeft, Integer holdTurns) {
    }

    /**
     * @param place     ваше место по мощи среди знакомых (1 — сильнейшая)
     * @param ranked    сколько империй в сравнении, считая вашу
     * @param turnsLeft сколько ходов до итога
     * @param turnLimit ход итога
     * @param pendingPlayerId итог ждёт согласия людей (09.10.2026): кого назвали сильнейшим; пусто — не ждёт
     * @param asked     спрашивают ли вас
     * @param yourAnswer ваш ответ — AGREE, REFUSE или пусто
     */
    public record Might(Integer place, Integer ranked, Integer turnsLeft, Integer turnLimit,
                        UUID pendingPlayerId, Boolean asked, String yourAnswer) {
    }
}
