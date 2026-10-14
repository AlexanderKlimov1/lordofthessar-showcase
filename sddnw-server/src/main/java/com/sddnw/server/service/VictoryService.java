package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.VictoryKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чем кончается партия — п. 3.
 * <p>
 * В MOO II путей к победе три: покорить галактику, быть избранным Высшим советом и
 * разбить антаран у Антареса. Здесь их четыре — покорение, совет, удержание Wardenhold
 * ({@link WardenholdRules}) и итог по могуществу на трёхсотом ходу
 * ({@link MightVictoryRules}). Все, кроме покорения, — признаки партии, которые окно новой
 * игры включает по умолчанию. Wardenhold стоит на месте антаранской победы — это цель у
 * особой звезды, которую видно с первого хода; итог по могуществу лечит затянутый финал.
 * <p>
 * <b>Совет однажды убирали и вернули с оговорками.</b> Он даёт победу за две трети голосов
 * галактики, а голос весит как население: в партии из двух-трёх империй такое большинство
 * набиралось само собой, и партия обрывалась голосованием на двадцать пятом ходу, не
 * начавшись, — балансировке это било по рукам, а сквозному прогону мешало играть сценарии
 * на двоих. Теперь совет собирается не раньше пятидесятого хода, не чаще раза в двадцать
 * пять и только при трёх живых империях и более ({@link CouncilRules}), а при четырёх и
 * более две трети голосов значат, что победитель и так забрал галактику.
 * <p>
 * Антаран в игре нет вовсе — это <i>точка расширения</i>: появится Антарес, сюда встанет
 * ещё одна проверка, а всё остальное не изменится.
 * <p>
 * <b>Покорение.</b> Империя осталась в галактике одна: у остальных не осталось ни одной
 * колонии. Флот без колоний империей не считается — восстановиться ему негде.
 */
@Service
public class VictoryService {

    private static final Logger log = LoggerFactory.getLogger(VictoryService.class);

    private final CouncilService councilService;
    private final EmpireInfoService empireInfoService;

    /** История партий (пункт 18); пусто в проверках, которым она ни к чему. */
    private final GameRecordService gameRecords;

    /**
     * Союзы — для общей победы (field-backlog, пункт 145). Полем: службу собирают руками юнит-тесты,
     * и без отношений они проверяют прежнее правило «колонии у одного».
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sddnw.server.repository.DiplomacyRelationRepository relations;

    public VictoryService(CouncilService councilService, EmpireInfoService empireInfoService) {
        this(councilService, empireInfoService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public VictoryService(CouncilService councilService, EmpireInfoService empireInfoService,
                          GameRecordService gameRecords) {
        this.gameRecords = gameRecords;
        this.councilService = councilService;
        this.empireInfoService = empireInfoService;
    }

    /**
     * Проверяет, не кончилась ли партия, и записывает победителя.
     * <p>
     * Вызывается последней фазой хода: к этому моменту сосчитано всё — захваты, колонии
     * и население, — и голосовать есть чем.
     */
    public void check(TurnContext context) {
        GameEntity game = context.game();
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }
        // Продолженная после победы партия (пункт 131) новых побед не ищет: победа уже записана.
        if (Boolean.TRUE.equals(game.getPlayedOn())) {
            return;
        }

        Map<UUID, Integer> population = populationByPlayer(context);
        PlayerEntity conqueror = conqueror(context, population);
        if (conqueror != null) {
            // Покорение посреди открытого совета совет и закрывает: голосовать больше не о
            // чем, а спрашивать людей о выборах в оконченной партии было бы враньём сцены.
            game.setCouncilOpen(Boolean.FALSE);
            finish(context, conqueror, VictoryKind.CONQUEST);
            return;
        }
        List<PlayerEntity> alliance = alliedConquest(context, population);
        if (!alliance.isEmpty()) {
            game.setCouncilOpen(Boolean.FALSE);
            finishAllied(context, alliance);
            return;
        }

        // Удержание особой звезды — до совета: двадцать ходов держали на глазах у всех, и
        // голосование того же хода не должно отнимать заработанное.
        PlayerEntity warden = wardenhold(context);
        if (warden != null) {
            game.setCouncilOpen(Boolean.FALSE);
            finish(context, warden, VictoryKind.WARDENHOLD);
            return;
        }

        // Совет собирается ПОСЛЕ проверки покорения: когда колонии остались у одного,
        // голосовать не о чем, а объявлять победителя дважды незачем.
        PlayerEntity elected = councilService.hold(context, population);
        if (elected != null) {
            finish(context, elected, VictoryKind.COUNCIL);
            return;
        }

