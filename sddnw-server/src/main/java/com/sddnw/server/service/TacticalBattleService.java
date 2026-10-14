package com.sddnw.server.service;

import com.sddnw.server.domain.BattleMissile;
import com.sddnw.server.domain.ShipHull;
import com.sddnw.server.domain.ShipStats;
import com.sddnw.server.domain.entity.BattleShipEntity;
import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.FleetShipEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import com.sddnw.server.domain.entity.SpaceBattleEntity;
import com.sddnw.server.domain.enums.BattleActionType;
import com.sddnw.server.domain.enums.BattleSide;
import com.sddnw.server.domain.enums.BattleState;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.enums.WeaponKind;
import com.sddnw.server.dto.BattleActionRequest;
import com.sddnw.server.dto.BattleDto;
import com.sddnw.server.dto.BattleEventDto;
import com.sddnw.server.dto.BattleMissileDto;
import com.sddnw.server.domain.enums.ShipComponentSlot;
import com.sddnw.server.domain.WeaponModification;
import com.sddnw.server.dto.BattleShipDto;
import com.sddnw.server.dto.BattleWeaponDto;
import com.sddnw.server.repository.BattleShipRepository;
import com.sddnw.server.repository.FleetRepository;
import com.sddnw.server.repository.SpaceBattleRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import com.sddnw.server.domain.enums.ShipEffectType;
import java.util.TreeMap;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

/**
 * Тактический бой кораблей — п. 8, сцена боя MOO II.
 * <p>
 * <b>Ходят корабли, а не игроки.</b> Очередь в круге строится по инициативе корабля
 * ({@link BattleRules#initiative}) и идёт сквозь обе стороны: быстрый фрегат успевает
 * выстрелить раньше чужого дредноута. Отходил круг — начинается следующий, щиты
 * восстанавливаются, движение и залп открываются заново.
 * <p>
 * <b>Бой живёт на сервере.</b> Он списывает корабли и решает исход встречи, поэтому
 * состояние поля, попадания и потери считает сервер, а экран боя только показывает поле
 * и просит ходы. Кораблями ИИ ходит сам сервер — сразу, как очередь доходит до них:
 * клиент получает пачку событий и проигрывает их подряд.
 * <p>
 * <b>Заглушка осталась рядом.</b> «Авто» в диалоге встречи по-прежнему считает бой одной
 * формулой ({@code stub/SpaceBattleService}) — это быстрый путь для тех, кому сцена не
 * нужна. Ручной бой идёт сюда.
 */
@Service
public class TacticalBattleService {

    private static final Logger log = LoggerFactory.getLogger(TacticalBattleService.class);

    /**
     * Сколько кораблей одной стороны помещается на поле.
     * <p>
     * Реконструкция: в MOO II на тактическую карту тоже выходит не весь флот. Двадцать
     * четыре — два полных столбца поля; остальные корабли в бою не участвуют и потерь
     * не несут.
     */
    public static final int MAX_SHIPS_PER_SIDE = 24;

    /**
     * Ключ подписи стороны чудищ — п. 11.1.
     * <p>
     * Ключ, а не текст: он идёт и в живой журнал (там переводится сразу), и в хранимый
     * исход боя (там переводится при чтении, на языке того, кто открыл отчёт).
     */
    private static final String MONSTER_SIDE_KEY = "battle.side.monster";

    /**
     * Предел кругов боя. Реконструкция: в MOO II бой кончается сам, когда одна сторона
     * разбита или ушла. Два флота из одних «черепах» могут не пробить друг друга вовсе,
     * и без предела бой висел бы вечно — на этом круге стороны расходятся.
     */
    private static final int MAX_ROUNDS = 30;

    private final Messages messages;
    private final SpaceBattleRepository battleRepository;
    private final BattleShipRepository shipRepository;
    private final BattleRules rules;
    private final ShipDesignService shipDesignService;
    private final ShipDesignRules shipDesignRules;
    private final ShipCatalog shipCatalog;
    private final RaceService raceService;
    private final FleetService fleetService;
    private final FleetRepository fleetRepository;
    private final LeaderBonusService leaderBonuses;

    /** Разбитая оборона колонии — п. 8, п. 11: платформа списывается с планеты, а не с флота. */
    private final OrbitalDefenceService orbitalDefence;
    /** Кто из людей ушёл из-за стола — его империю ведёт ИИ (backlog-promo, пункт 11). */
    private final PresenceService presence;
    /** Абордаж считается тем же расчётом, что и наземный бой (п. 8, п. 12). */
    private final GroundCombatService groundCombat;
    private final LeaderRecordService leaderRecord;

    /**
     * Счётчик первых встреч с механиками — field-backlog, пункт 93. Полем, а не параметром
     * конструктора: службу собирают руками юнит-тесты, а им замер ни к чему (там он пуст).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmpireActivityService activity;

    public TacticalBattleService(Messages messages,
                                 SpaceBattleRepository battleRepository,
                                 BattleShipRepository shipRepository,
                                 BattleRules rules,
                                 ShipDesignService shipDesignService,
                                 ShipDesignRules shipDesignRules,
                                 ShipCatalog shipCatalog,
                                 RaceService raceService,
                                 FleetService fleetService,
                                 FleetRepository fleetRepository,
                                 LeaderBonusService leaderBonuses,
                                 OrbitalDefenceService orbitalDefence,
                                 PresenceService presence,
                                 GroundCombatService groundCombat,
                                 LeaderRecordService leaderRecord) {
        this.leaderRecord = leaderRecord;
        this.presence = presence;
        this.groundCombat = groundCombat;
        this.messages = messages;
        this.orbitalDefence = orbitalDefence;
        this.fleetRepository = fleetRepository;
        this.leaderBonuses = leaderBonuses;
        this.battleRepository = battleRepository;
        this.shipRepository = shipRepository;
        this.rules = rules;
        this.shipDesignService = shipDesignService;
        this.shipDesignRules = shipDesignRules;
        this.shipCatalog = shipCatalog;
        this.raceService = raceService;
        this.fleetService = fleetService;
    }

    /** Бой и его корабли — то, чем оперируют все остальные методы. */
    public record BattleView(SpaceBattleEntity battle, List<BattleShipEntity> ships,
                             List<BattleEventDto> events) {
    }

    /**
     * Разворачивает два флота в бой — п. 8.
     * <p>
     * Во флоте корабли лежат числом по проектам; на поле каждый становится своей
     * единицей: иначе ни ходить, ни гибнуть по одному они не смогут. Строй нападающего
     * встаёт слева, обороняющегося — справа.
     */
    @Transactional
    public SpaceBattleEntity start(UUID gameId, UUID encounterId, UUID starSystemId, Integer turn,
                                   PlayerEntity attacker, FleetEntity attackerFleet,
                                   PlayerEntity defender, FleetEntity defenderFleet,
                                   Long gameSeed) {
        return start(gameId, encounterId, starSystemId, turn, attacker, attackerFleet,
                defender, defenderFleet, List.of(), gameSeed);
    }

    /**
     * Тот же бой, но с ОБОРОНОЙ КОЛОНИИ на стороне защищающегося — п. 8, п. 11.
     * <p>
     * Платформы приходят проектами ({@code defenderPlatforms} — по записи на каждую) и
     * встают в строй наравне с кораблями: у них те же пушки, та же броня и та же кривая
     * попадания, только двигателя нет — с места они не сходят. Флота у обороны при этом
     * может не быть вовсе: колония со звёздной базой защищается и сама.
     */
    @Transactional
    public SpaceBattleEntity start(UUID gameId, UUID encounterId, UUID starSystemId, Integer turn,
                                   PlayerEntity attacker, FleetEntity attackerFleet,
                                   PlayerEntity defender, FleetEntity defenderFleet,
                                   List<UUID> defenderPlatforms,
                                   Long gameSeed) {
        SpaceBattleEntity battle = new SpaceBattleEntity();
        battle.setGameId(gameId);
        battle.setEncounterId(encounterId);
        battle.setStarSystemId(starSystemId);
        battle.setAttackerPlayerId(attacker.getId());
        battle.setDefenderPlayerId(defender.getId());
        battle.setTurn(turn);
        battle.setRound(1);
        battle.setState(BattleState.IN_PROGRESS);
        // Зерно от партии, хода и мест обеих сторон — и ни в коем случае не от
        // идентификаторов: те у каждой партии свои случайные, и повтор той же партии с тем
        // же зерном давал бы другие бои. Парные прогоны балансировки на этом и держатся
        // (balance-metrics-works.txt, этап 0). Правило переехало в BattleRules, к остальным
        // числам боя: быстрый бой бросает жребий тем же зерном, и врозь они уже разошлись —
        // см. BattleRules.battleSeed.
        battle.setSeed(BattleRules.battleSeed(gameSeed, turn,
                attacker.getSlot(), defender.getSlot()));
        battleRepository.saveAndFlush(battle);

        List<BattleShipEntity> ships = new ArrayList<>();
        ships.addAll(deploy(battle, attacker, attackerFleet, BattleSide.ATTACKER));
        ships.addAll(deploy(battle, defender, defenderFleet, defenderPlatforms, BattleSide.DEFENDER));
        shipRepository.saveAll(ships);
        shipRepository.flush();

        battle.setCurrentShipId(order(ships).stream().findFirst().map(BattleShipEntity::getId).orElse(null));
        battleRepository.save(battle);

        log.info("Бой в системе {}: {} кораблей против {}", starSystemId,
                ships.stream().filter(ship -> ship.getSide() == BattleSide.ATTACKER).count(),
                ships.stream().filter(ship -> ship.getSide() == BattleSide.DEFENDER).count());
        return battle;
    }

    /**
     * Заводит бой прямо из составов, без флотов и без встречи — п. 8.
     * <p>
     * Так собирается демонстрационный бой: партии с флотами за ним нет, есть только два
     * строя кораблей. Всё остальное — очередь, ходы, попадания, конец боя — то же самое,
     * что и в настоящем бою: демонстрация показывает игру, а не её подобие.
     */
    @Transactional
    public SpaceBattleEntity startBetween(UUID gameId, UUID starSystemId, Integer turn,
                                          PlayerEntity attacker, List<ShipCount> attackerShips,
                                          PlayerEntity defender, List<ShipCount> defenderShips,
                                          Long seed) {
        SpaceBattleEntity battle = new SpaceBattleEntity();
        battle.setGameId(gameId);
        battle.setStarSystemId(starSystemId);
        battle.setAttackerPlayerId(attacker.getId());
        battle.setDefenderPlayerId(defender.getId());
        battle.setTurn(turn);
        battle.setRound(1);
        battle.setState(BattleState.IN_PROGRESS);
        battle.setSeed(seed);
        battleRepository.saveAndFlush(battle);

        List<BattleShipEntity> ships = new ArrayList<>();
        ships.addAll(deploy(battle, attacker, attackerShips, BattleSide.ATTACKER));
        ships.addAll(deploy(battle, defender, defenderShips, BattleSide.DEFENDER));
        shipRepository.saveAll(ships);
        shipRepository.flush();

        battle.setCurrentShipId(order(ships).stream().findFirst().map(BattleShipEntity::getId).orElse(null));
        battleRepository.save(battle);
        return battle;
    }

    /**
     * Один ход демонстрации: сервер ходит за корабль, чья очередь, кем бы тот ни был — п. 8.
     * <p>
     * В обычном бою так ходят только корабли ИИ, и то следом за ходом человека. Здесь
     * человека нет вовсе, поэтому шаг делается по запросу извне: экран просит их один за
     * другим и показывает бой как кино. Ходить за живого игрока этим нельзя — метод
     * зовёт только демонстрация, у которой обе стороны ИИ.
     */
    @Transactional
    public BattleView autoStep(UUID battleId, Map<UUID, PlayerEntity> players) {
        SpaceBattleEntity battle = require(battleId);
        if (battle.getState() != BattleState.IN_PROGRESS) {
            return new BattleView(battle, order(ships(battleId)), List.of());
        }

        List<BattleEventDto> events = new ArrayList<>();
        Context context = context(battle, order(ships(battleId)), players);
        BattleShipEntity current = context.current();
        if (current != null) {
            playAi(context, current, events);
        }
        advance(context, events);
        save(context);
        return new BattleView(context.battle(), order(context.ships()), events);
    }

    /**
     * Ходы ИИ и чудищ, стоящих в очереди первыми, — п. 8 (13.10.2026).
     * <p>
     * Обычно корабли ИИ ходят следом за ходом человека ({@link #playOthers}). Но бой может
     * НАЧАТЬСЯ с их хода — чудище быстрее фрегата, — и тогда нажимать человеку нечего, а
     * сервер сам не ходит: бой стоял бы. Экран, открывшийся не на своём ходу, зовёт этот
     * метод и проигрывает пришедшие события, как будто бой шёл у игрока на глазах. Если
     * очередь у человека (своя или соседа), не делается ничего.
     */
    @Transactional
    public BattleView playOpponents(UUID battleId, Map<UUID, PlayerEntity> players) {
        SpaceBattleEntity battle = require(battleId);
        if (battle.getState() != BattleState.IN_PROGRESS) {
            return new BattleView(battle, order(ships(battleId)), List.of());
        }
        List<BattleEventDto> events = new ArrayList<>();
        Context context = context(battle, order(ships(battleId)), players);
        playOthers(context, events);
        save(context);
        return new BattleView(context.battle(), order(context.ships()), events);
    }

    /** Состояние боя без событий: экран открылся и спрашивает поле. */
    @Transactional(readOnly = true)
    public BattleView view(UUID battleId) {
        SpaceBattleEntity battle = require(battleId);
        return new BattleView(battle, order(ships(battleId)), List.of());
    }

    /** Бои партии, которые ещё идут: по ним клиент понимает, что сцену пора открыть. */
    @Transactional(readOnly = true)
    public List<SpaceBattleEntity> active(UUID gameId) {
        return battleRepository.findAllByGameIdAndState(gameId, BattleState.IN_PROGRESS);
    }

    /**
     * Ход корабля — п. 8.
     * <p>
     * Ходить можно только своим кораблём и только когда очередь его. Движение ход не
     * заканчивает, залп и пропуск — заканчивают; после этого очередь идёт дальше, и
     * корабли ИИ ходят тут же, пока очередь не дойдёт до корабля человека или бой
     * не кончится.
     */
    @Transactional
    public BattleView act(PlayerEntity player, UUID battleId, BattleActionRequest request,
                          Map<UUID, PlayerEntity> players) {
        SpaceBattleEntity battle = require(battleId);
        if (battle.getState() != BattleState.IN_PROGRESS) {
            throw new ConflictException("battle.over");
        }

        List<BattleShipEntity> ships = order(ships(battleId));
        BattleShipEntity ship = ships.stream()
                .filter(candidate -> candidate.getId().equals(request.shipId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("battle.shipNotFound", request.shipId()));

        if (!ship.getOwnerPlayerId().equals(player.getId())) {
            throw new ForbiddenException("battle.shipNotYours");
        }
        if (!ship.getId().equals(battle.getCurrentShipId())) {
            throw new ConflictException("battle.notYourTurn");
        }

        List<BattleEventDto> events = new ArrayList<>();
        Context context = context(battle, ships, players);

        switch (request.action()) {
            case MOVE -> move(context, ship, request.x(), request.y(), events);
            case FIRE -> {
                if (request.targetMissileId() != null) {
                    fireAtMissile(context, ship, request.targetMissileId(),
                            request.weaponRows() == null || request.weaponRows().isEmpty()
                                    ? null : new HashSet<>(request.weaponRows()), events);
                } else {
                    fire(context, ship, target(ships, request.targetShipId()), request.weaponRows(), events);
                }
                endShipTurn(context, events);
            }
            case PASS -> {
                events.add(event("PASS", ship, null, null,
                        messages.get("battle.log.pass", name(context, ship))));
                endShipTurn(context, events);
            }
            case BOARD -> {
                board(context, ship, target(ships, request.targetShipId()), Boolean.FALSE, events);
                endShipTurn(context, events);
            }
            case RAID -> {
                board(context, ship, target(ships, request.targetShipId()), Boolean.TRUE, events);
                endShipTurn(context, events);
            }
            case RETREAT -> {
                if (Boolean.TRUE.equals(retreat(context, ship, events))) {
                    // Нетронутый ход — корабль уходит сразу: указатель ещё на нём, и очередь
                    // отходящих уводит его тем же правилом, что и дождавшегося своего хода.
                    departRetreating(context, events);
                    playOthers(context, events);
                } else {
                    endShipTurn(context, events);
                }
            }
            case SELF_DESTRUCT -> {
                selfDestruct(context, ship, events);
                endShipTurn(context, events);
            }
        }

        save(context);
        return new BattleView(context.battle(), order(context.ships()), events);
    }

    /** Ставит корабли флота в строй своей стороны. */
    /** Сколько кораблей какого проекта выходит в бой — общий вид состава для расстановки. */
    public record ShipCount(UUID designId, Integer ships, Integer experience) {

        /** Корабли без опыта — платформы, чудища и демонстрация. */
        public ShipCount(UUID designId, Integer ships) {
            this(designId, ships, 0);
        }
    }

    /** Строй стороны из её флота: корабли лежат в нём числом по проектам. */
    private List<BattleShipEntity> deploy(SpaceBattleEntity battle, PlayerEntity owner,
                                          FleetEntity fleet, BattleSide side) {
        return deploy(battle, owner, fleet, List.of(), side);
    }

    /**
     * То же, но вместе с платформами обороны колонии — п. 8, п. 11.
     * <p>
     * Платформы ставятся ПЕРВЫМИ: поле не резиновое ({@code MAX_SHIPS_PER_SIDE}), и если
     * обороне не хватает места, уступать его должен подвижный флот, а не то, что улететь
     * всё равно не может. Флота может не быть вовсе — колонию прикрывает одна оборона.
     */
    private List<BattleShipEntity> deploy(SpaceBattleEntity battle, PlayerEntity owner,
                                          FleetEntity fleet, List<UUID> platforms,
                                          BattleSide side) {
        Map<UUID, ShipStats> stats = shipDesignService.statsOf(owner);

        List<FleetShipEntity> rows = fleet == null
                ? List.of()
                : fleetService.composition(List.of(fleet)).getOrDefault(fleet.getId(), List.of());

        // Сперва самые сильные: если весь флот на поле не помещается, в бой идут лучшие.
        List<ShipCount> sorted = rows.stream()
                .sorted(Comparator.comparing((FleetShipEntity row) -> {
                    ShipStats value = stats.get(row.getDesignId());
                    return value == null ? 0 : value.structure() + value.armour() + value.attack();
                }).reversed())
                .map(row -> new ShipCount(row.getDesignId(), row.getShips(), row.getExperience()))
                .toList();

        if (platforms.isEmpty()) {
            return deploy(battle, owner, sorted, side);
        }
        List<ShipCount> composition = new ArrayList<>(platforms.size() + sorted.size());
        platforms.stream()
                .collect(Collectors.groupingBy(one -> one, LinkedHashMap::new, Collectors.counting()))
                .forEach((designId, count) -> composition.add(new ShipCount(designId, count.intValue())));
        composition.addAll(sorted);
        return deploy(battle, owner, composition, side);
    }

    /**
     * Ставит строй стороны на поле — п. 8.
     * <p>
     * Клетки считаются от <b>общего</b> числа кораблей стороны, а не от числа кораблей
     * одного проекта: иначе каждый проект центрировался бы отдельно и корабли разных
     * проектов встали бы на одни и те же клетки.
     */
    private List<BattleShipEntity> deploy(SpaceBattleEntity battle, PlayerEntity owner,
                                          List<ShipCount> composition, BattleSide side) {
        Map<UUID, ShipDesignEntity> designs = shipDesignService.allDesignsOf(owner);
        Map<UUID, ShipStats> stats = shipDesignService.statsByDesign(designs.values(),
                raceService.effects(owner));
        Map<UUID, List<ShipDesignRules.Item>> items = shipDesignService.itemsOf(designs.values());

        int total = Math.min(MAX_SHIPS_PER_SIDE,
                composition.stream().mapToInt(ShipCount::ships).sum());

        List<BattleShipEntity> ships = new ArrayList<>();
        int index = 0;
        for (ShipCount row : composition) {
            ShipStats value = stats.get(row.designId());
            if (value == null) {
                continue;
            }
            List<ShipDesignRules.Item> parts = items.getOrDefault(row.designId(), List.of());
            for (int number = 0; number < row.ships() && index < MAX_SHIPS_PER_SIDE; number++, index++) {
                BattleShipEntity ship = new BattleShipEntity();
                ship.setBattleId(battle.getId());
                ship.setOwnerPlayerId(owner.getId());
                ship.setDesignId(row.designId());
                ship.setSide(side);
                ship.setOrdinal(index + 1);
                ship.setStructure(value.structure());
                ship.setArmour(value.armour());
                ship.setShield(value.shield());
                ship.setInitiative(rules.initiative(value, parts));
                ship.setMarines(rules.marines(shipCatalog.hull(designs.get(row.designId()).getHullCode()), parts));
                ship.setCrewBonus(CrewRules.bonus(row.experience()));
                int[] cell = rules.startingCell(side, index, total);
                ship.setX(cell[0]);
                ship.setY(cell[1]);
                ships.add(ship);
            }
        }
        return ships;
    }

    /** Перелёт корабля: не дальше своей скорости и не на занятую клетку. */
    private void move(Context context, BattleShipEntity ship, Integer x, Integer y,
                      List<BattleEventDto> events) {
        if (x == null || y == null || !rules.onField(x, y)) {
            throw new ConflictException("battle.noSuchCell");
        }
        if (occupied(context.ships(), x, y)) {
            throw new ConflictException("battle.cellTaken");
        }

        // Разбитый двигатель — это неподвижность (п. 8), и сказать об этом нужно прямо:
        // «слишком далеко на 0 клеток» игрок прочёл бы как поломку игры, а не корабля.
        if (Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM))) {
            throw new ConflictException("battle.engineWrecked");
        }
        int distance = rules.distance(ship.getX(), ship.getY(), x, y);
        int left = speed(context, ship) - ship.getMoved();
        if (distance > left) {
            throw new ConflictException("battle.tooFar", left, distance);
        }

