package com.sddnw.server.service;

import com.sddnw.server.domain.BuildingEffects;
import com.sddnw.server.domain.entity.DiplomacyProposalEntity;
import com.sddnw.server.domain.entity.DiplomacyRelationEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.PlayerTechnologyEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.AiPersonality;
import com.sddnw.server.domain.enums.BuildingEffectType;
import com.sddnw.server.domain.enums.DiplomacyAction;
import com.sddnw.server.domain.enums.DiplomacyStance;
import com.sddnw.server.domain.enums.DiplomacyTreaty;
import com.sddnw.server.dto.DiplomacyActionRequest;
import com.sddnw.server.dto.DiplomacyRelationDto;
import com.sddnw.server.dto.TechTradeDto;
import com.sddnw.server.domain.enums.FuelTech;
import com.sddnw.server.repository.DiplomacyProposalRepository;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.HashSet;

/**
 * Дипломатия — п. 15.
 * <p>
 * Империи, которые ещё не встретились, друг о друге не знают вовсе: отношений между ними
 * нет, и в списке дипломатии их не видно.
 * <p>
 * <b>Знакомятся двумя путями, и главный из них — дальность.</b> В MOO II контакт
 * возникает, «когда одна из сторон в состоянии послать корабли без дополнительных баков
 * в системы, занятые другой»: лететь никуда не нужно, достаточно оказаться в пределах
 * досягаемости — {@link #contactByRange}. Второй путь — разведка чужой системы
 * ({@link ExplorationService}): увидел чужую колонию, значит, познакомился с хозяином.
 * Он остаётся для случая, когда флот дотянулся дальше собственной дальности империи,
 * прыгая от колонии к колонии.
 * <p>
 * Знакомство начинается с нейтралитета и доверия в половину шкалы. Дальше — договоры
 * MOO II: торговый и исследовательский поднимают доход и науку обеих сторон, пакт о
 * ненападении запрещает войну, союз добавляет к нему общую разведку. Мир и войну стороны
 * объявляют друг другу, дань требуют, договоры разрывают — каждое действие сдвигает
 * доверие.
 * <p>
 * Состояние войны и мира общее — сервис держит его одинаковым в обеих строках, как и
 * набор договоров; доверие у каждой стороны своё: одна империя может считать другую
 * другом, пока та готовит удар.
 * <p>
 * <b>У каждой империи ИИ свой характер</b> ({@link com.sddnw.server.domain.enums.AiPersonality})
 * — шесть характеров MOO II, от беспощадного до миролюбивого. Он решает две вещи: насколько
 * сильно империя принимает к сердцу хорошее и плохое ({@link #gainTrust}, {@link #loseTrust})
 * и насколько охотно соглашается ({@link #agrees}, {@link #treatyBias}). Ксенофоб «вдвое
 * слабее принимает хорошее и вдвое сильнее плохое», благородный не прощает ударов без
 * повода, миролюбивый быстрее идёт на мир. Сам ИИ ведёт дипломатию в
 * {@code AiDiplomacyService} — там же и решение о войне.
 * <p>
 * <b>Обмен и подарки</b> (п. 15) — то, чем в MOO II занимаются чаще всего остального.
 * Обмен технологиями требует согласия и подчиняется правилу оригинала: ИИ соглашается
 * только на выгодную себе сделку, то есть когда получает не дешевле, чем отдаёт.
 * Подарок согласия не требует вовсе — его не отвергают, — и поднимает доверие.
 */
@Service
public class DiplomacyService {

    private static final Logger log = LoggerFactory.getLogger(DiplomacyService.class);

    /** Доверие при первом знакомстве — середина шкалы. */
    public static final int INITIAL_TRUST = 50;

    /** С какого доверия принимают мир. */
    public static final int PEACE_TRUST = 50;

    /** Насколько доверие меняется от согласия, отказа, разрыва и объявления войны. */
    public static final int AGREEMENT_BONUS = 15;
    private static final int REFUSAL_PENALTY = 10;
    public static final int BREAK_PENALTY = 25;
    public static final int WAR_PENALTY = 40;

    /** Доверие, ниже которого дань платят из страха, и выше которого — по дружбе. */
    private static final int TRIBUTE_FEAR_TRUST = 25;
    private static final int TRIBUTE_FRIEND_TRUST = 75;

    /** Какую часть казны отдаёт данник и сколько это стоит его доверию. */
    private static final int TRIBUTE_PERCENT = 25;
    private static final int TRIBUTE_TRUST_PENALTY = 10;

    /**
     * С какого доверия соглашаются на обмен технологиями.
     * <p>
     * Ниже мира: обмен выгоден обеим сторонам и на войну не влияет, поэтому торгуют
     * знанием и те, кто мириться ещё не готов. Реконструкция: в MOO II порог не назван,
     * зато названо главное правило — ИИ соглашается лишь на выгодную себе сделку,
     * и оно вынесено в {@link #agreesToExchange}.
     */
    private static final int EXCHANGE_TRUST = 35;

    /**
     * Сколько доверия приносит подарок — п. 15.
     * <p>
     * Считается от размера подарка относительно казны дарящего: отдать половину казны
     * значит куда больше, чем бросить монету со стола. Потолок не даёт купить дружбу
     * одним переводом — в MOO II подарки располагают, но не заменяют политику.
     */
    private static final int GIFT_TRUST_MAX = 20;

    /** Доверие за подаренную технологию: знание дороже денег, и помнят его дольше. */
    private static final int GIFT_TECH_TRUST = 25;

    /**
     * На сколько характер двигает планку согласия — п. 15.
     * <p>
     * <i>Реконструкция:</i> MOO II называет склонности («миролюбивый дорожит миром»,
     * «ксенофоб не доверяет никому»), но не числа. Пятнадцать очков — заметно, но за
     * характер не решает: договор всё равно надо заслужить.
     */
    private static final int TREATY_BIAS = 15;

    private final DiplomacyRelationRepository relationRepository;
    private final TechSightService techSight;
    private final EmpireActivityService activity;
    private final Messages messages;
    private final PlayerRepository playerRepository;
    private final RaceService raceService;
    private final PlayerRoster playerRoster;
    private final PlayerEventService playerEvents;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final ResearchCatalog researchCatalog;
    private final LeaderBonusService leaderBonuses;

    /** Хроника (пункт 138): в неё ложатся обиды игрока, их сосед и вспоминает вслух (пункт 139). */
    private final MilestoneService milestones;

    /** Предложения, ждущие ответа человека (field-backlog, пункт 182). */
    private final DiplomacyProposalRepository proposalRepository;

    /** Кто за столом: человеку, которого ведёт ИИ, предложение не шлют — решает доверие. */
    private final PresenceService presence;

    public DiplomacyService(Messages messages,
                            DiplomacyRelationRepository relationRepository,
                            PlayerRepository playerRepository,
                            RaceService raceService,
                            PlayerRoster playerRoster,
                            PlayerEventService playerEvents,
                            PlayerTechnologyRepository playerTechnologyRepository,
                            ResearchCatalog researchCatalog,
                            LeaderBonusService leaderBonuses,
                            EmpireActivityService activity,
                            TechSightService techSight,
                            MilestoneService milestones,
                            DiplomacyProposalRepository proposalRepository,
                            PresenceService presence) {
        this.proposalRepository = proposalRepository;
        this.presence = presence;
        this.techSight = techSight;
        this.milestones = milestones;
        this.leaderBonuses = leaderBonuses;
        this.activity = activity;
        this.messages = messages;
        this.relationRepository = relationRepository;
        this.playerRepository = playerRepository;
        this.raceService = raceService;
        this.playerRoster = playerRoster;
        this.playerEvents = playerEvents;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.researchCatalog = researchCatalog;
    }

    /** С кем игрок знаком, на каких условиях и по каким договорам — п. 15. */
    public List<DiplomacyRelationDto> relations(PlayerEntity player) {
        // Знакомство на паузе (п. 15: потеряно по дальности) экрану не показывается: сосед
        // для игрока снова незнакомец, пока знакомство не восстановится.
        List<DiplomacyRelationEntity> relations = relationRepository.findAllByPlayerId(player.getId()).stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                .toList();
        if (relations.isEmpty()) {
            return List.of();
        }

        Map<UUID, PlayerEntity> others = playerRepository.findAllById(
                        relations.stream().map(DiplomacyRelationEntity::getOtherPlayerId).toList()).stream()
                .collect(Collectors.toMap(PlayerEntity::getId, Function.identity()));
        Map<String, String> raceNames = playerRoster.raceNames();
        // Доверие соседей к игроку — их собственные строки: по ним они и решают.
        Map<UUID, Integer> theirTrust = relationRepository.findAllByOtherPlayerId(player.getId())
                .stream()
                .collect(Collectors.toMap(DiplomacyRelationEntity::getPlayerId,
                        DiplomacyRelationEntity::getTrust, (first, second) -> first));

        return relations.stream()
                .map(relation -> toDto(relation, others.get(relation.getOtherPlayerId()), raceNames,
                        theirTrust.get(relation.getOtherPlayerId()), null, player))
                .sorted((first, second) -> first.metTurn().compareTo(second.metTurn()))
                .toList();
    }

