package com.sddnw.server.service;

import com.sddnw.server.domain.Building;
import com.sddnw.server.domain.enums.BuildingEffectType;
import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlanetIntelEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.enums.ScannerTech;
import com.sddnw.server.repository.FleetRepository;
import com.sddnw.server.repository.PlanetIntelRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Туман войны о чужих колониях — п. 15 (решение хозяина проекта, 03.10.2026, по оригиналу).
 * <p>
 * Чужая колония в разведанной системе видна такой, какой её застали в последний раз:
 * хозяин и население — «как при последнем посещении», а оборона (орбитальные платформы,
 * батареи, щиты, казармы) — пока в системе свои ГЛАЗА, дальше тоже памятью. Глаза — своя
 * колония или застава, свой стоящий флот, радиус сканера от них; союзник делится своими
 * (п. 15). Всевидящей расе глаза не нужны: она видит всё и всегда (п. 7).
 * <p>
 * Прежде разведка давала вечный живой обзор: однажды побывав в системе, игрок видел у
 * соседа каждый ход население, постройки, очередь стройки и по ней — изученное. Империи ИИ
 * видели то же самое и сверх того считали силу защитников по их настоящим технологиям.
 * Теперь человек и ИИ смотрят одними глазами — через эту службу.
 * <p>
 * Память пишет фаза {@link IntelPhase} в самом конце хода — когда бои, высадки и события
 * уже случились, — а читают её карта игрока ({@code GameService.getMap}) и решения ИИ
 * ({@code AiEmpireService.Galaxy}).
 */
@Service
public class IntelService {

    /** Разделитель кодов оборонных построек в хранимой строке. */
    private static final String SEPARATOR = ",";

    private final PlanetIntelRepository intelRepository;
    private final FleetRepository fleetRepository;
    private final PlayerTechnologyRepository technologyRepository;
    private final OrbitalDefenceRules defenceRules;
    private final DiplomacyService diplomacyService;
    private final RaceService raceService;
    private final com.sddnw.server.repository.MonsterIntelRepository monsterRepository;
    private final com.sddnw.server.repository.FleetShipRepository fleetShipRepository;
    private final ShipDesignService shipDesignService;
    private final TechSightService techSight;

    /**
     * Особенность галактики партии (пункт 94: «тёмная галактика» укорачивает сканеры). Полем, а не
     * параметром конструктора: службу собирают руками юнит-тесты, а у игрока на карте партия —
     * ленивая ссылка, читать признак с неё вне сессии нельзя (правила проекта, грабли).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sddnw.server.repository.GameRepository games;

    private String galaxyFeature(PlayerEntity player) {
        return games == null ? null : games.findById(player.getGame().getId())
                .map(com.sddnw.server.domain.entity.GameEntity::getGalaxyFeature).orElse(null);
    }

    public IntelService(PlanetIntelRepository intelRepository, FleetRepository fleetRepository,
                        PlayerTechnologyRepository technologyRepository, OrbitalDefenceRules defenceRules,
                        DiplomacyService diplomacyService, RaceService raceService,
                        com.sddnw.server.repository.MonsterIntelRepository monsterRepository,
                        com.sddnw.server.repository.FleetShipRepository fleetShipRepository,
                        ShipDesignService shipDesignService, TechSightService techSight) {
        this.monsterRepository = monsterRepository;
        this.fleetShipRepository = fleetShipRepository;
        this.shipDesignService = shipDesignService;
        this.techSight = techSight;
        this.intelRepository = intelRepository;
        this.fleetRepository = fleetRepository;
        this.technologyRepository = technologyRepository;
        this.defenceRules = defenceRules;
        this.diplomacyService = diplomacyService;
        this.raceService = raceService;
    }

    /**
     * Что игрок знает о чужой планете.
     *
     * @param ownerId    чья она (на ход наблюдения)
     * @param population сколько жителей
     * @param defences   коды оборонных построек
     * @param seenTurn   ход наблюдения; пусто — видна прямо сейчас
     */
    public record Known(UUID ownerId, Integer population, List<String> defences, Integer seenTurn) {

    }

