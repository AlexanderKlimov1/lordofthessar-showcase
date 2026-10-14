package com.sddnw.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Сцена конца партии — field-backlog, пункт 33.
 * <p>
 * Конец обязан быть пиком: его вспоминают, решая, вернуться ли. Поэтому сцене отдаётся не
 * строка «победа: покорение», а всё, из чего складывается рассказ: кто и чем победил, почему
 * партия кончилась (словами и с числами — как у всякой потери), что получилось у самого
 * игрока и график мощи всех империй за всю партию.
 *
 * @param gameName    название партии
 * @param turn        ход, на котором партия кончилась
 * @param victoryKind путь к победе: CONQUEST, COUNCIL, WARDENHOLD или MIGHT
 * @param kindLabel   тот же путь словами, на языке запроса
 * @param winnerId    победившая империя; пусто у признавшего поражение в партии, которая
 *                    идёт дальше без него (пункт 81)
 * @param winnerName  её имя на языке читателя
 * @param winnerColor её знамя
 * @param won         победил ли спрашивающий
 * @param reason      почему партия кончилась — фраза с числами
 * @param place       место спрашивающего по мощи на последнем ходу (1 — первый)
 * @param empires     сколько империй было в партии
 * @param mine        что получилось у спрашивающего
 * @param history     мощь каждой империи по ходам: игра кончилась, и прятать соседей не от
 *                    кого — график показывает всех
 * @param chronicle   летопись главных моментов спрашивающего (пункт 34), по ходам
 * @param story       «как это было» (пункт 143): пять-семь сильнейших строк хроники по ходам
 * @param firsts      первые места партии по осям (пункт 116): только настоящие, из записанных чисел
 * @param hardship    какой трудной была победа (пункт 185), фразой с числами; пусто — не была
 * @param winnerPath  путь победителя по контрольным ходам (пункт 106); пусто — победителя нет
 * @param pastSelf    сравнение с лучшей своей партией той же настройки (пункт 115); пусто — не с чем
 * @param guest       гостевая ли запись у спрашивающего (сцена предлагает ей своё)
 */
public record GameEndingDto(
        String gameName,
        Integer turn,
        String victoryKind,
        String kindLabel,
        UUID winnerId,
        String winnerName,
        String winnerColor,
        Boolean won,
        String reason,
        Integer place,
        Integer empires,
        Achievements mine,
        List<EmpireLine> history,
        List<Moment> chronicle,
        List<Moment> story,
        List<FirstPlace> firsts,
        String hardship,
        WinnerPath winnerPath,
        PastSelfDto pastSelf,
        Boolean guest,
        /** Сыграть снова тем же составом — пункт 149; пусто — в партии не было других людей. */
        Rematch rematch,
        /** Ваша раса названием (пункт 98): своя раса — тем именем, что дал игрок. */
        String raceName,
        /** Свои проекты кораблей партии (пункт 98), лучший первым; пусто — своих не было. */
        List<OwnDesignDto> designs,
        /** Звания за трудное, которые принесла эта партия (пункт 41), кодами; пусто — ни одного. */
        List<String> titles,
        /** Доля среди похожих партий других игроков (пункт 117); пусто — рано или не с чем. */
        PeerShareDto peers,
        /** Рейтинг и сдвиг от этой партии (пункт 19); пусто — партия без других людей или своих партий мало. */
        RatingDto rating
) {

    /**
     * Новая партия тем же составом — field-backlog, пункт 149.
     *
     * @param started кто-то из участников её уже завёл: сцена предлагает войти, а не завести
     * @param byName  кто завёл
     * @param joined  вы за её столом уже сидите
     */
    public record Rematch(Boolean started, String byName, Boolean joined) {
    }

    /**
     * Путь победителя — пункт 106: проигравший видит не «вас обыграли», а путь, который можно
     * повторить. Партия кончилась, туман ничего не защищает.
     *
     * @param firstColonyTurn ход, на котором у победителя стало больше одной колонии
     * @param checkpoints     колонии, флот и технологии на контрольных ходах
     * @param wars            объявленных войн за партию
     * @param battles         боёв за партию
     * @param captured        взято колоний десантом и телепатией
     */
    public record WinnerPath(String name, String color, Boolean self, Integer firstColonyTurn,
                             List<Checkpoint> checkpoints, Integer wars, Integer battles, Integer captured) {
    }

    /** Состояние империи на контрольном ходу. */
    public record Checkpoint(Integer turn, Integer colonies, Integer fleetPower, Integer technologies) {
    }

    /**
     * Первое место на оси — пункт 116.
     *
     * @param axis    ось: POPULATION, PRODUCTION, RESEARCH, FLEET, TECHNOLOGIES, COLONIES,
     *                COLONIZED, BATTLES, CAPTURED, MONSTERS
     * @param holders кто держит (равные делят место)
     * @param value   число лидера
     * @param mine    среди держащих — спрашивающий
     */
    public record FirstPlace(String axis, List<Holder> holders, Integer value, Boolean mine) {
    }

    /** Держатель первого места: имя на языке читателя и знамя. */
    public record Holder(String name, String color, Boolean self) {
    }

    /**
     * Главный момент партии (field-backlog, пункт 34): ход, род ({@code MilestoneService}) и
     * текст на языке читателя.
     */
    public record Moment(Integer turn, String kind, String text) {
    }

    /**
     * Что получилось у игрока — «сперва то, что вышло» (пункт 33): даже проигравший видит
     * свой лучший миг, а не одну строку приговора.
     *
     * @param bestTurn         ход, на котором доля мощи игрока в галактике была наибольшей
     * @param bestSharePercent эта доля в процентах
     * @param peakColonies     наибольшее число колоний за партию
     * @param peakPopulationK  наибольшее население, в тысячах
     * @param technologies     изучено технологий к концу
     * @param founded          основано колоний
     * @param captured         взято колоний десантом и телепатией
     * @param lost             потеряно колоний
     * @param battles          боёв
     * @param wars             объявленных войн
     */
    public record Achievements(
            Integer bestTurn,
            Integer bestSharePercent,
            Integer peakColonies,
            Integer peakPopulationK,
            Integer technologies,
            Integer founded,
            Integer captured,
            Integer lost,
            Integer battles,
            Integer wars
    ) {
    }

    /** Линия мощи одной империи на графике конца партии. */
    public record EmpireLine(
            UUID playerId,
            String name,
            String color,
            Boolean self,
            Boolean winner,
            List<Point> points
    ) {
    }

    /** Мощь империи на ходу — та же мера, что первая линия графика «Инфо». */
    public record Point(Integer turn, Integer might) {
    }
}
