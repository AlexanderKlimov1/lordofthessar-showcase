package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.enums.PlanetGravity;
import com.sddnw.server.dto.GalaxyMapDto;
import com.sddnw.server.dto.GameDetailsDto;
import com.sddnw.server.dto.GameSummaryDto;
import com.sddnw.server.dto.PlanetDto;
import com.sddnw.server.dto.PlayerDto;
import com.sddnw.server.dto.StarSystemDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Преобразование сущностей игры в DTO REST API. */
@Component
public class GameMapper {

    private final ColonyService colonyService;
    private final GovernmentService governmentService;
    private final PopulationTransferService transferService;
    private final Messages messages;

    /** Кто из людей ушёл из-за стола — backlog-promo, пункт 11. */
    private final PresenceService presence;
    private final IntelService intelService;

    /**
     * Лидеры — только чтобы сказать, приходило ли империи хоть одно предложение службы (пункт 27:
     * раздел «Лидеры» появляется по мере надобности). Полем: конструктор маппера и так длинен.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sddnw.server.repository.PlayerLeaderRepository leaders;

    public GameMapper(ColonyService colonyService,
                      GovernmentService governmentService,
                      PopulationTransferService transferService,
                      Messages messages,
                      PresenceService presence,
                      IntelService intelService) {
        this.intelService = intelService;
        this.colonyService = colonyService;
        this.governmentService = governmentService;
        this.transferService = transferService;
        this.messages = messages;
        this.presence = presence;
    }

    public GameSummaryDto toSummary(GameEntity game, List<PlayerEntity> players) {
        long humans = players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .count();
        return new GameSummaryDto(
                game.getId(),
                game.getName(),
                game.getStatus().name(),
                game.getGalaxySize().name(),
                game.getWidthParsecs(),
                game.getHeightParsecs(),
                game.getStarCount(),
                (int) humans,
                game.getMaxHumanPlayers(),
                game.getTotalPlayers(),
                game.getTurn(),
                game.getHostPlayerId(),
                game.getGalacticEvents(),
                game.getWinnerPlayerId(),
                game.getVictoryKind() == null ? null : game.getVictoryKind().name(),
                game.getCreatedAt(),
                game.getWardenholdVictory(),
                WardenholdRules.turnsLeft(game) == null ? null : game.getWardenholdHolderPlayerId(),
                WardenholdRules.turnsLeft(game),
                game.getCouncil(),
                game.getMightVictory(),
                MightVictoryRules.limit(game),
                game.getTurnSeconds(),
                TurnClockRules.deadline(game.getTurnStartedAt(), game.getTurnSeconds()),
                game.getRichStart(),
                game.getAdminWatched(),
                null,
                null,
                game.getStatus() == com.sddnw.server.domain.enums.GameStatus.LOBBY ? game.getAutoStartAt() : null,
                game.getSpectators(),
                game.getGalaxyFeature(),
                game.getWeeklyWeek(),
                game.winners().size() > 1 ? game.winners().subList(1, game.winners().size()) : null,
                game.getPlayedOn(),
                game.getAiSkill(),
                game.getPeacefulAi());
    }

    public GameDetailsDto toDetails(GameEntity game, List<PlayerEntity> players, Map<String, String> raceNames) {
        // Занятые рейсами грузовики считаются на весь состав разом: иначе на каждого
        // игрока уходил бы свой запрос — п. 4.1.1.
        Map<UUID, Integer> reserved = transferService.reservedByPlayers(
                players.stream().map(PlayerEntity::getId).toList());
        // Срок до передачи империи ИИ (пункт 127) — только у идущей партии: в лобби часов нет.
        Map<UUID, Integer> takeover = game.getStatus() == com.sddnw.server.domain.enums.GameStatus.IN_PROGRESS
                ? presence.takeoverSeconds(game, players, java.time.OffsetDateTime.now())
                : Map.of();
        // Был ли у империи хоть один лидер — предложенный или нанятый (пункт 27): одной выборкой на состав.
        java.util.Set<UUID> withLeaders = leaders == null ? java.util.Set.of()
                : leaders.findAllByPlayerIdIn(players.stream().map(PlayerEntity::getId).toList()).stream()
                        .map(com.sddnw.server.domain.entity.PlayerLeaderEntity::getPlayerId)
                        .collect(java.util.stream.Collectors.toSet());
        // Знак раннего доступа (пункт 147) — одной выборкой на состав.
        Set<UUID> early = earlyBirds == null ? Set.of()
                : earlyBirds.among(players.stream().map(PlayerEntity::getAccountId).toList());
        // Знак спутника (пункты 50, 174) — тоже одной выборкой на состав.
        Map<UUID, Integer> companionsBy = companions == null ? Map.of()
                : companions.count(players.stream().map(PlayerEntity::getAccountId).toList());
        return new GameDetailsDto(
                toSummary(game, players),
                players.stream().map(player -> toPlayer(player, raceNames, reserved, takeover,
                        withLeaders.contains(player.getId()),
                        player.getAccountId() != null && early.contains(player.getAccountId()),
                        player.getAccountId() == null ? 0 : companionsBy.getOrDefault(player.getAccountId(), 0))).toList());
    }

    public PlayerDto toPlayer(PlayerEntity player, Map<String, String> raceNames) {
        return toPlayer(player, raceNames, transferService.reservedByPlayers(List.of(player.getId())), Map.of(),
                leaders != null && !leaders.findAllByPlayerId(player.getId()).isEmpty(),
                player.getAccountId() != null && earlyBirds != null
                        && !earlyBirds.among(List.of(player.getAccountId())).isEmpty(),
                player.getAccountId() == null || companions == null ? 0
                        : companions.count(List.of(player.getAccountId())).getOrDefault(player.getAccountId(), 0));
    }

    /** Знак спутника (пункты 50, 174); необязателен. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CompanionService companions;

    /** Знак «с нами с раннего доступа» (пункт 147); необязателен — тестам без него не нужен. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EarlyBirdService earlyBirds;

    private PlayerDto toPlayer(PlayerEntity player, Map<String, String> raceNames,
                               Map<UUID, Integer> reservedFreighters, Map<UUID, Integer> takeover,
                               Boolean leadersSeen, Boolean earlyBird, Integer companionMarks) {
        return new PlayerDto(
                player.getId(),
                player.getSlot(),
                messages.empire(player.getName()),
                player.getPlayerType().name(),
                player.getRaceCode(),
                // Своя раса называется так, как назвал её игрок; справочная — как в каталоге.
                player.getRaceName() == null ? raceNames.get(player.getRaceCode()) : player.getRaceName(),
                player.getColor(),
                player.getHomeSystemId(),
                player.getCredits(),
                governmentService.government(player),
                player.getEspionagePoints(),
                player.getFreighters(),
                reservedFreighters.getOrDefault(player.getId(), 0),
                turnEnded(player),
                player.getPlayerType() == PlayerType.HUMAN && Boolean.TRUE.equals(presence.away(player)),
                player.getLastActionAt(),
                takeover.get(player.getId()),
                Boolean.TRUE.equals(PresenceService.onVacation(player)) ? player.getVacationUntil() : null,
                player.getChapter(),
                player.getMentorPlayerId(),
                leadersSeen,
                earlyBird,
                companionMarks,
                player.getMutationPicks());
    }

    /**
     * Закончил ли игрок текущий ход — п. 11.1. ИИ считается закончившим всегда: своих
     * решений он не принимает и ждать себя не заставляет.
     */
    private Boolean turnEnded(PlayerEntity player) {
        if (player.getPlayerType() != PlayerType.HUMAN) {
            return Boolean.TRUE;
        }
        return player.getEndedTurn() >= player.getGame().getTurn();
    }