    /**
     * Что игрок знает о стороже системы — п. 11.1, п. 15: те же правила, что у чужой колонии.
     *
     * @param seenTurn ход наблюдения; пусто — видно сейчас
     */
    public record SeenMonster(com.sddnw.server.domain.enums.SpaceMonster monster, Integer strength,
                              Integer seenTurn) {
    }

    /**
     * Сторож системы глазами игрока: под глазами — правда, без них — память; нет памяти —
     * никого не видели. Пусто — сторожа (насколько игрок знает) нет.
     */
    public SeenMonster monsterView(StarSystemEntity system, Set<UUID> eyes, Map<UUID, SeenMonster> memory) {
        if (eyes.contains(system.getId())) {
            return Boolean.TRUE.equals(system.hasLiveMonster())
                    ? new SeenMonster(system.getMonster(), system.getMonsterStrength(), null)
                    : null;
        }
        return memory.get(system.getId());
    }

    /**
     * Флот увидел сторожа — на подлёте, в бою с ним (п. 11.1): флот ИИ, проигравший чудищу,
     * гибнет и глаз в системе к концу хода не оставляет, но сторожа он видел.
     */
    @Transactional
    public void sawMonster(UUID gameId, UUID playerId, StarSystemEntity system, Integer turn) {
        com.sddnw.server.domain.entity.MonsterIntelEntity row = monsterRepository.findAllByPlayerId(playerId).stream()
                .filter(one -> one.getStarSystemId().equals(system.getId()))
                .findFirst()
                .orElseGet(com.sddnw.server.domain.entity.MonsterIntelEntity::new);
        fillMonster(row, gameId, playerId, system, turn);
        monsterRepository.save(row);
    }

    private static void fillMonster(com.sddnw.server.domain.entity.MonsterIntelEntity row, UUID gameId,
                                    UUID playerId, StarSystemEntity system, Integer turn) {
        row.setGameId(gameId);
        row.setPlayerId(playerId);
        row.setStarSystemId(system.getId());
        boolean live = Boolean.TRUE.equals(system.hasLiveMonster());
        row.setMonster(live ? system.getMonster() : null);
        row.setStrength(live ? system.getMonsterStrength() : 0);
        row.setSeenTurn(turn);
    }

    /** Память игрока о сторожах: система → что о сторожем знают. */
    @Transactional(readOnly = true)
    public Map<UUID, SeenMonster> monstersOf(UUID playerId) {
        Map<UUID, SeenMonster> memory = new HashMap<>();
        for (com.sddnw.server.domain.entity.MonsterIntelEntity row : monsterRepository.findAllByPlayerId(playerId)) {
            memory.put(row.getStarSystemId(), row.getMonster() == null ? null
                    : new SeenMonster(row.getMonster(), row.getStrength(), row.getSeenTurn()));
        }
        return memory;
    }

    /** Память партии о сторожах: игрок → система → что знает. */
    public Map<UUID, Map<UUID, SeenMonster>> monstersOfGame(UUID gameId) {
        Map<UUID, Map<UUID, SeenMonster>> memory = new HashMap<>();
        for (com.sddnw.server.domain.entity.MonsterIntelEntity row : monsterRepository.findAllByGameId(gameId)) {
            memory.computeIfAbsent(row.getPlayerId(), key -> new HashMap<>()).put(row.getStarSystemId(),
                    row.getMonster() == null ? null
                            : new SeenMonster(row.getMonster(), row.getStrength(), row.getSeenTurn()));
        }
        return memory;
    }

    /**
     * Оборонные постройки — то, что чужой глаз видит у колонии: платформы орбиты, батареи и
     * щиты (они выходят в бой) и казармы (наземная оборона, `GROUND_DEFENCE_PERCENT`).
     * Казармы видны так же, как батареи: это постройки на поверхности, а их сила решает,
     * хватит ли десанта. Варп-перехватчик виден тоже: его поле держит чужие флоты, и срок
     * перелёта к такой системе игрок обязан знать заранее (п. 8).
     */
    public List<String> defences(Collection<Building> built) {
        return built.stream()
                .filter(building -> Boolean.TRUE.equals(defenceRules.isDefence(building.code()))
                        || building.amount(BuildingEffectType.GROUND_DEFENCE_PERCENT) > 0
                        || building.amount(BuildingEffectType.WARP_INTERDICTOR) > 0)
                .map(Building::code)
                .sorted()
                .toList();
    }