    /**
     * Знакомство по дальности — п. 15, ЕДИНСТВЕННЫЙ путь контакта, как в MOO II.
     * <p>
     * Правило оригинала дословно (StrategyWiki, «Diplomacy and intelligence»): «Contact is
     * made between 2 empires when either of them is in a position to send ships without
     * Extended Fuel Tanks into systems occupied by the other». Возникает оно, когда кто-то
     * занимает новую систему — колонией ИЛИ ЗАСТАВОЙ — или изучает лучшее топливо, поэтому
     * проверяется каждый ход по положению на его конец.
     * <p>
     * <b>Занятая система — это колония или застава.</b> Прежде дальность мерилась только от
     * колоний с жителями: заставы в правило не попадали, хотя оригинал меряет и от них, а
     * заставы для того и ставят — тянуть дальность к соседу.
     * <p>
     * <b>Знакомство теряется</b> — «if someone destroys or conquers the last colony or
     * Outpost in a system, and no other systems occupied by either empire are within either
     * empire's ships' range»; и восстанавливается тем же путём, что возникло. Дальность от
     * топлива не убывает никогда, значит «знакомы» здесь ровно то же, что «дотягиваются»:
     * условие одно на оба направления. Потерянное знакомство ставится на ПАУЗУ, а не
     * стирается (решение хозяина проекта): война, договоры и доверие хранятся в строке
     * отношений и вернутся вместе со знакомством — первоисточник о них молчит.
     * <p>
     * Разведка знакомства больше не даёт: флот, прилетевший к чужой колонии, — не путь
     * контакта в оригинале. Достаточно одной стороны: если сосед дотягивается до меня,
     * знакомы мы оба.
     */
    @Transactional
    public void contactByRange(TurnContext context) {
        List<PlayerEntity> players = context.players();
        if (players.size() < 2) {
            return;
        }

        // Занятые системы — с колонией или заставой, — по разу на игрока: пар в галактике на
        // восьмерых двадцать восемь, и ходить за этим на каждую пару значило бы читать одно
        // и то же. Список колоний хода заставы не держит (у них нет жителей), поэтому
        // системы перебираются сами.
        Map<UUID, List<StarSystemEntity>> occupied = new LinkedHashMap<>();
        players.forEach(player -> occupied.put(player.getId(), new ArrayList<>()));
        for (StarSystemEntity system : context.systems()) {
            system.getPlanets().stream()
                    .map(PlanetEntity::getOwnerPlayerId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .forEach(owner -> {
                        List<StarSystemEntity> mine = occupied.get(owner);
                        if (mine != null) {
                            mine.add(system);
                        }
                    });
        }
        // Изученное — одной выборкой на всех: дальность зависит от топлива, и баки в неё
        // не входят (FuelTech.baseRangeParsecs) — правило оригинала дословно.
        Map<UUID, Set<String>> technologies = technologiesOf(players);
        Map<UUID, Integer> rangeParsecs = new HashMap<>();
        for (PlayerEntity player : players) {
            rangeParsecs.put(player.getId(),
                    FuelTech.baseRangeParsecs(technologies.getOrDefault(player.getId(), Set.of())));
        }
        // Отношения — одной выборкой: запросом на пару это двадцать восемь походов в базу за
        // ход, и каждый из них сбрасывал туда же всё, что ход успел изменить.
        Map<String, DiplomacyRelationEntity> relations = new HashMap<>();
        relationRepository.findAllByPlayerIdIn(players.stream().map(PlayerEntity::getId).toList())
                .forEach(relation -> relations.put(
                        relation.getPlayerId() + ":" + relation.getOtherPlayerId(), relation));

        for (int i = 0; i < players.size(); i++) {
            for (int j = i + 1; j < players.size(); j++) {
                PlayerEntity first = players.get(i);
                PlayerEntity second = players.get(j);
                Integer reach = Math.max(rangeParsecs.get(first.getId()), rangeParsecs.get(second.getId()));
                boolean inReach = Boolean.TRUE.equals(
                        withinReach(occupied.get(first.getId()), occupied.get(second.getId()), reach));
                DiplomacyRelationEntity mine = relations.get(first.getId() + ":" + second.getId());
                DiplomacyRelationEntity theirs = relations.get(second.getId() + ":" + first.getId());

                if (mine == null || theirs == null) {
                    if (inReach && Boolean.TRUE.equals(meet(first, second, context.turn()))) {
                        // Первое знакомство — первая встреча с механикой (пункт 93); юнит-тесты
                        // собирают службу без счётчика.
                        if (activity != null) {
                            activity.record(context.game().getId(), first.getId(), EmpireActivityService.CONTACT);
                            activity.record(context.game().getId(), second.getId(), EmpireActivityService.CONTACT);
                        }
                        announce(context, first, second, "turn.diplomacy.met");
                    }
                    continue;
                }
                boolean inContact = Boolean.TRUE.equals(mine.getInContact());
                if (inReach == inContact) {
                    continue;
                }
                mine.setInContact(inReach);
                theirs.setInContact(inReach);
                relationRepository.saveAll(List.of(mine, theirs));
                announce(context, first, second, inReach ? "turn.diplomacy.regained" : "turn.diplomacy.lost");
                log.info("Знакомство империй {} и {} {}", first.getName(), second.getName(),
                        inReach ? "восстановлено" : "потеряно");
            }
        }
    }

    /** Дотягивается ли хоть одна занятая система одной империи до системы другой. */
    private Boolean withinReach(List<StarSystemEntity> mine, List<StarSystemEntity> theirs,
                                Integer reachParsecs) {
        double limit = (double) reachParsecs * reachParsecs;
        for (StarSystemEntity from : mine) {
            for (StarSystemEntity to : theirs) {
                double dx = from.getXParsec() - to.getXParsec();
                double dy = from.getYParsec() - to.getYParsec();
                // Сравниваем квадраты: корень здесь не нужен, а на восьми империях с
                // десятками колоний это единственная горячая точка знакомства.
                if (dx * dx + dy * dy <= limit) {
                    return Boolean.TRUE;
                }
            }
        }
        return Boolean.FALSE;
    }

    /** Обе стороны узнают о знакомстве (и о его потере) в итогах хода: событие касается каждой. */
    private void announce(TurnContext context, PlayerEntity first, PlayerEntity second, String key) {
        context.report().add(first.getId(), "DIPLOMACY", new MessageKey(key, second.getName()));
        context.report().add(second.getId(), "DIPLOMACY", new MessageKey(key, first.getName()));
    }

    /** Изученное участниками партии одной выборкой — по нему считается дальность кораблей. */
    private Map<UUID, Set<String>> technologiesOf(List<PlayerEntity> players) {
        return playerTechnologyRepository
                .findAllByPlayerIdIn(players.stream().map(PlayerEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(
                        technology -> technology.getPlayerId(),
                        Collectors.mapping(technology -> technology.getOptionCode(),
                                Collectors.toSet())));
    }

    /**
     * Действие дипломатии — п. 15.
     * <p>
     * Согласия другой стороны требуют мир, договоры и дань; война и разрыв договора —
     * решение одной стороны: напасть и предать можно всегда.
     * <p>
     * <b>Конфликт не откатывает транзакцию</b> ({@code noRollbackFor}). Отсюда ходит не
     * только игрок: этот же метод зовёт {@link AiDiplomacyService} из фазы конца хода, и
     * зовёт внутри той самой транзакции, в которой считается весь ход. Обычный
     * {@link ConflictException} пометил бы её rollback-only, и невозможное предложение
     * соседа (договор с отталкивающей расой, война при действующем пакте) роняло бы
     * пересчёт хода целиком — так и вышло, пока пометки здесь не было. Откатывать при этом
     * нечего: конфликт всегда бросается до первой правки, проверками в начале действия.
     */
    @Transactional(noRollbackFor = ConflictException.class)
    public DiplomacyRelationDto act(PlayerEntity player, DiplomacyActionRequest request, Integer turn) {
        DiplomacyRelationEntity mine = requireRelation(player.getId(), request.targetPlayerId());
        DiplomacyRelationEntity theirs = requireRelation(request.targetPlayerId(), player.getId());
        PlayerEntity other = playerRepository.findById(request.targetPlayerId())
                .orElseThrow(() -> new NotFoundException("empire.notFound", request.targetPlayerId()));

        requireNotRepulsive(player, other, request.action());

        Consent consent = asksHuman(player, other, request.action()) ? Consent.ASK : Consent.TRUST;
        if (consent == Consent.ASK && Boolean.TRUE.equals(proposalRepository
                .existsByFromPlayerIdAndToPlayerIdAndAction(player.getId(), other.getId(), request.action()))) {
            throw new ConflictException("diplomacy.proposalPending", other.getName());
        }
        MessageKey answer = perform(player, other, mine, theirs, request, turn, consent);
        if (answer == null) {
            propose(player, other, request, turn);
            answer = new MessageKey("diplomacy.proposal.sent", other.getName());
            return toDto(mine, other, playerRoster.raceNames(), theirs.getTrust(),
                    messages.get(answer.key(), answer.args()), player);
        }
        conclude(player, other, request, turn, answer);

        relationRepository.saveAll(List.of(mine, theirs));
        return toDto(mine, other, playerRoster.raceNames(), theirs.getTrust(),
                speech(player, other, request, answer), player);
    }

    /**
     * Как решается просьба — field-backlog, пункт 182: доверием (так отвечает ИИ и человек, не
     * ответивший в срок), ответом человека «да» или «нет» — или откладывается до ответа
     * ({@code ASK}: проверки правил пройдены, решать будет адресат).
     */
    private enum Consent { TRUST, YES, NO, ASK }

    /**
     * Просьбы, на которые человек отвечает сам, — пункт 182: мир, договор, дань и обмен. Война,
     * разрыв и подарок согласия не спрашивают и у ИИ, поэтому идут сразу.
     */
    private static final java.util.Set<DiplomacyAction> CONSENTED = java.util.EnumSet.of(
            DiplomacyAction.PROPOSE_PEACE, DiplomacyAction.PROPOSE_TREATY,
            DiplomacyAction.DEMAND_TRIBUTE, DiplomacyAction.EXCHANGE_TECH);

    /** Адресат — живой человек за столом, и просьба требует его согласия. */
    private Boolean asksHuman(PlayerEntity player, PlayerEntity other, DiplomacyAction action) {
        return CONSENTED.contains(action)
                && other.getPlayerType() == com.sddnw.server.domain.enums.PlayerType.HUMAN
                && !Boolean.TRUE.equals(presence.aiDriven(other))
                && !other.getId().equals(player.getId());
    }

    /**
     * Действие целиком. Ответ собирается КЛЮЧОМ, а не текстом (п. 3.5): язык известен только в
     * момент ответа, и по ключу же видно, был ли отказ. {@code null} — просьба отложена до ответа
     * человека ({@link Consent#ASK}).
     */
    private MessageKey perform(PlayerEntity player, PlayerEntity other, DiplomacyRelationEntity mine,
                               DiplomacyRelationEntity theirs, DiplomacyActionRequest request,
                               Integer turn, Consent consent) {
        return switch (request.action()) {
            case DECLARE_WAR -> declareWar(player, other, mine, theirs);
            case PROPOSE_PEACE -> proposePeace(player, other, mine, theirs, consent);
            case PROPOSE_TREATY -> proposeTreaty(player, other, mine, theirs, requireTreaty(request), consent);
            case BREAK_TREATY -> breakTreaty(player, other, mine, theirs, requireTreaty(request));
            case DEMAND_TRIBUTE -> demandTribute(player, other, mine, theirs, consent);
            case EXCHANGE_TECH -> exchangeTech(player, other, mine, theirs, request, turn, consent);
            case GIFT_CREDITS -> giftCredits(player, other, theirs, request);
            case GIFT_TECH -> giftTech(player, other, theirs, request, turn);
        };
    }

    /** Что остаётся после исполненного действия: весть второй стороне и обида в хронику. */
    private void conclude(PlayerEntity player, PlayerEntity other, DiplomacyActionRequest request,
                          Integer turn, MessageKey answer) {
        // Вторая сторона узнаёт о случившемся в итогах своего хода: дипломатию с ней ведут,
        // не спрашивая, и без этого события чужая война свалилась бы как снег на голову.
        notifyOther(player, other, turn, request, answer);
        // Обида — в хронику самого игрока (пункты 138 и 139): по ней сосед и вспомнит.
        rememberOffence(player, other, turn, request, answer);
    }

    /** Записывает предложение человеку и кладёт ему весть в итоги хода — пункт 182. */
    private void propose(PlayerEntity player, PlayerEntity other, DiplomacyActionRequest request,
                         Integer turn) {
        DiplomacyProposalEntity row = new DiplomacyProposalEntity();
        row.setGameId(player.getGame().getId());
        row.setFromPlayerId(player.getId());
        row.setToPlayerId(other.getId());
        row.setAction(request.action());
        row.setTreaty(request.action() == DiplomacyAction.PROPOSE_TREATY ? request.treaty() : null);
        row.setOfferedTech(request.action() == DiplomacyAction.EXCHANGE_TECH ? request.offeredTech() : null);
        row.setRequestedTech(request.action() == DiplomacyAction.EXCHANGE_TECH ? request.requestedTech() : null);
        row.setTurn(turn);
        proposalRepository.save(row);
        playerEvents.record(row.getGameId(), other.getId(), turn, "DIPLOMACY",
                new MessageKey("turn.diplomacy.proposalReceived", player.getName(), request.action()));
        log.info("Игрок {} предложил империи {}: {} — ждёт ответа человека",
                player.getName(), other.getName(), request.action());
    }

    /**
     * Ответ человека на предложение — пункт 182. Ответ записывается, а исполняется в конце хода
     * ({@link #resolveProposals}): посреди хода галактика не меняется ответом одного игрока.
     * Передумать можно, пока ход не посчитан.
     */
    @Transactional
    public void answerProposal(PlayerEntity player, UUID proposalId, Boolean accept) {
        DiplomacyProposalEntity row = proposalRepository.findById(proposalId)
                .filter(found -> found.getToPlayerId().equals(player.getId()))
                .orElseThrow(() -> new NotFoundException("diplomacy.proposalNotFound"));
        row.setAccepted(Boolean.TRUE.equals(accept));
        proposalRepository.save(row);
    }

    /** Предложения игроку и от игрока, ждущие итога, — для переговоров и окна ответа (пункт 182). */
    @Transactional(readOnly = true)
    public com.sddnw.server.dto.DiplomacyProposalsDto proposals(PlayerEntity player) {
        Map<UUID, String> names = playerRepository.findAllByGameIdOrderBySlotAsc(player.getGame().getId())
                .stream().collect(Collectors.toMap(PlayerEntity::getId, PlayerEntity::getName));
        return new com.sddnw.server.dto.DiplomacyProposalsDto(
                proposalRepository.findAllByToPlayerIdOrderByTurnAsc(player.getId()).stream()
                        .map(row -> proposalDto(row, names)).toList(),
                proposalRepository.findAllByFromPlayerIdOrderByTurnAsc(player.getId()).stream()
                        .map(row -> proposalDto(row, names)).toList());
    }

    private com.sddnw.server.dto.DiplomacyProposalDto proposalDto(DiplomacyProposalEntity row,
                                                                  Map<UUID, String> names) {
        return new com.sddnw.server.dto.DiplomacyProposalDto(
                row.getId(), row.getFromPlayerId(), messages.empire(names.get(row.getFromPlayerId())),
                row.getToPlayerId(), messages.empire(names.get(row.getToPlayerId())),
                row.getAction(), row.getTreaty(),
                row.getOfferedTech(), techName(row.getOfferedTech()),
                row.getRequestedTech(), techName(row.getRequestedTech()),
                row.getTurn(), row.getTurn() + 1, row.getAccepted());
    }

    private String techName(String code) {
        return code == null ? null : researchCatalog.place(code).option().name();
    }

    /**
     * Итог предложений человеку — пункт 182, внутри посчитанного хода. Ответил — исполняется
     * его ответ; не ответил до конца следующего хода — решает доверие, как отвечал бы ИИ. Правила
     * могли измениться за ход (война, договор уже есть, технология изучена), и тогда предложение
     * теряет силу — обе стороны узнают об этом словами.
     */
    public void resolveProposals(TurnContext context) {
        List<DiplomacyProposalEntity> rows = proposalRepository
                .findAllByGameIdOrderByTurnAscActionAsc(context.game().getId());
        if (rows.isEmpty()) {
            return;
        }
        Map<UUID, PlayerEntity> players = context.players().stream()
                .collect(Collectors.toMap(PlayerEntity::getId, player -> player));
        for (DiplomacyProposalEntity row : rows) {
            PlayerEntity from = players.get(row.getFromPlayerId());
            PlayerEntity to = players.get(row.getToPlayerId());
            Boolean late = context.turn() > row.getTurn();
            Consent consent = row.getAccepted() != null
                    ? (row.getAccepted() ? Consent.YES : Consent.NO)
                    : (late ? Consent.TRUST : null);
            if (from == null || to == null) {
                proposalRepository.delete(row);
                continue;
            }
            if (consent == null) {
                continue;
            }
            DiplomacyActionRequest request = new DiplomacyActionRequest(from.getAccessToken(), to.getId(),
                    row.getAction(), row.getTreaty(), row.getOfferedTech(), row.getRequestedTech(), null);
            UUID gameId = context.game().getId();
            try {
                DiplomacyRelationEntity mine = requireRelation(from.getId(), to.getId());
                DiplomacyRelationEntity theirs = requireRelation(to.getId(), from.getId());
                MessageKey answer = perform(from, to, mine, theirs, request, context.turn(), consent);
                conclude(from, to, request, context.turn(), answer);
                relationRepository.saveAll(List.of(mine, theirs));
                Boolean agreed = !answer.key().startsWith("diplomacy.refusal.");
                String key = consent == Consent.TRUST
                        ? (agreed ? "turn.diplomacy.proposalTrustAccepted" : "turn.diplomacy.proposalTrustRefused")
                        : (agreed ? "turn.diplomacy.proposalAccepted" : "turn.diplomacy.proposalDeclined");
                playerEvents.record(gameId, from.getId(), context.turn(), "DIPLOMACY",
                        new MessageKey(key, to.getName(), row.getAction()));
                if (consent == Consent.TRUST) {
                    playerEvents.record(gameId, to.getId(), context.turn(), "DIPLOMACY",
                            new MessageKey("turn.diplomacy.proposalTimedOut", from.getName(), row.getAction()));
                }
            } catch (ConflictException | NotFoundException lapsed) {
                playerEvents.record(gameId, from.getId(), context.turn(), "DIPLOMACY",
                        new MessageKey("turn.diplomacy.proposalLapsed", to.getName(), row.getAction()));
                playerEvents.record(gameId, to.getId(), context.turn(), "DIPLOMACY",
                        new MessageKey("turn.diplomacy.proposalLapsed", from.getName(), row.getAction()));
            }
            proposalRepository.delete(row);
        }
    }

    /**
     * Чем ответ сводится к одному из семи исходов — для реплики посла по характеру (пункт 139).
     * Ответ без исхода (неизвестный ключ) реплики не получает: лучше сухо, чем невпопад.
     */
    static String outcome(String answerKey) {
        if (answerKey.startsWith("diplomacy.refusal.")) {
            return "refusal";
        }
        return switch (answerKey) {
            case "diplomacy.answer.war" -> "war";
            case "diplomacy.answer.peace" -> "peace";
            case "diplomacy.answer.treatySigned" -> "treaty";
            case "diplomacy.answer.treatyBroken" -> "broken";
            case "diplomacy.answer.tribute" -> "tribute";
            case "diplomacy.answer.exchange" -> "exchange";
            case "diplomacy.answer.giftCredits", "diplomacy.answer.giftTech" -> "gift";
            default -> null;
        };
    }

    /**
     * Речь посла — field-backlog, пункт 139: сосед с характером, а не строка доверия.
     * <p>
     * Решение не меняется — меняется то, КАК оно сказано: реплика по характеру правителя
     * ({@code envoy.<ХАРАКТЕР>.<исход>}), за ней — сам ответ, чтобы смысл не терялся в манере. А
     * если игрок просит (мира, договора, обмена, дани), сосед вспоминает вслух последнюю обиду из
     * хроники: «вы разорвали договор на 80-м ходу». Обида, названная словами, — сюжет, а не
     * число. У империи человека посла нет: за неё говорит человек.
     */
    private String speech(PlayerEntity player, PlayerEntity other, DiplomacyActionRequest request,
                          MessageKey answer) {
        String plain = messages.get(answer.key(), answer.args());
        if (other.getPlayerType() != com.sddnw.server.domain.enums.PlayerType.AI
                || other.getAiPersonality() == null) {
            return plain;
        }
        String outcome = outcome(answer.key());
        if (outcome == null) {
            return plain;
        }
        String said = messages.get("envoy.say",
                messages.get("envoy." + other.getAiPersonality().name() + "." + outcome), plain);
        String memory = memory(player, other, request);
        return memory == null ? said : messages.get("envoy.withMemory", said, memory);
    }

    /** Действия-просьбы: на них сосед припоминает обиду. На войну и подарок — нет. */
    private static final java.util.Set<DiplomacyAction> ASKS = java.util.EnumSet.of(
            DiplomacyAction.PROPOSE_PEACE, DiplomacyAction.PROPOSE_TREATY,
            DiplomacyAction.EXCHANGE_TECH, DiplomacyAction.DEMAND_TRIBUTE);

    /** Ключи обид в хронике игрока: их пишет {@link #rememberOffence}. */
    static final String OFFENCE_WAR = "milestone.youDeclaredWar";
    static final String OFFENCE_BROKEN = "milestone.youBrokeTreaty";

    private String memory(PlayerEntity player, PlayerEntity other, DiplomacyActionRequest request) {
        if (!ASKS.contains(request.action()) || player.getPlayerType() != com.sddnw.server.domain.enums.PlayerType.HUMAN) {
            return null;
        }
        com.sddnw.server.domain.entity.GameMilestoneEntity last = null;
        for (var row : milestones.chronicle(player.getId())) {
            boolean offence = OFFENCE_WAR.equals(row.getMessageKey()) || OFFENCE_BROKEN.equals(row.getMessageKey());
            if (offence && row.getArgs() != null && !row.getArgs().isEmpty()
                    && other.getName().equals(row.getArgs().get(0))) {
                last = row;
            }
        }
        if (last == null) {
            return null;
        }
        return OFFENCE_WAR.equals(last.getMessageKey())
                ? messages.get("envoy.memory.war", last.getTurn())
                : messages.event("envoy.memory.broken", List.of(String.valueOf(last.getTurn()),
                        last.getArgs().size() > 1 ? last.getArgs().get(1) : ""));
    }

    /**
     * Объявленная игроком война и разорванный им договор — в его хронику (пункты 138 и 139):
     * отказ ничего не сделал и обидой не считается.
     */
    private void rememberOffence(PlayerEntity player, PlayerEntity other, Integer turn,
                                 DiplomacyActionRequest request, MessageKey answer) {
        if (milestones == null || answer == null || answer.key().startsWith("diplomacy.refusal.")) {
            return;
        }
        UUID gameId = player.getGame().getId();
        if (request.action() == DiplomacyAction.DECLARE_WAR) {
            milestones.note(gameId, player, turn, MilestoneService.WAR, new MessageKey(OFFENCE_WAR, other.getName()));
        } else if (request.action() == DiplomacyAction.BREAK_TREATY) {
            milestones.note(gameId, player, turn, MilestoneService.TREATY_BROKEN,
                    new MessageKey(OFFENCE_BROKEN, other.getName(), requireTreaty(request)));
        }
    }

    /**
     * Договориться с отталкивающей расой нельзя — п. 15, п. 7.
     * <p>
     * В MOO II у такой империи в переговорах остаются только объявление войны, мир и
     * капитуляция: договоров, союзов и дани для неё не существует. Правило смотрит на
     * <b>обе</b> стороны — переговоры ведут двое, и одной отталкивающей достаточно, чтобы
     * их не было: соседи такую империю не переносят ровно так же, как она их.
     * <p>
     * За это раса и возвращает шесть очков в конструкторе — самый щедрый недостаток
     * в игре; до этой проверки он ничего не стоил.
     */
    private void requireNotRepulsive(PlayerEntity player, PlayerEntity other, DiplomacyAction action) {
        if (action == DiplomacyAction.DECLARE_WAR || action == DiplomacyAction.PROPOSE_PEACE) {
            return;
        }
        if (Boolean.TRUE.equals(raceService.effects(player).repulsive())) {
            throw new ConflictException("diplomacy.youRepulsive");
        }
        if (Boolean.TRUE.equals(raceService.effects(other).repulsive())) {
            throw new ConflictException("diplomacy.theyRepulsive", other.getName());
        }
    }

    /**
     * Что договоры дают колониям империи — п. 15.
     * <p>
     * Торговый поднимает доход, исследовательский — науку; действуют они так же, как
     * здания и раса, и складываются с ними. Несколько договоров одного вида с разными
     * соседями складываются: чем шире торговая сеть, тем богаче империя.
     * <p>
     * Лидер-торговец добавляет процентов к торговому договору — п. 6, и только к нему:
     * в MOO II он ведает торговлей, а не наукой.
     */
    public BuildingEffects treatyEffects(UUID playerId) {
        return treatyEffects(Set.of(playerId)).getOrDefault(playerId, BuildingEffects.NONE);
    }

    /**
     * То же самое СРАЗУ НА ВСЕХ владельцев колоний — п. 15.
     * <p>
     * <b>Зачем отдельный метод.</b> Контекст колоний собирается каждый ход и звал прежний
     * {@code treatyEffects} по одному на игрока, а внутри каждого вызова сидели ТРИ
     * обращения в базу: отношения игрока, его лидеры и он сам. Шесть империй — восемнадцать
     * запросов каждый ход, изнутри посчитанного хода, где любой запрос заставляет Hibernate
     * сбросить в базу всё, что ход успел изменить. Это ровно тот запрет, что записан в
     * граблях проекта, и замер его подтвердил: выборка хода съедала 35 % всего конца хода —
     * больше, чем любая фаза.
     * <p>
     * Здесь те же три выборки делаются ОДИН РАЗ на всех: отношения, лидеры и игроки берутся
     * набором. Правило подсчёта не изменилось ни на йоту — сменился только способ достать
     * данные.
     */
    public Map<UUID, BuildingEffects> treatyEffects(Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        // Договор на паузе знакомства дохода не приносит: торговать и делиться наукой не с
        // кем, пока империи не дотягиваются друг до друга (п. 15). Сам договор хранится.
        Map<UUID, List<DiplomacyRelationEntity>> byPlayer =
                relationRepository.findAllByPlayerIdIn(playerIds).stream()
                        .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                        .collect(Collectors.groupingBy(DiplomacyRelationEntity::getPlayerId));
        if (byPlayer.isEmpty()) {
            return Map.of();
        }
        Map<UUID, LeaderBonusService.Bonuses> bonuses = leaderBonuses.of(byPlayer.keySet());
        // Прирождённые торговцы берут с договора на четверть больше — п. 7, MOO II:
        // «прибыль от торговых соглашений получает прибавку в 25%».
        Map<UUID, Integer> raceByPlayer = new HashMap<>();
        playerRepository.findAllById(byPlayer.keySet()).forEach(player ->
                raceByPlayer.put(player.getId(), raceService.effects(player).tradeTreatyPercent()));

        Map<UUID, BuildingEffects> effects = new HashMap<>();
        byPlayer.forEach((playerId, relations) -> {
            Integer trader = bonuses.containsKey(playerId)
                    ? bonuses.get(playerId).empireValue("TRADER")
                    : 0;
            Integer race = raceByPlayer.getOrDefault(playerId, 0);

            Map<BuildingEffectType, Integer> amounts = new EnumMap<>(BuildingEffectType.class);
            for (DiplomacyRelationEntity relation : relations) {
                for (DiplomacyTreaty treaty : relation.getTreaties()) {
                    if (treaty.getEffect() != null && treaty.getEffect().getColonyEffect() != null) {
                        Integer amount = treaty == DiplomacyTreaty.TRADE
                                ? treaty.getAmount() * (100 + trader + race) / 100
                                : treaty.getAmount();
                        amounts.merge(treaty.getEffect().getColonyEffect(), amount, Integer::sum);
                    }
                }
            }
            effects.put(playerId, new BuildingEffects(Map.copyOf(amounts), 0));
        });
        return effects;
    }

    /** Империи, с которыми у игрока союз — п. 15: их разведка общая. */
    /**
     * Союзники сразу многих империй — одной выборкой на всех.
     * <p>
     * Нужна разведке ИИ ({@link ExplorationService#exploredByAll}): союзники делятся
     * разведанным, и спрашивать их по одному значило бы запрос на игрока изнутри
     * посчитанного хода.
     */
    public Map<UUID, Set<UUID>> alliesOfAll(Collection<UUID> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> allies = new HashMap<>();
        relationRepository.findAllByPlayerIdIn(playerIds).stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                .filter(relation -> relation.getTreaties().contains(DiplomacyTreaty.ALLIANCE))
                .forEach(relation -> allies
                        .computeIfAbsent(relation.getPlayerId(), one -> new HashSet<>())
                        .add(relation.getOtherPlayerId()));
        return allies;
    }

    /** Знакомые империи — п. 15: те, с кем знакомство есть сейчас (потерянное — пауза). */
    public Set<UUID> contacts(UUID playerId) {
        return relationRepository.findAllByPlayerId(playerId).stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                .map(DiplomacyRelationEntity::getOtherPlayerId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<UUID> allies(UUID playerId) {
        return relationRepository.findAllByPlayerId(playerId).stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                .filter(relation -> relation.getTreaties().contains(DiplomacyTreaty.ALLIANCE))
                .map(DiplomacyRelationEntity::getOtherPlayerId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Запрещает ли договор нападение на эту империю — п. 15: пакт о ненападении и союз
     * держат руки связанными, пока их не разорвут.
     */
    public void requireAttackAllowed(UUID playerId, UUID targetPlayerId) {
        relationRepository.findByPlayerIdAndOtherPlayerId(playerId, targetPlayerId).ifPresent(relation -> {
            Set<DiplomacyTreaty> treaties = relation.getTreaties();
            if (treaties.contains(DiplomacyTreaty.ALLIANCE)) {
                throw new ConflictException("diplomacy.allyAttack");
            }
            if (treaties.contains(DiplomacyTreaty.NON_AGGRESSION)) {
                throw new ConflictException("diplomacy.nonAggression");
            }
        });
    }

    /** Знакомы ли империи — п. 15; иначе действия с ней невозможны. */
    public void requireKnown(UUID playerId, UUID otherPlayerId) {
        requireRelation(playerId, otherPlayerId);
    }

    /**
     * Дипломатический скандал: доверие пострадавшей стороны падает — п. 13.
     * Так отзываются удавшиеся операции шпионов.
     * <p>
     * Насколько сильно — решает характер пострадавшего (п. 15): благородный «вдвое
     * сильнее отзывается на нападения без повода и на диверсии», ксенофоб — на всё
     * враждебное разом. Кража и поимка агента как раз такие случаи.
     */
    @Transactional
    public void incident(UUID victimId, UUID culpritId, Integer trustPenalty) {
        relationRepository.findByPlayerIdAndOtherPlayerId(victimId, culpritId).ifPresent(relation -> {
            loseTrust(relation, playerRepository.findById(victimId).orElse(null), trustPenalty);
            relationRepository.save(relation);
        });
    }

    private MessageKey declareWar(PlayerEntity player, PlayerEntity other,
                              DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs) {
        if (mine.getStance() == DiplomacyStance.WAR) {
            throw new ConflictException("diplomacy.alreadyAtWar", other.getName());
        }
        // Войну запрещает не ВСЯКИЙ договор, а только тот, что её запрещает: пакт о
        // ненападении и союз ({@code DiplomacyTreaty.forbidsWar}). Здесь стояло «есть хоть
        // какой-то договор — сначала разорвите», и торговое соглашение запирало войну
        // наглухо. Измерено: империи ИИ пытались объявить войну 86 раз за партию и смогли
        // ТРИ — остальные 83 отказа приходились на торговый и исследовательский договоры,
        // которые к войне отношения не имеют. Галактика при этом стояла в вечном мире, а
        // наземное мерило балансировки не находило ни одного захвата.
        if (mine.getTreaties().stream().anyMatch(treaty -> Boolean.TRUE.equals(treaty.forbidsWar()))) {
            throw new ConflictException("diplomacy.breakTreatiesFirst", other.getName());
        }
        // Война обрывает то, что от договоров осталось: торговать и делиться наукой с тем,
        // кому только что объявил войну, нельзя. Пакт и союз сюда не попадают — их рвут
        // ДО объявления, иначе война обходила бы их молча.
        List.copyOf(mine.getTreaties()).forEach(treaty -> removeTreaty(mine, theirs, treaty));

        setStance(mine, theirs, DiplomacyStance.WAR);
        loseTrust(theirs, other, WAR_PENALTY);
        activity.record(player.getGame().getId(), player.getId(), EmpireActivityService.WAR);
        log.info("Игрок {} объявил войну империи {}", player.getName(), other.getName());
        return new MessageKey("diplomacy.answer.war");
    }

    private MessageKey proposePeace(PlayerEntity player, PlayerEntity other,
                                DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs,
                                Consent consent) {
        if (mine.getStance() == DiplomacyStance.PEACE) {
            throw new ConflictException("diplomacy.alreadyAtPeace", other.getName());
        }
        if (consent == Consent.ASK) {
            return null;
        }
        if (!decided(consent, theirs.getTrust() >= charmed(player, PEACE_TRUST) + treatyBias(other))) {
            loseTrust(mine, player, REFUSAL_PENALTY);
            log.info("Империя {} отказала игроку {} в мире", other.getName(), player.getName());
            return refusal(consent, "diplomacy.refusal.trust");
        }

        setStance(mine, theirs, DiplomacyStance.PEACE);
        reward(player, other, mine, theirs);
        log.info("Империя {} приняла мир с игроком {}", other.getName(), player.getName());
        return new MessageKey("diplomacy.answer.peace");
    }

    private MessageKey proposeTreaty(PlayerEntity player, PlayerEntity other,
                                 DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs,
                                 DiplomacyTreaty treaty, Consent consent) {
        if (mine.getTreaties().contains(treaty)) {
            throw new ConflictException("diplomacy.treatyExists", treaty, other.getName());
        }
        if (mine.getStance() == DiplomacyStance.WAR) {
            throw new ConflictException("diplomacy.peaceFirst");
        }
        if (consent == Consent.ASK) {
            return null;
        }
        if (!decided(consent, agrees(player, other, theirs, treaty))) {
            loseTrust(mine, player, REFUSAL_PENALTY);
            log.info("Империя {} отказала игроку {} в договоре {}",
                    other.getName(), player.getName(), treaty.getLabel());
            return refusal(consent, "diplomacy.refusal.trust");
        }

        activity.record(player.getGame().getId(), player.getId(), EmpireActivityService.TREATY);
        activity.record(player.getGame().getId(), other.getId(), EmpireActivityService.TREATY);
        addTreaty(mine, theirs, treaty);
        // Союз подразумевает ненападение: воевать с союзником нельзя и без отдельного пакта.
        if (treaty == DiplomacyTreaty.ALLIANCE) {
            addTreaty(mine, theirs, DiplomacyTreaty.NON_AGGRESSION);
            setStance(mine, theirs, DiplomacyStance.PEACE);
        }
        reward(player, other, mine, theirs);
        log.info("Империя {} подписала с игроком {} договор {}",
                other.getName(), player.getName(), treaty.getLabel());
        return new MessageKey("diplomacy.answer.treatySigned", treaty);
    }

    private MessageKey breakTreaty(PlayerEntity player, PlayerEntity other,
                               DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs,
                               DiplomacyTreaty treaty) {
        if (!mine.getTreaties().contains(treaty)) {
            throw new ConflictException("diplomacy.noTreaty", treaty, other.getName());
        }

        removeTreaty(mine, theirs, treaty);
        // Разорванный союз тянет за собой ненападение: держать его в одиночку незачем.
        if (treaty == DiplomacyTreaty.ALLIANCE) {
            removeTreaty(mine, theirs, DiplomacyTreaty.NON_AGGRESSION);
        }
        loseTrust(theirs, other, BREAK_PENALTY);
        log.info("Игрок {} разорвал договор {} с империей {}",
                player.getName(), treaty.getLabel(), other.getName());
        return new MessageKey("diplomacy.answer.treatyBroken", treaty);
    }

    /**
     * Требование дани — п. 15.
     * <p>
     * Платят от страха (доверие низкое — соперник считает войну возможной) или по большой
     * приязни. В обоих случаях дань бьёт по доверию: её помнят.
     */
    private MessageKey demandTribute(PlayerEntity player, PlayerEntity other,
                                 DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs,
                                 Consent consent) {
        if (consent == Consent.ASK) {
            return null;
        }
        if (!decided(consent, agreesToTribute(player, theirs))) {
            loseTrust(theirs, other, REFUSAL_PENALTY);
            log.info("Империя {} отказала игроку {} в дани", other.getName(), player.getName());
            return refusal(consent, "diplomacy.refusal.tribute");
        }

        Integer tribute = Math.max(0, other.getCredits()) * TRIBUTE_PERCENT / 100;
        other.setCredits(other.getCredits() - tribute);
        player.setCredits(player.getCredits() + tribute);
        playerRepository.saveAll(List.of(player, other));

        loseTrust(theirs, other, TRIBUTE_TRUST_PENALTY);
        log.info("Империя {} выплатила игроку {} дань в {} кредитов",
                other.getName(), player.getName(), tribute);
        return new MessageKey("diplomacy.answer.tribute", tribute);
    }

    /**
     * Что можно обменять с этой империей — п. 15.
     * <p>
     * Обе половины сделки считаются вычитанием: моё за вычетом их и их за вычетом моего.
     * Технологии, которые есть у обоих, в обмене бессмысленны, и показывать их значило бы
     * предлагать игроку заведомый отказ.
     * <p>
     * Список того, что есть у соседа, — не утечка: в MOO II контакт как раз и «даёт
     * сведения о сопернике с момента знакомства», и обмен без такого списка невозможен —
     * пришлось бы угадывать код чужой технологии.
     */
    @Transactional(readOnly = true)
    public TechTradeDto tradeableWith(PlayerEntity player, UUID otherPlayerId) {
        requireRelation(player.getId(), otherPlayerId);

        Set<String> ours = technologiesOf(player.getId());
        // Чужое изученное — только увиденное (п. 15, туман войны): доклад о расе больше не
        // перечисляет науку соседа, как в оригинале, — решение хозяина проекта.
        Set<String> seen = techSight.observedBy(player.getId(), otherPlayerId);
        return new TechTradeDto(offers(ours, seen), offers(seen, ours), offers(seen, Set.of()));
    }

    /** Технологии первой стороны, которых нет у второй, — в порядке дерева. */
    private List<TechTradeDto.TechnologyOfferDto> offers(Set<String> have, Set<String> lack) {
        return have.stream()
                .filter(code -> !lack.contains(code))
                .map(researchCatalog::place)
                .sorted(Comparator
                        .comparing(ResearchCatalog.TechnologyPlace::categoryCode)
                        .thenComparing(ResearchCatalog.TechnologyPlace::levelOrder))
                .map(place -> new TechTradeDto.TechnologyOfferDto(
                        place.option().code(),
                        place.option().name(),
                        place.categoryCode(),
                        place.levelOrder(),
                        place.levelCost()))
                .toList();
    }

    /**
     * Обмен технологиями — п. 15, самое ходовое действие дипломатии MOO II.
     * <p>
     * Одна сторона называет технологию, которую хочет, и ту, которую отдаёт взамен.
     * Правило согласия — из оригинала: «компьютер всегда предлагает обмен, выгодный ему
     * самому», то есть соглашается, лишь когда получает не дешевле, чем отдаёт. Ценность
     * меряется стоимостью уровня в очках исследований — другой меры дерево не даёт.
     * <p>
     * Обмен идёт в обе стороны сразу: технологию получает и тот, кто просил, и тот, кто
     * согласился. В MOO II это разовая сделка, а не договор, поэтому в отношениях от неё
     * остаётся только выросшее доверие.
     */
    private MessageKey exchangeTech(PlayerEntity player, PlayerEntity other,
                                DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs,
                                DiplomacyActionRequest request, Integer turn, Consent consent) {
        ResearchCatalog.TechnologyPlace offered = requireTech(request.offeredTech(), "diplomacy.tech.offered");
        ResearchCatalog.TechnologyPlace requested = requireTech(request.requestedTech(), "diplomacy.tech.requested");
        if (offered.option().code().equals(requested.option().code())) {
            throw new ConflictException("diplomacy.sameTech");
        }

        Set<String> ours = technologiesOf(player.getId());
        Set<String> yours = technologiesOf(other.getId());
        if (!ours.contains(offered.option().code())) {
            throw new ConflictException("diplomacy.youLackTech", offered.option().name());
        }
        if (!yours.contains(requested.option().code())) {
            throw new ConflictException("diplomacy.theyLackTech", other.getName(), requested.option().name());
        }
        if (ours.contains(requested.option().code())) {
            throw new ConflictException("diplomacy.youHaveTech", requested.option().name());
        }
        if (yours.contains(offered.option().code())) {
            // Отказ «это у нас есть» — тоже знание (п. 15, туман войны): игрок узнал, что у
            // соседа эта технология есть, и предлагать её снова не станет. Конфликт здесь
            // транзакцию не откатывает (noRollbackFor у act) — запись остаётся.
            techSight.observe(player.getGame().getId(), player.getId(), other.getId(),
                    List.of(offered.option().code()), turn);
            throw new ConflictException("diplomacy.theyHaveTech", offered.option().name(), other.getName());
        }

        if (consent == Consent.ASK) {
            return null;
        }
        if (!decided(consent, agreesToExchange(player, theirs, offered, requested))) {
            loseTrust(theirs, other, REFUSAL_PENALTY);
            log.info("Империя {} отказала игроку {} в обмене {} на {}", other.getName(),
                    player.getName(), offered.option().name(), requested.option().name());
            return refusal(consent, "diplomacy.refusal.exchange");
        }

        activity.record(player.getGame().getId(), player.getId(),
                EmpireActivityService.TECH_EXCHANGE);
        activity.record(player.getGame().getId(), other.getId(),
                EmpireActivityService.TECH_EXCHANGE);
        grantTechnology(player, requested, turn);
        grantTechnology(other, offered, turn);
        // После обмена обе стороны знают, что отданное теперь у другой (п. 15).
        techSight.observe(player.getGame().getId(), player.getId(), other.getId(),
                List.of(offered.option().code(), requested.option().code()), turn);
        techSight.observe(player.getGame().getId(), other.getId(), player.getId(),
                List.of(offered.option().code(), requested.option().code()), turn);
        reward(player, other, mine, theirs);
        log.info("Игрок {} обменял с империей {}: {} на {}", player.getName(), other.getName(),
                offered.option().name(), requested.option().name());
        return new MessageKey("diplomacy.answer.exchange",
                requested.option().name(), offered.option().name());
    }

    /**
     * Подарок деньгами — п. 15: согласия не требует, подарок не отвергают.
     * <p>
     * Доверие растёт от доли казны, а не от суммы: тысяча кредитов от нищего значит
     * больше, чем от богача. Потолок в {@link #GIFT_TRUST_MAX} не даёт купить дружбу
     * одним переводом — в MOO II подарки располагают, но политику не заменяют.
     */
    private MessageKey giftCredits(PlayerEntity player, PlayerEntity other,
                               DiplomacyRelationEntity theirs, DiplomacyActionRequest request) {
        Integer amount = request.credits();
        if (amount == null || amount <= 0) {
            throw new ConflictException("diplomacy.giftAmount");
        }
        if (player.getCredits() < amount) {
            throw new ConflictException("diplomacy.giftNoCredits", player.getCredits());
        }

        Integer before = player.getCredits();
        player.setCredits(before - amount);
        other.setCredits(other.getCredits() + amount);
        playerRepository.saveAll(List.of(player, other));

        Integer share = before <= 0
                ? GIFT_TRUST_MAX
                : Math.min(GIFT_TRUST_MAX, amount * GIFT_TRUST_MAX / before);
        gainTrust(theirs, other, share);
        log.info("Игрок {} подарил империи {} {} кредитов: доверие +{}",
                player.getName(), other.getName(), amount, share);
        return new MessageKey("diplomacy.answer.giftCredits", amount, share);
    }

    /** Подарок технологией — п. 15: то же, что и деньгами, только платят знанием. */
    private MessageKey giftTech(PlayerEntity player, PlayerEntity other,
                            DiplomacyRelationEntity theirs, DiplomacyActionRequest request,
                            Integer turn) {
        ResearchCatalog.TechnologyPlace gift = requireTech(request.offeredTech(), "diplomacy.tech.gift");
        if (!technologiesOf(player.getId()).contains(gift.option().code())) {
            throw new ConflictException("diplomacy.youLackTech", gift.option().name());
        }
        if (technologiesOf(other.getId()).contains(gift.option().code())) {
            techSight.observe(player.getGame().getId(), player.getId(), other.getId(),
                    List.of(gift.option().code()), turn);
            throw new ConflictException("diplomacy.theyHaveTech", gift.option().name(), other.getName());
        }

        grantTechnology(other, gift, turn);
        // Подаренное у соседа теперь есть — и дарящий это знает (п. 15).
        techSight.observe(player.getGame().getId(), player.getId(), other.getId(),
                List.of(gift.option().code()), turn);
        gainTrust(theirs, other, GIFT_TECH_TRUST);
        log.info("Игрок {} подарил империи {} технологию {}",
                player.getName(), other.getName(), gift.option().name());
        return new MessageKey("diplomacy.answer.giftTech", gift.option().name(), GIFT_TECH_TRUST);
    }

    /**
     * Согласна ли другая сторона на обмен — п. 15.
     * <p>
     * Два условия. Сделка должна быть ей выгодна: получить не дешевле, чем отдать, — это
     * прямое правило MOO II. И разговаривать она должна быть готова: с тем, кому вовсе
     * не доверяют, знанием не делятся ни на каких условиях.
     */
    /**
     * Согласится ли сосед на такой обмен — п. 15; спрашивают до предложения.
     * <p>
     * Нужно это ИИ: он перебирает пары «что отдать за что получить» и не должен предлагать
     * заведомо невозможное — отказ стоит доверия, и империя, тыкающая наугад, растеряла бы
     * его на ровном месте. Считает тот же {@link #agreesToExchange}, которым решается и сам
     * обмен: второго свода правил торговли у ИИ нет.
     */
    public Boolean acceptsExchange(PlayerEntity asking, DiplomacyRelationEntity theirs,
                                   ResearchCatalog.TechnologyPlace offered,
                                   ResearchCatalog.TechnologyPlace requested) {
        return agreesToExchange(asking, theirs, offered, requested);
    }

    private Boolean agreesToExchange(PlayerEntity asking, DiplomacyRelationEntity theirs,
                                     ResearchCatalog.TechnologyPlace offered,
                                     ResearchCatalog.TechnologyPlace requested) {
        return theirs.getTrust() >= charmed(asking, EXCHANGE_TRUST)
                && offered.levelCost() >= requested.levelCost();
    }

    /** Технология по коду; пустой код — ошибка запроса, а не молчаливый пропуск. */
    private ResearchCatalog.TechnologyPlace requireTech(String code, String what) {
        if (code == null || code.isBlank()) {
            throw new ConflictException("diplomacy.techMissing", new MessageKey(what));
        }
        return researchCatalog.place(code);
    }

    /** Что империя уже знает — по кодам технологий. */
    private Set<String> technologiesOf(UUID playerId) {
        return playerTechnologyRepository.findAllByPlayerIdIn(List.of(playerId)).stream()
                .map(PlayerTechnologyEntity::getOptionCode)
                .collect(Collectors.toSet());
    }

    /**
     * Записывает полученную не исследованием технологию — п. 15.
     * <p>
     * Строка та же, что у изученного: дальше игре безразлично, добыто знание своими
     * учёными, обменом или подарком, — важно, что оно есть. Ход записывается текущий:
     * по нему видно, когда технология появилась у империи.
     */
    private void grantTechnology(PlayerEntity player, ResearchCatalog.TechnologyPlace place,
                                 Integer turn) {
        PlayerTechnologyEntity technology = new PlayerTechnologyEntity();
        technology.setPlayerId(player.getId());
        technology.setCategoryCode(place.categoryCode());
        technology.setLevelOrder(place.levelOrder());
        technology.setOptionCode(place.option().code());
        technology.setAcquiredTurn(turn);
        playerTechnologyRepository.save(technology);
    }

    /**
     * Что узнает вторая сторона — п. 15.
     * <p>
     * Отказы не рассылаются: отказала как раз она сама, и новости в этом нет. А вот
     * война, договор, разрыв и дань меняют её положение, и увидеть это она должна.
     */
    private void notifyOther(PlayerEntity player, PlayerEntity other, Integer turn,
                             DiplomacyActionRequest request, MessageKey answer) {
        if (answer != null && answer.key().startsWith("diplomacy.refusal.")) {
            return;
        }
        UUID gameId = player.getGame().getId();
        MessageKey message = switch (request.action()) {
            case DECLARE_WAR -> new MessageKey("turn.diplomacy.warDeclared", player.getName());
            case PROPOSE_PEACE -> new MessageKey("turn.diplomacy.peaceMade", player.getName());
            case PROPOSE_TREATY -> new MessageKey("turn.diplomacy.treatySigned",
                    player.getName(), requireTreaty(request));
            case BREAK_TREATY -> new MessageKey("turn.diplomacy.treatyBroken",
                    player.getName(), requireTreaty(request));
            case DEMAND_TRIBUTE -> new MessageKey("turn.diplomacy.tributePaid", player.getName());
            case EXCHANGE_TECH -> new MessageKey("turn.diplomacy.techExchanged", player.getName());
            case GIFT_CREDITS -> new MessageKey("turn.diplomacy.giftCredits", player.getName());
            case GIFT_TECH -> new MessageKey("turn.diplomacy.giftTech", player.getName());
        };
        playerEvents.record(gameId, other.getId(), turn, "DIPLOMACY", message);
    }

    /** Согласие по ответу человека, а без ответа — по правилу доверия (пункт 182). */
    private static Boolean decided(Consent consent, Boolean byTrust) {
        return consent == Consent.TRUST ? byTrust : consent == Consent.YES;
    }

    /** Отказ человека называется отказом человека, а не нехваткой доверия (пункт 182). */
    private static MessageKey refusal(Consent consent, String byTrustKey) {
        return new MessageKey(consent == Consent.NO ? "diplomacy.refusal.declined" : byTrustKey);
    }

    /**
     * Согласна ли другая сторона на договор: у каждого свой порог доверия.
     * <p>
     * Лидер-дипломат опускает этот порог на свои очки — п. 6: в MOO II он и нужен затем,
     * чтобы договориться там, где своими силами не выходит. Служит он всегда, где бы ни
     * стоял, поэтому надбавка берётся по всей империи.
     */
    private Boolean agrees(PlayerEntity player, PlayerEntity other,
                           DiplomacyRelationEntity theirs, DiplomacyTreaty treaty) {
        Integer diplomat = leaderBonuses.of(player.getId()).empireValue("DIPLOMAT");
        return theirs.getTrust()
                >= charmed(player, treaty.getRequiredTrust()) + treatyBias(other) - diplomat;
    }

    /**
     * Насколько характер поднимает или опускает планку согласия — п. 15.
     * <p>
     * Миролюбивый «дорожит мирными отношениями» и договаривается охотнее; дипломат к тому
     * же и стремится; ксенофоб «не доверяет никому», и уговорить его труднее.
     * <i>Реконструкция:</i> величину оригинал не называет — названы только склонности.
     */
    private Integer treatyBias(PlayerEntity other) {
        if (other == null || other.getAiPersonality() == null) {
            return 0;
        }
        int bias = 0;
        if (Boolean.TRUE.equals(other.getAiPersonality().seeksPeace())) {
            bias -= TREATY_BIAS;
        }
        if (other.getAiPersonality() == AiPersonality.XENOPHOBIC) {
            bias += TREATY_BIAS;
        }
        if (other.getAiObjective() != null
                && Boolean.TRUE.equals(other.getAiObjective().favoursDiplomacy())) {
            bias -= TREATY_BIAS;
        }
        return bias;
    }

    /**
     * Платят дань от страха или по большой приязни; посередине — отказывают. Обаяние
     * просящего опускает планку приязни — п. 7.
     */
    private Boolean agreesToTribute(PlayerEntity asking, DiplomacyRelationEntity theirs) {
        return theirs.getTrust() <= TRIBUTE_FEAR_TRUST
                || theirs.getTrust() >= charmed(asking, TRIBUTE_FRIEND_TRUST);
    }

    /** Заводит отношения в обе стороны; {@code false} — империи уже знакомы. */
    private Boolean meet(PlayerEntity player, PlayerEntity other, Integer turn) {
        if (relationRepository.findByPlayerIdAndOtherPlayerId(player.getId(), other.getId()).isPresent()) {
            return Boolean.FALSE;
        }
        relationRepository.saveAll(List.of(
                relation(player.getId(), other.getId(), turn),
                relation(other.getId(), player.getId(), turn)));
        log.info("Игрок {} познакомился с империей {}", player.getName(), other.getName());
        return Boolean.TRUE;
    }

    /**
     * Галактика объявляет войну тому, кто не подчинился Высшему совету, — п. 3.
     * <p>
     * В MOO II проигравший вправе не признать избрания, и тогда все, кто голосовал за
     * избранного, объявляют отказнику войну. Это <b>не</b> обычное объявление войны, и
     * потому оно живёт здесь отдельным методом, а не зовёт {@code act}:
     * <ul>
     *   <li><b>договоры не спасают</b>: пакт о ненападении и союз рвутся приговором
     *       совета — иначе отказ был бы бесплатным для того, кто заранее подписал пакт
     *       с каждым;</li>
     *   <li><b>согласия не спрашивают</b> ни у кого: воюет галактика, а не империя.</li>
     * </ul>
     * Второго свода правил войны при этом не заводится: стойка и разрыв договоров идут
     * теми же {@code setStance} и {@code removeTreaty}, что и у всякой другой войны.
     *
     * @return сколько империй объявило войну
     */
    @Transactional
    public Integer warOnCouncilRebel(PlayerEntity rebel, List<PlayerEntity> supporters, Integer turn) {
        int declared = 0;
        for (PlayerEntity supporter : supporters) {
            if (supporter.getId().equals(rebel.getId())) {
                continue;
            }
            DiplomacyRelationEntity theirs = requireOrCreate(supporter.getId(), rebel.getId(), turn);
            DiplomacyRelationEntity mine = requireOrCreate(rebel.getId(), supporter.getId(), turn);
            if (theirs.getStance() == DiplomacyStance.WAR) {
                continue;
            }
            List.copyOf(theirs.getTreaties()).forEach(treaty -> removeTreaty(theirs, mine, treaty));
            setStance(theirs, mine, DiplomacyStance.WAR);
            // Доверие падает у ОБЕИХ сторон, и это не симметрия ради симметрии: отказника
            // бьёт объявленная ему война (как всякого, на кого напали), а сам он для
            // галактики преступник — не признал приговора совета, — поэтому и они ему
            // больше не верят. В обычной войне падает доверие только у жертвы: там
            // виноват нападающий, а здесь виноваты оба.
            loseTrust(mine, rebel, WAR_PENALTY);
            loseTrust(theirs, supporter, WAR_PENALTY);
            activity.record(supporter.getGame().getId(), supporter.getId(), EmpireActivityService.WAR);
            declared++;
            log.info("Империя {} объявила войну отказнику {}: приговор совета", supporter.getName(),
                    rebel.getName());
        }
        relationRepository.flush();
        return declared;
    }

    /** Строка отношений этой пары; знакомства могло и не быть — тогда заводится новая. */
    private DiplomacyRelationEntity requireOrCreate(UUID playerId, UUID otherPlayerId, Integer turn) {
        return relationRepository.findByPlayerIdAndOtherPlayerId(playerId, otherPlayerId)
                .orElseGet(() -> relationRepository.save(relation(playerId, otherPlayerId, turn)));
    }

    private DiplomacyRelationEntity relation(UUID playerId, UUID otherPlayerId, Integer turn) {
        DiplomacyRelationEntity relation = new DiplomacyRelationEntity();
        relation.setPlayerId(playerId);
        relation.setOtherPlayerId(otherPlayerId);
        relation.setStance(DiplomacyStance.NEUTRAL);
        relation.setTrust(INITIAL_TRUST);
        relation.setMetTurn(turn);
        return relation;
    }

    private void setStance(DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs, DiplomacyStance stance) {
        mine.setStance(stance);
        theirs.setStance(stance);
    }

    /** Договор подписывают обе стороны — набор у них общий. */
    private void addTreaty(DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs, DiplomacyTreaty treaty) {
        Set<DiplomacyTreaty> ours = new LinkedHashSet<>(mine.getTreaties());
        ours.add(treaty);
        mine.setTreaties(ours);
        theirs.setTreaties(ours);
    }

    private void removeTreaty(DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs, DiplomacyTreaty treaty) {
        Set<DiplomacyTreaty> ours = new LinkedHashSet<>(mine.getTreaties());
        ours.remove(treaty);
        mine.setTreaties(ours);
        theirs.setTreaties(ours);
    }

    /** Удавшаяся договорённость поднимает доверие обеих сторон. */
    private void reward(PlayerEntity player, PlayerEntity other,
                        DiplomacyRelationEntity mine, DiplomacyRelationEntity theirs) {
        gainTrust(mine, player, AGREEMENT_BONUS);
        gainTrust(theirs, other, AGREEMENT_BONUS);
    }

    private DiplomacyTreaty requireTreaty(DiplomacyActionRequest request) {
        if (request.treaty() == null) {
            throw new ConflictException("diplomacy.treatyMissing");
        }
        return request.treaty();
    }

    /**
     * Отношения пары для действия — п. 15. Знакомство на паузе действовать не даёт: с
     * соседом, до которого нельзя дотянуться, не о чем и не через кого говорить.
     */
    private DiplomacyRelationEntity requireRelation(UUID playerId, UUID otherPlayerId) {
        return relationRepository.findByPlayerIdAndOtherPlayerId(playerId, otherPlayerId)
                .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                .orElseThrow(() -> new ConflictException("diplomacy.noContact"));
    }

    /**
     * Отношения для экрана — п. 15.
     * <p>
     * <b>Доверие показывается чужое, а не своё.</b> Отношения хранятся двумя строками, и
     * все решения соседа принимает его строка: он соглашается на договор, когда доверяет
     * <i>он</i>. Показывать своё отношение к нему значило бы дать игроку число, которое
     * ничего не предсказывает: подарок поднимал бы чужое доверие, а на экране ничего не
     * менялось бы — так и было, пока подарков не появилось и проверить это было нечем.
     *
     * Своё доверие уходит в {@code yourTrust}: оно не предсказывает чужой ответ, зато
     * именно его подтачивают чужие кражи и поимки агентов (п. 13), и по нему игрок судит,
     * как сам отнесётся к соседу.
     *
     * @param theirTrust доверие соседа к игроку; {@code null} — обратной строки нет,
     *                   тогда показывается своё, чтобы поле не осталось пустым
     */
    private DiplomacyRelationDto toDto(DiplomacyRelationEntity relation, PlayerEntity other,
                                       Map<String, String> raceNames, Integer theirTrust,
                                       String answer) {
        return toDto(relation, other, raceNames, theirTrust, answer, null);
    }

    /**
     * То же, но с самим игроком: по нему считается признак «переговоры ведутся».
     * <p>
     * Отталкивающая раса ЛЮБОЙ из двух сторон отменяет переговоры (см.
     * {@code requireNotRepulsive}), поэтому одного соседа тут мало — нужен и тот, кто
     * смотрит. Без игрока признак остаётся правдой: так строка ведёт себя как прежде.
     */
    private DiplomacyRelationDto toDto(DiplomacyRelationEntity relation, PlayerEntity other,
                                       Map<String, String> raceNames, Integer theirTrust,
                                       String answer, PlayerEntity player) {
        List<DiplomacyTreaty> treaties = List.copyOf(relation.getTreaties());
        return new DiplomacyRelationDto(
                relation.getOtherPlayerId(),
                other == null ? null : messages.empire(other.getName()),
                other == null ? null : raceService.raceName(other, raceNames::get),
                other == null ? null : other.getRaceCode(),
                other == null ? null : other.getColor(),
                relation.getStance().name(),
                messages.label(relation.getStance()),
                theirTrust == null ? relation.getTrust() : theirTrust,
                relation.getTrust(),
                treaties.stream().map(Enum::name).toList(),
                treaties.stream().map(messages::label).toList(),
                character(other),
                relation.getMetTurn(),
                answer,
                negotiates(player, other),
                other != null && other.getPlayerType() == com.sddnw.server.domain.enums.PlayerType.AI
                        && other.getAiPersonality() != null ? other.getAiPersonality().name() : null);
    }

    /** Ведутся ли переговоры этих двоих: отталкивающая раса любой стороны отменяет их. */
    private Boolean negotiates(PlayerEntity player, PlayerEntity other) {
        if (other == null) {
            return Boolean.TRUE;
        }
        if (player != null && Boolean.TRUE.equals(raceService.effects(player).repulsive())) {
            return Boolean.FALSE;
        }
        return !Boolean.TRUE.equals(raceService.effects(other).repulsive());
    }

    /**
     * Правитель соседа одной строкой — «Агрессивный промышленник» окна Report MOO II.
     * <p>
     * По этим двум словам игрок и судит, чего от соседа ждать: первое — как он ведёт себя
     * в дипломатии, второе — куда вкладывается. У человека их нет: за него решает человек.
     * <p>
     * Строка нужна и окну «Инфо» ({@link EmpireInfoService}) — там она стоит рядом со
     * сторонами расы соседа, — поэтому собирается здесь, в одном месте на всю игру.
     */
    public String character(PlayerEntity other) {
        if (other == null || other.getAiPersonality() == null) {
            return null;
        }
        return other.getAiObjective() == null
                ? messages.label(other.getAiPersonality())
                : messages.get("diplomacy.character", other.getAiPersonality(),
                        messages.label(other.getAiObjective()).toLowerCase());
    }

    private Integer clamp(Integer trust) {
        return Math.max(0, Math.min(100, trust));
    }

    /**
     * Прибавка доверия с поправкой на характер — п. 15.
     * <p>
     * Хорошее в дипломатии — договор, подарок, удачный обмен — каждый принимает по-своему.
     * Ксенофоб, по описанию MOO II, «вдвое слабее принимает хорошее», и это единственная
     * поправка на прибавку; у остальных характеров она полная. У человека характера нет:
     * за него решает он сам.
     *
     * @param owner чья это строка отношений — её доверие и правится
     */
    private void gainTrust(DiplomacyRelationEntity relation, PlayerEntity owner, Integer amount) {
        Integer charm = diplomacyPercentOf(relation.getOtherPlayerId());
        Integer value = scaled(scaled(amount, 100 + charm), gainPercent(owner));
        relation.setTrust(clamp(relation.getTrust() + value));
    }

    /**
     * Убавка доверия с поправкой на характер — п. 15.
     * <p>
     * Плохое — отказ, разрыв, война, дань, кража — ксенофоб и благородный принимают
     * «вдвое сильнее»: первый не доверяет никому, второй не прощает удара без повода.
     */
    private void loseTrust(DiplomacyRelationEntity relation, PlayerEntity owner, Integer amount) {
        // Обаяние смягчает удар: у MOO II «отрицательные действия приносят вдвое меньше
        // вреда», а вдвое меньше — это и есть сотня процентов обаяния в знаменателе.
        Integer charm = diplomacyPercentOf(relation.getOtherPlayerId());
        Integer value = scaled(amount * 100 / (100 + charm), lossPercent(owner));
        relation.setTrust(clamp(relation.getTrust() - value));
    }

    /**
     * Насколько империя приятна в переговорах — п. 7, п. 14: обаятельные и телепаты.
     * <p>
     * Спрашивается у самой империи, чьё дело меняет доверие: доверие держит мнение
     * <i>о ней</i>, и в строке отношений она стоит вторым игроком.
     */
    private Integer diplomacyPercentOf(UUID playerId) {
        return playerRepository.findById(playerId)
                .map(player -> raceService.effects(player).diplomacyPercent())
                .orElse(0);
    }

    /**
     * Планка согласия с поправкой на обаяние просящего — п. 7.
     * <p>
     * <b>Реконструкция.</b> В MOO II обаятельная раса получает «50% бонус на то, что
     * сторона согласится», то есть прибавку к вероятности. Здесь согласие считается не
     * броском, а порогом доверия, поэтому обаяние опускает сам порог: вдвое у
     * обаятельных, на пятую часть у телепатов.
     */
    private Integer charmed(PlayerEntity asking, Integer required) {
        int bar = required * 100 / (100 + raceService.effects(asking).diplomacyPercent());
        // Ксенопсихология (перенос MOO II): «permanently add +30 diplomatic points to every deal».
        if (technologiesOf(asking.getId()).contains(XENO_PSYCHOLOGY_TECH)) {
            bar -= XENO_PSYCHOLOGY_TRUST;
        }
        return Math.max(0, bar);
    }

    /** Технология, облегчающая всякую сделку, — Sociology (перенос MOO II). */
    public static final String XENO_PSYCHOLOGY_TECH = "xeno-psychology";

    /**
     * На сколько ксенопсихология опускает планку доверия. <i>Реконструкция</i>: «+30 дипломатических
     * очков» оригинала — на его шкале отношений от −100 до +100, то есть 15 % её размаха; наша шкала
     * доверия — 0…100, и 15 % от неё — 15.
     */
    public static final int XENO_PSYCHOLOGY_TRUST = 15;

    /** Доля от величины в процентах, но не меньше единицы: поправка не должна всё съедать. */
    private Integer scaled(Integer amount, Integer percent) {
        return amount <= 0 ? amount : Math.max(1, amount * percent / 100);
    }

    private Integer gainPercent(PlayerEntity owner) {
        return owner == null || owner.getAiPersonality() == null
                ? 100
                : owner.getAiPersonality().getGainPercent();
    }

    private Integer lossPercent(PlayerEntity owner) {
        return owner == null || owner.getAiPersonality() == null
                ? 100
                : owner.getAiPersonality().getLossPercent();
    }
}