    /**
     * Карта галактики: звёзды отдаются все, подробности — только по разведанным системам.
     *
     * @param exploredIds системы, о которых игрок знает больше, чем положение звезды
     * @param scannedIds  системы в дальности сканеров — п. 15
     * @param occupiedIds системы, где сканер заметил чужое присутствие — п. 15
     */
    public GalaxyMapDto toMap(GameEntity game,
                              List<StarSystemEntity> systems,
                              Set<UUID> exploredIds,
                              Set<UUID> scannedIds,
                              Set<UUID> occupiedIds,
                              Map<UUID, List<UUID>> knownOwners,
                              Double minStarDistanceParsecs,
                              Boolean omniscient,
                              IntelService.Lens lens,
                              List<com.sddnw.server.dto.ForeignFleetDto> foreignFleets) {
        // Колониям нужны изученное владельцем — п. 9 — и построенные здания — п. 10;
        // всё это берётся на планеты карты разом, иначе на каждую колонию уходили бы
        // свои запросы.
        ColonyService.ColonyContext context = colonyService.context(
                systems.stream().flatMap(system -> system.getPlanets().stream()).toList());

        return new GalaxyMapDto(
                game.getId(),
                game.getGalaxySize().name(),
                game.getWidthParsecs(),
                game.getHeightParsecs(),
                minStarDistanceParsecs,
                systems.stream()
                        .map(system -> exploredIds.contains(system.getId())
                                ? toSystem(system, context, lens)
                                : toUnexploredSystem(system,
                                        scannedIds.contains(system.getId()),
                                        occupiedIds.contains(system.getId()),
                                        omniscient,
                                        lens == null ? null : lens.monsters().get(system.getId()),
                                        knownOwners.get(system.getId())))
                        .toList(),
                foreignFleets,
                // Число — из правила клада, а не из словаря (пункт 135): правило правится в одном месте.
                systems.stream().anyMatch(system -> Boolean.TRUE.equals(system.getSpecial())
                        && !Boolean.TRUE.equals(system.getSpecialClaimed()))
                        ? SpecialStarReward.TECHNOLOGIES : null,
                null);
    }

