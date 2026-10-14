package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Строка списка игр, видимого всем, кто видит IP сервера — п. 3.1. */
public record GameSummaryDto(
        UUID id,
        String name,
        String status,
        String galaxySize,
        Integer widthParsecs,
        Integer heightParsecs,
        Integer starCount,
        Integer humanPlayers,
        Integer maxHumanPlayers,
        Integer totalPlayers,
        Integer turn,
        UUID hostPlayerId,
        /** Идут ли в партии случайные галактические события — п. 11.1. */
        Boolean galacticEvents,
        /** Победитель партии — п. 3; пусто, пока партия идёт. */
        UUID winnerPlayerId,
        /** Чем взята победа: покорением или Высшим советом; пусто, пока партия идёт. */
        String victoryKind,
        OffsetDateTime createdAt,
        /** Побеждает ли удержание Wardenhold — п. 3. */
        Boolean wardenholdVictory,
        /** Кто держит особую звезду; пусто — никто. */
        UUID wardenholdHolderPlayerId,
        /**
         * Сколько ходов держателю осталось до победы; пусто — звезду никто не держит. Число
         * считает сервер ({@code WardenholdRules}): правило счёта живёт в одном месте.
         */
        Integer wardenholdTurnsLeft,
        /** Собирается ли Высший совет — п. 3. */
        Boolean council,
        /** Подводится ли итог по могуществу — п. 3. */
        Boolean mightVictory,
        /** На каком ходу подводится итог по могуществу — настройка партии (пункт 24), 300 по умолчанию. */
        Integer mightVictoryTurn,
        /** Срок хода в секундах — backlog-promo, пункт 11; пусто — без срока. */
        Integer turnSeconds,
        /**
         * Когда истекает текущий ход; пусто — срока нет. Мгновение, а не «осталось секунд»:
         * клиент ведёт обратный отсчёт сам, и сводка, прочитанная минуту назад, не врёт.
         */
        OffsetDateTime turnDeadline,
        /**
         * Гостевые правила (признак {@code rich_start}): богатый старт, первые уровни по оплате,
         * наука вдвое — {@code GuestGameRules}. По нему клиент показывает памятку, что условия
         * партии не те, что в настоящей.
         */
        Boolean guestRules,
        /**
         * Партия под наблюдением администратора — field-backlog, пункт 197: он смотрел в ней
         * всю галактику при других людях. Клиент показывает это каждому красным оком.
         */
        Boolean adminWatched,
        /**
         * Ход, на котором СПРАШИВАЮЩИЙ признал в этой партии поражение (field-backlog, пункт 81);
         * пусто — не признавал. Заполняется только в «Ваших партиях»: партия для него окончена,
         * даже если идёт дальше без него, и меню предлагает итоги, а не возврат.
         */
        Integer yourConcededTurn,
        /** Чем эта партия запомнилась спрашивающему — только в «Ваших партиях» (пункт 109). */
        MineContextDto mine,
        /**
         * Партия подбора (field-backlog, пункт 65): когда она стартует сама, если за столом хотя бы
         * двое людей; пусто — обычная партия.
         */
        java.time.OffsetDateTime autoStartAt,
        /**
         * Партию можно смотреть со стороны — field-backlog, пункт 59: создатель разрешил зрителей,
         * и входящий за стол видит это до входа.
         */
        Boolean spectators,
        /** Особенность галактики — field-backlog, пункт 94 ({@code GalaxyFeature}); пусто — нет. */
        String galaxyFeature,
        /** Неделя галактики недели — пункт 146; пусто — обычная партия. */
        Integer weeklyWeek,
        /** Союзники, разделившие победу покорением с победителем, — пункт 145; пусто — победитель один. */
        java.util.List<UUID> coWinnerPlayerIds,
        /** Партию продолжили после победы — пункт 131: новых побед в ней не будет. */
        Boolean playedOn,
        /** Уровень сложности — пункт 9 ({@code AiSkill}); пусто — обычный. */
        String aiSkill,
        /** Тихая галактика — пункт 186: ИИ войну первым не начинает. */
        Boolean peacefulAi
) {

    /**
     * Контекст своей партии — field-backlog, пункт 109: по строке списка решают, куда вернуться.
     *
     * @param place          своё место по мощи среди всех империй на последнем замере
     * @param empires        сколько империй в партии
     * @param yourMove       ждёт ли партия ВАШЕГО хода (иначе — ход объявлен, ждём соперников)
     * @param lastEvent      последний главный момент летописи (пункт 34), на языке читателя
     * @param lastEventTurn  ход этого момента
     * @param lastActionAt   когда вы последний раз закончили ход или вошли
     */
    public record MineContextDto(Integer place, Integer empires, Boolean yourMove, String lastEvent,
                                 Integer lastEventTurn, java.time.OffsetDateTime lastActionAt) {
    }

    /** Та же сводка с контекстом своей партии. */
    public GameSummaryDto withMine(MineContextDto context) {
        return new GameSummaryDto(id, name, status, galaxySize, widthParsecs, heightParsecs, starCount,
                humanPlayers, maxHumanPlayers, totalPlayers, turn, hostPlayerId, galacticEvents,
                winnerPlayerId, victoryKind, createdAt, wardenholdVictory, wardenholdHolderPlayerId,
                wardenholdTurnsLeft, council, mightVictory, mightVictoryTurn, turnSeconds, turnDeadline,
                guestRules, adminWatched, yourConcededTurn, context, autoStartAt, spectators,
                galaxyFeature, weeklyWeek, coWinnerPlayerIds, playedOn, aiSkill, peacefulAi);
    }

    /** Та же сводка с отметкой признания спрашивающего. */
    public GameSummaryDto withConcededTurn(Integer turn) {
        return new GameSummaryDto(id, name, status, galaxySize, widthParsecs, heightParsecs, starCount,
                humanPlayers, maxHumanPlayers, totalPlayers, this.turn, hostPlayerId, galacticEvents,
                winnerPlayerId, victoryKind, createdAt, wardenholdVictory, wardenholdHolderPlayerId,
                wardenholdTurnsLeft, council, mightVictory, mightVictoryTurn, turnSeconds, turnDeadline,
                guestRules, adminWatched, turn, mine, autoStartAt, spectators,
                galaxyFeature, weeklyWeek, coWinnerPlayerIds, playedOn, aiSkill, peacefulAi);
    }
}