        // Итог по могуществу — последним: он нужен ровно тогда, когда ни один другой путь
        // к трёхсотому ходу не сработал (MightVictoryRules).
        PlayerEntity strongest = mightVerdict(context, population);
        if (strongest != null) {
            game.setCouncilOpen(Boolean.FALSE);
            finish(context, strongest, VictoryKind.MIGHT);
        }
    }

    /**
     * Итог по могуществу — п. 3, {@link MightVictoryRules}: на сроке победа отдаётся
     * сильнейшей живой империи. До срока — напоминания всем, за полсотни, десять и один ход:
     * срок, о котором не знали, — это обрыв, а не цель.
     * <p>
     * <b>С согласия людей</b> (09.10.2026, {@link MightConsentService}): есть за столом другие люди —
     * на сроке они спрошены, и итог подводится в конце следующего хода; один отказ снимает путь.
     */
    private PlayerEntity mightVerdict(TurnContext context, Map<UUID, Integer> population) {
        GameEntity game = context.game();
        if (!Boolean.TRUE.equals(game.getMightVictory())) {
            return null;
        }
        if (game.getMightPendingPlayerId() != null) {
            return mightAnswered(context, population);
        }
        int limit = MightVictoryRules.limit(game);
        if (!MightVictoryRules.due(context.turn(), limit) && !earlyVerdict(context, population, limit)) {
            if (Boolean.TRUE.equals(MightVictoryRules.remind(context.turn(), limit))) {
                Integer left = limit - context.turn();
                for (PlayerEntity player : context.players()) {
                    context.report().add(player.getId(), "MIGHT_VERDICT",
                            new MessageKey("turn.might.soon", left));
                }
            }
            return null;
        }
        PlayerEntity strongest = MightVictoryRules.winner(empireInfoService.mightByPlayer(context),
                context.players(), population.keySet());
        if (strongest == null || !MightConsentService.anyoneAsked(context.players(), strongest.getId())) {
            return strongest;
        }
        game.setMightPendingPlayerId(strongest.getId());
        game.setMightPendingTurn(context.turn());
        game.setMightLeadPlayerId(null);
        game.setMightLeadSinceTurn(null);
        for (PlayerEntity player : context.players()) {
            player.setMightAnswer(null);
            context.report().add(player.getId(), "MIGHT_VERDICT", new MessageKey("turn.might.ask", strongest.getName()));
        }
        return null;
    }

    /**
     * Досрочный итог — field-backlog, пункт 80: сильнейший держит огромный отрыв
     * ({@link MightVictoryRules#crushing}) {@link MightVictoryRules#EARLY_HOLD_TURNS} ходов подряд.
     * Отрыв называется всем в тот ход, когда начался, — галактика знает, сколько у неё времени
     * ответить; оборвался — счёт сбрасывается молча, как у удержания Тессара.
     *
     * @return пора ли подводить итог (дальше — тот же путь, что на сроке, с согласием людей)
     */
    private boolean earlyVerdict(TurnContext context, Map<UUID, Integer> population, int limit) {
        GameEntity game = context.game();
        if (context.turn() < MightVictoryRules.earlyFrom(limit)) {
            return false;
        }
        Map<UUID, Integer> might = empireInfoService.mightByPlayer(context);
        PlayerEntity leader = MightVictoryRules.winner(might, context.players(), population.keySet());
        if (leader == null || !MightVictoryRules.crushing(might, leader, context.players(), population.keySet())) {
            game.setMightLeadPlayerId(null);
            game.setMightLeadSinceTurn(null);
            return false;
        }
        if (!leader.getId().equals(game.getMightLeadPlayerId())) {
            game.setMightLeadPlayerId(leader.getId());
            game.setMightLeadSinceTurn(context.turn());
            for (PlayerEntity player : context.players()) {
                context.report().add(player.getId(), "MIGHT_VERDICT", new MessageKey("turn.might.lead",
                        leader.getName(), MightVictoryRules.EARLY_RATIO, MightVictoryRules.EARLY_HOLD_TURNS));
            }
            return false;
        }
        return context.turn() - game.getMightLeadSinceTurn() + 1 >= MightVictoryRules.EARLY_HOLD_TURNS;
    }

    /**
     * Итог, ждавший согласия: подводится в конце хода, следующего за вопросом. Хоть один отказ —
     * путь снимается (партия идёт дальше иными путями), иначе победа сильнейшему, названному на
     * сроке, — если он ещё жив; нет — сильнейшему из живых сейчас.
     */
    private PlayerEntity mightAnswered(TurnContext context, Map<UUID, Integer> population) {
        GameEntity game = context.game();
        if (context.turn() <= game.getMightPendingTurn()) {
            return null;
        }
        UUID candidate = game.getMightPendingPlayerId();
        boolean refused = context.players().stream()
                .anyMatch(player -> MightConsentService.asked(player, candidate)
                        && MightConsentService.REFUSE.equals(player.getMightAnswer()));
        game.setMightPendingPlayerId(null);
        game.setMightPendingTurn(null);
        context.players().forEach(player -> player.setMightAnswer(null));
        if (refused) {
            game.setMightVictory(Boolean.FALSE);
            for (PlayerEntity player : context.players()) {
                context.report().add(player.getId(), "MIGHT_VERDICT", new MessageKey("turn.might.refused"));
            }
            return null;
        }
        return context.players().stream()
                .filter(player -> player.getId().equals(candidate) && population.containsKey(candidate))
                .findFirst()
                .orElseGet(() -> MightVictoryRules.winner(empireInfoService.mightByPlayer(context),
                        context.players(), population.keySet()));
    }

    /**
     * Победа удержанием Wardenhold — п. 3, {@link WardenholdRules}.
     * <p>
     * Ведёт счёт удержания в самой партии (держатель и ход начала) и рассказывает о нём
     * ВСЕМ: двадцать ходов — это время ответить, а ответить можно только зная, что пора.
     * Держатель сменился или звезду потеряли — об этом тоже говорится, иначе игрок видел
     * бы, как счёт соседа просто пропал.
     *
     * @return победитель, если звезду продержали положенное число ходов
     */
    private PlayerEntity wardenhold(TurnContext context) {
        GameEntity game = context.game();
        if (!Boolean.TRUE.equals(game.getWardenholdVictory())) {
            return null;
        }
        StarSystemEntity special = context.systems().stream()
                .filter(system -> Boolean.TRUE.equals(system.getSpecial()))
                .findFirst()
                .orElse(null);
        if (special == null) {
            return null;
        }
        UUID holderId = WardenholdRules.holder(special.getPlanets());
        UUID previous = game.getWardenholdHolderPlayerId();
        if (holderId == null) {
            if (previous != null) {
                game.setWardenholdHolderPlayerId(null);
                game.setWardenholdSinceTurn(null);
                PlayerEntity lost = playerById(context, previous);
                for (PlayerEntity player : context.players()) {
                    context.report().add(player.getId(), "WARDENHOLD",
                            new MessageKey("turn.wardenhold.lost",
                                    lost == null ? "" : lost.getName(), special.getName()),
                            special.getId(), null);
                }
            }
            return null;
        }
        if (!holderId.equals(previous) || game.getWardenholdSinceTurn() == null) {
            game.setWardenholdHolderPlayerId(holderId);
            game.setWardenholdSinceTurn(context.turn());
        }
        PlayerEntity holder = playerById(context, holderId);
        if (holder == null) {
            return null;
        }
        Integer held = WardenholdRules.heldTurns(game.getWardenholdSinceTurn(), context.turn());
        Integer left = WardenholdRules.turnsLeft(game.getWardenholdSinceTurn(), context.turn());
        if (left == 0) {
            return holder;
        }
        if (Boolean.TRUE.equals(WardenholdRules.remind(held))) {
            for (PlayerEntity player : context.players()) {
                context.report().add(player.getId(), "WARDENHOLD",
                        player.getId().equals(holderId)
                                ? new MessageKey("turn.wardenhold.yours", special.getName(), left)
                                : new MessageKey("turn.wardenhold.theirs", holder.getName(), special.getName(), left),
                        special.getId(), null);
            }
        }
        return null;
    }

    private PlayerEntity playerById(TurnContext context, UUID playerId) {
        return context.players().stream()
                .filter(player -> player.getId().equals(playerId))
                .findFirst()
                .orElse(null);
    }

    /** Население каждой империи: по нему считаются и одиночество, и голоса совета. */
    private Map<UUID, Integer> populationByPlayer(TurnContext context) {
        Map<UUID, Integer> population = new HashMap<>();
        for (PlanetEntity colony : context.colonies()) {
            // Список колоний взят на начало хода: стёртая бомбёжкой колония к концу хода ничья (14.10.2026 — без
            // этой оговорки ничья колония считалась «империей» и роняла ход: NullPointerException в союзном покорении).
            if (colony.getOwnerPlayerId() == null) {
                continue;
            }
            population.merge(colony.getOwnerPlayerId(), colony.getPopulation(), Integer::sum);
        }
        return population;
    }

    /**
     * Победа покорением: колонии остались у одной империи.
     * <p>
     * Партия на одного не кончается никогда: считать покорителем единственного участника
     * значило бы объявлять победу на первом же ходу.
     */
    private PlayerEntity conqueror(TurnContext context, Map<UUID, Integer> population) {
        if (context.players().size() < 2 || population.size() != 1) {
            return null;
        }
        UUID lastStanding = population.keySet().iterator().next();
        return context.players().stream()
                .filter(player -> player.getId().equals(lastStanding))
                .findFirst()
                .orElse(null);
    }

    /**
     * Покорение союзом — field-backlog, пункт 145, отступление от MOO II (решение хозяина проекта
     * 08.10.2026): колонии остались у нескольких империй, но каждая пара из них связана союзом
     * ({@code ALLIANCE}), — побеждают все. Без этого кооперативная партия с другом против ИИ к
     * концу превращалась в партию друг против друга. Совет, удержание Тессара и итог по
     * могуществу остаются единоличными.
     * <p>
     * Отношения спрашиваются одной выборкой и лишь когда хоть одна империя уже пала: в начале
     * партии союзу побеждать некого, а выборка изнутри посчитанного хода дорога.
     *
     * @return союзники-победители, первым — самый населённый (он и запишется победителем партии);
     *         пусто — покорения союзом нет
     */
    private List<PlayerEntity> alliedConquest(TurnContext context, Map<UUID, Integer> population) {
        if (relations == null || population.size() < 2 || population.size() >= context.players().size()) {
            return List.of();
        }
        java.util.Set<String> allied = new java.util.HashSet<>();
        for (var relation : relations.findAllByPlayerIdIn(population.keySet())) {
            if (relation.getTreaties().contains(com.sddnw.server.domain.enums.DiplomacyTreaty.ALLIANCE)) {
                allied.add(relation.getPlayerId() + ":" + relation.getOtherPlayerId());
            }
        }
        List<UUID> survivors = new java.util.ArrayList<>(population.keySet());
        for (UUID first : survivors) {
            for (UUID second : survivors) {
                if (!first.equals(second) && !allied.contains(first + ":" + second)) {
                    return List.of();
                }
            }
        }
        return context.players().stream()
                .filter(player -> population.containsKey(player.getId()))
                .sorted(java.util.Comparator.comparing((PlayerEntity player) -> -population.get(player.getId()))
                        .thenComparing(PlayerEntity::getSlot))
                .toList();
    }

    /** Конец партии победой союза: победитель — первый, прочие союзники — рядом (пункт 145). */
    private void finishAllied(TurnContext context, List<PlayerEntity> alliance) {
        PlayerEntity winner = alliance.get(0);
        context.game().setCoWinners(alliance.subList(1, alliance.size()).stream()
                .map(player -> player.getId().toString())
                .collect(java.util.stream.Collectors.joining(",")));
        String names = alliance.stream().map(PlayerEntity::getName)
                .collect(java.util.stream.Collectors.joining(", "));
        java.util.Set<UUID> winners = alliance.stream().map(PlayerEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (PlayerEntity player : context.players()) {
            if (winners.contains(player.getId())) {
                context.report().add(player.getId(), "VICTORY", new MessageKey("turn.victory.alliedWith", names));
            }
        }
        finish(context, winner, VictoryKind.CONQUEST, winners, names);
    }

    /** Записывает конец партии: дальше ходов в ней нет. */
    private void finish(TurnContext context, PlayerEntity winner, VictoryKind kind) {
        finish(context, winner, kind, java.util.Set.of(winner.getId()), winner.getName());
    }

    private void finish(TurnContext context, PlayerEntity winner, VictoryKind kind,
                        java.util.Set<UUID> winners, String winnerNames) {
        GameEntity game = context.game();
        game.setStatus(GameStatus.FINISHED);
        game.setWinnerPlayerId(winner.getId());
        game.setVictoryKind(kind);
        game.setFinishedAt(OffsetDateTime.now());

        for (PlayerEntity player : context.players()) {
            context.report().add(player.getId(), "VICTORY",
                    winners.contains(player.getId())
                            ? new MessageKey("turn.victory.won", kind)
                            : new MessageKey("turn.victory.lost", winnerNames, kind));
        }
        log.info("Партия {} окончена на ходу {}: победила империя {} ({})",
                game.getName(), context.turn(), winner.getName(), kind.getLabel());
        // История своих партий (пункт 18): людям, кто ещё за столом; признавшие уже записаны.
        if (gameRecords != null) {
            gameRecords.record(game, context.players(), context.players().stream()
                    .filter(player -> player.getPlayerType() == com.sddnw.server.domain.enums.PlayerType.HUMAN && player.getConcededTurn() == null)
                    .toList(), winner.getId(), kind.name(), context.turn(), null);
        }
    }
}