    /**
     * Неразведанная система: на карте это безымянная звезда.
     * <p>
     * Название, планеты и владельца не отдаём вовсе — клиент не может показать того,
     * чего ему не прислали. Метку Orion скрываем вместе с названием: она опознаёт звезду
     * не хуже него.
     * <p>
     * Сканеры добавляют к звезде один факт: накрыта ли она ими и видно ли там чужое
     * присутствие. Состав системы это не раскрывает — за ним нужен флот (п. 15).
     */
    public StarSystemDto toUnexploredSystem(StarSystemEntity system, Boolean scanned,
                                            Boolean occupied, Boolean omniscient,
                                            IntelService.SeenMonster remembered,
                                            List<UUID> knownOwners) {
        // Сторожа неразведанной системы видит всевидящая раса (п. 7, п. 11.1) и тот, кто о нём
        // ЗНАЕТ: флот, разбитый на подлёте, и слух о стороже у старта гостевой партии (пункт 105).
        // Сама система при этом остаётся неразведанной — её состав открывает только флот.
        com.sddnw.server.domain.enums.SpaceMonster seen =
                Boolean.TRUE.equals(omniscient) && Boolean.TRUE.equals(system.hasLiveMonster())
                        ? system.getMonster()
                        : remembered == null ? null : remembered.monster();
        // Хозяин известен (знакомство, сканер — п. 15) — звезда получает и название: подписать её
        // цветом хозяина иначе нечем. Состав системы по-прежнему не приходит.
        Boolean ownerKnown = knownOwners != null && !knownOwners.isEmpty();
        return new StarSystemDto(
                system.getId(),
                Boolean.TRUE.equals(ownerKnown) ? system.getName() : null,
                system.getXParsec(),
                system.getYParsec(),
                system.getStarColor().name(),
                system.getStarColor().getHexColor(),
                Boolean.FALSE,
                null,
                List.of(),
                Boolean.FALSE,
                scanned,
                occupied,
                seen == null ? null : messages.label(seen),
                seen == null ? null : seen.getHullCode(),
                Boolean.TRUE.equals(ownerKnown) ? knownOwners : null);
    }