    /**
     * Не пускает ли видимая оборона десант — п. 12: любая уцелевшая платформа (орбита,
     * батарея, щит) закрывает высадку. Казармы высадке не мешают — они только сильнее.
     */
    public Boolean blocksLanding(Known known) {
        return known.defences().stream().anyMatch(code -> Boolean.TRUE.equals(defenceRules.isDefence(code)));
    }

    /** Союзники игроков одной выборкой — для фазы ИИ. */
    public Map<UUID, Set<UUID>> alliesOfAll(Collection<UUID> playerIds) {
        return diplomacyService.alliesOfAll(playerIds);
    }

    /**
     * Где у каждого игрока глаза — системы, о которых он знает правду прямо сейчас.
     *
     * @param ranges дальность сканеров по игрокам, парсеки; 0 — сканеров нет
     */
    public Map<UUID, Set<UUID>> eyesByAll(List<StarSystemEntity> systems, Collection<PlayerEntity> players,
                                          List<FleetEntity> fleets, Map<UUID, Integer> ranges) {
        List<UUID> ids = players.stream().map(PlayerEntity::getId).toList();
        Map<UUID, Set<UUID>> allies = diplomacyService.alliesOfAll(ids);
        Map<UUID, com.sddnw.server.domain.RaceEffects> races = raceService.effectsByPlayer(ids);
        Set<UUID> all = systems.stream().map(StarSystemEntity::getId).collect(Collectors.toUnmodifiableSet());

        Map<UUID, Set<UUID>> eyes = new HashMap<>();
        for (PlayerEntity player : players) {
            com.sddnw.server.domain.RaceEffects race = races.get(player.getId());
            if (race != null && Boolean.TRUE.equals(race.omniscient())) {
                eyes.put(player.getId(), all);
                continue;
            }
            Set<UUID> watchers = new HashSet<>(allies.getOrDefault(player.getId(), Set.of()));
            watchers.add(player.getId());
            Set<UUID> fleetPosts = fleets.stream()
                    .filter(fleet -> watchers.contains(fleet.getOwnerPlayerId()))
                    .filter(fleet -> fleet.getShips() > 0 && !Boolean.TRUE.equals(fleet.isInFlight()))
                    .map(FleetEntity::getStarSystemId)
                    .collect(Collectors.toSet());
            List<StarSystemEntity> posts = systems.stream()
                    .filter(system -> system.getId().equals(player.getHomeSystemId())
                            || fleetPosts.contains(system.getId())
                            || system.getPlanets().stream().anyMatch(planet ->
                                    planet.getOwnerPlayerId() != null && watchers.contains(planet.getOwnerPlayerId())))
                    .toList();
            Set<UUID> seen = posts.stream().map(StarSystemEntity::getId).collect(Collectors.toCollection(HashSet::new));
            int range = ranges.getOrDefault(player.getId(), 0);
            if (range > 0) {
                long squared = (long) range * range;
                for (StarSystemEntity system : systems) {
                    if (posts.stream().anyMatch(post -> distanceSquared(post, system) <= squared)) {
                        seen.add(system.getId());
                    }
                }
            }
            eyes.put(player.getId(), Set.copyOf(seen));
        }
        return eyes;
    }

    /**
     * Через что игрок смотрит на чужие планеты: кто он, его союзники, где у него глаза и что
     * он помнит. Карта и ответы действий собирают каждую планету через неё.
     */
    public record Lens(UUID viewerId, Set<UUID> allies, Set<UUID> eyes, Map<UUID, Known> memory,
                       Map<UUID, SeenMonster> monsters) {
    }

    /** Линза игрока для ответа на его запрос — вне хода, своими выборками. */
    @Transactional(readOnly = true)
    public Lens lensOf(PlayerEntity player, List<StarSystemEntity> systems) {
        return new Lens(player.getId(), diplomacyService.allies(player.getId()), eyesOf(systems, player),
                memoryOf(player), monstersOf(player.getId()));
    }