        ship.setX(x);
        ship.setY(y);
        ship.setMoved(ship.getMoved() + distance);
        events.add(event("MOVE", ship, null, null,
                messages.get("battle.log.move", name(context, ship), x, y)));
    }

    /**
     * Залп по цели — п. 8.
     * <p>
     * Каждый ствол стреляет отдельно: попадание разыгрывается по шансу от размера корпуса
     * цели, а щит гасит выстрелы поодиночке. Дошедший урон снимает броню, а остаток —
     * прочность корпуса; на нуле корабль уничтожен.
     */
    private void fire(Context context, BattleShipEntity ship, BattleShipEntity target,
                      List<Integer> weaponRows, List<BattleEventDto> events) {
        if (Boolean.TRUE.equals(ship.getFired())) {
            throw new ConflictException("battle.alreadyFired");
        }
        if (target.getSide() == ship.getSide()) {
            throw new ConflictException("battle.friendlyFire");
        }
        if (!target.alive()) {
            throw new ConflictException("battle.targetDestroyed");
        }
        if (Boolean.TRUE.equals(frozen(context, target))) {
            throw new ConflictException("battle.targetFrozen");
        }
        if (Boolean.TRUE.equals(phased(context, target))) {
            throw new ConflictException("battle.targetHidden");
        }
        Model model = context.model(ship);
        // Стволы берутся у САМОГО КОРАБЛЯ, а не у проекта: разбитая пушка не стреляет —
        // п. 8. Корабль, у которого выбили все стволы, не стреляет вовсе.
        //
        // Стреляют только ВЫБРАННЫЕ строки оружия (п. 8, как в полосе боя оригинала): игрок
        // включает и выключает стволы, чтобы, скажем, не тратить ракеты на добитый корабль.
        // Пустой выбор — стреляет всё, как было всегда.
        List<ShipDesignRules.Item> parts = weaponRows == null || weaponRows.isEmpty()
                ? parts(context, ship)
                : rules.partsLeft(model.items(), ship.getDamagedSystems(), new HashSet<>(weaponRows));
        List<BattleRules.Shot> salvo = weaponRows == null || weaponRows.isEmpty()
                ? shots(context, ship)
                : rules.shots(parts, rules.engineCombatSpeed(model.items()));
        // Шаттлы уходят вместе с залпом (п. 8, абордаж): корабль с одними шаттлами стреляет ими.
        int shuttles = shuttlesFor(context, ship, target);
        // После двойного залпа вид оружия круг молчит (гиперконденсаторы, скоростные стойки — п. 8).
        salvo = salvo.stream().filter(shot -> !resting(context, ship, shot)).toList();
        // Отсеки истребителей уходят с залпом тоже (п. 8): корабль-носитель стреляет звеньями.
        // Звено, отбившее удары, возвращается на носитель перевооружаться (п. 8, MOO II: «before returning to
        // the launching ship»): пока оно не дома, отсек молчит ({@link BattleRules#FIGHTER_REARM_ROUNDS}).
        boolean home = Boolean.TRUE.equals(fightersHome(context, ship));
        int bays = home && rules.special(parts(context, ship), ShipEffectType.FIGHTER_BAY) > 0 ? 1 : 0;
        // Устройства, бьющие с залпом (пульсар, гиродестабилизатор, плазменная сеть — п. 8), ходят
        // за ствол: корабль с одним пульсаром тоже «стреляет». Цель вне их досягаемости — отказ.
        int reachOfDevices = deviceReach(context, ship);
        int targetDistance = rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY());
        if (salvo.isEmpty() && shuttles == 0 && bays == 0 && reachOfDevices > 0) {
            if (targetDistance > reachOfDevices) {
                throw new ConflictException("battle.outOfRange", reachOfDevices);
            }
            bays = 1;
        }
        if (salvo.isEmpty() && shuttles == 0 && bays == 0 && !parts.isEmpty()
                && !shots(context, ship).isEmpty() && Boolean.TRUE.equals(allResting(context, ship))) {
            throw new ConflictException("battle.weaponsResting");
        }
        if (salvo.isEmpty() && shuttles == 0 && bays == 0) {
            throw new ConflictException(weaponRows == null || weaponRows.isEmpty()
                    ? "battle.weaponsWrecked" : "battle.noWeaponChosen");
        }
        if (!salvo.isEmpty() && shuttles == 0 && bays == 0 && !rules.inReach(salvo, ship.getX(), ship.getY(),
                target.getX(), target.getY())) {
            throw new ConflictException("battle.outOfRange", rules.reach(salvo));
        }

        Model targetModel = context.model(target);
        // Офицеры флота считаются наравне с приборами корабля — п. 6: «Оружейник»
        // добавляет меткости стреляющему, «Рулевой» — уклонения цели.
        // Прицел тоже считается по уцелевшему: сожжённый компьютер больше не целится.
        Integer attackRating = rules.attackPercent(parts) + model.officers().attack()
                + scoutLabBonus(context, ship, target) + ship.getCrewBonus();
        Integer chance = chance(context, target, attackRating);

        // Залп разбирается по видам оружия: лучи, снаряды и ракеты уходят своими
        // событиями, и сцена рисует их по-разному. Урон копится и снимается разом.
        Volley volley = new Volley();
        Integer distance = rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY());
        Integer combatSpeed = rules.engineCombatSpeed(model.items());
        List<ShipDesignRules.Item> live = parts(context, ship);
        int round = context.battle().getRound();
        for (Map.Entry<WeaponKind, List<BattleRules.Shot>> group
                : rules.shotsByKind(parts, combatSpeed).entrySet()) {
            // Дальность у каждого ствола своя — п. 8: тяжёлая установка достаёт туда,
            // куда ближняя оборона не дотягивается вдвое.
            List<BattleRules.Shot> reaching = group.getValue().stream()
                    .filter(shot -> Boolean.TRUE.equals(rules.shotReaches(shot, distance)))
                    .filter(shot -> !resting(context, ship, shot))
                    .toList();
            if (reaching.isEmpty()) {
                continue;
            }
            // Двойной залп (п. 8): гиперконденсаторы удваивают лучи и снаряды, скоростные стойки —
            // ракеты; следующий круг этот вид оружия молчит.
            boolean beams = group.getKey() == WeaponKind.BEAM || group.getKey() == WeaponKind.PROJECTILE;
            if (beams && rules.special(live, ShipEffectType.DOUBLE_BEAM_VOLLEY) > 0) {
                reaching = doubled(reaching);
                ship.setBeamsRestRound(round + 1);
            } else if (group.getKey() == WeaponKind.MISSILE
                    && rules.special(live, ShipEffectType.DOUBLE_MISSILE_VOLLEY) > 0) {
                reaching = doubled(reaching);
                ship.setMissilesRestRound(round + 1);
            }
            if (group.getKey() == WeaponKind.MISSILE) {
                launch(context, ship, target, reaching, attackRating, model.officers().damage(),
                        chance, volley, events);
                continue;
            }
            int dealt = strike(context, ship, target, reaching, attackRating,
                    model.officers().damage(), distance, volley);
            events.add(new BattleEventDto("FIRE", ship.getId(), target.getId(),
                    target.getX(), target.getY(), dealt, group.getKey(),
                    messages.get("battle.log.fire", name(context, ship), name(context, target),
                            messages.label(group.getKey()), dealt, chance), reaching.size()));
        }
        tractor(context, ship, target, distance, events);
        if (shuttles > 0) {
            launchShuttles(context, ship, target, shuttles, events);
        }
        if (bays > 0 && rules.special(parts(context, ship), ShipEffectType.FIGHTER_BAY) > 0) {
            launchFighters(context, ship, target, events);
            ship.setFightersBackRound(context.battle().getRound() + BattleRules.FIGHTER_REARM_ROUNDS);
        }
        ship.setFired(Boolean.TRUE);
        // Выстрел раскрывает замаскированный корабль (п. 8).
        ship.setLastFiredRound(context.battle().getRound());
        land(context, ship.getId(), target, volley, events);
        stasis(context, ship, target, distance, events);
        gyro(context, ship, target, distance, events);
        plasmaWeb(context, ship, target, distance, events);
        pulsar(context, ship, events);
    }

    /** Наибольшая досягаемость устройств корабля, бьющих с залпом (п. 8); ноль — их нет. */
    private int deviceReach(Context context, BattleShipEntity ship) {
        List<ShipDesignRules.Item> live = parts(context, ship);
        return Math.max(rules.special(live, ShipEffectType.PULSAR),
                Math.max(rules.special(live, ShipEffectType.GYRO), rules.special(live, ShipEffectType.PLASMA_WEB)));
    }

    /**
     * Удар устройства мимо кривой попадания — п. 8: пульсар, гиродестабилизатор и плазменная сеть
     * не промахиваются. {@code structural} — урон прямо в корпус, мимо щита и брони
     * (гиродестабилизатор); иначе удар идёт через щит, как выстрел.
     */
    private int deviceHit(Context context, UUID shooterId, BattleShipEntity target, int damage,
                          boolean structural, List<BattleEventDto> events) {
        Volley volley = new Volley();
        int through = damage;
        if (structural) {
            volley.piercing += damage;
        } else {
            BattleRules.ShieldHit hit = rules.shieldHit(damage, shieldClass(context, target)
                    + hardShield(context, target), target.getShield());
            target.setShield(hit.pool());
            through = hit.through();
            volley.damage += through;
        }
        land(context, shooterId, target, volley, events);
        return through;
    }

    /** Гиродестабилизатор — п. 8: 1–4 урона корпусу на класс цели, дальность 15. */
    private void gyro(Context context, BattleShipEntity ship, BattleShipEntity target, int distance,
                      List<BattleEventDto> events) {
        int reach = rules.special(parts(context, ship), ShipEffectType.GYRO);
        if (reach <= 0 || distance > reach || !target.alive()) {
            return;
        }
        int damage = rules.sizedDamage(context.random(), BattleRules.GYRO_DAMAGE_PER_CLASS,
                rules.sizeClass(context.model(target).hull()));
        int dealt = deviceHit(context, ship.getId(), target, damage, true, events);
        events.add(event("FIRE", ship, target.getId(), dealt,
                messages.get("battle.log.gyro", name(context, ship), name(context, target), dealt)));
    }

    /** Плазменная сеть — п. 8: ложится на цель в пределах 15 клеток и жжёт её каждый круг. */
    private void plasmaWeb(Context context, BattleShipEntity ship, BattleShipEntity target, int distance,
                           List<BattleEventDto> events) {
        int reach = rules.special(parts(context, ship), ShipEffectType.PLASMA_WEB);
        if (reach <= 0 || distance > reach || !target.alive()) {
            return;
        }
        int strength = BattleRules.PLASMA_WEB_MIN
                + context.random().nextInt(BattleRules.PLASMA_WEB_MAX - BattleRules.PLASMA_WEB_MIN + 1);
        target.setPlasmaWeb(Math.max(target.getPlasmaWeb(), strength));
        int dealt = deviceHit(context, ship.getId(), target, strength, false, events);
        events.add(event("FIRE", ship, target.getId(), dealt,
                messages.get("battle.log.plasmaWeb", name(context, ship), name(context, target), dealt)));
    }

    /**
     * Пульсар — п. 8: бьёт все чужие корабли в радиусе (1–24 на класс корпуса, через щит) и
     * сбивает чужие залпы и звенья в полёте там же.
     */
    private void pulsar(Context context, BattleShipEntity ship, List<BattleEventDto> events) {
        int radius = rules.special(parts(context, ship), ShipEffectType.PULSAR);
        if (radius <= 0) {
            return;
        }
        int total = 0;
        for (BattleShipEntity other : order(context.ships())) {
            if (other.getSide() == ship.getSide() || !Boolean.TRUE.equals(targetable(context, other))
                    || rules.distance(ship.getX(), ship.getY(), other.getX(), other.getY()) > radius) {
                continue;
            }
            int damage = rules.sizedDamage(context.random(), BattleRules.PULSAR_DAMAGE_PER_CLASS,
                    rules.sizeClass(context.model(other).hull()));
            total += deviceHit(context, ship.getId(), other, damage, false, events);
        }
        SpaceBattleEntity battle = context.battle();
        List<BattleMissile> flying = battle.getMissiles().stream()
                .filter(missile -> missile.side() == ship.getSide()
                        || rules.distance(ship.getX(), ship.getY(), missile.x(), missile.y()) > radius)
                .toList();
        int downed = battle.getMissiles().size() - flying.size();
        battle.setMissiles(new ArrayList<>(flying));
        events.add(event("FIRE", ship, ship.getId(), total,
                messages.get("battle.log.pulsar", name(context, ship), total, downed)));
    }

    /**
     * Стазисное поле — п. 8 (13.10.2026, перенос MOO II): бьёт вместе с залпом по той же цели в
     * пределах своей дальности и замораживает её до конца следующего круга — ни хода, ни выстрела,
     * и атаковать её нельзя.
     */
    private void stasis(Context context, BattleShipEntity ship, BattleShipEntity target, int distance,
                        List<BattleEventDto> events) {
        int reach = rules.special(parts(context, ship), ShipEffectType.STASIS);
        if (reach <= 0 || distance > reach || !target.alive()) {
            return;
        }
        target.setFrozenUntilRound(context.battle().getRound() + 1);
        events.add(event("STASIS", ship, target.getId(), 0,
                messages.get("battle.log.stasis", name(context, ship), name(context, target))));
    }

    /** Заморожен ли корабль стазисом сейчас (п. 8). */
    private Boolean frozen(Context context, BattleShipEntity ship) {
        Integer until = ship.getFrozenUntilRound();
        return until != null && context.battle().getRound() <= until;
    }

    /**
     * Замаскирован ли корабль сейчас — п. 8: маскировка стоит и цела, а корабль не стрелял в
     * прошлом круге и в этом («must wait one turn without firing to recloak»).
     */
    private Boolean cloaked(Context context, BattleShipEntity ship) {
        if (rules.special(parts(context, ship), ShipEffectType.CLOAK) <= 0) {
            return Boolean.FALSE;
        }
        Integer fired = ship.getLastFiredRound();
        return fired == null || context.battle().getRound() > fired + 1;
    }

    /** Невидим ли корабль под фазовым плащом: замаскирован и не вышли его первые круги (п. 8). */
    private Boolean phased(Context context, BattleShipEntity ship) {
        int rounds = rules.special(parts(context, ship), ShipEffectType.PHASING);
        return rounds > 0 && context.battle().getRound() <= rounds && Boolean.TRUE.equals(cloaked(context, ship));
    }

    /** Можно ли атаковать корабль: жив, не заморожен и не скрыт фазовым плащом (п. 8). */
    private Boolean targetable(Context context, BattleShipEntity ship) {
        return ship.alive() && !Boolean.TRUE.equals(frozen(context, ship))
                && !Boolean.TRUE.equals(phased(context, ship));
    }

    /** Прибавка маскировки к защите от лучей (п. 8): +80, пока корабль замаскирован. */
    private Integer cloakDefence(Context context, BattleShipEntity ship) {
        return Boolean.TRUE.equals(cloaked(context, ship))
                ? rules.special(parts(context, ship), ShipEffectType.CLOAK) : 0;
    }

    /** Класс щита корабля сейчас: разбитый генератор не срезает ничего (п. 8). */
    private Integer shieldClass(Context context, BattleShipEntity ship) {
        if (Boolean.TRUE.equals(ship.systemDamaged(BattleRules.SHIELD_SYSTEM))) {
            return 0;
        }
        Integer value = context.model(ship).stats().shieldClass();
        return value == null ? 0 : value;
    }

    /** Сколько урона срезают прочные щиты сверх класса (п. 8): у генератора свой модуль. */
    private Integer hardShield(Context context, BattleShipEntity ship) {
        return rules.special(parts(context, ship), ShipEffectType.HARD_SHIELD);
    }

    /** Молчит ли этот выстрел в нынешнем круге после двойного залпа (п. 8). */
    private boolean resting(Context context, BattleShipEntity ship, BattleRules.Shot shot) {
        Integer round = context.battle().getRound();
        if (shot.kind() == WeaponKind.MISSILE) {
            return round.equals(ship.getMissilesRestRound());
        }
        return Boolean.TRUE.equals(rules.beamLike(shot)) && round.equals(ship.getBeamsRestRound());
    }

    /** Все стволы корабля молчат после двойного залпа — ходу нечем стрелять (п. 8). */
    private Boolean allResting(Context context, BattleShipEntity ship) {
        List<BattleRules.Shot> all = shots(context, ship);
        return !all.isEmpty() && all.stream().allMatch(shot -> resting(context, ship, shot));
    }

    private static List<BattleRules.Shot> doubled(List<BattleRules.Shot> shots) {
        List<BattleRules.Shot> twice = new ArrayList<>(shots);
        twice.addAll(shots);
        return twice;
    }

    /**
     * Разведлаборатория против чудищ — п. 8 (13.10.2026, перенос MOO II): «+20 for all ship in
     * fleet against space monsters and the Guardian for Scout Lab». Достаточно одной уцелевшей
     * лаборатории на своей стороне поля.
     */
    private Integer scoutLabBonus(Context context, BattleShipEntity ship, BattleShipEntity target) {
        if (!Boolean.TRUE.equals(context.model(target).hull().monster())) {
            return 0;
        }
        boolean lab = context.ships().stream()
                .filter(one -> one.alive() && one.getSide() == ship.getSide())
                .anyMatch(one -> rules.special(parts(context, one), ShipEffectType.SCOUT_LAB) > 0);
        return lab ? SCOUT_LAB_MONSTER_ATTACK : 0;
    }

    /** Прибавка разведлаборатории к меткости против чудищ — число оригинала. */
    private static final int SCOUT_LAB_MONSTER_ATTACK = 20;

    /**
     * Тяговые лучи — п. 8: бьют вместе с залпом по той же цели и держат её до конца круга.
     * Луч на класс корпуса обездвиживает, лучи разных кораблей складываются. Платформу и чудище
     * не держат: платформа и так стоит, а чудище — не корабль.
     */
    private void tractor(Context context, BattleShipEntity ship, BattleShipEntity target, int distance,
                         List<BattleEventDto> events) {
        int beams = rules.tractorBeams(parts(context, ship));
        Model their = context.model(target);
        if (beams <= 0 || distance > BattleRules.TRACTOR_RANGE || !target.alive()
                || Boolean.TRUE.equals(their.hull().platform()) || Boolean.TRUE.equals(their.hull().monster())) {
            return;
        }
        target.setTractored(target.getTractored() + beams);
        boolean held = target.getTractored() >= rules.sizeClass(their.hull());
        events.add(event("TRACTOR", ship, target.getId(), beams,
                messages.get(held ? "battle.log.tractorHeld" : "battle.log.tractor",
                        name(context, ship), name(context, target), target.getTractored(),
                        rules.sizeClass(their.hull()))));
    }

    /** Что залп сделал с целью, пока это не снято с её брони и корпуса. */
    private static final class Volley {
        private int damage;
        private int piercing;
        private int intercepted;
        private boolean engineHit;
        /** Пехота на борту цели, убитая нейтронным бластером — п. 8, абордаж. */
        private int marinesKilled;
        /** Во сколько раз чаще выбивается система — прицел «Ахилл» (п. 8). */
        private int systemOdds = 1;
    }

    /**
     * Бросок попадания стрелка с полосой (пункт 86): промах растит его счёт, попадание сбрасывает.
     * Стрелка уже нет (ракета пережила его) — бросок обычный.
     */
    private Boolean streakHit(Context context, BattleShipEntity shooter, Integer chance) {
        if (shooter == null) {
            return rules.rollHit(context.random(), chance);
        }
        int misses = shooter.getMissStreak() == null ? 0 : shooter.getMissStreak();
        Boolean hit = StreakRules.roll(context.random(), chance, misses);
        shooter.setMissStreak(Boolean.TRUE.equals(hit) ? 0 : misses + 1);
        return hit;
    }

    /** Корабль поля по идентификатору; пусто — его уже нет на поле. */
    private BattleShipEntity shipOf(Context context, java.util.UUID shipId) {
        return shipId == null ? null : context.ships().stream()
                .filter(one -> shipId.equals(one.getId())).findFirst().orElse(null);
    }

    /** Шанс попасть по цели — для строки журнала: решает его кривая попадания. */
    private Integer chance(Context context, BattleShipEntity target, Integer attackRating) {
        Model targetModel = context.model(target);
        Integer defenceRating = rules.defenceBonus(targetModel.hull(), targetModel.stats())
                + targetModel.officers().defence() + cloakDefence(context, target) + target.getCrewBonus();
        return rules.hitChancePercent(targetModel.hull(), attackRating, defenceRating);
    }

    /**
     * Выстрелы, долетевшие до цели, — п. 8: перехват, попадание, щит. Урон копится в
     * {@code volley}, а снимается с брони и корпуса потом, разом ({@link #land}).
     * <p>
     * Урона два вида: обычный снимает сперва броню, бронебойный идёт прямо в корпус —
     * модификация ствола решает, каким будет выстрел.
     *
     * @return сколько урона дошло до цели — для события залпа
     */
    private int strike(Context context, BattleShipEntity shooter, BattleShipEntity target, List<BattleRules.Shot> shots,
                       Integer attackRating, Integer damagePercent, Integer distance, Volley volley) {
        Model targetModel = context.model(target);
        Integer defenceRating = rules.defenceBonus(targetModel.hull(), targetModel.stats())
                + targetModel.officers().defence() + cloakDefence(context, target) + target.getCrewBonus();
        boolean cloakedTarget = Boolean.TRUE.equals(cloaked(context, target));
        // Ближняя оборона цели сбивает чужие ракеты — п. 8: её стволы считаются один раз
        // на залп, а бросок идёт на каждую ракету. Разбитые стволы не сбивают ничего.
        Integer defenceGuns = rules.pointDefenceGuns(parts(context, target));
        // Модули стрелка (13.10.2026, перенос MOO II): фокусировщик энергии, прицел дальнего
        // боя и «Ахилл». Берутся у уцелевших систем — выбитый модуль не работает. У ракеты,
        // пережившей своего стрелка, модулей нет: прицелы корабля ведут только лучи.
        List<ShipDesignRules.Item> shooterParts = shooter == null || !shooter.alive()
                ? List.of() : parts(context, shooter);
        int beamPercent = rules.special(shooterParts, ShipEffectType.BEAM_DAMAGE_PERCENT);
        int rangeDivisor = Math.max(1, rules.special(shooterParts, ShipEffectType.LONG_RANGE_TARGETING));
        int achilles = rules.special(shooterParts, ShipEffectType.ARMOUR_BYPASS);
        // Тяжёлая броня останавливает «Ахилла»: «can be bypassed... (Heavy Armor blocks)».
        boolean heavyArmour = rules.special(parts(context, target), ShipEffectType.ARMOUR_PERCENT) > 0;
        if (achilles > 0) {
            volley.systemOdds = Math.max(volley.systemOdds, achilles);
        }
        // Устройства цели (перенос MOO II, п. 8): смещение уводит любой выстрел, поле молний
        // жжёт ракеты, торпеды и истребители, прочные щиты срезают удар и не пропускают
        // щитопробивающее оружие.
        List<ShipDesignRules.Item> targetParts = parts(context, target);
        int displacement = rules.special(targetParts, ShipEffectType.DISPLACEMENT);
        int lightning = rules.special(targetParts, ShipEffectType.LIGHTNING_PERCENT);
        int hard = hardShield(context, target);
        int dealt = 0;
        for (BattleRules.Shot shot : shots) {
            Integer interception = rules.interceptChancePercent(defenceGuns, shot);
            if (interception > 0
                    && Boolean.TRUE.equals(rules.rollHit(context.random(), interception))) {
                volley.intercepted++;
                continue;
            }
            if (lightning > 0 && shot.kind() == WeaponKind.MISSILE
                    && Boolean.TRUE.equals(rules.rollHit(context.random(), lightning))) {
                volley.intercepted++;
                continue;
            }
            if (displacement > 0 && Boolean.TRUE.equals(rules.rollHit(context.random(), displacement))) {
                continue;
            }
            Integer shotChance = rules.shotHitChancePercent(targetModel.hull(),
                    attackRating, defenceRating, shot, targetModel.stats().missileEvasion());
            // Замаскированный корабль: «+50% chance for missiles and torpedoes to miss» (п. 8).
            if (cloakedTarget && shot.kind() == WeaponKind.MISSILE) {
                shotChance = shotChance / 2;
            }
            // Полоса промахов сглажена (пункт 86): у стрелка свой счёт, средний шанс — показанный.
            if (!Boolean.TRUE.equals(streakHit(context, shooter, shotChance))) {
                continue;
            }
            // Луч слабеет в пути (п. 8), а «артиллерист» усиливает каждый выстрел до
            // щита, а не после: в MOO II он добавляет урона оружию, а щит гасит то,
            // что до него долетело.
            Integer reached = rules.focused(shot,
                    rules.damageAtRange(shot, distance / rangeDivisor), beamPercent);
            Integer damaged = reached * (100 + damagePercent) / 100;
            // Обволакивающий удар (ENV) приходится на все стороны корабля разом, и щит гасит
            // его на каждой по отдельности — п. 8. Щит оригинала (13.10.2026): класс срезает с
            // каждого удара, запас поглощает остаток и падает, когда кончится.
            int sides = rules.envelopingFactor(shot);
            int through = 0;
            for (int side = 0; side < sides; side++) {
                if (Boolean.TRUE.equals(shot.piercesShield()) && hard == 0) {
                    through += damaged;
                    continue;
                }
                BattleRules.ShieldHit hit = rules.shieldHit(damaged, shieldClass(context, target) + hard,
                        target.getShield());
                target.setShield(hit.pool());
                through += hit.through();
            }
            dealt += through;
            // Нейтронный бластер выжигает пехоту — п. 8: пехотинец за каждые N очков сквозь щит.
            if (through > 0 && shot.marineKill() != null && shot.marineKill() > 0) {
                volley.marinesKilled += through / shot.marineKill();
            }
            boolean bypass = achilles > 0 && !heavyArmour && Boolean.TRUE.equals(rules.beamLike(shot));
            if (Boolean.TRUE.equals(shot.piercesArmour()) || bypass) {
                volley.piercing += through;
            } else {
                volley.damage += through;
            }
            // Наведение по излучению (EMG): пробившись сквозь щит, ракета выбивает
            // двигатель — корабль до конца боя ползёт.
            if (through > 0 && Boolean.TRUE.equals(shot.hitsEngine())) {
                volley.engineHit = true;
            }
        }
        return dealt;
    }

    /**
     * Ракеты уходят со стапеля — backlog-promo, пункт 30.
     * <p>
     * Ракета летит своей скоростью ({@link BattleRules#missileFlightSpeed}): цель ближе
     * этого — залп долетает тут же и бьёт, как прежде; дальше — ракеты остаются на поле
     * залпом в полёте и долетают на смене круга ({@link #flyMissiles}). Ракеты с разной
     * скоростью (быстрые рядом с обычными) летят разными залпами: вместе они не долетели бы.
     * По пути их сбивает ближняя оборона кораблей, мимо которых они идут ({@link #interceptOnPath}).
     */
    private void launch(Context context, BattleShipEntity ship, BattleShipEntity target,
                        List<BattleRules.Shot> shots, Integer attackRating, Integer damagePercent,
                        Integer chance, Volley volley, List<BattleEventDto> events) {
        Map<Integer, List<BattleRules.Shot>> bySpeed = new TreeMap<>();
        for (BattleRules.Shot shot : shots) {
            bySpeed.computeIfAbsent(shot.flightSpeed(), key -> new ArrayList<>()).add(shot);
        }
        SpaceBattleEntity battle = context.battle();
        List<BattleMissile> flying = new ArrayList<>(battle.getMissiles());
        String shooter = name(context, ship);
        for (Map.Entry<Integer, List<BattleRules.Shot>> group : bySpeed.entrySet()) {
            int[] cell = rules.flightStep(ship.getX(), ship.getY(),
                    target.getX(), target.getY(), group.getKey());
            boolean arrives = cell[0] == target.getX() && cell[1] == target.getY();
            int count = group.getValue().size();
            List<BattleEventDto> downs = new ArrayList<>();
            PathResult path = interceptOnPath(context, ship.getSide(), ship.getId(), target.getId(),
                    ship.getX(), ship.getY(), cell[0], cell[1], group.getValue(), downs);
            if (path.left().isEmpty()) {
                events.add(downedLeg(ship.getId(), target.getId(), ship.getX(), ship.getY(),
                        path.lastDownAt(), shooter, count));
                events.addAll(downs);
                continue;
            }
            if (arrives) {
                events.addAll(downs);
                int dealt = strike(context, ship, target, path.left(), attackRating, damagePercent, 0, volley);
                events.add(new BattleEventDto("FIRE", ship.getId(), target.getId(),
                        target.getX(), target.getY(), dealt, WeaponKind.MISSILE,
                        messages.get("battle.log.fire", shooter, name(context, target),
                                messages.label(WeaponKind.MISSILE), dealt, chance), path.left().size()));
                continue;
            }
            battle.setMissileCounter(battle.getMissileCounter() + 1);
            flying.add(new BattleMissile(battle.getMissileCounter(), ship.getSide(), ship.getId(),
                    target.getId(), cell[0], cell[1], group.getKey(),
                    BattleRules.MISSILE_FUEL_TURNS - 1, attackRating, damagePercent,
                    path.left()));
            events.add(new BattleEventDto("LAUNCH", ship.getId(), target.getId(),
                    cell[0], cell[1], null, WeaponKind.MISSILE,
                    messages.get("battle.log.launched", shooter, name(context, target), count), count));
            events.addAll(downs);
        }
        battle.setMissiles(flying);
    }

    /**
     * Ракеты в полёте делают ещё круг пути — backlog-promo, пункт 30.
     * <p>
     * Зовётся на смене круга. Долетевший залп бьёт так же, как долетевший сразу, — меткостью
     * и надбавкой стрелявшего на миг пуска; по пути его сбивает ближняя оборона встречных
     * кораблей, а на подлёте — оборона самой цели. Цели больше нет или кончилось топливо —
     * залп пропадает: в MOO II от медленной ракеты уходят, а погибшую цель она на другую не
     * меняет.
     */
    private void flyMissiles(Context context, List<BattleEventDto> events) {
        SpaceBattleEntity battle = context.battle();
        if (battle.getMissiles().isEmpty()) {
            return;
        }
        List<BattleMissile> still = new ArrayList<>();
        for (BattleMissile launched : battle.getMissiles()) {
            BattleMissile missile = retargeted(context, launched);
            BattleShipEntity target = shipById(context, missile.targetShipId());
            BattleShipEntity shooterShip = shipById(context, missile.shooterShipId());
            String shooter = shooterShip == null ? context.unknownOwner() : name(context, shooterShip);
            int count = missile.shots().size();
            if (target == null || !Boolean.TRUE.equals(targetable(context, target))) {
                events.add(new BattleEventDto("MISSILE_LOST", missile.shooterShipId(), null,
                        missile.x(), missile.y(), null, WeaponKind.MISSILE,
                        messages.get("battle.log.missileNoTarget", shooter, count),
                        count, missile.x(), missile.y()));
                continue;
            }
            int[] cell = rules.flightStep(missile.x(), missile.y(),
                    target.getX(), target.getY(), missile.speed());
            List<BattleEventDto> downs = new ArrayList<>();
            PathResult path = interceptOnPath(context, missile.side(), missile.shooterShipId(),
                    target.getId(), missile.x(), missile.y(), cell[0], cell[1], missile.shots(), downs);
            if (path.left().isEmpty()) {
                events.add(downedLeg(missile.shooterShipId(), target.getId(), missile.x(), missile.y(),
                        path.lastDownAt(), shooter, count));
                events.addAll(downs);
                continue;
            }
            int left = path.left().size();
            if (cell[0] == target.getX() && cell[1] == target.getY() && Boolean.TRUE.equals(missile.shuttles())) {
                events.addAll(downs);
                shuttlesArrive(context, missile.shooterShipId(), missile.side(), target,
                        missile.marines() * left / count, missile.x(), missile.y(), events);
                continue;
            }
            if (cell[0] == target.getX() && cell[1] == target.getY()) {
                events.addAll(downs);
                Volley volley = new Volley();
                int dealt = strike(context, shipOf(context, missile.shooterShipId()), target, path.left(),
                        missile.attackRating(), missile.damagePercent(), 0, volley);
                events.add(new BattleEventDto("FIRE", missile.shooterShipId(), target.getId(),
                        target.getX(), target.getY(), dealt, WeaponKind.MISSILE,
                        messages.get("battle.log.fire", shooter, name(context, target),
                                messages.label(WeaponKind.MISSILE), dealt,
                                chance(context, target, missile.attackRating())),
                        left, missile.x(), missile.y()));
                land(context, missile.shooterShipId(), target, volley, events);
                continue;
            }
            BattleMissile flown = missile.flown(cell[0], cell[1], path.left());
            if (flown.fuel() <= 0) {
                events.add(new BattleEventDto("MISSILE_LOST", missile.shooterShipId(), target.getId(),
                        cell[0], cell[1], null, WeaponKind.MISSILE,
                        messages.get("battle.log.missileOutOfFuel", shooter, name(context, target), left),
                        left, missile.x(), missile.y()));
                events.addAll(downs);
                continue;
            }
            still.add(flown);
            events.add(new BattleEventDto("MISSILE_MOVE", missile.shooterShipId(), target.getId(),
                    cell[0], cell[1], null, WeaponKind.MISSILE,
                    messages.get("battle.log.missileFlies", shooter, name(context, target), left),
                    left, missile.x(), missile.y()));
            events.addAll(downs);
        }
        battle.setMissiles(still);
    }

    /**
     * Наведение Dauntless — п. 8 (13.10.2026, перенос MOO II): ракета, чья цель погибла или
     * ушла с поля, «redirect themselves at the nearest enemy ship or base instead of
     * self-destructing». Достижение империи-хозяина стороны; шаттлы не перенацеливаются —
     * абордаж идёт к названному кораблю.
     */
    private BattleMissile retargeted(Context context, BattleMissile missile) {
        BattleShipEntity target = shipById(context, missile.targetShipId());
        if (target != null && Boolean.TRUE.equals(targetable(context, target))
                || Boolean.TRUE.equals(missile.shuttles())) {
            return missile;
        }
        UUID ownerId = missile.side() == BattleSide.ATTACKER
                ? context.battle().getAttackerPlayerId() : context.battle().getDefenderPlayerId();
        PlayerEntity owner = context.player(ownerId);
        if (owner == null || !shipDesignService.technologies(owner).contains(DAUNTLESS_GUIDANCE)) {
            return missile;
        }
        return context.ships().stream()
                .filter(one -> Boolean.TRUE.equals(targetable(context, one)) && one.getSide() != missile.side())
                .min(Comparator.comparingInt((BattleShipEntity one) ->
                                rules.distance(missile.x(), missile.y(), one.getX(), one.getY()))
                        .thenComparing(BattleShipEntity::getOrdinal))
                .map(next -> new BattleMissile(missile.id(), missile.side(), missile.shooterShipId(),
                        next.getId(), missile.x(), missile.y(), missile.speed(), missile.fuel(),
                        missile.attackRating(), missile.damagePercent(), missile.shots(), missile.marines()))
                .orElse(missile);
    }

    /** Код достижения, перенацеливающего ракеты, — Computers, уровень 2. */
    private static final String DAUNTLESS_GUIDANCE = "dauntless-guidance-system";

    /**
     * Огонь по ракете в полёте — backlog-promo, пункт 30, правило MOO II: ракета — предмет
     * на поле, и корабль вправе навести на неё свои стволы вместо корабля противника.
     * <p>
     * Стреляют выбранные строки оружия, как и по кораблю, но только лучи и снаряды: ракетой
     * ракету не сбить. Каждый выстрел, дотянувшийся до клетки залпа, сбивает одну ракету с
     * шансом {@link BattleRules#missileShotChancePercent}. Противоракеты, если они стоят на
     * корабле, бьют ПЕРВЫМИ и по каждой ракете залпа ({@link BattleRules#antiMissileChancePercent});
     * строкой оружия они не числятся и выбором строк не гасятся. Залп кончает ход — так же,
     * как выстрел по кораблю: ход корабля стоит одного залпа, по чему бы он ни был.
     *
     * @param rows выбранные строки оружия; {@code null} — всё оружие
     */
    private void fireAtMissile(Context context, BattleShipEntity ship, Integer missileId,
                               Set<Integer> rows, List<BattleEventDto> events) {
        if (Boolean.TRUE.equals(ship.getFired())) {
            throw new ConflictException("battle.alreadyFired");
        }
        SpaceBattleEntity battle = context.battle();
        BattleMissile missile = battle.getMissiles().stream()
                .filter(one -> one.id().equals(missileId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("battle.missileNotFound", missileId));
        if (missile.side() == ship.getSide()) {
            throw new ConflictException("battle.friendlyFire");
        }
        Model model = context.model(ship);
        List<ShipDesignRules.Item> all = parts(context, ship);
        List<ShipDesignRules.Item> parts = rows == null
                ? all
                : rules.partsLeft(model.items(), ship.getDamagedSystems(), rows);
        List<BattleRules.Shot> guns = rules.shots(parts, rules.engineCombatSpeed(model.items())).stream()
                .filter(shot -> shot.kind() != WeaponKind.MISSILE)
                .toList();
        Integer antiMissile = rules.antiMissilePercent(all);
        if (guns.isEmpty() && antiMissile == 0) {
            throw new ConflictException("battle.noGunsForMissile");
        }
        int distance = rules.distance(ship.getX(), ship.getY(), missile.x(), missile.y());
        List<BattleRules.Shot> reaching = guns.stream()
                .filter(shot -> Boolean.TRUE.equals(rules.shotReaches(shot, distance)))
                .toList();
        Integer rocketChance = rules.antiMissileChancePercent(antiMissile, distance);
        if (reaching.isEmpty() && rocketChance == 0) {
            int reach = Math.max(guns.isEmpty() ? 0 : rules.reach(guns),
                    (antiMissile - 1) / BattleRules.ANTI_MISSILE_STEP_PERCENT);
            throw new ConflictException("battle.outOfRange", reach);
        }

        Integer attackRating = rules.attackPercent(parts) + model.officers().attack();
        List<BattleRules.Shot> left = new ArrayList<>(missile.shots());
        int downed = 0;
        if (rocketChance > 0) {
            for (Iterator<BattleRules.Shot> rocket = left.iterator(); rocket.hasNext(); ) {
                rocket.next();
                if (Boolean.TRUE.equals(rules.rollHit(context.random(), rocketChance))) {
                    rocket.remove();
                    downed++;
                }
            }
        }
        for (BattleRules.Shot gun : reaching) {
            if (left.isEmpty()) {
                break;
            }
            BattleRules.Shot rocket = left.get(0);
            Integer chance = rules.missileShotChancePercent(attackRating + gun.attackPercent(),
                    missile.speed(), rocket.missileArmourPercent());
            if (Boolean.TRUE.equals(streakHit(context, ship, chance))) {
                left.remove(0);
                downed++;
            }
        }
        ship.setFired(Boolean.TRUE);
        ship.setLastFiredRound(context.battle().getRound());

        List<BattleMissile> missiles = new ArrayList<>();
        for (BattleMissile one : battle.getMissiles()) {
            if (!one.id().equals(missileId)) {
                missiles.add(one);
            } else if (!left.isEmpty()) {
                missiles.add(new BattleMissile(one.id(), one.side(), one.shooterShipId(),
                        one.targetShipId(), one.x(), one.y(), one.speed(), one.fuel(),
                        one.attackRating(), one.damagePercent(), List.copyOf(left),
                        one.shuttles() ? one.marines() * left.size() / one.shots().size() : null));
            }
        }
        battle.setMissiles(missiles);
        // Противоракеты рисуются ракетой: на поле их отличает от лучей именно это.
        WeaponKind kind = rocketChance > 0 ? WeaponKind.MISSILE : reaching.get(0).kind();
        events.add(new BattleEventDto("INTERCEPT", ship.getId(), missile.shooterShipId(),
                missile.x(), missile.y(), downed, kind,
                messages.get(rocketChance > 0 ? "battle.log.antiMissile" : "battle.log.shotAtMissile",
                        name(context, ship), downed, missile.shots().size())));
    }

    /**
     * Сколько ракет (в сотых долях) должен ждаться от перехвата, чтобы ИИ потратил на него ход, —
     * половина ракеты. Реконструкция: см. {@link #interceptForAi}.
     */
    private static final int AI_INTERCEPT_MIN_CENTIS = 50;

    /**
     * Можно ли взять этот корабль на абордаж (или устроить на нём рейд) этим — п. 8, правило
     * оригинала: «the ship to be boarded must have their shields down and have been
     * immobilized, and the ship commencing the boarding action must be nearby».
     * <ul>
     *   <li><b>вплотную</b> — у цели опущен щит и она обездвижена (разбит двигатель или
     *       держат тяговые лучи);</li>
     *   <li><b>телепортом</b> — с его дальности, у цели опущен щит, обездвиживать не нужно
     *       («beams Marines into enemy ships and Star Bases whose shields are down»);</li>
     *   <li><b>телепаты</b> — ни щит, ни ход цели им не помеха.</li>
     * </ul>
     * Базы и платформы берут так же — «beams Marines into enemy ships and Star Bases»: взятая
     * база воюет за захватчика до конца боя, а после него для хозяина потеряна (здание колонии
     * с захватчиком не улетает, и он его не удерживает — реконструкция). Чудищ не берут никак.
     * {@code null} — можно, иначе ключ отказа.
     */
    private String boardingRefusal(Context context, BattleShipEntity ship, BattleShipEntity target,
                                   Boolean freely, Boolean raid) {
        if (Boolean.TRUE.equals(ship.getFired())) {
            return "battle.alreadyFired";
        }
        if (!target.alive() || target.getSide() == ship.getSide()) {
            return "battle.board.notEnemy";
        }
        Model own = context.model(ship);
        Model their = context.model(target);
        if (platform(own) || Boolean.TRUE.equals(own.hull().monster()) || ship.getMarines() <= 0) {
            return "battle.board.noMarines";
        }
        if (Boolean.TRUE.equals(their.hull().monster())) {
            return "battle.board.notShip";
        }
        int distance = rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY());
        int range = rules.boardingRange(parts(context, ship));
        if (distance > range) {
            return range > BattleRules.BOARDING_RANGE ? "battle.board.outOfTransporter" : "battle.board.notAdjacent";
        }
        // Прочные щиты не пускают чужой телепорт даже опущенными (п. 8, перенос MOO II).
        if (distance > BattleRules.BOARDING_RANGE && hardShield(context, target) > 0) {
            return "battle.board.hardShields";
        }
        if (!Boolean.TRUE.equals(freely)) {
            if (target.getShield() > 0) {
                return "battle.board.shieldsUp";
            }
            // Телепорт обходится без обездвиживания; вплотную цель обязана стоять. Платформа
            // не ходит по устройству, и рейд на неё условия хода не спрашивает.
            if (distance > BattleRules.BOARDING_RANGE || platform(their)) {
                return null;
            }
            if (!Boolean.TRUE.equals(immobile(context, target))
                    && rules.transporterRange(parts(context, ship)) == 0) {
                return "battle.board.moving";
            }
        }
        return null;
    }

    /**
     * Абордаж и рейд — п. 8, кнопка BOARD оригинала («to either raid or attempt to capture an
     * enemy ship»). Вся пехота корабля бьётся с командой цели расчётом наземного боя
     * ({@code GroundCombatService.boarding}).
     * <ul>
     *   <li><b>захват</b>: взяли — корабль переходит к захватчику прямо в бою (хозяин,
     *       сторона, команда — уцелевшие абордажники), со следующего круга он ходит и стреляет
     *       за него; не взяли — абордажники погибли все;</li>
     *   <li><b>рейд</b>: взяли верх — каждый уцелевший разбивает по системе («raiding damages
     *       ship systems and weapons») и возвращается на свой корабль; не взяли — погибли.</li>
     * </ul>
     * Ход кончается в обоих случаях.
     */
    private void board(Context context, BattleShipEntity ship, BattleShipEntity target, Boolean raid,
                       List<BattleEventDto> events) {
        PlayerEntity owner = context.player(ship.getOwnerPlayerId());
        PlayerEntity defender = context.player(target.getOwnerPlayerId());
        if (owner == null || defender == null) {
            throw new ConflictException("battle.board.notShip");
        }
        String refusal = boardingRefusal(context, ship, target, groundCombat.boardsFreely(owner), raid);
        if (refusal != null) {
            throw new ConflictException(refusal);
        }
        int marines = ship.getMarines();
        ship.setMarines(0);
        ship.setFired(Boolean.TRUE);
        resolveBoarding(context, ship.getId(), ship, owner, marines, target, defender, raid, events);
    }

    /**
     * Исход абордажа — общий у пехоты с корабля и у долетевших шаттлов. {@code boarder} —
     * корабль, на который рейд возвращает уцелевших; у шаттлов его может уже не быть.
     */
    private void resolveBoarding(Context context, UUID boarderId, BattleShipEntity boarder, PlayerEntity owner,
                                 int marines, BattleShipEntity target, PlayerEntity defender, Boolean raid,
                                 List<BattleEventDto> events) {
        GroundCombatService.Outcome outcome = groundCombat.boarding(marines, owner, target.getMarines(), defender,
                rules.special(parts(context, target), ShipEffectType.MARINE_DEFENSE_PERCENT));
        String who = boarder == null ? context.name(owner.getId()) : name(context, boarder);
        String prize = name(context, target);
        BattleShipEntity anchor = boarder == null ? target : boarder;
        if (!Boolean.TRUE.equals(outcome.captured())) {
            target.setMarines(outcome.survivors());
            events.add(new BattleEventDto(Boolean.TRUE.equals(raid) ? "RAID" : "BOARD", boarderId, target.getId(),
                    target.getX(), target.getY(), 0, null,
                    messages.get("battle.log.boardRepelled", who, prize, outcome.survivors())));
            return;
        }
        if (Boolean.TRUE.equals(raid)) {
            // Каждый уцелевший громит по системе — пока есть что громить.
            int wrecked = 0;
            for (int i = 0; i < outcome.survivors(); i++) {
                String system = rules.pickSystem(context.random(),
                        rules.liveSystems(context.model(target).items(), target.getDamagedSystems()));
                if (system == null) {
                    break;
                }
                target.damageSystem(system);
                wrecked++;
            }
            target.setMarines(0);
            if (boarder != null && boarder.alive()) {
                boarder.setMarines(boarder.getMarines() + outcome.survivors());
            }
            events.add(new BattleEventDto("RAID", boarderId, target.getId(),
                    target.getX(), target.getY(), wrecked, null,
                    messages.get("battle.log.raided", who, prize, wrecked, outcome.survivors())));
            return;
        }
        if (activity != null) {
            activity.record(context.battle().getGameId(), owner.getId(), EmpireActivityService.BOARDING);
        }
        target.setOwnerPlayerId(owner.getId());
        target.setSide(anchor == target ? opposite(target.getSide()) : anchor.getSide());
        target.setCaptured(Boolean.TRUE);
        target.setMarines(outcome.survivors());
        target.setTractored(0);
        // Взятый корабль в этом круге уже не ходит: команда только что сменилась.
        target.setFired(Boolean.TRUE);
        target.setMoved(Math.max(target.getMoved(), speed(context, target)));
        events.add(new BattleEventDto("BOARD", boarderId, target.getId(),
                target.getX(), target.getY(), 1, null,
                messages.get("battle.log.boarded", who, prize, outcome.survivors())));
    }

    /**
     * Сколько шаттлов корабль пустит залпом по этой цели — п. 8: отсеков × шаттлов, но не
     * больше, чем пехоты на борту по два на шаттл, и только по тому, кого берут абордажем или
     * рейдом, и если шаттлы долетят на своём топливе. Ноль — шаттлов нет или пускать некуда.
     */
    private int shuttlesFor(Context context, BattleShipEntity ship, BattleShipEntity target) {
        List<ShipDesignRules.Item> parts = parts(context, ship);
        int bays = rules.shuttles(parts);
        Model their = context.model(target);
        if (bays <= 0 || ship.getMarines() < BattleRules.SHUTTLE_MARINES
                || target.getSide() == ship.getSide() || !target.alive()
                || Boolean.TRUE.equals(their.hull().monster())) {
            return 0;
        }
        int reach = rules.shuttleSpeed(parts) * BattleRules.MISSILE_FUEL_TURNS;
        if (rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY()) > reach) {
            return 0;
        }
        return Math.min(bays, ship.getMarines() / BattleRules.SHUTTLE_MARINES);
    }

    /**
     * Штурмовые шаттлы уходят к цели залпа — п. 8: «shuttles carry troops that automatically
     * board enemy ships or Starbases when in range, trying to either raid or capture them».
     * Летят они как ракеты — залпом в полёте ({@link BattleMissile#shuttles}) со своей
     * скоростью ({@link BattleRules#shuttleSpeed}), и ближняя оборона сбивает их так же;
     * пехота уходит с борта сразу. Ближе своей скорости — долетают тут же.
     */
    /**
     * Звенья истребителей уходят к цели — п. 8 (13.10.2026, перенос MOO II): по звену с каждого
     * отсека. Звено летит, как ракетный залп, и его сбивает ближняя оборона на пути; долетевшее
     * бьёт сразу, а не долетевшее в этот круг остаётся на поле залпом в полёте.
     */
    private void launchFighters(Context context, BattleShipEntity ship, BattleShipEntity target,
                                List<BattleEventDto> events) {
        PlayerEntity owner = context.player(ship.getOwnerPlayerId());
        if (owner == null) {
            return;
        }
        Set<String> known = shipDesignService.technologies(owner);
        int beam = fighterBeam(known);
        int bomb = fighterBomb(known);
        List<ShipDesignRules.Item> live = parts(context, ship);
        Integer attackRating = BattleRules.FIGHTER_ATTACK + rules.attackPercent(live)
                + context.model(ship).officers().attack();
        for (ShipDesignRules.Item item : live) {
            int kind = item.component().amount(ShipEffectType.FIGHTER_BAY);
            for (int bay = 0; kind > 0 && bay < item.count(); bay++) {
                int speed = rules.fighterSpeed(live, kind);
                List<BattleRules.Shot> craft = rules.fighterShots(kind, beam, bomb, speed, fighterArmour(known));
                if (craft.isEmpty()) {
                    continue;
                }
                int[] cell = rules.flightStep(ship.getX(), ship.getY(), target.getX(), target.getY(), speed);
                List<BattleEventDto> downs = new ArrayList<>();
                PathResult path = interceptOnPath(context, ship.getSide(), ship.getId(), target.getId(),
                        ship.getX(), ship.getY(), cell[0], cell[1], craft, downs);
                events.add(new BattleEventDto("LAUNCH", ship.getId(), target.getId(), cell[0], cell[1], null,
                        WeaponKind.MISSILE, messages.get("battle.log.fightersLaunched", name(context, ship),
                                name(context, target)), craft.size()));
                events.addAll(downs);
                if (path.left().isEmpty()) {
                    continue;
                }
                if (cell[0] == target.getX() && cell[1] == target.getY()) {
                    Volley volley = new Volley();
                    int dealt = strike(context, ship, target, path.left(), attackRating, 0, 0, volley);
                    events.add(new BattleEventDto("FIRE", ship.getId(), target.getId(),
                            target.getX(), target.getY(), dealt, WeaponKind.MISSILE,
                            messages.get("battle.log.fightersHit", name(context, ship), name(context, target), dealt),
                            path.left().size()));
                    land(context, ship.getId(), target, volley, events);
                    continue;
                }
                SpaceBattleEntity battle = context.battle();
                List<BattleMissile> flying = new ArrayList<>(battle.getMissiles());
                battle.setMissileCounter(battle.getMissileCounter() + 1);
                flying.add(new BattleMissile(battle.getMissileCounter(), ship.getSide(), ship.getId(), target.getId(),
                        cell[0], cell[1], speed, BattleRules.FIGHTER_FUEL_TURNS - 1, attackRating, 0, path.left()));
                battle.setMissiles(flying);
            }
        }
    }

    /**
     * Луч истребителя — п. 8: лучший изученный ствол, который берёт модификацию ближней обороны,
     * «normal damage +1 (Mass Drivers being the exception)». Ноль — такого ствола нет.
     */
    private int fighterBeam(Set<String> known) {
        com.sddnw.server.domain.WeaponModification pointDefence = shipCatalog.modification("point-defense");
        return shipCatalog.components(ShipComponentSlot.WEAPON).stream()
                .filter(weapon -> weapon.weaponKind() == WeaponKind.BEAM || weapon.weaponKind() == WeaponKind.PROJECTILE)
                .filter(weapon -> weapon.requiredTechCode() == null || known.contains(weapon.requiredTechCode()))
                .filter(weapon -> pointDefence == null || Boolean.TRUE.equals(weapon.carries(pointDefence)))
                .mapToInt(weapon -> weapon.amount(ShipEffectType.WEAPON_DAMAGE)
                        + ("mass-driver".equals(weapon.code()) ? 0 : 1))
                .max()
                .orElse(0);
    }

    /**
     * Множитель лучшей брони империи для прочности истребителей — п. 8 (MOO II: «structure scales with your
     * best armor»): броня лестницы, делённая на титановую (4), — титан 1, тритан 2, корриевая 3 …
     */
    private int fighterArmour(Set<String> known) {
        return Math.max(1, shipCatalog.components(ShipComponentSlot.ARMOR).stream()
                .filter(armour -> armour.requiredTechCode() != null && known.contains(armour.requiredTechCode()))
                .mapToInt(armour -> armour.amount(ShipEffectType.ARMOUR) / 4)
                .max()
                .orElse(1));
    }

    /** Бомба истребителя — лучшая изученная, без биологических (п. 8). Ноль — бомб нет. */
    private int fighterBomb(Set<String> known) {
        return shipCatalog.components(ShipComponentSlot.WEAPON).stream()
                .filter(weapon -> weapon.weaponKind() == WeaponKind.BOMB)
                .filter(weapon -> weapon.amount(ShipEffectType.BIO_KILL_PERCENT) <= 0)
                .filter(weapon -> weapon.requiredTechCode() == null || known.contains(weapon.requiredTechCode()))
                .mapToInt(weapon -> weapon.amount(ShipEffectType.WEAPON_DAMAGE))
                .max()
                .orElse(0);
    }

    private void launchShuttles(Context context, BattleShipEntity ship, BattleShipEntity target, int shuttles,
                                List<BattleEventDto> events) {
        int speed = rules.shuttleSpeed(parts(context, ship));
        int marines = shuttles * BattleRules.SHUTTLE_MARINES;
        ship.setMarines(ship.getMarines() - marines);
        List<BattleRules.Shot> craft = rules.shuttleShots(shuttles, speed);
        int[] cell = rules.flightStep(ship.getX(), ship.getY(), target.getX(), target.getY(), speed);
        List<BattleEventDto> downs = new ArrayList<>();
        PathResult path = interceptOnPath(context, ship.getSide(), ship.getId(), target.getId(),
                ship.getX(), ship.getY(), cell[0], cell[1], craft, downs);
        String shooter = name(context, ship);
        events.add(new BattleEventDto("LAUNCH", ship.getId(), target.getId(), cell[0], cell[1], null,
                WeaponKind.MISSILE, messages.get("battle.log.shuttlesLaunched", shooter, name(context, target),
                        shuttles, marines), shuttles));
        events.addAll(downs);
        if (path.left().isEmpty()) {
            return;
        }
        int arriving = marines * path.left().size() / shuttles;
        if (cell[0] == target.getX() && cell[1] == target.getY()) {
            shuttlesArrive(context, ship.getId(), ship.getSide(), target, arriving, ship.getX(), ship.getY(), events);
            return;
        }
        SpaceBattleEntity battle = context.battle();
        List<BattleMissile> flying = new ArrayList<>(battle.getMissiles());
        battle.setMissileCounter(battle.getMissileCounter() + 1);
        flying.add(new BattleMissile(battle.getMissileCounter(), ship.getSide(), ship.getId(), target.getId(),
                cell[0], cell[1], speed, BattleRules.MISSILE_FUEL_TURNS - 1, 0, 0, path.left(), arriving));
        battle.setMissiles(flying);
    }

    /**
     * Шаттлы долетели — п. 8: пехота идёт на абордаж сама, без условий абордажа с корабля
     * (шаттл пристыковывается и к кораблю на ходу, и под щитом — ради этого его и строят).
     * Корабль берут, базу громят рейдом.
     */
    private void shuttlesArrive(Context context, UUID shooterId, BattleSide side, BattleShipEntity target,
                                int marines, int fromX, int fromY, List<BattleEventDto> events) {
        if (marines <= 0 || !target.alive() || target.getSide() == side) {
            return;
        }
        // Хозяин шаттлов — сторона пуска: корабль, что их пустил, мог уже и погибнуть.
        PlayerEntity owner = context.player(ownerOf(context, side));
        PlayerEntity defender = context.player(target.getOwnerPlayerId());
        if (owner == null || defender == null || owner.getId().equals(defender.getId())) {
            return;
        }
        events.add(new BattleEventDto("FIRE", shooterId, target.getId(), target.getX(), target.getY(),
                0, WeaponKind.MISSILE, messages.get("battle.log.shuttlesDocked", name(context, target), marines),
                1, fromX, fromY));
        resolveBoarding(context, shooterId, null, owner, marines, target, defender, Boolean.FALSE, events);
    }

    /**
     * ИИ берёт корабль на абордаж — п. 8: если чужой корабль в досягаемости хода (подойти
     * вплотную или телепортом) и исход предрешён в его пользу — а он предрешён: абордаж
     * считается силами, без жребия. Захваченный корабль дороже подбитого: он переходит в строй,
     * а не просто выбывает. Базу, которую не увести, громит рейдом — с телепортом.
     *
     * @return брал ли корабль кого-нибудь на абордаж
     */
    private Boolean boardForAi(Context context, BattleShipEntity ship, List<BattleEventDto> events) {
        if (Boolean.TRUE.equals(ship.getFired()) || ship.getMarines() <= 0 || platform(context.model(ship))) {
            return Boolean.FALSE;
        }
        PlayerEntity owner = context.player(ship.getOwnerPlayerId());
        if (owner == null) {
            return Boolean.FALSE;
        }
        int range = rules.boardingRange(parts(context, ship));
        int reach = speed(context, ship) - ship.getMoved() + range;
        Boolean freely = groundCombat.boardsFreely(owner);
        for (BattleShipEntity target : order(context.ships())) {
            if (!target.alive() || target.getSide() == ship.getSide()
                    || rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY()) > reach) {
                continue;
            }
            Model their = context.model(target);
            if (Boolean.TRUE.equals(their.hull().monster())) {
                continue;
            }
            boolean raid = false;
            PlayerEntity defender = context.player(target.getOwnerPlayerId());
            if (defender == null || !Boolean.TRUE.equals(groundCombat.boarding(
                    ship.getMarines(), owner, target.getMarines(), defender,
                    rules.special(parts(context, target), ShipEffectType.MARINE_DEFENSE_PERCENT)).captured())) {
                continue;
            }
            if (rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY()) > range) {
                approach(context, ship, target, range, events);
            }
            if (boardingRefusal(context, ship, target, freely, raid) == null) {
                board(context, ship, target, raid, events);
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * ИИ сбивает ракеты в полёте — backlog-promo, пункт 30. Бьёт он ими только тем, что для
     * этого создано: стволами с ближней обороной ({@link BattleRules#pointDefenceRows}) и
     * противоракетами. Обычный луч по ракете тоже стреляет — так может человек, — но для ИИ
     * это залп, отнятый у корабля противника, и окупается он, только когда ствол для перехвата
     * и строился.
     * <p>
     * Залп берётся тот, где ждётся больше сбитых ракет ({@link BattleRules#expectedDownsCentis}),
     * и только если ждётся хотя бы половина ракеты ({@link #AI_INTERCEPT_MIN_CENTIS}):
     * <b>реконструкция</b> — в MOO II стволы ближней обороны и противоракеты бьют по ракетам
     * сами, без решения игрока, а у нас перехват стоит хода, и бросать ход ради почти нулевого
     * шанса незачем. Оборона идёт ПРЕЖДЕ нападения: долетевший залп по своему кораблю — это
     * урон наверняка, а свой выстрел — лишь возможный.
     *
     * @return стрелял ли корабль по ракетам
     */
    private Boolean interceptForAi(Context context, BattleShipEntity ship, List<BattleEventDto> events) {
        if (Boolean.TRUE.equals(ship.getFired())) {
            return Boolean.FALSE;
        }
        Model model = context.model(ship);
        Set<Integer> rows = rules.pointDefenceRows(model.items());
        List<ShipDesignRules.Item> all = parts(context, ship);
        Integer antiMissile = rules.antiMissilePercent(all);
        if (rows.isEmpty() && antiMissile == 0) {
            return Boolean.FALSE;
        }
        List<ShipDesignRules.Item> parts = rows.isEmpty()
                ? List.of()
                : rules.partsLeft(model.items(), ship.getDamagedSystems(), rows);
        List<BattleRules.Shot> guns = rules.shots(parts, rules.engineCombatSpeed(model.items())).stream()
                .filter(shot -> shot.kind() != WeaponKind.MISSILE)
                .toList();
        Integer attackRating = rules.attackPercent(all) + model.officers().attack();

        BattleMissile best = null;
        int bestCentis = 0;
        for (BattleMissile missile : context.battle().getMissiles()) {
            if (missile.side() == ship.getSide() || missile.shots().isEmpty()) {
                continue;
            }
            int distance = rules.distance(ship.getX(), ship.getY(), missile.x(), missile.y());
            List<Integer> chances = new ArrayList<>();
            for (BattleRules.Shot gun : guns) {
                if (Boolean.TRUE.equals(rules.shotReaches(gun, distance))) {
                    chances.add(rules.missileShotChancePercent(attackRating + gun.attackPercent(),
                            missile.speed(), missile.shots().get(0).missileArmourPercent()));
                }
            }
            int centis = rules.expectedDownsCentis(rules.antiMissileChancePercent(antiMissile, distance),
                    chances, missile.shots().size());
            if (centis > bestCentis) {
                best = missile;
                bestCentis = centis;
            }
        }
        if (best == null || bestCentis < AI_INTERCEPT_MIN_CENTIS) {
            return Boolean.FALSE;
        }
        // Без ближней обороны стреляют одни противоракеты: строка, которой нет, гасит всё оружие.
        fireAtMissile(context, ship, best.id(), rows.isEmpty() ? Set.of(-1) : rows, events);
        return Boolean.TRUE;
    }

    /** Что осталось от залпа после пути и где сбита последняя ракета. */
    private record PathResult(List<BattleRules.Shot> left, int[] lastDownAt) {
    }

    /**
     * Перехват ракет в полёте — backlog-promo, пункт 30, правило MOO II: стволы ближней
     * обороны бьют по ракете на всём её пути, а не только у цели.
     * <p>
     * На отрезке пути, который залп проходит за круг, каждый живой корабль ЧУЖОЙ стороны,
     * мимо которого ракеты идут в пределах его ближней обороны
     * ({@link BattleRules#pointDefenceRange}), бросает на каждую ракету тот же бросок
     * перехвата, что и цель на подлёте ({@link BattleRules#interceptChancePercent}). Цель на
     * пути не стреляет: её бросок — на подлёте, внутри {@link #strike}, и дважды по одному
     * залпу она не бьёт. Защитники идут в порядке очереди — порядок задан явно, и бой
     * повторяется с тем же зерном.
     *
     * @param downs сюда кладутся события перехвата — по одному на защитника, сбившего хоть одну
     */
    private PathResult interceptOnPath(Context context, BattleSide side, UUID shooterId, UUID targetId,
                                       int fromX, int fromY, int toX, int toY,
                                       List<BattleRules.Shot> shots, List<BattleEventDto> downs) {
        List<BattleRules.Shot> left = new ArrayList<>(shots);
        int[] lastDownAt = {toX, toY};
        BattleShipEntity shooterShip = shipById(context, shooterId);
        for (BattleShipEntity guard : order(context.ships())) {
            if (left.isEmpty()) {
                break;
            }
            if (!guard.alive() || guard.getSide() == side || guard.getId().equals(targetId)) {
                continue;
            }
            List<ShipDesignRules.Item> guardParts = parts(context, guard);
            int guns = rules.pointDefenceGuns(guardParts);
            if (guns <= 0) {
                continue;
            }
            int[] near = rules.closestOnPath(guard.getX(), guard.getY(), fromX, fromY, toX, toY);
            if (near[2] > rules.pointDefenceRange(guardParts)) {
                continue;
            }
            int downed = 0;
            for (java.util.Iterator<BattleRules.Shot> it = left.iterator(); it.hasNext(); ) {
                BattleRules.Shot shot = it.next();
                Integer interception = rules.interceptChancePercent(guns, shot);
                if (interception > 0 && Boolean.TRUE.equals(rules.rollHit(context.random(), interception))) {
                    it.remove();
                    downed++;
                }
            }
            if (downed > 0) {
                lastDownAt = new int[]{near[0], near[1]};
                downs.add(new BattleEventDto("INTERCEPT", guard.getId(), shooterId,
                        near[0], near[1], downed, WeaponKind.MISSILE,
                        messages.get("battle.log.interceptedInFlight", name(context, guard),
                                shooterShip == null ? context.unknownOwner() : name(context, shooterShip),
                                downed)));
            }
        }
        return new PathResult(left, lastDownAt);
    }

    /** Отрезок пути залпа, сбитого целиком: сцена доводит ракеты до места, где их сбили. */
    private BattleEventDto downedLeg(UUID shooterId, UUID targetId, int fromX, int fromY, int[] at,
                                     String shooter, int count) {
        return new BattleEventDto("MISSILE_LOST", shooterId, targetId, at[0], at[1], null,
                WeaponKind.MISSILE, messages.get("battle.log.missilesDowned", shooter, count),
                count, fromX, fromY);
    }

    /** Корабль поля по идентификатору — живой или уже погибший; нет такого — пусто. */
    private BattleShipEntity shipById(Context context, UUID shipId) {
        return context.ships().stream()
                .filter(ship -> ship.getId().equals(shipId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Залп снимается с цели — п. 8: сбитые ракеты, выбитый двигатель, броня, корпус, гибель
     * и то, что прошло сквозь броню и рвёт корабль изнутри.
     */
    private void land(Context context, UUID shooterId, BattleShipEntity target, Volley volley,
                      List<BattleEventDto> events) {
        if (volley.intercepted > 0) {
            events.add(new BattleEventDto("INTERCEPT", target.getId(), shooterId,
                    target.getX(), target.getY(), volley.intercepted, WeaponKind.MISSILE,
                    messages.get("battle.log.intercepted",
                            name(context, target), volley.intercepted)));
        }
        if (!target.alive()) {
            return;
        }
        if (volley.marinesKilled > 0 && target.getMarines() > 0) {
            int killed = Math.min(target.getMarines(), volley.marinesKilled);
            target.setMarines(target.getMarines() - killed);
            events.add(new BattleEventDto("MARINES", shooterId, target.getId(),
                    target.getX(), target.getY(), killed, WeaponKind.BEAM,
                    messages.get("battle.log.marinesKilled", name(context, target), killed,
                            target.getMarines())));
        }
        if (volley.engineHit && !Boolean.TRUE.equals(target.systemDamaged(BattleRules.ENGINE_SYSTEM))) {
            target.damageSystem(BattleRules.ENGINE_SYSTEM);
            events.add(new BattleEventDto("ENGINE", shooterId, target.getId(),
                    target.getX(), target.getY(), 0, WeaponKind.MISSILE,
                    messages.get("battle.log.engineHit", name(context, target))));
        }

        // Энергопоглотитель гасит часть урона, дошедшего до брони и корпуса (п. 8): «decreases
        // by 25% the damage your ships take».
        int absorb = Math.min(100, rules.special(parts(context, target), ShipEffectType.DAMAGE_ABSORB_PERCENT));
        if (absorb > 0) {
            volley.damage = volley.damage * (100 - absorb) / 100;
            volley.piercing = volley.piercing * (100 - absorb) / 100;
        }
        int armour = Math.max(0, target.getArmour() - volley.damage);
        int throughArmour = Math.max(0, volley.damage - target.getArmour()) + volley.piercing;
        target.setArmour(armour);
        target.setStructure(Math.max(0, target.getStructure() - throughArmour));

        if (target.getStructure() <= 0) {
            destroy(context, target, events, "SHOT", shooterId);
            return;
        }
        // Всё, что прошло сквозь щит и броню, рвёт корабль ИЗНУТРИ — п. 8.
        if (throughArmour > 0) {
            wreckSystem(context, target, events, volley.systemOdds);
        }
    }

    /**
     * Попадание по корпусу выбивает бортовую систему — п. 8.
     * <p>
     * Правило MOO II: щит и броня держат удар, а прошедшее сквозь них гасит ствол,
     * разбивает двигатель или сжигает прицел. Жребий идёт по тому, что ещё цело, поэтому
     * у корабля с полным трюмом стволов двигатель под ударом реже: рвётся то, чего в
     * корабле больше. Числа — в {@link BattleRules}.
     * <p>
     * <b>Разбитый двигатель взрывается</b> с вероятностью
     * {@link BattleRules#ENGINE_BLAST_PERCENT}: корабль уходит сразу и уносит с собой
     * соседей — и своих тоже.
     */
    private void wreckSystem(Context context, BattleShipEntity target,
                             List<BattleEventDto> events, Integer odds) {
        if (!Boolean.TRUE.equals(rules.rollSystemHit(context.random(), odds))) {
            return;
        }
        List<String> live = rules.liveSystems(context.model(target).items(),
                target.getDamagedSystems());
        String system = rules.pickSystem(context.random(), live);
        if (system == null) {
            // Ломать больше нечего: у корабля выбито всё, что ломается.
            return;
        }
        target.damageSystem(system);
        events.add(new BattleEventDto("SYSTEM", target.getId(), null,
                target.getX(), target.getY(), null, null,
                messages.get("battle.log.systemWrecked", name(context, target),
                        systemName(context, target, system))));

        if (BattleRules.ENGINE_SYSTEM.equals(system)
                && Boolean.TRUE.equals(rules.rollEngineBlast(context.random()))) {
            explode(context, target, events, "battle.log.blast");
        }
    }

    /**
     * Корабль взрывается — п. 8: сам гибнет и бьёт всех, кто рядом.
     * <p>
     * Взрывом кончается разбитый двигатель ({@link #wreckSystem}) и самоподрыв
     * ({@link #selfDestruct}) — разница только в том, чей это был выбор. Сила взрыва
     * считается от ПОЛНОЙ прочности корпуса: рвётся корабль, а не его остаток, и потому
     * дредноут опасен даже добитым.
     * <p>
     * <b>Соседям взрыв систем не ломает и цепи не устраивает.</b> Взрыв бьёт снаружи, а
     * не пробивает корабль насквозь, — и это же снимает бесконечную цепочку: иначе один
     * подрыв в плотном строю уносил бы всё поле, а у боя не осталось бы ни одного
     * решения игрока.
     */
    private void explode(Context context, BattleShipEntity ship, List<BattleEventDto> events,
                         String key) {
        Model model = context.model(ship);
        Integer damage = rules.blastDamage(model.hull());
        Integer radius = rules.blastRadius(model.hull());
        // Сперва взрыв, потом гибель: журнал рассказывает бой по порядку, а «уничтожен»
        // раньше «взрывается» читается как две разных беды.
        events.add(new BattleEventDto("BLAST", ship.getId(), null,
                ship.getX(), ship.getY(), damage, null,
                messages.get(key, name(context, ship), damage)));
        destroy(context, ship, events, "battle.log.selfDestruct".equals(key) ? "SELF_DESTRUCT" : "BLAST", null);

        for (BattleShipEntity near : context.ships()) {
            if (near.getId().equals(ship.getId()) || !near.alive()) {
                continue;
            }
            if (rules.distance(ship.getX(), ship.getY(), near.getX(), near.getY()) > radius) {
                continue;
            }
            BattleRules.ShieldHit hit = rules.shieldHit(damage, shieldClass(context, near), near.getShield());
            near.setShield(hit.pool());
            Integer through = hit.through();
            Integer armourLeft = Math.max(0, near.getArmour() - through);
            Integer structureLeft = Math.max(0,
                    near.getStructure() - Math.max(0, through - near.getArmour()));
            near.setArmour(armourLeft);
            near.setStructure(structureLeft);
            events.add(new BattleEventDto("BLAST_HIT", ship.getId(), near.getId(),
                    near.getX(), near.getY(), through, null,
                    messages.get("battle.log.blastHit", name(context, near), through)));
            if (near.getStructure() <= 0) {
                destroy(context, near, events, "BLAST_HIT", ship.getId());
            }
        }
    }

    /**
     * Самоподрыв — п. 8, кнопка SELF DESTRUCT оригинала.
     * <p>
     * Гарантированный взрыв по воле игрока: корабль уходит сам и уносит с собой всех, кто
     * стоит рядом. Смысл его в том, что подбитый корабль уже не спасти, а взрыв — это
     * единственное, что он ещё может сделать по чужому строю.
     */
    private void selfDestruct(Context context, BattleShipEntity ship,
                              List<BattleEventDto> events) {
        explode(context, ship, events, "battle.log.selfDestruct");
    }

    /**
     * Корабль уничтожен: с поля уходит, а в записи боя остаётся ради подсчёта потерь.
     * <p>
     * Гибель называет причину — field-backlog, пункт 204, и правило «потеря называет причину»
     * (backlog-promo, пункт 6): «уничтожен» без того, ОТ ЧЕГО, читалось концом боя без объяснения,
     * особенно у чудища, чьи когти и взрывы двигателей игрок не отличал. Причина — кодом в
     * событии (для сцены итога) и словами в журнале.
     *
     * @param by корабль, чей удар или взрыв стал последним; пусто — свой взрыв, самоподрыв, отход
     */
    private void destroy(Context context, BattleShipEntity ship, List<BattleEventDto> events,
                         String cause, UUID by) {
        if (!ship.alive()) {
            return;
        }
        ship.setDestroyed(Boolean.TRUE);
        BattleShipEntity killer = by == null ? null : context.ships().stream()
                .filter(candidate -> candidate.getId().equals(by)).findFirst().orElse(null);
        String text = switch (cause) {
            case "SHOT" -> killer == null ? messages.get("battle.log.destroyed", name(context, ship))
                    : messages.get("battle.log.destroyedBy", name(context, ship), name(context, killer));
            case "BLAST_HIT" -> killer == null ? messages.get("battle.log.destroyed", name(context, ship))
                    : messages.get("battle.log.destroyedByBlast", name(context, ship), name(context, killer));
            case "SELF_DESTRUCT" -> messages.get("battle.log.destroyedSelf", name(context, ship));
            case "STRANDED" -> messages.get("battle.log.destroyedStranded", name(context, ship));
            default -> messages.get("battle.log.destroyedBlast", name(context, ship));
        };
        events.add(new BattleEventDto("DESTROYED", ship.getId(), by,
                ship.getX(), ship.getY(), null, null, text, null, null, null, cause));
    }

    /**
     * Как называется выбитая система на языке читателя.
     * <p>
     * У ствола имя берётся из справочника — игрок должен видеть, ЧТО именно замолчало:
     * потерять ближнюю оборону и потерять тяжёлую установку — разные беды.
     */
    private String systemName(Context context, BattleShipEntity ship, String system) {
        if (BattleRules.ENGINE_SYSTEM.equals(system)) {
            return messages.get("battle.system.engine");
        }
        if (BattleRules.SHIELD_SYSTEM.equals(system)) {
            return messages.get("battle.system.shield");
        }
        if (BattleRules.COMPUTER_SYSTEM.equals(system)) {
            return messages.get("battle.system.computer");
        }
        return context.model(ship).items().stream()
                .map(ShipDesignRules.Item::component)
                .filter(component -> component.code().equals(system))
                .map(component -> component.name())
                .findFirst()
                .orElse(system);
    }

    /**
     * Состав корабля без выбитых систем — п. 8: по нему считаются залп, прицел и дальность.
     * <p>
     * У целого корабля возвращается сам состав проекта: пока ломать нечего, лишних
     * списков не строим — залп считается на каждый выстрел каждого корабля.
     */
    private List<ShipDesignRules.Item> parts(Context context, BattleShipEntity ship) {
        return rules.partsLeft(context.model(ship).items(), ship.getDamagedSystems());
    }

    /** Стволы, которые у этого корабля ещё стреляют. */
    private List<BattleRules.Shot> shots(Context context, BattleShipEntity ship) {
        Model model = context.model(ship);
        List<ShipDesignRules.Item> left = parts(context, ship);
        return left == model.items() ? model.shots()
                : rules.shots(left, rules.engineCombatSpeed(model.items()));
    }

    /** Отступление: сторона уходит с поля, бой кончается, поле остаётся за противником. */
    /**
     * Отступление: сторона уходит с поля, бой кончается, поле остаётся за противником.
     * <p>
     * <b>Уходят только те, кто может идти</b> — п. 8: корабль с разбитым двигателем
     * остаётся на месте и гибнет вместе с полем. Это и есть цена неподвижности: пока
     * двигатель цел, из безнадёжного боя можно выйти, а подбитому бежать уже нечем.
     */
    private Boolean retreat(Context context, BattleShipEntity ship, List<BattleEventDto> events) {
        if (platform(context.model(ship))) {
            throw new ConflictException("battle.retreat.platform");
        }
        if (Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM)) || speed(context, ship) == 0) {
            throw new ConflictException("battle.retreat.immobile", name(context, ship));
        }
        BattleShipEntity dissipater = retreatBlocker(context, ship);
        if (dissipater != null) {
            throw new ConflictException("battle.retreat.dissipated", name(context, dissipater));
        }
        ship.setRetreating(Boolean.TRUE);
        events.add(event("RETREAT", ship, null, null,
                messages.get("battle.log.retreatOrder", name(context, ship))));
        /*
          Уйти сразу можно, только пока ход НЕ ТРОНУТ (09.10.2026, решение хозяина проекта): корабль
          не сдвинулся и не стрелял. Иначе разгон к прыжку — следующий ход корабля, и до него он
          на поле и под огнём. Разворота корпуса как отдельного действия в бою нет: корабль
          поворачивается сам, когда ходит, — поэтому «не вращался» и есть «не ходил».
        */
        return ship.getMoved() == 0 && !Boolean.TRUE.equals(ship.getFired());
    }

    /**
     * Чужой корабль с варп-рассеивателем, который держит этот на поле, — п. 8 (13.10.2026,
     * перенос MOO II): «prevents enemy ships from retreating during combat». Пусто — уйти можно.
     */
    private BattleShipEntity retreatBlocker(Context context, BattleShipEntity ship) {
        return context.ships().stream()
                .filter(one -> one.alive() && one.getSide() != ship.getSide())
                .filter(one -> rules.special(parts(context, one), ShipEffectType.RETREAT_BLOCK) > 0)
                .findFirst()
                .orElse(null);
    }

    /**
     * Корабль с приказом отходить дождался своего хода — уходит в гиперпространство (п. 8).
     * Обездвиженный за это время (разбит двигатель, держат тяговые лучи) уйти не может и
     * остаётся на поле, а приказ снимается: стоять «уходящим» ему незачем.
     */
    private void warpOut(Context context, BattleShipEntity ship, List<BattleEventDto> events) {
        ship.setRetreating(null);
        if (Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM)) || speed(context, ship) == 0) {
            events.add(event("PASS", ship, null, null,
                    messages.get("battle.log.stranded", name(context, ship))));
            return;
        }
        // Рассеиватель, появившийся на поле после приказа, держит так же (п. 8).
        BattleShipEntity dissipater = retreatBlocker(context, ship);
        if (dissipater != null) {
            events.add(event("PASS", ship, null, null,
                    messages.get("battle.log.dissipated", name(context, ship), name(context, dissipater))));
            return;
        }
        ship.setRetreated(Boolean.TRUE);
        events.add(event("WARP_OUT", ship, null, null,
                messages.get("battle.log.warpOut", name(context, ship))));
    }

    /**
     * Ход переходит следующему кораблю, а корабли ИИ ходят тут же.
     * <p>
     * Ждать от ИИ отдельного запроса некому: своих решений он не принимает нигде в игре.
     * Поэтому сервер проигрывает его ходы сразу и отдаёт клиенту пачку событий — экран
     * покажет их подряд, как будто бой шёл у игрока на глазах.
     */
    private void endShipTurn(Context context, List<BattleEventDto> events) {
        advance(context, events);
        playOthers(context, events);
    }

    /** Корабли ИИ ходят тут же, пока очередь не дойдёт до человека или бой не кончится. */
    private void playOthers(Context context, List<BattleEventDto> events) {
        int guard = 0;
        while (context.battle().getState() == BattleState.IN_PROGRESS
                && guard++ < MAX_SHIPS_PER_SIDE * 4) {
            BattleShipEntity current = context.current();
            if (current == null || !isAi(context, current)) {
                return;
            }
            playAi(context, current, events);
            advance(context, events);
        }
    }

    /**
     * Ход корабля ИИ: подойти к ближайшему врагу на ВЫГОДНОЕ расстояние и выстрелить, если
     * достанет.
     * <p>
     * Выгодное — не предел дальности, а то, с которого залп бьёт в полную силу
     * ({@link BattleRules#preferredDistance}, backlog-promo, пункт 30). Прежде корабль
     * подходил ровно до предела самого дальнобойного ствола: лучевые вставали на 24 клетках
     * и били на треть силы, а ракетные не двигались вовсе — бой стоял, и эскадры
     * перестреливались с краёв поля. В MOO II корабли сходятся.
     */
    /** Звено носителя на борту и готово к взлёту — п. 8 ({@link BattleRules#FIGHTER_REARM_ROUNDS}). */
    private Boolean fightersHome(Context context, BattleShipEntity ship) {
        Integer back = ship.getFightersBackRound();
        return back == null || context.battle().getRound() >= back;
    }

    private void playAi(Context context, BattleShipEntity ship, List<BattleEventDto> events) {
        BattleShipEntity target = nearestEnemy(context, ship);
        if (target == null) {
            return;
        }

        List<BattleRules.Shot> shots = shots(context, ship);
        boolean carrier = rules.special(parts(context, ship), ShipEffectType.FIGHTER_BAY) > 0;
        int devices = deviceReach(context, ship);
        if (shots.isEmpty() && !carrier && devices > 0) {
            // Одни устройства (пульсар, гиродестабилизатор, сеть — п. 8): подойти на их досягаемость.
            if (rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY()) > devices) {
                approach(context, ship, target, devices, events);
            }
            if (!Boolean.TRUE.equals(ship.getFired())
                    && rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY()) <= devices) {
                fire(context, ship, target, null, events);
            }
            return;
        }
        if (shots.isEmpty() && !carrier) {
            // Стволов не осталось вовсе: безоружный корабль ходит, но не стреляет.
            return;
        }
        if (shots.isEmpty()) {
            // Носитель одних истребителей (п. 8): звенья летят сами, подходить ему незачем. Звено не дома —
            // стрелять нечем, и ход пропускается: иначе отказ «стволы выбиты» вставал бы на этом корабле и
            // запирал ход ИИ (найдено 14.10.2026 — у носителя выбили ракеты, звено было в полёте).
            if (!Boolean.TRUE.equals(ship.getFired()) && Boolean.TRUE.equals(fightersHome(context, ship))) {
                fire(context, ship, target, null, events);
            }
            return;
        }
        // Ракетчик стоит на пределе против того, кому нечем сбивать ракеты (пункт 30).
        Model aim = context.model(target);
        Boolean intercepts = !rules.pointDefenceRows(aim.items()).isEmpty()
                || rules.antiMissilePercent(parts(context, target)) > 0;
        Integer preferred = rules.preferredDistance(shots, intercepts);
        if (rules.distance(ship.getX(), ship.getY(), target.getX(), target.getY()) > preferred) {
            approach(context, ship, target, preferred, events);
        }
        if (Boolean.TRUE.equals(boardForAi(context, ship, events))) {
            return;
        }
        if (Boolean.TRUE.equals(interceptForAi(context, ship, events))) {
            return;
        }
        if (Boolean.TRUE.equals(rules.inReach(shots, ship.getX(), ship.getY(),
                target.getX(), target.getY()))
                && !Boolean.TRUE.equals(ship.getFired())
                && !Boolean.TRUE.equals(allResting(context, ship))) {
            fire(context, ship, target, null, events);
        }
    }

    /** Шаг за шагом к цели: по клетке, пока есть ход и пока не подошли на нужное расстояние. */
    private void approach(Context context, BattleShipEntity ship, BattleShipEntity target,
                          Integer preferred, List<BattleEventDto> events) {
        int left = speed(context, ship) - ship.getMoved();
        int x = ship.getX();
        int y = ship.getY();
        for (int step = 0; step < left; step++) {
            if (rules.distance(x, y, target.getX(), target.getY()) <= preferred) {
                break;
            }
            int nextX = x + Integer.signum(target.getX() - x);
            int nextY = y + Integer.signum(target.getY() - y);
            if (!rules.onField(nextX, nextY) || occupied(context.ships(), nextX, nextY)) {
                // Дорогу загородили — обходим по одной оси, а не стоим на месте.
                if (!occupied(context.ships(), nextX, y) && rules.onField(nextX, y)) {
                    nextY = y;
                } else if (!occupied(context.ships(), x, nextY) && rules.onField(x, nextY)) {
                    nextX = x;
                } else {
                    break;
                }
            }
            x = nextX;
            y = nextY;
        }

        if (x != ship.getX() || y != ship.getY()) {
            int distance = rules.distance(ship.getX(), ship.getY(), x, y);
            ship.setX(x);
            ship.setY(y);
            ship.setMoved(ship.getMoved() + distance);
            events.add(event("MOVE", ship, null, null,
                    messages.get("battle.log.move", name(context, ship), x, y)));
        }
    }

    /**
     * Передаёт ход следующему живому кораблю очереди. Круг кончился — начинается
     * следующий: щиты восстанавливаются, движение и залп открываются заново.
     */
    private void advance(Context context, List<BattleEventDto> events) {
        step(context, events);
        departRetreating(context, events);
    }

    /** Отходящие, до которых дошла очередь, уходят один за другим (п. 8). */
    private void departRetreating(Context context, List<BattleEventDto> events) {
        /*
          Очередь дошла до корабля с приказом отходить — он уходит, и ход тут же переходит
          дальше (п. 8): решать ему больше нечего. Указатель сдвигается ДО ухода — пока корабль
          ещё в очереди, иначе его место в ней потерялось бы и круг кончился раньше срока.
        */
        int guard = 0;
        while (context.battle().getState() == BattleState.IN_PROGRESS
                && guard++ < MAX_SHIPS_PER_SIDE * 2 + 2) {
            BattleShipEntity current = context.current();
            if (current == null || !Boolean.TRUE.equals(current.getRetreating()) || !current.alive()) {
                return;
            }
            step(context, events);
            warpOut(context, current, events);
            if (Boolean.TRUE.equals(finished(context, events))) {
                return;
            }
            if (context.current() == null || context.current() == current) {
                // Ушедший открывал новый круг: ход — первому из оставшихся, круг тот же.
                context.battle().setCurrentShipId(order(context.ships()).stream()
                        .filter(BattleShipEntity::alive)
                        .findFirst()
                        .map(BattleShipEntity::getId)
                        .orElse(null));
            }
        }
    }

    /** Ход — следующему кораблю очереди; кончился круг — новый круг. */
    private void step(Context context, List<BattleEventDto> events) {
        if (finished(context, events)) {
            return;
        }

        // Замороженный стазисом хода не получает (п. 8); нынешний корабль в очереди остаётся
        // всегда — от него считается следующий.
        UUID currentId = context.battle().getCurrentShipId();
        List<BattleShipEntity> queue = order(context.ships()).stream()
                .filter(BattleShipEntity::alive)
                .filter(one -> one.getId().equals(currentId) || !Boolean.TRUE.equals(frozen(context, one)))
                .toList();
        int index = -1;
        for (int position = 0; position < queue.size(); position++) {
            if (queue.get(position).getId().equals(currentId)) {
                index = position;
                break;
            }
        }

        // Лишний ход ускорителя времени (п. 8, перенос MOO II) идёт В КОНЦЕ круга: корабль, который
        // сейчас его берёт, свой обычный ход уже сделал, и следующего по очереди за ним нет.
        Integer round = context.battle().getRound();
        BattleShipEntity now = context.current();
        boolean bonus = now != null && round.equals(now.getWarpRound());
        if (!bonus && index >= 0 && index + 1 < queue.size()) {
            context.battle().setCurrentShipId(queue.get(index + 1).getId());
            return;
        }
        BattleShipEntity warped = queue.stream()
                .filter(one -> !round.equals(one.getWarpRound()))
                .filter(one -> !Boolean.TRUE.equals(frozen(context, one)) && !Boolean.TRUE.equals(one.getRetreating()))
                .filter(one -> rules.special(parts(context, one), ShipEffectType.TIME_WARP) > 0)
                .findFirst()
                .orElse(null);
        if (warped != null) {
            warped.setWarpRound(round);
            warped.setMoved(0);
            warped.setFired(Boolean.FALSE);
            context.battle().setCurrentShipId(warped.getId());
            events.add(event("TIME_WARP", warped, warped.getId(), 0,
                    messages.get("battle.log.timeWarp", name(context, warped))));
            return;
        }

        // Круг кончился.
        if (context.battle().getRound() >= MAX_ROUNDS) {
            events.add(new BattleEventDto("FINISHED", null, null, null, null, null, null,
                    messages.get("battle.log.stalemate")));
            finish(context, events);
            return;
        }

        context.battle().setRound(context.battle().getRound() + 1);
        for (BattleShipEntity ship : context.ships()) {
            ship.setMoved(0);
            ship.setFired(Boolean.FALSE);
            // Тяговые лучи держат до конца круга — п. 8: в новом круге их наводят заново.
            ship.setTractored(0);
            // Щит восстанавливается между кругами на 30 % запаса (MOO II), со щитовым
            // конденсатором втрое быстрее. Разбитому щиту восстанавливаться нечем: выбитая
            // система держится до конца боя (п. 8).
            ship.setShield(Boolean.TRUE.equals(ship.systemDamaged(BattleRules.SHIELD_SYSTEM))
                    ? 0
                    : rules.recharged(ship.getShield(), context.model(ship).stats().shield(),
                            rules.special(parts(context, ship), ShipEffectType.SHIELD_RECHARGE_MULTIPLIER)));
            repairCybernetic(context, ship, events);
        }
        // Плазменная сеть жжёт в начале круга — после щитов — и тает на 5 (п. 8).
        for (BattleShipEntity ship : order(context.ships())) {
            int web = ship.getPlasmaWeb();
            if (web <= 0 || !ship.alive()) {
                continue;
            }
            int dealt = deviceHit(context, null, ship, web, false, events);
            ship.setPlasmaWeb(Math.max(0, web - BattleRules.PLASMA_WEB_DECAY));
            events.add(new BattleEventDto("FIRE", null, ship.getId(), ship.getX(), ship.getY(), dealt, null,
                    messages.get("battle.log.plasmaBurn", name(context, ship), dealt)));
        }
        if (finished(context, events)) {
            return;
        }
        // Ракеты в полёте делают круг пути (backlog-promo, пункт 30) — после щитов: щит
        // встречает долетевший залп восстановленным, как и залп нового круга. Долетевшие
        // могут добить последнего, поэтому конец боя проверяется заново, а очередь
        // берётся из тех, кто пережил подлёт.
        flyMissiles(context, events);
        if (finished(context, events)) {
            return;
        }
        context.battle().setCurrentShipId(order(context.ships()).stream()
                .filter(BattleShipEntity::alive)
                .filter(one -> !Boolean.TRUE.equals(frozen(context, one)))
                .findFirst()
                .or(() -> order(context.ships()).stream().filter(BattleShipEntity::alive).findFirst())
                .map(BattleShipEntity::getId)
                .orElse(null));
    }

    /**
     * Кибернетическая раса чинит свои корабли прямо в бою — п. 7.
     * <p>
     * Между кругами корабль возвращает себе десятую долю брони и корпуса (число MOO II).
     * Погибшему это уже не помогает: чинить нечего, и правило оригинала о полном
     * восстановлении «после битвы» здесь выполняется само — корабли выходят из боя
     * целыми, потому что урон между боями не хранится вовсе.
     */
    private void repairCybernetic(Context context, BattleShipEntity ship,
                                  List<BattleEventDto> events) {
        if (!ship.alive()) {
            return;
        }
        PlayerEntity owner = context.players().get(ship.getOwnerPlayerId());
        boolean cybernetic = owner != null && Boolean.TRUE.equals(raceService.effects(owner).cybernetic());
        // Ремонтный модуль (п. 8, 13.10.2026, перенос MOO II) чинит своё сверх кибернетики:
        // «repairs up to 20% of a ship's armor or structure per combat turn». Разбитый модуль
        // не чинит ничего — он уже не в составе.
        int percent = (cybernetic ? BattleRules.CYBERNETIC_REPAIR_PERCENT : 0)
                + rules.special(parts(context, ship), ShipEffectType.REPAIR_PERCENT);
        if (percent <= 0) {
            return;
        }

        ShipStats stats = context.model(ship).stats();
        Integer armour = rules.repaired(ship.getArmour(), stats.armour(), percent);
        Integer structure = rules.repaired(ship.getStructure(), stats.structure(), percent);
        if (armour.equals(ship.getArmour()) && structure.equals(ship.getStructure())) {
            return;
        }

        Integer healed = (armour - ship.getArmour()) + (structure - ship.getStructure());
        ship.setArmour(armour);
        ship.setStructure(structure);
        events.add(new BattleEventDto("REPAIR", ship.getId(), ship.getId(),
                ship.getX(), ship.getY(), healed, null,
                messages.get("battle.log.repaired", name(context, ship), healed)));
    }

    /** Кончился ли бой: у одной из сторон не осталось живых кораблей. */
    private Boolean finished(Context context, List<BattleEventDto> events) {
        if (context.battle().getState() != BattleState.IN_PROGRESS) {
            return Boolean.TRUE;
        }
        boolean attackers = context.ships().stream()
                .anyMatch(ship -> ship.getSide() == BattleSide.ATTACKER && ship.alive());
        boolean defenders = context.ships().stream()
                .anyMatch(ship -> ship.getSide() == BattleSide.DEFENDER && ship.alive());
        if (attackers && defenders) {
            return Boolean.FALSE;
        }
        finish(context, events);
        return Boolean.TRUE;
    }

    /**
     * Конец боя: потери списываются с флотов, исход ложится в запись боя.
     * <p>
     * Уцелевшие возвращаются во флот числом, погибшие уходят из состава. Встречу и итоги
     * хода закрывает {@code EncounterService} — он же и начал бой.
     */
    private void finish(Context context, List<BattleEventDto> events) {
        SpaceBattleEntity battle = context.battle();
        battle.setState(BattleState.FINISHED);
        battle.setCurrentShipId(null);
        // Бой кончился — ракетам в полёте лететь больше не к кому (пункт 30).
        battle.setMissiles(List.of());

        Map<UUID, Integer> lost = new HashMap<>();
        for (BattleShipEntity ship : context.ships()) {
            if (Boolean.TRUE.equals(ship.getCaptured())) {
                // Взятый корабль — потеря прежнего хозяина, что бы с ним ни стало потом.
                lost.merge(ownerOf(context, opposite(ship.getSide())), 1, Integer::sum);
            } else if (Boolean.TRUE.equals(ship.getDestroyed())) {
                lost.merge(ship.getOwnerPlayerId(), 1, Integer::sum);
            }
        }

        // Сторона, у которой на поле не осталось никого, а кто-то ушёл отходом, — отступила:
        // это её поражение, но не разгром (п. 8). Уход ловится по кораблям, а не по приказу.
        for (BattleSide side : BattleSide.values()) {
            boolean left = context.ships().stream()
                    .anyMatch(ship -> ship.getSide() == side && Boolean.TRUE.equals(ship.getRetreated()));
            boolean stays = context.ships().stream()
                    .anyMatch(ship -> ship.getSide() == side && ship.alive());
            if (left && !stays) {
                battle.setRetreatedPlayerId(ownerOf(context, side));
            }
        }

        recordLeaders(context);
        applyLosses(context, BattleSide.ATTACKER);
        applyLosses(context, BattleSide.DEFENDER);
        // Опыт за бой уцелевшим обеих сторон (п. 8, CrewRules); у чудищ и демонстрации флота нет.
        for (UUID owner : java.util.Arrays.asList(context.battle().getAttackerPlayerId(),
                context.battle().getDefenderPlayerId())) {
            FleetEntity survived = owner == null ? null : fleetService.fleetAt(owner, context.battle().getStarSystemId());
            if (survived != null) {
                fleetService.gainExperience(survived.getId(), CrewRules.BATTLE_XP);
            }
        }
        takePrizes(context);
        fallBack(context);

        battle.setOutcome(outcome(context, lost).packed());
        events.add(new BattleEventDto("FINISHED", null, null, null, null, null, null,
                messages.text(battle.getOutcome())));
        log.info("Бой в системе {} окончен: {}",
                battle.getStarSystemId(), battle.getOutcome());
    }

    /**
     * Списывает погибшие корабли стороны с её флота — по проектам.
     * <p>
     * Платформы обороны списываются не с флота, а С ПЛАНЕТЫ: разбитая звёздная база
     * перестаёт быть зданием колонии (п. 8, п. 11). Без этого оборону нельзя было бы
     * подавить вовсе — она возрождалась бы к каждому следующему бою целой.
     */
    private void applyLosses(Context context, BattleSide side) {
        UUID ownerId = side == BattleSide.ATTACKER
                ? context.battle().getAttackerPlayerId()
                : context.battle().getDefenderPlayerId();
        Map<UUID, Integer> lostByDesign = new HashMap<>();
        for (BattleShipEntity ship : context.ships()) {
            // Взятый на абордаж корабль уходит из флота прежнего хозяина (он стоит теперь на
            // другой стороне), а погибший потом за захватчика его флоту не принадлежал вовсе.
            boolean taken = Boolean.TRUE.equals(ship.getCaptured()) && ship.getSide() != side;
            boolean killed = !Boolean.TRUE.equals(ship.getCaptured())
                    && ship.getSide() == side && Boolean.TRUE.equals(ship.getDestroyed());
            if (taken || killed) {
                lostByDesign.merge(ship.getDesignId(), 1, Integer::sum);
            }
        }
        if (lostByDesign.isEmpty()) {
            return;
        }

        PlayerEntity owner = context.player(ownerId);
        Map<String, ShipDesignEntity> platforms = owner == null
                ? Map.of()
                : shipDesignService.platformDesigns(owner);
        Map<UUID, String> hullByDesign = new HashMap<>();
        platforms.forEach((hull, design) -> hullByDesign.put(design.getId(), hull));
        if (!hullByDesign.isEmpty()) {
            Map<UUID, Integer> lostPlatforms = new HashMap<>();
            lostByDesign.entrySet().removeIf(entry -> {
                if (hullByDesign.containsKey(entry.getKey())) {
                    lostPlatforms.put(entry.getKey(), entry.getValue());
                    return true;
                }
                return false;
            });
            lostPlatforms.forEach((designId, count) -> orbitalDefence.destroy(
                    ownerId, context.battle().getStarSystemId(),
                    hullByDesign.get(designId), count));
        }
        if (lostByDesign.isEmpty()) {
            return;
        }

        FleetEntity fleet = fleetService.fleetAt(ownerId, context.battle().getStarSystemId());
        fleetService.applyBattleLosses(fleet, lostByDesign);
    }

    /**
     * Послужной список (пункт 140): лидерам флотов обеих сторон — исход, тем же правилом, что
     * у строки итога (ушедший проиграл, иначе побеждает сторона, у которой остались корабли).
     * Флот берётся ДО списания потерь: погибший целиком флот после него исчезает.
     */
    private void recordLeaders(Context context) {
        SpaceBattleEntity battle = context.battle();
        if (battle.getEncounterId() == null) {
            return;
        }
        Boolean attackerWon;
        if (battle.getRetreatedPlayerId() != null) {
            attackerWon = !battle.getRetreatedPlayerId().equals(battle.getAttackerPlayerId());
        } else {
            boolean attackersAlive = context.ships().stream()
                    .anyMatch(ship -> ship.getSide() == BattleSide.ATTACKER && ship.alive());
            boolean defendersAlive = context.ships().stream()
                    .anyMatch(ship -> ship.getSide() == BattleSide.DEFENDER && ship.alive());
            attackerWon = attackersAlive == defendersAlive ? null : attackersAlive;
        }
        if (attackerWon == null) {
            return;
        }
        for (BattleSide side : BattleSide.values()) {
            UUID owner = ownerOf(context, side);
            FleetEntity fleet = owner == null ? null : fleetService.fleetAt(owner, battle.getStarSystemId());
            if (fleet != null) {
                Boolean won = side == BattleSide.ATTACKER ? attackerWon : !attackerWon;
                leaderRecord.battle(owner, fleet.getId(), won);
                // Подвиги флота (пункт 141): счёт в строке самого флота.
                fleet.recordBattle(won);
                fleetRepository.save(fleet);
            }
        }
    }

    /**
     * Флот, не оставивший на поле ни одного корабля и уведший кого-то отходом, летит к
     * ближайшей своей колонии — п. 8 (06.10.2026, решение хозяина проекта): ушедшие в
     * гиперпространство не возвращаются стоять под чужими пушками. Платформы не в счёт — они
     * не флот. Бой с чудищем — тоже: встречи у него нет, а флот есть. У демонстрации флотов
     * нет вовсе, и улетать там некому.
     */
    private void fallBack(Context context) {
        SpaceBattleEntity battle = context.battle();
        for (BattleSide side : BattleSide.values()) {
            boolean left = context.ships().stream()
                    .anyMatch(ship -> ship.getSide() == side && Boolean.TRUE.equals(ship.getRetreated())
                            && !Boolean.TRUE.equals(ship.getCaptured()));
            boolean stays = context.ships().stream()
                    .anyMatch(ship -> ship.getSide() == side && ship.alive() && !platform(context.model(ship)));
            PlayerEntity owner = context.player(ownerOf(context, side));
            if (left && !stays && owner != null) {
                fleetService.fallBack(owner, battle.getStarSystemId());
            }
        }
    }

    private static BattleSide opposite(BattleSide side) {
        return side == BattleSide.ATTACKER ? BattleSide.DEFENDER : BattleSide.ATTACKER;
    }

    private static UUID ownerOf(Context context, BattleSide side) {
        return side == BattleSide.ATTACKER
                ? context.battle().getAttackerPlayerId()
                : context.battle().getDefenderPlayerId();
    }

    /**
     * Трофеи абордажа — п. 8: уцелевший взятый корабль встаёт во флот захватчика в системе
     * боя. Проект у него чужой, поэтому захватчик получает его КОПИЮ — вытесненной строкой,
     * которая не занимает ячеек дизайна (как в MOO II: трофей летает, но строить такой нельзя).
     * Бой без встречи (демонстрация) флотов не имеет, и трофеев в нём нет.
     */
    private void takePrizes(Context context) {
        SpaceBattleEntity battle = context.battle();
        if (battle.getEncounterId() == null) {
            return;
        }
        Map<UUID, Map<UUID, Integer>> prizes = new LinkedHashMap<>();
        for (BattleShipEntity ship : order(context.ships())) {
            // Взятая база в трофеи не идёт: здание колонии не улетает (её хозяин её потерял).
            if (Boolean.TRUE.equals(ship.getCaptured()) && !Boolean.TRUE.equals(ship.getDestroyed())
                    && !platform(context.model(ship))) {
                prizes.computeIfAbsent(ship.getOwnerPlayerId(), key -> new LinkedHashMap<>())
                        .merge(ship.getDesignId(), 1, Integer::sum);
            }
        }
        prizes.forEach((captorId, byDesign) -> {
            PlayerEntity captor = context.player(captorId);
            if (captor == null) {
                return;
            }
            byDesign.forEach((designId, count) -> {
                UUID trophy = shipDesignService.trophyDesign(captor, designId, battle.getTurn()).getId();
                for (int i = 0; i < count; i++) {
                    fleetService.addShip(battle.getGameId(), captorId, battle.getStarSystemId(),
                            battle.getTurn(), trophy);
                }
            });
            log.info("Империя {} забрала абордажем {} кораблей в системе {}", captor.getName(),
                    byDesign.values().stream().mapToInt(Integer::intValue).sum(), battle.getStarSystemId());
        });
    }

    /** Готовая строка исхода для итогов хода обеих сторон. */
    private MessageKey outcome(Context context, Map<UUID, Integer> lost) {
        String attacker = context.nameKey(context.battle().getAttackerPlayerId());
        String defender = context.nameKey(context.battle().getDefenderPlayerId());
        int attackerLost = lost.getOrDefault(context.battle().getAttackerPlayerId(), 0);
        int defenderLost = lost.getOrDefault(context.battle().getDefenderPlayerId(), 0);
        /*
          Название системы спрашивается запросом, и это допустимо: бой кончается считанные
          разы за партию — как высадка десанта, — а не в каждой фазе на каждого игрока.
          Нужно оно затем, что этот же ключ идёт строкой в отчёт хода, где системы не видно.
        */
        String system = fleetService.systemNames(context.battle().getGameId())
                .getOrDefault(context.battle().getStarSystemId(),
                        messages.get("turn.system.unnamed"));

        if (context.battle().getRetreatedPlayerId() != null) {
            return new MessageKey("battle.outcome.retreated", system,
                    context.nameKey(context.battle().getRetreatedPlayerId()),
                    attacker, attackerLost, defender, defenderLost);
        }

        boolean attackersAlive = context.ships().stream()
                .anyMatch(ship -> ship.getSide() == BattleSide.ATTACKER && ship.alive());
        boolean defendersAlive = context.ships().stream()
                .anyMatch(ship -> ship.getSide() == BattleSide.DEFENDER && ship.alive());

        String winner = attackersAlive && !defendersAlive ? attacker
                : defendersAlive && !attackersAlive ? defender : null;
        return winner == null
                ? new MessageKey("battle.outcome.draw", system,
                        attacker, attackerLost, defender, defenderLost)
                : new MessageKey("battle.outcome.winner", system, winner,
                        attacker, attackerLost, defender, defenderLost);
    }

    /**
     * Очередь хода: по убыванию инициативы, при равной — по номеру в строю, а затем по
     * стороне.
     * <p>
     * Сторона нужна потому, что номер в строю СВОЙ У КАЖДОЙ СТОРОНЫ ({@code deploy} считает
     * его от единицы для нападающего и для обороны отдельно): первый корабль есть у обоих, и
     * при равной инициативе спор решался идентификатором строки — случайным UUID, своим у
     * каждого прогона. Тройка «инициатива — номер — сторона» различает корабли полностью, и
     * идентификатор здесь больше не нужен вовсе.
     */
    private List<BattleShipEntity> order(List<BattleShipEntity> ships) {
        List<BattleShipEntity> sorted = new ArrayList<>(ships);
        sorted.sort(Comparator.comparing(BattleShipEntity::getInitiative).reversed()
                .thenComparing(BattleShipEntity::getOrdinal)
                .thenComparing(ship -> ship.getSide().name()));
        return sorted;
    }

    private BattleShipEntity nearestEnemy(Context context, BattleShipEntity ship) {
        // Замороженного стазисом и скрытого фазовым плащом атаковать нельзя (п. 8).
        return context.ships().stream()
                .filter(candidate -> candidate.getSide() != ship.getSide()
                        && Boolean.TRUE.equals(targetable(context, candidate)))
                .min(Comparator.comparing(candidate ->
                        rules.distance(ship.getX(), ship.getY(), candidate.getX(), candidate.getY())))
                .orElse(null);
    }

    private Boolean occupied(List<BattleShipEntity> ships, Integer x, Integer y) {
        return ships.stream()
                .anyMatch(ship -> ship.alive() && ship.getX().equals(x) && ship.getY().equals(y));
    }

    private BattleShipEntity target(List<BattleShipEntity> ships, UUID targetId) {
        if (targetId == null) {
            throw new ConflictException("battle.noTarget");
        }
        return ships.stream()
                .filter(ship -> ship.getId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("battle.targetNotFound", targetId));
    }

    /**
     * Скорость корабля на поле — сколько клеток он проходит за свой ход.
     * <p>
     * Надпространственная раса (п. 7) складывает пространство и в бою: её корабли ходят
     * дальше прочих при том же двигателе.
     */
    /**
     * Сколько клеток корабль проходит за ход — п. 8: боевая скорость двигателя, переведённая
     * в клетки (`BattleRules.cellsPerTurn`), плюс расовая прибавка надпространственных.
     */
    private Integer speed(Context context, BattleShipEntity ship) {
        // Разбитый двигатель — это НЕПОДВИЖНОСТЬ, а не медленный ход (п. 8): корабль
        // остаётся там, где его подбили, и уйти с поля уже не может. Так его выбивает и
        // ракета с наведением по излучению (EMG), и всякое попадание по корпусу.
        if (Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM))) {
            return 0;
        }
        // Платформа обороны не ходит вовсе (п. 8, п. 11). Проекты платформ собирались тем же
        // составом, что и корабли, — с двигателем, — и звёздная база получала боевую скорость:
        // ИИ мог повести её на сближение, а полоса боя писала ей «двигатель» и «ход 12».
        // Новые проекты двигателя не получают (ShipDesignService.defaultItems), а старые, с
        // двигателем в составе, останавливает это правило.
        if (platform(context.model(ship))) {
            return 0;
        }
        PlayerEntity owner = context.players().get(ship.getOwnerPlayerId());
        Integer racial = owner == null ? 0 : raceService.effects(owner).shipCombatSpeed();
        // Подпространственный телепорт (п. 8): прыжок до 20 клеток сверх обычного хода.
        int own = Math.max(1, rules.cellsPerTurn(context.model(ship).stats()) + racial)
                + rules.special(parts(context, ship), ShipEffectType.TELEPORT);
        // Тяговые лучи держат корабль до конца круга — п. 8: луч на класс корпуса обездвиживает.
        return rules.tractoredSpeed(own, ship.getTractored(), context.model(ship).hull());
    }

    /**
     * Обездвижен ли корабль — п. 8, условие абордажа: разбит двигатель или его держат
     * тяговые лучи. Платформа сюда не относится: она стоит на месте по устройству, а не
     * подбитая, и абордажем её не берут — только рейдом.
     */
    private Boolean immobile(Context context, BattleShipEntity ship) {
        return Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM))
                || (!platform(context.model(ship)) && speed(context, ship) == 0);
    }

    /**
     * За этот корабль ходит сервер, а не человек.
     * <p>
     * Это империи ИИ и <b>чудища</b> (п. 11.1): у чудища хозяина нет вовсе, поэтому ходит
     * за него тот же код, что и за соседа. Проверка идёт по роду стороны, а не «не
     * человек ли»: род без хозяина в игре ровно один, и назвать его прямо честнее.
     */
    private Boolean isAi(Context context, BattleShipEntity ship) {
        PlayerEntity owner = context.players().get(ship.getOwnerPlayerId());
        // Корабли ушедшего человека ведёт ИИ — backlog-promo, пункт 11.
        return owner != null
                && (Boolean.TRUE.equals(presence.aiDriven(owner))
                    || owner.getPlayerType() == PlayerType.MONSTER);
    }

    /**
     * Как корабль зовут в журнале боя: проект, номер в строю и империя.
     * <p>
     * Империя в названии не для красоты: проекты у обеих сторон часто называются
     * одинаково («Фрегат» против «Фрегата»), и без неё журнал читался бы как разговор
     * корабля с самим собой.
     */
    private String name(Context context, BattleShipEntity ship) {
        Model model = context.model(ship);
        // Чудище зовётся своим телом из справочника, а тот переводится (п. 11.1);
        // у корабля имя проекта хранится в базе и языка не имеет — кроме проектов, которые
        // назвала сама игра ключом словаря (демонстрация): те переводятся (`known`).
        String design = Boolean.TRUE.equals(model.hull().monster())
                ? model.hull().name()
                : messages.known(model.design().getName());
        return design + " " + ship.getOrdinal()
                + " (" + context.name(ship.getOwnerPlayerId()) + ")";
    }

    private BattleEventDto event(String type, BattleShipEntity ship, UUID targetId, Integer damage,
                                 String text) {
        return new BattleEventDto(type, ship.getId(), targetId, ship.getX(), ship.getY(),
                damage, null, text);
    }

    private List<BattleShipEntity> ships(UUID battleId) {
        return shipRepository.findAllByBattleIdOrderByInitiativeDescOrdinalAsc(battleId);
    }

    public SpaceBattleEntity require(UUID battleId) {
        return battleRepository.findById(battleId)
                .orElseThrow(() -> new NotFoundException("battle.notFound", battleId));
    }

    private void save(Context context) {
        shipRepository.saveAll(context.ships());
        battleRepository.save(context.battle());
    }

    private Context context(SpaceBattleEntity battle, List<BattleShipEntity> ships,
                            Map<UUID, PlayerEntity> players) {
        Map<UUID, Model> models = new HashMap<>();
        for (PlayerEntity player : List.of(
                players.get(battle.getAttackerPlayerId()), players.get(battle.getDefenderPlayerId()))) {
            if (player == null) {
                continue;
            }
            Map<UUID, ShipDesignEntity> designs = shipDesignService.allDesignsOf(player);
            Map<UUID, ShipStats> stats = shipDesignService.statsByDesign(designs.values(),
                    raceService.effects(player));
            Map<UUID, List<ShipDesignRules.Item>> items = shipDesignService.itemsOf(designs.values());
            Officers officers = officers(player.getId(), battle.getStarSystemId());
            designs.forEach((id, design) -> {
                List<ShipDesignRules.Item> parts = items.getOrDefault(id, List.of());
                models.put(id, new Model(design, shipCatalog.hull(design.getHullCode()),
                        stats.get(id), parts, rules.attackPercent(parts), rules.shots(parts),
                        officers));
            });
        }
        return new Context(battle, ships, players, models,
                new Random(battle.getSeed() + battle.getRound()),
                messages.get("battle.ship.unknownOwner"),
                messages.get(MONSTER_SIDE_KEY),
                messages::empire);
    }

    /** Проект корабля со всем, что нужно бою: корпус, характеристики, стволы, офицеры. */
    public record Model(ShipDesignEntity design, ShipHull hull, ShipStats stats,
                        List<ShipDesignRules.Item> items, Integer attackPercent,
                        List<BattleRules.Shot> shots, Officers officers) {
    }

    /**
     * Что корабельные лидеры дают этой стороне боя — п. 6.
     * <p>
     * Офицер приписан к флоту, а флот у империи в системе один (стоящий), поэтому надбавка
     * у всех её кораблей в этом бою общая: разбираться, на каком именно корабле стоит
     * адмирал, игра не умеет — в MOO II он приписан к кораблю, и это точка расширения,
     * когда корабли станут именными и в базе.
     *
     * @param attack  «Оружейник»: прибавка к меткости
     * @param defence «Рулевой»: прибавка к уклонению
     * @param damage  «Артиллерист»: проценты к урону каждого выстрела
     */
    public record Officers(Integer attack, Integer defence, Integer damage) {

        static Officers none() {
            return new Officers(0, 0, 0);
        }
    }

    /** Надбавки офицеров флота этой империи, стоящего в системе боя, — п. 6. */
    private Officers officers(UUID playerId, UUID starSystemId) {
        return fleetRepository
                .findByOwnerPlayerIdAndStarSystemIdAndTargetSystemIdIsNull(playerId, starSystemId)
                .map(fleet -> {
                    LeaderBonusService.Bonuses bonuses = leaderBonuses.of(playerId);
                    return new Officers(
                            bonuses.fleetValue(fleet.getId(), "WEAPONRY"),
                            bonuses.fleetValue(fleet.getId(), "HELMSMAN"),
                            bonuses.fleetValue(fleet.getId(), "ORDNANCE"));
                })
                .orElseGet(Officers::none);
    }

    /** Всё, что нужно одному ходу боя, собранное разом: по кораблю в базу не ходим. */
    public record Context(SpaceBattleEntity battle, List<BattleShipEntity> ships,
                          Map<UUID, PlayerEntity> players, Map<UUID, Model> models,
                          RandomGenerator random, String unknownOwner,
                          /** Подпись стороны чудищ на языке запроса — п. 11.1. */
                          String monsterSide,
                          /** Имя империи на языке запроса: у ИИ хранится английским. */
                          java.util.function.UnaryOperator<String> shownName) {

        Model model(BattleShipEntity ship) {
            Model model = models.get(ship.getDesignId());
            if (model == null) {
                throw new NotFoundException("ship.designNotFound", ship.getDesignId());
            }
            return model;
        }

        BattleShipEntity current() {
            return ships.stream()
                    .filter(ship -> ship.getId().equals(battle.getCurrentShipId()))
                    .findFirst()
                    .orElse(null);
        }

        /**
         * Как зовут сторону в ЖИВОМ журнале боя: он собирается на запрос, поэтому имя
         * чудища берётся переведённым. В ХРАНИМЫЙ исход идёт ключ ({@link #nameKey}).
         */
        String name(UUID playerId) {
            PlayerEntity player = players.get(playerId);
            if (player == null) {
                return unknownOwner;
            }
            return player.getPlayerType() == PlayerType.MONSTER ? monsterSide : shownName.apply(player.getName());
        }

        /**
         * То же имя, но для ХРАНИМОГО исхода боя — п. 3.5.
         * <p>
         * Исход ложится в базу подстановками и читается потом на языке читателя, а
         * чудище зовётся переводимой подписью: поэтому подставляется её КЛЮЧ — словарь
         * узнает его при показе. У империи имя своё, хранимое, и ключом оно не бывает.
         */
        String nameKey(UUID playerId) {
            PlayerEntity player = players.get(playerId);
            if (player == null) {
                return unknownOwner;
            }
            return player.getPlayerType() == PlayerType.MONSTER ? MONSTER_SIDE_KEY : player.getName();
        }

        /** Сам игрок — нужен там, где спрашивают его проекты (например, платформы обороны). */
        PlayerEntity player(UUID playerId) {
            return players.get(playerId);
        }
    }

    /**
     * Чем корабль вооружён — п. 8: строки для нижней полосы сцены и окна осмотра.
     * <p>
     * Состав берётся у проекта, а не у поля боя: на поле корабль хранится числом
     * прочности и брони, а что на нём стоит — знает только его проект. Одинаковые стволы
     * с разными модификациями приходят разными строками, как в оригинале.
     */
    private List<BattleWeaponDto> weapons(Model model, BattleShipEntity ship) {
        if (model == null) {
            return List.of();
        }
        List<String> broken = ship.getDamagedSystems();
        return model.items().stream()
                .filter(item -> item.component().slot() == ShipComponentSlot.WEAPON)
                .map(item -> new BattleWeaponDto(
                        item.component().name(),
                        item.count(),
                        item.damage(),
                        item.component().weaponKind() == null
                                ? null : item.component().weaponKind().name(),
                        item.modifications().stream().map(WeaponModification::name).toList(),
                        // Сколько гнёзд этого ствола замолчало — п. 8: окно осмотра
                        // показывает не «пушка есть», а «пушка есть, да не стреляет».
                        (int) broken.stream()
                                .filter(one -> one.equals(item.component().code()))
                                .count(),
                        // Дальность строки — по её собственному стволу: у ракет это путь на
                        // топливе (пункт 30), у тяжёлой установки вдвое больше обычного (п. 8).
                        rules.reach(rules.shots(List.of(item),
                                rules.engineCombatSpeed(model.items())))))
                .toList();
    }

    /** Скорость корабля для сцены: разбитый двигатель держит его на месте — п. 8. */
    private Integer speedOf(BattleShipEntity ship, Model model, ShipStats stats) {
        return Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM)) || platform(model)
                ? 0
                : rules.tractoredSpeed(rules.cellsPerTurn(stats)
                        + rules.special(model.items(), ShipEffectType.TELEPORT), ship.getTractored(), model.hull());
    }

    /** Платформа обороны — сооружение, а не корабль: с места она не сходит (п. 8, п. 11). */
    private static boolean platform(Model model) {
        return model != null && Boolean.TRUE.equals(model.hull().platform());
    }


    /** Названия всего, что стоит в этом гнезде: особые модули идут списком. */
    private List<String> named(Model model, ShipComponentSlot slot) {
        if (model == null) {
            return List.of();
        }
        return model.items().stream()
                .filter(item -> item.component().slot() == slot)
                .map(item -> item.count() > 1
                        ? item.component().name() + " x" + item.count()
                        : item.component().name())
                .toList();
    }

    /** Название единственной системы этого гнезда; {@code null} — гнездо пусто. */
    private String single(Model model, ShipComponentSlot slot) {
        return named(model, slot).stream().findFirst().orElse(null);
    }

    /**
     * Ответ для сцены боя — п. 8.
     * <p>
     * Экран рисует корабли рисунками их корпусов, и размер берёт из корпуса: у фрегата он
     * маленький, у Leviathan — во всю клетку с запасом. Порядок хода отдаётся отдельным
     * списком: очередь в бою идёт по кораблям, а не по игрокам, и её видно целиком.
     */
    @Transactional(readOnly = true)
    public BattleDto toDto(BattleView view, PlayerEntity player, Map<UUID, PlayerEntity> players,
                           String systemName) {
        SpaceBattleEntity battle = view.battle();
        Context shown = context(battle, view.ships(), players);
        Map<UUID, Model> models = shown.models();

        List<BattleShipDto> ships = view.ships().stream()
                .map(ship -> {
                    Model model = models.get(ship.getDesignId());
                    ShipStats stats = model == null ? null : model.stats();
                    PlayerEntity owner = players.get(ship.getOwnerPlayerId());
                    return new BattleShipDto(
                            ship.getId(),
                            ship.getOwnerPlayerId(),
                            // Хозяин корабля: у чудища служебного имени строки игрок не
                            // видит — сторона подписывается переводимым ключом (п. 11.1).
                            owner == null
                                    ? messages.get("battle.ship.unknownOwner")
                                    : owner.getPlayerType() == PlayerType.MONSTER
                                            ? messages.get(MONSTER_SIDE_KEY)
                                            : messages.empire(owner.getName()),
                            ship.getSide(),
                            // Имя проекта хранится в базе и потому английское — но тело
                            // чудища зовётся по справочнику, а тот переводится (п. 11.1).
                            model == null
                                    ? messages.get("battle.ship.unknown")
                                    : Boolean.TRUE.equals(model.hull().monster())
                                            ? model.hull().name()
                                            : messages.known(model.design().getName()),
                            model == null ? "" : model.hull().name(),
                            model == null ? "" : model.hull().code(),
                            model == null ? 1 : model.hull().sortOrder(),
                            ship.getOrdinal(),
                            ship.getX(),
                            ship.getY(),
                            ship.getStructure(),
                            stats == null ? ship.getStructure() : stats.structure(),
                            ship.getArmour(),
                            stats == null ? ship.getArmour() : stats.armour(),
                            ship.getShield(),
                            stats == null ? 0 : stats.shield(),
                            stats == null ? 0 : stats.shieldClass(),
                            stats == null ? 0 : stats.attack(),
                            ship.getInitiative(),
                            stats == null ? 1 : speedOf(ship, model, stats),
                            Math.max(0, (stats == null ? 1 : speedOf(ship, model, stats))
                                    - ship.getMoved()),
                            model == null ? BattleRules.WEAPON_RANGE : rules.reach(shots(shown, ship)),
                            ship.getFired(),
                            ship.getDestroyed(),
                            ship.getOwnerPlayerId().equals(player.getId()),
                            model != null && Boolean.TRUE.equals(model.hull().platform()),
                            model != null && Boolean.TRUE.equals(model.hull().monster()),
                            stats == null ? 0 : stats.defense(),
                            stats == null ? 0 : stats.missileEvasion(),
                            weapons(model, ship),
                            named(model, ShipComponentSlot.SPECIAL),
                            ship.systemDamaged(BattleRules.ENGINE_SYSTEM),
                            ship.systemDamaged(BattleRules.SHIELD_SYSTEM),
                            ship.systemDamaged(BattleRules.COMPUTER_SYSTEM),
                            // У платформы двигателя нет — у старых проектов он ещё стоит в
                            // составе, но в бою не значит ничего (см. speed).
                            platform(model) ? null : single(model, ShipComponentSlot.ENGINE),
                            single(model, ShipComponentSlot.ARMOR),
                            single(model, ShipComponentSlot.SHIELD),
                            single(model, ShipComponentSlot.COMPUTER),
                            model == null ? 0 : rules.antiMissilePercent(parts(shown, ship)),
                            ship.getMarines(),
                            ship.getCaptured(),
                            model == null ? BattleRules.BOARDING_RANGE : rules.boardingRange(parts(shown, ship)),
                            model != null && !platform(model)
                                    && (Boolean.TRUE.equals(ship.systemDamaged(BattleRules.ENGINE_SYSTEM))
                                        || speedOf(ship, model, stats) == 0),
                            ship.getRetreating(),
                            ship.getRetreated(),
                            model != null && !platform(model) && !Boolean.TRUE.equals(model.hull().monster())
                                    && !Boolean.TRUE.equals(model.design().getAuto())
                                    && !Boolean.TRUE.equals(model.design().getTrophy())
                                    // Гражданские корабли (ячейка 0) и платформы (ниже нуля) собирает игра.
                                    && model.design().getSlot() != null && model.design().getSlot() > 0,
                            model != null && Boolean.TRUE.equals(frozen(shown, ship)),
                            model != null && Boolean.TRUE.equals(cloaked(shown, ship)));
                })
                .toList();

        BattleShipEntity current = view.ships().stream()
                .filter(ship -> ship.getId().equals(battle.getCurrentShipId()))
                .findFirst()
                .orElse(null);

        return new BattleDto(
                battle.getId(),
                battle.getStarSystemId(),
                systemName,
                battle.getTurn(),
                battle.getRound(),
                battle.getState().name(),
                messages.label(battle.getState()),
                BattleRules.FIELD_WIDTH,
                BattleRules.FIELD_HEIGHT,
                BattleRules.WEAPON_RANGE,
                battle.getAttackerPlayerId().equals(player.getId())
                        ? BattleSide.ATTACKER : BattleSide.DEFENDER,
                nameOf(players, battle.getAttackerPlayerId()),
                nameOf(players, battle.getDefenderPlayerId()),
                battle.getCurrentShipId(),
                current != null && current.getOwnerPlayerId().equals(player.getId()),
                power(view.ships(), models, BattleSide.ATTACKER),
                power(view.ships(), models, BattleSide.DEFENDER),
                ships,
                view.ships().stream().filter(BattleShipEntity::alive).map(BattleShipEntity::getId).toList(),
                view.events(),
                messages.text(battle.getOutcome()),
                battle.getMissiles().stream()
                        .map(missile -> new BattleMissileDto(missile.id(), missile.side(),
                                missile.shooterShipId(), missile.targetShipId(),
                                missile.x(), missile.y(), missile.shots().size(),
                                missile.speed(), missile.fuel(), missile.marines()))
                        .toList());
    }

    /**
     * Сила уцелевших кораблей стороны — та же, что у флотов ({@code ShipDesignRules.power}).
     * По ней на экране видно, кто сильнее и как перевес тает по ходу боя.
     */
    private Integer power(List<BattleShipEntity> ships, Map<UUID, Model> models, BattleSide side) {
        int total = 0;
        for (BattleShipEntity ship : ships) {
            Model model = models.get(ship.getDesignId());
            if (ship.getSide() == side && ship.alive() && model != null && model.stats() != null) {
                total += shipDesignRules.power(model.stats());
            }
        }
        return total;
    }

    /**
     * Как зовут сторону боя.
     * <p>
     * У империи — её имя, оно хранится в базе и языка не имеет. У <b>чудища</b> (п. 11.1)
     * хранимое имя служебное («Space Monsters»), и на экран оно не годится: сторона
     * подписывается переводимым ключом, как и всё, что собирается на запрос.
     */
    private String nameOf(Map<UUID, PlayerEntity> players, UUID playerId) {
        PlayerEntity player = players.get(playerId);
        if (player == null) {
            return messages.get("battle.ship.unknownOwner");
        }
        return player.getPlayerType() == PlayerType.MONSTER
                ? messages.get("battle.side.monster")
                : messages.empire(player.getName());
    }
}