    /**
     * Разведанная система глазами игрока — п. 15. Чужие колонии собираются через линзу
     * тумана войны ({@link IntelService}): под глазами — правда, без них — память.
     */
    public StarSystemDto toSystem(StarSystemEntity system, ColonyService.ColonyContext context,
                                  IntelService.Lens lens) {
        List<PlanetDto> planets = system.getPlanets().stream()
                .map(planet -> toPlanet(planet, context, lens, system.getId()))
                .toList();
        // Сторож системы — тоже глазами игрока (п. 11.1, п. 15): под глазами правда, без
        // них память; линзы нет — это взгляд администратора, и он видит правду.
        IntelService.SeenMonster monster = lens == null
                ? (Boolean.TRUE.equals(system.hasLiveMonster())
                        ? new IntelService.SeenMonster(system.getMonster(), system.getMonsterStrength(), null)
                        : null)
                : intelService.monsterView(system, lens.eyes(), lens.monsters());
        // Хозяин системы — хозяин её родного мира, но тоже так, как его видит игрок.
        UUID owner = planets.stream()
                .filter(planet -> Boolean.TRUE.equals(planet.homeworld()))
                .map(PlanetDto::ownerPlayerId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        return new StarSystemDto(
                system.getId(),
                system.getName(),
                system.getXParsec(),
                system.getYParsec(),
                system.getStarColor().name(),
                system.getStarColor().getHexColor(),
                system.getSpecial(),
                owner,
                planets,
                Boolean.TRUE,
                // Разведанной системе сканеры ничего не добавляют: о ней и так известно всё.
                Boolean.TRUE,
                Boolean.FALSE,
                monster == null ? null : messages.label(monster.monster()),
                monster == null ? null : monster.monster().getHullCode(),
                null);
    }

    /** Одна планета — для ответа на действие игрока с её колонией. */
    public PlanetDto toPlanet(PlanetEntity planet) {
        return toPlanet(planet, colonyService.context(planet));
    }

    public PlanetDto toPlanet(PlanetEntity planet, ColonyService.ColonyContext context) {
        return toPlanet(planet, context, null, null);
    }

    /**
     * Планета глазами игрока ({@code lens}); без линзы — правда, это ответ хозяину о его
     * же колонии. Чужая колония показывается ТОЛЬКО тем, что видно со стороны (п. 15):
     * хозяин, население и оборона — правдой под глазами, памятью без них; ни раскладки
     * жителей, ни стройки, ни построек сверх обороны — по ним читались бы технологии
     * соседа, чего в оригинале не видно никак.
     */
    public PlanetDto toPlanet(PlanetEntity planet, ColonyService.ColonyContext context,
                              IntelService.Lens lens, UUID systemId) {
        boolean own = lens == null || lens.viewerId().equals(planet.getOwnerPlayerId());
        IntelService.Known known = own ? null
                : intelService.view(lens, planet, systemId, context::buildings);
        UUID owner = own ? planet.getOwnerPlayerId() : known == null ? null : known.ownerId();
        Integer population = own ? planet.getPopulation() : known == null ? 0 : known.population();
        List<String> defences = own || known == null ? List.of()
                : known.defences().stream()
                        .map(code -> context.catalog().containsKey(code) ? context.catalog().get(code).name() : code)
                        .toList();
        return new PlanetDto(
                planet.getId(),
                planet.getOrbit(),
                planet.getName(),
                planet.getPlanetSize().name(),
                planet.getPlanetSize().getLabel(),
                planet.getClimate().name(),
                planet.getClimate().getLabel(),
                planet.getClimate().getPopulationMultiplierPercent(),
                planet.getClimate().getColonizable(),
                planet.getClimate().getFoodPerFarmer(),
                PlanetGravity.of(planet.getPlanetSize(), planet.getHomeworld()).name(),
                messages.label(PlanetGravity.of(planet.getPlanetSize(), planet.getHomeworld())),
                PlanetGravity.of(planet.getPlanetSize(), planet.getHomeworld())
                        .productionPercent(Boolean.FALSE, Boolean.FALSE),
                planet.getMinerals().name(),
                planet.getMinerals().getLabel(),
                planet.getMinerals().getProductionPerWorker(),
                planet.getMaxPopulation(),
                population,
                owner,
                // Родной мир чужой империи узнаётся вместе с её колонией: свободная на
                // взгляд игрока планета родным миром не бывает.
                owner == null ? Boolean.FALSE : planet.getHomeworld(),
                own ? colonyService.colony(planet, context) : null,
                own ? planet.getColonyBaseReady() : Boolean.FALSE,
                // Находка — п. 4.1. Имя и описание собираются на языке запроса: ключи
                // лежат в messages, потому что о находке рассказывает и отчёт хода.
                planet.getFind() == null ? null : planet.getFind().name(),
                planet.getFind() == null ? null : messages.label(planet.getFind()),
                planet.getFind() == null ? null
                        : messages.get("planetFind." + planet.getFind().name() + ".description"),
                defences,
                known == null ? null : known.seenTurn(),
                interdictor(known, context));
    }

    /** Радиус чужого перехватчика по видимым постройкам — п. 8; пусто — его нет. */
    private Integer interdictor(IntelService.Known known, ColonyService.ColonyContext context) {
        if (known == null) {
            return null;
        }
        int radius = known.defences().stream()
                .map(code -> context.catalog().get(code))
                .filter(java.util.Objects::nonNull)
                .mapToInt(building -> building.amount(com.sddnw.server.domain.enums.BuildingEffectType.WARP_INTERDICTOR))
                .max()
                .orElse(0);
        return radius > 0 ? radius : null;
    }
}