    /**
     * Линза ответа на действие игрока в системе, где у него ЗАВЕДОМО глаза: его флот там
     * садится, высаживается или основывает колонию. Считать глаза по всей галактике ради
     * одной системы незачем — она и так видна.
     */
    public Lens presentLens(PlayerEntity player, UUID systemId) {
        return new Lens(player.getId(), diplomacyService.allies(player.getId()), Set.of(systemId), Map.of(),
                Map.of());
    }

    /** Что игрок видит о планете через свою линзу. */
    public Known view(Lens lens, PlanetEntity planet, UUID systemId,
                      Function<PlanetEntity, Collection<Building>> built) {
        return view(lens.viewerId(), lens.allies(), planet, systemId, lens.eyes(), lens.memory(), built);
    }

    /** Стоит ли маскировка ({@code STEALTH}) на каждом корабле флота — п. 8. */
    private static boolean cloakedFleet(List<com.sddnw.server.domain.entity.FleetShipEntity> rows,
                                        Map<UUID, List<ShipDesignRules.Item>> items) {
        List<com.sddnw.server.domain.entity.FleetShipEntity> present = rows.stream()
                .filter(row -> row.getShips() > 0).toList();
        return !present.isEmpty() && present.stream().allMatch(row -> items.getOrDefault(row.getDesignId(), List.of())
                .stream().anyMatch(item -> item.component().amount(
                        com.sddnw.server.domain.enums.ShipEffectType.STEALTH) > 0));
    }

    /**
     * Видит ли смотрящий чужой флот — п. 8, п. 15: одно правило для карты игрока и для ИИ.
     * <p>
     * Стоящий флот виден под глазами (своя колония, застава, флот, радиус сканера); летящий —
     * когда его текущая точка в радиусе сканеров от своих опор, и тогда видны и цель, и срок
     * прибытия (правило оригинала). Скрытные корабли (п. 7) прячутся от сканеров: их видит
     * лишь тот, кто стоит в той же системе сам, и всевидящая раса.
     *
     * @param posts    системы своих опор: колонии, заставы, родная, стоящие флоты (и союзников)
     * @param range    дальность сканеров, парсеки; 0 — сканеров нет
     * @param stealthy скрытны ли корабли хозяина флота
     */
    public Boolean seesFleet(FleetEntity fleet, Set<UUID> eyes, List<StarSystemEntity> posts, int range,
                             Boolean stealthy, Map<UUID, StarSystemEntity> systems, Integer turn) {
        boolean hidden = Boolean.TRUE.equals(stealthy);
        if (!Boolean.TRUE.equals(fleet.isInFlight())) {
            UUID at = fleet.getStarSystemId();
            boolean watching = posts.stream().anyMatch(post -> post.getId().equals(at));
            return eyes.contains(at) && (!hidden || watching);
        }
        if (hidden || range <= 0 || fleet.getOriginSystemId() == null) {
            return Boolean.FALSE;
        }
        StarSystemEntity from = systems.get(fleet.getOriginSystemId());
        StarSystemEntity to = systems.get(fleet.getTargetSystemId());
        if (from == null || to == null) {
            return Boolean.FALSE;
        }
        int total = Math.max(1, fleet.getArrivalTurn() - fleet.getDepartureTurn());
        double passed = Math.max(0, Math.min(1, (double) (turn - fleet.getDepartureTurn()) / total));
        double x = from.getXParsec() + (to.getXParsec() - from.getXParsec()) * passed;
        double y = from.getYParsec() + (to.getYParsec() - from.getYParsec()) * passed;
        long squared = (long) range * range;
        return posts.stream().anyMatch(post -> {
            double dx = post.getXParsec() - x;
            double dy = post.getYParsec() - y;
            return dx * dx + dy * dy <= squared;
        });
    }

    /** Опоры игрока (и союзников): системы с их планетами, родная, стоящие флоты. */
    public List<StarSystemEntity> posts(List<StarSystemEntity> systems, PlayerEntity player, Set<UUID> allies,
                                        List<FleetEntity> fleets) {
        Set<UUID> watchers = new HashSet<>(allies);
        watchers.add(player.getId());
        Set<UUID> fleetPosts = fleets.stream()
                .filter(fleet -> watchers.contains(fleet.getOwnerPlayerId()))
                .filter(fleet -> fleet.getShips() > 0 && !Boolean.TRUE.equals(fleet.isInFlight()))
                .map(FleetEntity::getStarSystemId)
                .collect(Collectors.toSet());
        return systems.stream()
                .filter(system -> system.getId().equals(player.getHomeSystemId())
                        || fleetPosts.contains(system.getId())
                        || system.getPlanets().stream().anyMatch(planet ->
                                planet.getOwnerPlayerId() != null && watchers.contains(planet.getOwnerPlayerId())))
                .toList();
    }

    /**
     * Чужие флоты на карте игрока — п. 8, п. 15. Администратору с показом всей галактики —
     * все: это взгляд отладки.
     */
    @Transactional(readOnly = true)
    public List<com.sddnw.server.dto.ForeignFleetDto> foreignFleets(PlayerEntity player, List<StarSystemEntity> systems,
                                                                   Integer turn, Boolean revealAll) {
        List<FleetEntity> fleets = fleetRepository.findAllByGameId(player.getGame().getId());
        Set<UUID> allies = diplomacyService.allies(player.getId());
        List<FleetEntity> foreign = fleets.stream()
                .filter(fleet -> fleet.getShips() > 0)
                .filter(fleet -> !fleet.getOwnerPlayerId().equals(player.getId()))
                .toList();
        if (foreign.isEmpty()) {
            return List.of();
        }
        com.sddnw.server.domain.RaceEffects race = raceService.effects(player);
        boolean omniscient = Boolean.TRUE.equals(race.omniscient()) || Boolean.TRUE.equals(revealAll);
        Set<UUID> eyes = omniscient ? Set.of() : eyesOf(systems, player);
        List<StarSystemEntity> posts = omniscient ? List.of() : posts(systems, player, allies, fleets);
        Set<String> technologies = technologyRepository
                .findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId()).stream()
                .map(com.sddnw.server.domain.entity.PlayerTechnologyEntity::getOptionCode)
                .collect(Collectors.toSet());
        int range = ScannerTech.bestRange(technologies, galaxyFeature(player));
        Map<UUID, com.sddnw.server.domain.RaceEffects> owners = raceService.effectsByPlayer(
                foreign.stream().map(FleetEntity::getOwnerPlayerId).collect(Collectors.toSet()));
        Map<UUID, StarSystemEntity> byId = systems.stream()
                .collect(Collectors.toMap(StarSystemEntity::getId, system -> system));
        Map<UUID, List<com.sddnw.server.domain.entity.FleetShipEntity>> composition = fleetShipRepository
                .findAllByFleetIdIn(foreign.stream().map(FleetEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(com.sddnw.server.domain.entity.FleetShipEntity::getFleetId));
        Map<UUID, com.sddnw.server.domain.entity.ShipDesignEntity> designs =
                shipDesignService.designsOfGame(player.getGame().getId());
        Map<UUID, List<ShipDesignRules.Item>> items = shipDesignService.itemsOf(designs.values());

        List<com.sddnw.server.dto.ForeignFleetDto> seen = new ArrayList<>();
        for (FleetEntity fleet : foreign) {
            com.sddnw.server.domain.RaceEffects theirs = owners.get(fleet.getOwnerPlayerId());
            boolean visible = omniscient || allies.contains(fleet.getOwnerPlayerId())
                    || Boolean.TRUE.equals(seesFleet(fleet, eyes, posts, range,
                            theirs != null && Boolean.TRUE.equals(theirs.stealthyShips()), byId, turn));
            // Маскировка на каждом корабле прячет флот целиком (п. 8, 13.10.2026, перенос MOO II):
            // «completely invisible on the Galaxy map... except to Omniscient empires». Союзник видит.
            if (visible && !omniscient && !allies.contains(fleet.getOwnerPlayerId())
                    && cloakedFleet(composition.getOrDefault(fleet.getId(), List.of()), items)) {
                visible = false;
            }
            if (!visible) {
                continue;
            }
            boolean armed = composition.getOrDefault(fleet.getId(), List.of()).stream()
                    .filter(row -> row.getShips() > 0)
                    .anyMatch(row -> items.getOrDefault(row.getDesignId(), List.of()).stream()
                            .anyMatch(item -> item.component().slot()
                                    == com.sddnw.server.domain.enums.ShipComponentSlot.WEAPON));
            boolean flying = Boolean.TRUE.equals(fleet.isInFlight());
            seen.add(new com.sddnw.server.dto.ForeignFleetDto(
                    fleet.getId(),
                    fleet.getOwnerPlayerId(),
                    fleet.getShips(),
                    armed,
                    flying ? fleet.getOriginSystemId() : fleet.getStarSystemId(),
                    flying ? fleet.getTargetSystemId() : null,
                    flying ? fleet.getDepartureTurn() : null,
                    flying ? fleet.getArrivalTurn() : null));
        }
        return seen;
    }

    /** Глаза одного игрока — для карты, вне хода. */
    @Transactional(readOnly = true)
    public Set<UUID> eyesOf(List<StarSystemEntity> systems, PlayerEntity player) {
        Set<String> technologies = technologyRepository
                .findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId()).stream()
                .map(com.sddnw.server.domain.entity.PlayerTechnologyEntity::getOptionCode)
                .collect(Collectors.toSet());
        return eyesByAll(systems, List.of(player), fleetRepository.findAllByGameId(player.getGame().getId()),
                Map.of(player.getId(), ScannerTech.bestRange(technologies, galaxyFeature(player))))
                .getOrDefault(player.getId(), Set.of());
    }

    /**
     * Память игрока о планетах: планета → то, что о ней помнят. Чего в памяти нет, игрок
     * о колонии не знает.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Known> memoryOf(PlayerEntity player) {
        return intelRepository.findAllByPlayerId(player.getId()).stream()
                .collect(Collectors.toMap(PlanetIntelEntity::getPlanetId, IntelService::known));
    }

    /** Память всех игроков партии: игрок → планета → то, что он помнит. */
    public Map<UUID, Map<UUID, Known>> memoryOfGame(UUID gameId) {
        Map<UUID, Map<UUID, Known>> memory = new HashMap<>();
        for (PlanetIntelEntity row : intelRepository.findAllByGameIdOrderByPlayerIdAscPlanetIdAsc(gameId)) {
            memory.computeIfAbsent(row.getPlayerId(), key -> new HashMap<>()).put(row.getPlanetId(), known(row));
        }
        return memory;
    }

    /**
     * Что игрок видит о планете — одно правило для карты и для ИИ.
     * <p>
     * Своя (и союзника) планета и планета под глазами — правда сейчас; иначе — память;
     * нет памяти — пусто: на его карте планета свободна.
     *
     * @param built постройки планеты (правда), чтобы назвать оборону под глазами
     */
    public Known view(UUID viewerId, Set<UUID> allies, PlanetEntity planet, UUID systemId, Set<UUID> eyes,
                      Map<UUID, Known> memory, Function<PlanetEntity, Collection<Building>> built) {
        UUID owner = planet.getOwnerPlayerId();
        boolean ownSide = owner != null && (owner.equals(viewerId) || allies.contains(owner));
        if (ownSide || eyes.contains(systemId)) {
            return owner == null ? null : new Known(owner, planet.getPopulation(), defences(built.apply(planet)), null);
        }
        return memory.get(planet.getId());
    }

    /**
     * Переписывает память всех игроков партии — в конце хода ({@link IntelPhase}).
     * <p>
     * Под глазами каждая планета системы записывается заново: чужая колония — строкой,
     * ставшая свободной (вымерла, разбомблена) — удалением строки. Вся память партии —
     * одна выборка, а записи — только планеты под чьими-то глазами.
     */
    @Transactional
    public void refresh(TurnContext context) {
        List<PlayerEntity> empires = context.players().stream()
                .filter(player -> player.getPlayerType() != PlayerType.MONSTER)
                .toList();
        if (empires.isEmpty()) {
            return;
        }
        UUID gameId = context.game().getId();
        List<FleetEntity> fleets = fleetRepository.findAllByGameId(gameId);
        Map<UUID, Integer> ranges = new HashMap<>();
        Map<UUID, Set<String>> technologies = context.colonyContext().technologiesByOwner();
        for (PlayerEntity player : empires) {
            ranges.put(player.getId(), ScannerTech.bestRange(technologies.getOrDefault(player.getId(), Set.of()),
                    context.game().getGalaxyFeature()));
        }
        Map<UUID, Set<UUID>> eyes = eyesByAll(context.systems(), empires, fleets, ranges);

        Map<String, PlanetIntelEntity> rows = new HashMap<>();
        for (PlanetIntelEntity row : intelRepository.findAllByGameIdOrderByPlayerIdAscPlanetIdAsc(gameId)) {
            rows.put(row.getPlayerId() + "/" + row.getPlanetId(), row);
        }
        List<PlanetIntelEntity> changed = new ArrayList<>();
        List<PlanetIntelEntity> gone = new ArrayList<>();
        for (PlayerEntity player : empires) {
            Set<UUID> watched = eyes.getOrDefault(player.getId(), Set.of());
            for (StarSystemEntity system : context.systems()) {
                if (!watched.contains(system.getId())) {
                    continue;
                }
                for (PlanetEntity planet : system.getPlanets()) {
                    String key = player.getId() + "/" + planet.getId();
                    PlanetIntelEntity row = rows.get(key);
                    UUID owner = planet.getOwnerPlayerId();
                    if (owner == null || owner.equals(player.getId())) {
                        if (row != null) {
                            gone.add(row);
                        }
                        continue;
                    }
                    String defences = String.join(SEPARATOR, defences(context.colonyContext().buildings(planet)));
                    if (row == null) {
                        row = new PlanetIntelEntity();
                        row.setGameId(gameId);
                        row.setPlayerId(player.getId());
                        row.setPlanetId(planet.getId());
                    }
                    // Ход наблюдения переписывается и без перемен: «сведения на ход N»
                    // обязаны называть последний взгляд, а не последнюю перемену.
                    row.setOwnerPlayerId(owner);
                    row.setPopulation(planet.getPopulation());
                    row.setDefences(defences);
                    row.setSeenTurn(context.turn());
                    changed.add(row);
                }
            }
        }
        if (!gone.isEmpty()) {
            intelRepository.deleteAll(gone);
        }
        if (!changed.isEmpty()) {
            intelRepository.saveAll(changed);
        }
        refreshMonsters(context, empires, eyes);
        scanForeign(context, empires, fleets, technologies);
    }

    /** Сторожа систем под глазами — правдой в память, как и колонии. */
    private void refreshMonsters(TurnContext context, List<PlayerEntity> empires, Map<UUID, Set<UUID>> eyes) {
        UUID gameId = context.game().getId();
        Map<String, com.sddnw.server.domain.entity.MonsterIntelEntity> rows = new HashMap<>();
        for (com.sddnw.server.domain.entity.MonsterIntelEntity row : monsterRepository.findAllByGameId(gameId)) {
            rows.put(row.getPlayerId() + "/" + row.getStarSystemId(), row);
        }
        List<com.sddnw.server.domain.entity.MonsterIntelEntity> changed = new ArrayList<>();
        for (PlayerEntity player : empires) {
            Set<UUID> watched = eyes.getOrDefault(player.getId(), Set.of());
            for (StarSystemEntity system : context.systems()) {
                if (!watched.contains(system.getId())) {
                    continue;
                }
                com.sddnw.server.domain.entity.MonsterIntelEntity row = rows.get(player.getId() + "/" + system.getId());
                // Пустую систему, о которой и прежде ничего не помнили, не пишем: строка
                // нужна там, где сторож был или есть, — иначе память росла бы на всю карту.
                if (row == null && !Boolean.TRUE.equals(system.hasLiveMonster())) {
                    continue;
                }
                if (row == null) {
                    row = new com.sddnw.server.domain.entity.MonsterIntelEntity();
                }
                fillMonster(row, gameId, player.getId(), system, context.turn());
                changed.add(row);
            }
        }
        if (!changed.isEmpty()) {
            monsterRepository.saveAll(changed);
        }
    }

    /**
     * Сканеры флота — п. 15 ({@link TechSightService}): свой стоящий флот в одной системе с
     * чужим флотом или колонией разглядывает технологии их кораблей и построек до уровня
     * дерева, который позволяют сканеры. Бой открывает всё и пишется в бою, а здесь — то, что
     * видно и без боя.
     */
    private void scanForeign(TurnContext context, List<PlayerEntity> empires, List<FleetEntity> fleets,
                             Map<UUID, Set<String>> technologies) {
        List<FleetEntity> standing = fleets.stream()
                .filter(fleet -> fleet.getShips() > 0 && !Boolean.TRUE.equals(fleet.isInFlight()))
                .toList();
        if (standing.isEmpty()) {
            return;
        }
        Map<UUID, List<com.sddnw.server.domain.entity.FleetShipEntity>> composition = fleetShipRepository
                .findAllByFleetIdIn(standing.stream().map(FleetEntity::getId).toList()).stream()
                .filter(row -> row.getShips() > 0)
                .collect(Collectors.groupingBy(com.sddnw.server.domain.entity.FleetShipEntity::getFleetId));
        Map<UUID, com.sddnw.server.domain.entity.ShipDesignEntity> designs =
                shipDesignService.designsOfGame(context.game().getId());
        Map<UUID, List<ShipDesignRules.Item>> items = shipDesignService.itemsOf(designs.values());
        Set<UUID> empireIds = empires.stream().map(PlayerEntity::getId).collect(Collectors.toSet());
        Map<UUID, StarSystemEntity> systems = context.systems().stream()
                .collect(Collectors.toMap(StarSystemEntity::getId, system -> system));

        for (FleetEntity mine : standing) {
            UUID viewer = mine.getOwnerPlayerId();
            if (!empireIds.contains(viewer)) {
                continue;
            }
            List<List<ShipDesignRules.Item>> ownItems = composition.getOrDefault(mine.getId(), List.of()).stream()
                    .map(row -> items.getOrDefault(row.getDesignId(), List.of()))
                    .toList();
            int tier = techSight.scannerTier(technologies.getOrDefault(viewer, Set.of()),
                    techSight.carriesBattleScanner(ownItems));
            if (techSight.levelLimit(tier) <= 0) {
                continue;
            }
            Map<UUID, Set<String>> seen = new HashMap<>();
            for (FleetEntity other : standing) {
                if (other.getOwnerPlayerId().equals(viewer) || !empireIds.contains(other.getOwnerPlayerId())
                        || !other.getStarSystemId().equals(mine.getStarSystemId())) {
                    continue;
                }
                List<com.sddnw.server.domain.entity.ShipDesignEntity> theirs = composition
                        .getOrDefault(other.getId(), List.of()).stream()
                        .map(row -> designs.get(row.getDesignId()))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                seen.computeIfAbsent(other.getOwnerPlayerId(), key -> new java.util.TreeSet<>())
                        .addAll(techSight.designTechs(theirs));
            }
            StarSystemEntity system = systems.get(mine.getStarSystemId());
            if (system != null) {
                for (PlanetEntity planet : system.getPlanets()) {
                    UUID owner = planet.getOwnerPlayerId();
                    if (owner != null && !owner.equals(viewer) && empireIds.contains(owner)) {
                        seen.computeIfAbsent(owner, key -> new java.util.TreeSet<>())
                                .addAll(techSight.buildingTechs(context.colonyContext().buildings(planet)));
                    }
                }
            }
            seen.forEach((owner, codes) -> techSight.observe(context.game().getId(), viewer, owner,
                    techSight.withinScanners(codes, tier), context.turn()));
        }
    }

    private static Known known(PlanetIntelEntity row) {
        List<String> defences = row.getDefences() == null || row.getDefences().isBlank()
                ? List.of()
                : Arrays.stream(row.getDefences().split(SEPARATOR)).toList();
        return new Known(row.getOwnerPlayerId(), row.getPopulation(), defences, row.getSeenTurn());
    }

    private static double distanceSquared(StarSystemEntity first, StarSystemEntity second) {
        double dx = first.getXParsec() - second.getXParsec();
        double dy = first.getYParsec() - second.getYParsec();
        return dx * dx + dy * dy;
    }
}
