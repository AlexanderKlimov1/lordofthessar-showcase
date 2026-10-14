package com.sddnw.server.service;

import com.sddnw.server.config.GameProperties;
import com.sddnw.server.domain.ColonyProject;
import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetBuildingEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.GalaxySize;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.enums.ShipRole;
import com.sddnw.server.dto.CreateGameRequest;
import com.sddnw.server.dto.CreateGameResponse;
import com.sddnw.server.dto.EndTurnRequest;
import com.sddnw.server.dto.EndTurnResponse;
import com.sddnw.server.dto.GalaxyMapDto;
import com.sddnw.server.dto.GameDetailsDto;
import com.sddnw.server.dto.GameSaveDto;
import com.sddnw.server.dto.GameSummaryDto;
import com.sddnw.server.dto.JoinGameRequest;
import com.sddnw.server.dto.PlayerCredentialsDto;
import com.sddnw.server.dto.SaveGameRequest;
import com.sddnw.server.dto.TurnReportDto;
import com.sddnw.server.dto.StartGameRequest;
import com.sddnw.server.galaxy.GalaxyGenerator;
import com.sddnw.server.galaxy.HomeworldAllocator;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlanetBuildingRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.StarSystemRepository;
import com.sddnw.server.service.stub.TurnService;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import com.sddnw.server.domain.enums.SpaceMonster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Жизненный цикл партии — п. 3.1, 3.2 и генерация галактики по п. 4.2.
 * <p>
 * Игру создаёт произвольный игрок, она сразу попадает в список видимых игр.
 * До {@code maxHumanPlayers} человек могут присоединиться; при старте свободные
 * слоты добираются ИИ-игроками до {@code totalPlayers}.
 * <p>
 * Действия внутри партии — колонии, стройка, исследования — живут в своих сервисах и
 * сюда не заходят: здесь только сама партия, её ходы, карта и сохранения.
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    /**
     * Автосохранения пишутся ЗА ответом, а не перед ним (12.10.2026, замер хозяина проекта:
     * «ход должен передаваться почти мгновенно»). Слепок вычитывает всю галактику и стоил в
     * запросе конца хода 0,2–0,3 с — игрок ждал записи, которая ему не нужна до следующего
     * входа. Поток один: слепки одной партии ложатся по порядку ходов и не дерутся за строку
     * {@code game_save}. Демон — остановку сервера он не держит; недописанный слепок — это
     * прошлое автосохранение, а не потерянная партия.
     */
    private final java.util.concurrent.ExecutorService autoSaves =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "autosave");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Нужен ровно для одного — убрать за удалённой партией то, что связано с ней полем,
     * а не ссылкой (см. {@link #deleteBelongings}). Пятнадцать репозиториев ради
     * одиннадцати удалений завести можно, но читать их будет труднее, чем этот список.
     */
    @PersistenceContext
    private EntityManager entityManager;

    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final StarSystemRepository starSystemRepository;
    private final GalaxyGenerator galaxyGenerator;
    private final HomeworldAllocator homeworldAllocator;
    private final GameMapper gameMapper;
    private final MyGamesContextService myGamesContext;
    private final GameProperties gameProperties;
    private final GameAccess gameAccess;
    private final PlayerRoster playerRoster;
    private final ShipDesignService shipDesignService;
    private final ResearchService researchService;
    private final PlanetBuildingRepository planetBuildingRepository;
    private final TurnService turnService;
    private final GameSaveService gameSaveService;
    private final ExplorationService explorationService;
    private final IntelService intelService;
    private final RaceService raceService;
    private final GameEventPublisher gameEvents;
    private final GameEventService gameEventService;
    private final PresenceService presence;
    private final FleetService fleetService;
    private final RaceTemplateService raceTemplates;
    private final TurnNotifyService turnNotify;
    private final SpectatorService spectators;

    /** Метки наставника на карте ученика — пункт 184. Полем: конструктор службы и так длинен. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.sddnw.server.repository.MapMarkRepository mapMarks;

    @org.springframework.beans.factory.annotation.Autowired
    private Messages messages;

    /**
     * Сколько партий отдаётся списком. Партий в базе накапливаются сотни, а в лобби
     * смотрят на свежие: без предела список рос бы вместе с базой.
     */
    private static final int GAMES_PAGE = 50;

    public GameService(GameRepository gameRepository,
                       PlayerRepository playerRepository,
                       StarSystemRepository starSystemRepository,
                       GalaxyGenerator galaxyGenerator,
                       HomeworldAllocator homeworldAllocator,
                       GameMapper gameMapper,
                       MyGamesContextService myGamesContext,
                       GameProperties gameProperties,
                       GameAccess gameAccess,
                       PlayerRoster playerRoster,
                       ShipDesignService shipDesignService,
                       ResearchService researchService,
                       PlanetBuildingRepository planetBuildingRepository,
                       TurnService turnService,
                       GameSaveService gameSaveService,
                       ExplorationService explorationService,
                       IntelService intelService,
                       RaceService raceService,
                       GameEventPublisher gameEvents,
                       GameEventService gameEventService,
                       PresenceService presence,
                       FleetService fleetService,
                       RaceTemplateService raceTemplates,
                       TurnNotifyService turnNotify,
                       SpectatorService spectators) {
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.starSystemRepository = starSystemRepository;
        this.galaxyGenerator = galaxyGenerator;
        this.homeworldAllocator = homeworldAllocator;
        this.gameMapper = gameMapper;
        this.myGamesContext = myGamesContext;
        this.gameProperties = gameProperties;
        this.gameAccess = gameAccess;
        this.playerRoster = playerRoster;
        this.shipDesignService = shipDesignService;
        this.researchService = researchService;
        this.planetBuildingRepository = planetBuildingRepository;
        this.turnService = turnService;
        this.gameSaveService = gameSaveService;
        this.explorationService = explorationService;
        this.intelService = intelService;
        this.raceService = raceService;
        this.gameEvents = gameEvents;
        this.gameEventService = gameEventService;
        this.fleetService = fleetService;
        this.presence = presence;
        this.raceTemplates = raceTemplates;
        this.turnNotify = turnNotify;
        this.spectators = spectators;
    }

    /**
     * Список игр, видимых всем, кто видит IP сервера — п. 3.1.
     * <p>
     * Два запроса на весь список: партии страницей и все их игроки разом. Раньше игроки
     * запрашивались на каждую партию, и список из сотни игр стоил сотни запросов.
     */
    @Transactional(readOnly = true)
    public List<GameSummaryDto> listGames(Boolean openOnly) {
        PageRequest page = PageRequest.of(0, GAMES_PAGE);
        List<GameEntity> games = Boolean.TRUE.equals(openOnly)
                ? gameRepository.findAllByStatusOrderByCreatedAtDesc(GameStatus.LOBBY, page)
                : gameRepository.findAllByOrderByCreatedAtDesc(page);
        // Закрытая партия (пункт 67) в общем списке не видна: в неё входят только по приглашению.
        games = games.stream().filter(game -> !(game.getStatus() == GameStatus.LOBBY
                && Boolean.TRUE.equals(game.getInviteOnly()))).toList();
        if (games.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<PlayerEntity>> playersByGame = playerRepository
                .findAllByGameIdInOrderBySlotAsc(games.stream().map(GameEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(player -> player.getGame().getId()));

        return games.stream()
                .map(game -> gameMapper.toSummary(game, playersByGame.getOrDefault(game.getId(), List.of())))
                .toList();
    }

    /**
     * Свои в лобби видны — field-backlog, пункт 148: в группу своих входят охотнее, чем к
     * незнакомым. Для каждой открытой партии — сколько других партий у спрашивающего общих с кем-то
     * из её участников. Только число: чужие победы и поражения сюда не входят.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> playedTogether(UUID accountId) {
        Set<UUID> mine = new java.util.HashSet<>(playerRepository.gameIdsOfAccount(accountId));
        Map<UUID, Integer> together = new java.util.LinkedHashMap<>();
        Map<UUID, Set<UUID>> gamesOf = new HashMap<>();
        for (GameEntity game : gameRepository.findAllByStatusOrderByCreatedAtDesc(GameStatus.LOBBY,
                PageRequest.of(0, GAMES_PAGE))) {
            if (Boolean.TRUE.equals(game.getInviteOnly())) {
                continue;
            }
            Set<UUID> shared = new java.util.HashSet<>();
            for (PlayerEntity player : playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId())) {
                if (player.getAccountId() == null || player.getAccountId().equals(accountId)) {
                    continue;
                }
                Set<UUID> theirs = gamesOf.computeIfAbsent(player.getAccountId(),
                        id -> new java.util.HashSet<>(playerRepository.gameIdsOfAccount(id)));
                theirs.stream().filter(mine::contains).filter(id -> !id.equals(game.getId())).forEach(shared::add);
            }
            if (!shared.isEmpty()) {
                together.put(game.getId(), shared.size());
            }
        }
        return together;
    }

    /** Создание игры — п. 3.1. Создатель занимает слот 1 и становится хостом. */
    @Transactional
    public CreateGameResponse createGame(CreateGameRequest request, UUID accountId) {
        return createGame(request, accountId, Boolean.FALSE);
    }

    /**
     * Партия на своих справочниках основателя — field-backlog, пункт 42: одиночная (второго
     * человека не пустят — у всех за столом должны быть одни правила) и закрытая (в списке
     * открытых её нет). Копию набора кладёт {@code ModService.attach} рядом.
     */
    @Transactional
    public void makeModded(UUID gameId) {
        GameEntity game = gameAccess.requireGame(gameId);
        game.setModded(Boolean.TRUE);
        game.setMaxHumanPlayers(1);
        game.setInviteOnly(Boolean.TRUE);
        gameRepository.save(game);
    }

    /**
     * То же, но для гостевой партии: тесный старт (backlog-promo, пункт 5) — родные миры ближе
     * друг к другу — и богатый (пункт 15) — двигатель, химия и корабли с первого хода.
     * Отдельным параметром, а не полем запроса: решает его не тот, кто шлёт запрос, а сервер
     * — по роли записи ({@code GameController}), как и прочие настройки гостевой партии.
     */
    @Transactional
    public CreateGameResponse createGame(CreateGameRequest request, UUID accountId, Boolean guest) {
        GalaxySize galaxySize = request.galaxySizeOrDefault();

        GameEntity game = new GameEntity();
        game.setName(gameName(request));
        game.setGalaxySize(galaxySize);
        game.setWidthParsecs(galaxySize.getWidthParsecs());
        game.setHeightParsecs(galaxySize.getHeightParsecs());
        game.setStarCount(galaxySize.getStarCount());
        game.setStatus(GameStatus.LOBBY);
        game.setTurn(0);
        game.setTotalPlayers(request.totalPlayersOr(gameProperties.totalPlayers()));
        game.setMaxHumanPlayers(gameProperties.maxHumanPlayers());
        game.setSeed(request.seed() == null ? new Random().nextLong() : request.seed());
        // Случайные события — п. 11.1: переключатель окна новой игры, как в MOO II.
        game.setGalacticEvents(request.galacticEventsOrDefault());
        game.setCouncil(request.councilOrDefault());
        game.setAiActive(request.aiActiveOrDefault());
        game.setWardenholdVictory(request.wardenholdVictoryOrDefault());
        game.setMightVictory(request.mightVictoryOrDefault());
        // Срок итога — партия под вечер (пункт 24): только из списка правила.
        if (request.mightTurn() != null && !MightVictoryRules.ALLOWED_TURNS.contains(request.mightTurn())) {
            throw new BadRequestException("game.mightTurnInvalid", request.mightTurn());
        }
        game.setMightTurn(request.mightTurn() == null || request.mightTurn() == MightVictoryRules.TURN_LIMIT
                ? null : request.mightTurn());
        // Тихая галактика (пункт 186): признак партии, как aiActive; гостю не назначается.
        game.setPeacefulAi(!Boolean.TRUE.equals(guest) && Boolean.TRUE.equals(request.peacefulAi()));
        // Срок хода — backlog-promo, пункт 11: только из предложенных окном новой игры.
        if (!TurnClockRules.allowed(request.turnSeconds())) {
            throw new BadRequestException("game.turnSecondsInvalid", request.turnSeconds());
        }
        game.setTurnSeconds(request.turnSeconds());
        // Особенность галактики (пункт 94): у гостя её нет — первая партия учит обычной игре.
        String feature = guest ? null : com.sddnw.server.domain.enums.GalaxyFeature.resolve(
                request.galaxyFeature(), game.getSeed());
        game.setGalaxyFeature(feature);
        game.setCloseStart(guest || com.sddnw.server.domain.enums.GalaxyFeature.is(feature,
                com.sddnw.server.domain.enums.GalaxyFeature.CLOSE));
        game.setRichStart(guest);
        // Галактика недели (пункт 146): одиночная партия, условия назначил контроллер.
        if (Boolean.TRUE.equals(request.weekly())) {
            game.setWeeklyWeek(WeeklyGalaxyRules.currentWeek());
            game.setMaxHumanPlayers(1);
        }
        game.setTutorial(Boolean.TRUE.equals(request.tutorial()));
        // Уровень сложности — field-backlog, пункт 9: только из перечня (незнакомый код — отказ, а не
        // молчаливый «обычный»). Обучение без явного уровня — новичок: соперник обучения учит, а не
        // наказывает. Галактика недели — обычный: условия одни на всех, иначе итоги по расам врут.
        String skill = request.aiSkill();
        if (skill != null && java.util.Arrays.stream(com.sddnw.server.domain.enums.AiSkill.values())
                .noneMatch(one -> one.name().equals(skill))) {
            throw new BadRequestException("game.aiSkillInvalid", skill);
        }
        if (Boolean.TRUE.equals(request.weekly())) {
            game.setAiSkill(null);
        } else if (skill == null && Boolean.TRUE.equals(request.tutorial())) {
            game.setAiSkill(com.sddnw.server.domain.enums.AiSkill.BEGINNER.name());
        } else {
            game.setAiSkill(skill);
        }
        game.setSpectators(Boolean.TRUE.equals(request.spectators()));
        game.setInviteOnly(Boolean.TRUE.equals(request.inviteOnly()));
        game.setCreatedAt(OffsetDateTime.now());

        PlayerEntity host = playerRoster.human(request.playerName(), 1, request.raceCode(), Set.of(),
                request.banner(), Set.of());
        // Хозяин места — учётная запись, с которой партию завели (п. 3.1). Пусто бывает
        // только у балансового прогона: у его партий человека нет вовсе.
        host.setAccountId(accountId);
        host.setLastActionAt(OffsetDateTime.now());
        host.setHomeStarName(playerRoster.homeStarName(request.homeStarName()));
        playerRoster.applyRaceDesign(host, request.raceName(), request.raceTraits());
        game.addPlayer(host);

        // Партия без людей — п. 3.2: создатель остаётся хозяином партии и держит пропуск,
        // но империю за него ведёт ИИ. Ждать такую партию не приходится ни от кого
        // (`waitingFor` считает только людей), поэтому каждый конец хода считает ход сразу.
        if (Boolean.TRUE.equals(request.observerOrDefault())) {
            host.setPlayerType(PlayerType.AI);
            playerRoster.giveCharacter(host, game, host.getSlot());
            // Империя наблюдателя играет уровнем партии (пункт 9), как и соседи.
            host.setAiSkill(game.getAiSkill());
        }

        gameRepository.saveAndFlush(game);
        game.setHostPlayerId(host.getId());
        // Раса по чужой ссылке (пункт 99): автору прибавится сыгравший.
        raceTemplates.started(accountId, request.raceTemplateId(), game.getId());

        log.info("Создана игра {} ({}), хост {}{}", game.getName(), galaxySize, host.getName(),
                Boolean.TRUE.equals(request.observerOrDefault()) ? " (наблюдатель)" : "");
        return joinedResponse(game, host);
    }

    @Transactional(readOnly = true)
    public GameDetailsDto getGame(UUID gameId) {
        return details(gameAccess.requireGame(gameId));
    }

    /** Присоединение игрока — п. 3.2, не более {@code maxHumanPlayers} человек. */
    @Transactional
    public CreateGameResponse joinGame(UUID gameId, JoinGameRequest request, UUID accountId) {
        return joinGame(gameId, request, accountId, Boolean.FALSE);
    }

    /**
     * Вход в лобби; {@code invited} — по ссылке-приглашению ({@code InviteService}). Закрытая партия
     * (пункт 67) пускает только так: прямой вход по идентификатору ей отказывает.
     */
    @Transactional
    public CreateGameResponse joinGame(UUID gameId, JoinGameRequest request, UUID accountId, Boolean invited) {
        GameEntity game = gameAccess.requireGame(gameId);
        if (Boolean.TRUE.equals(game.getInviteOnly()) && !Boolean.TRUE.equals(invited)) {
            throw new com.sddnw.server.web.error.ForbiddenException("game.inviteOnly");
        }
        gameAccess.requireLobby(game);

        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        if (players.size() >= game.getMaxHumanPlayers()) {
            throw new ConflictException("game.humansFull", game.getMaxHumanPlayers());
        }

        Set<String> takenRaces = players.stream().map(PlayerEntity::getRaceCode).collect(Collectors.toSet());
        // Знамя — п. 3.2: занятое тем, кто уже сидит в партии, не взять.
        Set<String> takenColors = players.stream().map(PlayerEntity::getColor).collect(Collectors.toSet());
        PlayerEntity player = playerRoster.human(
                request.playerName(), playerRoster.nextFreeSlot(players), request.raceCode(), takenRaces,
                request.banner(), takenColors);
        player.setAccountId(accountId);
        player.setLastActionAt(OffsetDateTime.now());
        player.setHomeStarName(playerRoster.homeStarName(request.homeStarName()));
        playerRoster.applyRaceDesign(player, request.raceName(), request.raceTraits());
        player.setGame(game);
        playerRepository.saveAndFlush(player);
        raceTemplates.started(accountId, request.raceTemplateId(), game.getId());
        gameEvents.playerJoined(game, player);
        log.info("Игрок {} присоединился к игре {}", player.getName(), game.getName());
        return joinedResponse(game, player);
    }

    /**
     * Старт игры — п. 3.2. Стартует только создатель, при любом количестве
     * присоединившихся игроков, в том числе без них.
     */
    @Transactional
    public GameDetailsDto startGame(UUID gameId, StartGameRequest request) {
        GameEntity game = gameAccess.requireGame(gameId);
        gameAccess.requireLobby(game);
        gameAccess.requireHost(game, request.accessToken());
        return start(game, request.aiEmpires());
    }

    /**
     * «Сыграть с людьми» — field-backlog, пункт 65: вход в ближайшую открытую партию подбора или,
     * если такой нет, новая с ожиданием. Пустой список открытых партий — холодный старт, видимый
     * каждому; здесь пустоты нет: человек сразу оказывается за столом, и стол стартует САМ, как
     * только за ним двое людей и вышел срок ожидания ({@link #autoStart}).
     * <p>
     * Настройки стола назначает сервер, а не пришедший: подбор сводит незнакомых, и спорить о
     * размере галактики им не с кем. Малая галактика на четверых, все пути к победе и срок хода —
     * часы нужны, иначе один задумавшийся держит всех ({@link MatchmakingRules}).
     */
    /** История партий для подбора соперников по силе (пункт 119); необязательна — тестам не нужна. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GameRecordService gameRecords;

    @Transactional
    public CreateGameResponse quickMatch(UUID accountId, String playerName) {
        // Уже сидит за столом подбора — возвращаем его место, а не второе.
        for (GameEntity game : gameRepository.findAllByStatusAndAutoStartAtNotNullOrderByCreatedAtAsc(GameStatus.LOBBY)) {
            List<PlayerEntity> seated = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId());
            if (seated.stream().anyMatch(player -> accountId.equals(player.getAccountId()))) {
                return rejoin(game.getId(), accountId);
            }
        }
        // Соперники по силе (пункт 119): из столов со свободным местом — самый похожий по опыту и
        // рейтингу тех, кто за ним сидит, а не первый. Пустого лобби ради сходства не заводим: есть
        // свободный стол — человек садится за него, пусть и не за равный (холодный старт, пункт 63).
        List<GameEntity> open = new ArrayList<>();
        Map<UUID, List<PlayerEntity>> seatedAt = new HashMap<>();
        for (GameEntity game : gameRepository.findAllByStatusAndAutoStartAtNotNullOrderByCreatedAtAsc(GameStatus.LOBBY)) {
            List<PlayerEntity> seated = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId());
            if (seated.size() < Math.min(game.getMaxHumanPlayers(), game.getTotalPlayers())) {
                open.add(game);
                seatedAt.put(game.getId(), seated);
            }
        }
        if (open.size() > 1 && gameRecords != null) {
            java.util.Set<UUID> accountIds = new java.util.HashSet<>();
            accountIds.add(accountId);
            seatedAt.values().forEach(seated -> seated.stream().map(PlayerEntity::getAccountId)
                    .filter(java.util.Objects::nonNull).forEach(accountIds::add));
            Map<UUID, PeerRules.Profile> profiles = gameRecords.profiles(accountIds);
            PeerRules.Profile mine = profiles.get(accountId);
            // Сортировка устойчивая: при равном сходстве остаётся прежний порядок — старший стол первым.
            open.sort(java.util.Comparator.comparingDouble(game -> PeerRules.distanceToTable(mine,
                    seatedAt.get(game.getId()).stream().map(PlayerEntity::getAccountId)
                            .filter(java.util.Objects::nonNull).map(profiles::get).toList())));
        }
        if (!open.isEmpty()) {
            GameEntity game = open.get(0);
            List<PlayerEntity> seated = seatedAt.get(game.getId());
            CreateGameResponse joined = joinGame(game.getId(),
                    new JoinGameRequest(playerName, null, null, null, null, null, null, null), accountId);
            int humans = seated.size() + 1;
            if (humans >= Math.min(game.getMaxHumanPlayers(), game.getTotalPlayers())) {
                // Стол полон — ждать некого: стартует сразу, со следующим обходом часов.
                game.setAutoStartAt(OffsetDateTime.now());
            } else if (humans >= MatchmakingRules.MIN_HUMANS) {
                // Двое уже есть — партия состоялась; третьего ждём недолго, а не остаток срока.
                OffsetDateTime soon = OffsetDateTime.now().plus(MatchmakingRules.GRACE);
                if (game.getAutoStartAt().isAfter(soon)) {
                    game.setAutoStartAt(soon);
                }
            }
            return joined;
        }
        CreateGameResponse created = createGame(MatchmakingRules.request(playerName), accountId, Boolean.FALSE);
        GameEntity game = gameAccess.requireGame(created.game().id());
        game.setAutoStartAt(OffsetDateTime.now().plus(MatchmakingRules.WAIT));
        gameRepository.save(game);
        return joinedResponse(game, playerRepository.findById(created.credentials().playerId()).orElseThrow());
    }

    /**
     * Стол подбора стартует сам — field-backlog, пункт 65: срок ожидания вышел и людей хотя бы двое.
     * Один человек — ждём дальше: партия подбора и есть обещание сыграть с людьми, а не с ИИ;
     * играть с ИИ сейчас он вправе сам, кнопкой старта (место для второго останется
     * приглашением, пункт 66). Замок берётся ДО чтения партии: создатель мог нажать старт в ту же
     * секунду.
     */
    @Transactional
    public void autoStart(UUID gameId) {
        GameEntity game = gameRepository.lockById(gameId).orElse(null);
        if (game == null || game.getStatus() != GameStatus.LOBBY || game.getAutoStartAt() == null
                || game.getAutoStartAt().isAfter(OffsetDateTime.now())) {
            return;
        }
        long humans = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId).stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN).count();
        if (humans < MatchmakingRules.MIN_HUMANS) {
            return;
        }
        log.info("Стол подбора {} стартует сам: людей {}", game.getName(), humans);
        start(game, null);
    }

    /** Общее для старта создателем и автостарта: добор ИИ, галактика, стартовое хозяйство. */
    private GameDetailsDto start(GameEntity game, List<StartGameRequest.AiEmpireDesign> aiEmpires) {
        UUID gameId = game.getId();
        List<PlayerEntity> humans = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        playerRepository.saveAll(playerRoster.aiPlayers(game, humans, aiEmpires));
        playerRepository.flush();

        List<StarSystemEntity> systems = starSystemRepository.saveAll(galaxyGenerator.generate(game));
        starSystemRepository.flush();

        List<PlayerEntity> allPlayers = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        homeworldAllocator.allocate(systems, allPlayers, playerRoster.homeClimatesByRace(),
                playerRoster.raceEffectsByPlayer(allPlayers), game.getCloseStart(),
                new Random(game.getSeed()), starSystemRepository::flush);
        playerRepository.flush();

        game.setStatus(GameStatus.IN_PROGRESS);
        game.setStartedAt(OffsetDateTime.now());
        game.setTurnStartedAt(game.getStartedAt());
        game.setTurn(1);
        gameRepository.saveAndFlush(game);

        // Стартовые уровни дерева — п. 9: их называет само описание дерева
        // (`starting_levels`). С 01.10.2026 список пуст — первые уровни тоже исследуются, —
        // но выдача осталась: вернуть стартовые технологии значит поправить справочник.
        allPlayers.forEach(player -> researchService.grantStarting(player, game.getTurn()));
        // Богатый старт гостевой партии (backlog-promo, пункт 15) — у ВСЕХ империй: старт
        // одинаков у человека и ИИ, иначе соседи отставали бы на десяток ходов. Выдаётся ДО
        // проектов кораблей ниже: автоматические проекты собираются из изученного.
        if (Boolean.TRUE.equals(game.getRichStart())) {
            allPlayers.forEach(player -> researchService.grantLevels(
                    player, GuestGameRules.STARTING_LEVELS, game.getTurn()));
        }

        // Каждая империя входит в партию со стартовым проектом корабля — п. 8. Без него
        // колонии нечего строить в первый же ход, а окно дизайна открывалось бы пустым.
        allPlayers.forEach(player -> {
            shipDesignService.ensureAutoDesigns(game.getId(), player, game.getTurn());
            // Звёздная база родного мира вооружена с первого хода — п. 8, п. 11.
            shipDesignService.ensurePlatformDesigns(game.getId(), player, game.getTurn());
        });

        // Родной мир начинает со звёздной базой на орбите и с казармами — п. 8 и п. 12.
        //
        // Звёздная база в MOO II это верфь («star dock capable of building ships larger than
        // destroyers»): без неё колония поднимает только два наименьших корпуса, и с первого
        // дня империя должна уметь строить всё, что изучит.
        //
        // Казармы морской пехоты в оригинале не требуют исследований вовсе и стоят на родном
        // мире с начала партии: колонию защищают обученные бойцы, а не жители с винтовками.
        // Строятся они и на всякой новой колонии — технологии у них нет, и список стройки
        // предлагает их с первого хода (resources/Buildings/buildings.json).
        //
        // Столица — п. 10.1: престол империи стоит на родном мире с начала партии, кроме
        // объединения, у которого столицы нет вовсе (CapitolRules).
        Map<UUID, RaceEffects> startRaces = raceService.effectsByPlayer(
                allPlayers.stream().map(PlayerEntity::getId).toList());
        planetBuildingRepository.saveAll(systems.stream()
                .flatMap(system -> system.getPlanets().stream())
                .filter(planet -> Boolean.TRUE.equals(planet.getHomeworld()))
                .flatMap(planet -> Stream.concat(
                        Stream.of(
                                building(planet.getId(), ShipDesignRules.STAR_BASE, game.getTurn()),
                                building(planet.getId(), MARINE_BARRACKS, game.getTurn())),
                        Boolean.TRUE.equals(startRaces.getOrDefault(planet.getOwnerPlayerId(), RaceEffects.NONE)
                                .hasCapitol())
                                ? Stream.of(building(planet.getId(), CapitolRules.CODE, game.getTurn()))
                                : Stream.empty()))
                .toList());

        if (Boolean.TRUE.equals(game.getRichStart())) {
            allPlayers.forEach(player -> startingFleet(game, player));
            guestLairs(game, systems, allPlayers, startRaces);
            guestFinds(systems, allPlayers);
        }

        gameEvents.gameStarted(game);
        spectatorFrame(game);
        log.info("Игра {} стартовала: {} игроков, {} звёздных систем",
                game.getName(), allPlayers.size(), systems.size());
        return details(game);
    }

    /**
     * Казармы морской пехоты — п. 12. Код здания из
     * {@code resources/Buildings/buildings.json}; технологии у них нет, поэтому строить их
     * можно с первого хода, а родной мир получает их готовыми.
     */
    private static final String MARINE_BARRACKS = "marine-barracks";

    /** Постройка на планете — строка построенного здания. */
    /**
     * Стартовые корабли богатого старта — backlog-promo, пункт 15: разведчики (фрегаты
     * автоматического проекта — их собирает игра из изученного) и колониальный корабль с
     * поселенцами, у родной звезды. Как на средних технологиях MOO II: первым ходом есть кем
     * лететь и кем селиться — без этого первые десятки ходов на карте не происходит ничего.
     * Поселенцы колониального корабля даются сверх населения родного мира, как и в оригинале.
     */
    private void startingFleet(GameEntity game, PlayerEntity player) {
        if (player.getHomeSystemId() == null) {
            return;
        }
        shipDesignService.allDesignsOf(player).values().stream()
                .filter(design -> Boolean.TRUE.equals(design.getAuto()) && !Boolean.TRUE.equals(design.getObsolete()))
                .filter(design -> GuestGameRules.SCOUT_HULL.equals(design.getHullCode()))
                .findFirst()
                .ifPresent(scout -> {
                    for (int i = 0; i < GuestGameRules.STARTING_SCOUTS; i++) {
                        fleetService.addShip(game.getId(), player.getId(), player.getHomeSystemId(),
                                game.getTurn(), scout.getId());
                    }
                });
        ShipDesignEntity colonyShip = shipDesignService.civilDesign(game, player, ShipRole.COLONY);
        fleetService.addShip(game.getId(), player.getId(), player.getHomeSystemId(), game.getTurn(),
                colonyShip.getId(), ColonyProject.COLONY_SHIP_SETTLERS);
    }

    /**
     * Сторож у старта гостевой партии — field-backlog, пункты 190 и 105: восхищающее обязано
     * случиться рано, а бросивший на 20-м ходу не встретил ни одного чудища. Замер
     * ({@code tools/guest_delight.py}, восемь партий) показал: посол приходит к 19-му ходу, бой
     * и чудище — ни разу за 60 ходов, а в двух партиях из восьми не случилось ничего вовсе.
     * <p>
     * Поэтому в ближайшей к каждой родной звезде системе (не родной, не особой, без своего
     * чудища и с планетами) встаёт амёба, ОСЛАБЛЕННАЯ под стартовых разведчиков: её сила —
     * {@link GuestGameRules#LAIR_PERCENT} от их силы. Первый бой на поле получается своим и
     * честным (пункт 105): его выигрывают тем, что дали, но не даром. Сторож у каждой империи,
     * а не только у человека, — старт в этой игре у всех одинаков; ИИ чудищ обходит и так.
     * Жребий не тратится: выбор — ближайшая система, спор равных — по названию.
     */
    private void guestLairs(GameEntity game, List<StarSystemEntity> systems, List<PlayerEntity> players,
                            Map<UUID, RaceEffects> races) {
        int range = com.sddnw.server.domain.enums.FuelTech.baseRangeParsecs(Set.of());
        Set<UUID> homes = players.stream().map(PlayerEntity::getHomeSystemId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        for (PlayerEntity player : players) {
            StarSystemEntity home = systems.stream()
                    .filter(system -> system.getId().equals(player.getHomeSystemId()))
                    .findFirst().orElse(null);
            if (home == null) {
                continue;
            }
            Integer scouts = shipDesignService.allDesignsOf(player).values().stream()
                    .filter(design -> Boolean.TRUE.equals(design.getAuto()) && !Boolean.TRUE.equals(design.getObsolete()))
                    .filter(design -> GuestGameRules.SCOUT_HULL.equals(design.getHullCode()))
                    .findFirst()
                    .map(design -> shipDesignService.power(design, races.getOrDefault(player.getId(), RaceEffects.NONE))
                            * GuestGameRules.STARTING_SCOUTS)
                    .orElse(0);
            if (scouts <= 0) {
                continue;
            }
            systems.stream()
                    .filter(system -> !homes.contains(system.getId()) && !Boolean.TRUE.equals(system.getSpecial())
                            && system.getMonster() == null && !system.getPlanets().isEmpty())
                    // Сперва — в пределах стартовой дальности (пункт 105): сторож, до которого
                    // разведчикам не долететь, первым успехом не станет. Таких нет — ближайшая.
                    .min(Comparator.comparing((StarSystemEntity system) -> Math.hypot(
                                    system.getXParsec() - home.getXParsec(), system.getYParsec() - home.getYParsec()) > range)
                            .thenComparingDouble((StarSystemEntity system) -> Math.hypot(
                                    system.getXParsec() - home.getXParsec(), system.getYParsec() - home.getYParsec()))
                            .thenComparing(StarSystemEntity::getName))
                    .ifPresent(lair -> {
                        lair.setMonster(SpaceMonster.AMOEBA);
                        lair.setMonsterStrength(Math.max(1, Math.min(SpaceMonster.AMOEBA.getStrength(),
                                scouts * GuestGameRules.LAIR_PERCENT / 100)));
                        starSystemRepository.save(lair);
                        // Сторож виден на карте с первого хода (пункт 105) — памятью империи, как
                        // слух о нём: сама система остаётся неразведанной, её состав — награда.
                        intelService.sawMonster(game.getId(), player.getId(), lair, 0);
                    });
        }
    }

    /**
     * Первая находка — в пределах первых перелётов (backlog-promo, пункт 35), только в гостевой
     * партии. Пока поведение не закрепилось, награда должна прийти рано: случайная находка
     * встречается в среднем у одной системы из пяти-шести, и новичок до неё не доживает. Здесь у
     * каждой империи в пределах стартовой дальности (стандартные топливные элементы) лежат
     * АРТЕФАКТЫ — находка, которая награждает того, кто долетел, а не того, кто поселился, то есть
     * сразу, первым же разведчиком. Поровну всем: каждая империя получает свою, в разных системах,
     * ближайшую к своему дому после логова сторожа. Стартовые системы не трогаются — честность
     * старта остаётся. Без жребия: ближайшая система и первая годная планета в ней.
     */
    private void guestFinds(List<StarSystemEntity> systems, List<PlayerEntity> players) {
        Set<UUID> homes = players.stream().map(PlayerEntity::getHomeSystemId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> taken = new java.util.HashSet<>();
        int range = com.sddnw.server.domain.enums.FuelTech.baseRangeParsecs(Set.of());
        for (PlayerEntity player : players) {
            StarSystemEntity home = systems.stream()
                    .filter(system -> system.getId().equals(player.getHomeSystemId()))
                    .findFirst().orElse(null);
            if (home == null) {
                continue;
            }
            systems.stream()
                    .filter(system -> !homes.contains(system.getId()) && !taken.contains(system.getId())
                            && !Boolean.TRUE.equals(system.getSpecial()) && system.getMonster() == null
                            && Math.hypot(system.getXParsec() - home.getXParsec(),
                                    system.getYParsec() - home.getYParsec()) <= range
                            && system.getPlanets().stream().anyMatch(this::takesGuestFind))
                    .min(Comparator.comparingDouble((StarSystemEntity system) -> Math.hypot(
                                    system.getXParsec() - home.getXParsec(), system.getYParsec() - home.getYParsec()))
                            .thenComparing(StarSystemEntity::getName))
                    .ifPresent(system -> {
                        taken.add(system.getId());
                        system.getPlanets().stream().filter(this::takesGuestFind)
                                .min(Comparator.comparing(PlanetEntity::getOrbit))
                                .ifPresent(planet -> {
                                    planet.setFind(com.sddnw.server.domain.enums.PlanetFind.ARTIFACTS);
                                    planet.setFindClaimed(Boolean.FALSE);
                                });
                        starSystemRepository.save(system);
                    });
        }
    }

    /** Годится ли планета под находку гостевой партии: пригодная, без хозяина и без своей находки. */
    private boolean takesGuestFind(PlanetEntity planet) {
        return planet.getOwnerPlayerId() == null && planet.getFind() == null
                && planet.getMaxPopulation() != null && planet.getMaxPopulation() > 0;
    }

    private PlanetBuildingEntity building(UUID planetId, String code, Integer turn) {
        PlanetBuildingEntity building = new PlanetBuildingEntity();
        building.setPlanetId(planetId);
        building.setBuildingCode(code);
        building.setBuiltTurn(turn);
        return building;
    }

    /**
     * Конец хода игрока — п. 11.1.
     * <p>
     * Игроки ходят одновременно, каждый в своём темпе: игрок объявляет, что закончил, и
     * ждёт остальных. Когда закончили все люди партии, галактика считается один раз —
     * фазами {@link TurnService}: рост населения (п. 4.1.1), производство и доход (п. 10),
     * исследования (п. 9), шпионаж (п. 13). ИИ ждать не заставляет: своих решений он пока
     * не принимает.
     * <p>
     * Партия берётся под замок на запись: пересчёт трогает всю галактику, и делать это
     * одновременно нельзя. Замок стоит на строке партии, поэтому игроки соседних партий
     * друг друга не ждут.
     * <p>
     * Повторный конец хода в том же ходу ничего не ломает: игрок уже отмечен, и ответ
     * скажет, кого ещё ждут.
     */
    @Transactional
    public EndTurnResponse endTurn(UUID gameId, EndTurnRequest request) {
        // Пропуск проверяем запросом по самому игроку: игру до замка не читаем совсем.
        // Прочитанная заранее игра попадала бы в контекст со старой версией, и своя же
        // запись падала бы на проверке версии, хотя замок к тому времени уже наш.
        UUID playerId = gameAccess.requireToken(gameId, request.accessToken());

        // С этого места ход партии считается в одиночку: остальные ждут на замке.
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        gameAccess.requireInProgress(game);

        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        PlayerEntity player = players.stream()
                .filter(candidate -> candidate.getId().equals(playerId))
                .findFirst()
                .orElseThrow();

        player.setEndedTurn(game.getTurn());
        player.setLastActionAt(OffsetDateTime.now());
        playerRepository.save(player);
        gameEvents.playerReady(game, player);

        List<UUID> waiting = waitingFor(game, players);
        if (!waiting.isEmpty()) {
            // Зов вне игры (пункт 22): «соперники закончили ход» — тем, кого ещё ждут.
            turnNotify.waiting(game, players, waiting);
            log.info("Игрок {} закончил ход {} в игре {}: ждём ещё {}",
                    player.getName(), game.getTurn(), game.getName(), waiting.size());
            return new EndTurnResponse(details(game, players), Boolean.FALSE, waiting, null);
        }

        TurnReport report = advance(game, players);
        return new EndTurnResponse(details(game, players), Boolean.TRUE, List.of(),
                gameEventService.shown(report.forPlayer(player.getId())));
    }

    /**
     * Посчитать ход — общий путь конца хода, срока хода и ухода последнего, кого ждали.
     * Вызывается под замком партии.
     */
    private TurnReport advance(GameEntity game, List<PlayerEntity> players) {
        Integer completedTurn = game.getTurn();
        TurnReport report = turnService.endTurn(game);
        // Новый ход начинается сейчас: от этого мгновения и считается его срок (пункт 11).
        game.setTurnStartedAt(OffsetDateTime.now());
        gameRepository.saveAndFlush(game);

        // Отчёты ложатся в базу и уходят подписчикам: тот, кто закончил первым, узнает
        // о пересчёте только отсюда — его собственный запрос ответил ожиданием.
        gameEvents.turnAdvanced(game, players, report);
        // Зов вне игры (пункт 22): «ход посчитан» и «на колонию напали» — после фиксации.
        turnNotify.advanced(game, players, report);

        autoSave(game, players, completedTurn);
        spectatorFrame(game);

        log.info("Игра {}: ход {} посчитан, наступил {}", game.getName(), completedTurn, game.getTurn());
        return report;
    }

    /**
     * Часы хода — backlog-promo, пункт 11 ({@link TurnClockRules}). Зовёт их
     * {@code TurnClock} раз в несколько секунд для партий, где кто-то ждёт.
     * <p>
     * Под замком партии, как и конец хода: оба решают, считать ли ход, и решать это двоим
     * разом нельзя. Сначала отмечаются ушедшие по безделью, затем — если срок истёк —
     * пропустившие срок (три подряд — тоже ушли), и ход считается, когда ждать больше
     * некого или срок вышел.
     */
    @Transactional
    public void tick(UUID gameId) {
        GameEntity game = gameRepository.lockById(gameId).orElse(null);
        if (game == null || game.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        if (!somebodyWaits(game, players)) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<PlayerEntity> late = players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .filter(player -> !Boolean.TRUE.equals(presence.away(player)))
                .filter(player -> player.getEndedTurn() < game.getTurn())
                .toList();
        for (PlayerEntity player : late) {
            if (Boolean.TRUE.equals(TurnClockRules.idle(presence.lastAction(player.getId()), now, game.getTurnSeconds()))) {
                goAway(game, player, "бездействие");
            }
        }
        Boolean expired = TurnClockRules.expired(game.getTurnStartedAt(), game.getTurnSeconds(), now);
        if (Boolean.TRUE.equals(expired)) {
            for (PlayerEntity player : late) {
                if (!Boolean.TRUE.equals(presence.away(player))
                        && presence.miss(player.getId()) >= TurnClockRules.MISSED_LIMIT) {
                    goAway(game, player, "пропущенные сроки");
                }
            }
        }
        if (Boolean.TRUE.equals(expired) || waitingFor(game, players).isEmpty()) {
            log.info("Игра {}: ход {} считается часами ({})", game.getName(), game.getTurn(),
                    Boolean.TRUE.equals(expired) ? "срок вышел" : "ждать больше некого");
            advance(game, players);
        }
    }

    /**
     * Забрать объявленный ход — field-backlog, пункт 128: случайное «ход» в партии с людьми
     * необратимо до пересчёта, а сервер и так ждёт всех. Отмена снимает отметку игрока.
     * <p>
     * Под замком партии, как и сам конец хода: иначе отмена пришла бы в миг, когда последний
     * закончил и пересчёт уже идёт. Ход посчитан — отказ словами: забирать уже нечего (так
     * всегда в одиночной партии: там ход считается сразу).
     */
    @Transactional
    public GameDetailsDto undoEndTurn(UUID gameId, String accessToken) {
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        gameAccess.requireInProgress(game);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        PlayerEntity player = players.stream()
                .filter(candidate -> candidate.getId().equals(playerId))
                .findFirst()
                .orElseThrow();
        if (player.getEndedTurn() < game.getTurn()) {
            throw new ConflictException("turn.undo.counted");
        }
        player.setEndedTurn(game.getTurn() - 1);
        playerRepository.save(player);
        gameEvents.playerReady(game, player);
        log.info("Игрок {} забрал объявленный ход {} в игре {}", player.getName(), game.getTurn(), game.getName());
        return details(game, players);
    }

    /**
     * «Я здесь» — field-backlog, пункт 127: лёгкое действие, которым игрок отвечает на
     * предупреждение «через минуту вашей империей займётся ИИ» или забирает империю у ИИ.
     * Делать ему ничего не нужно: присутствие отмечает сам пропуск изменяющего запроса
     * ({@link GameAccess#requireToken}), а ответ — свежий состав с новым сроком.
     */
    @Transactional(readOnly = true)
    public GameDetailsDto here(UUID gameId, String accessToken) {
        gameAccess.requireToken(gameId, accessToken);
        return details(gameAccess.requireGame(gameId));
    }

    /**
     * Покинуть партию — backlog-promo, пункт 11: империю ведёт ИИ, пока игрок не вернётся.
     * Вернуться можно из «Ваших партий» главного меню или любым действием в партии.
     */
    @Transactional
    public GameDetailsDto leave(UUID gameId, String accessToken) {
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        gameAccess.requireInProgress(game);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        PlayerEntity player = players.stream()
                .filter(candidate -> candidate.getId().equals(playerId))
                .findFirst()
                .orElseThrow();
        goAway(game, player, "сам покинул партию");
        // Ушёл последний, кого ждали, — ход считается сразу, не дожидаясь часов.
        if (somebodyWaits(game, players) && waitingFor(game, players).isEmpty()) {
            advance(game, players);
        }
        return details(game, players);
    }

    /**
     * Игрок ушёл: дальше его империю ведёт ИИ. Характер правителя выдаётся тем же
     * правилом, что и соседу ИИ: встречи флотов ИИ решает по нему, а у человека его нет.
     * Дипломатию за ушедшего ИИ не ведёт ({@code AiDiplomacyService} смотрит на род
     * игрока): опекун хозяйствует, но не объявляет войн от чужого имени.
     */
    private void goAway(GameEntity game, PlayerEntity player, String why) {
        presence.leave(player.getId());
        if (player.getAiPersonality() == null) {
            playerRoster.giveCharacter(player, game, player.getSlot());
            playerRepository.save(player);
        }
        log.info("Игра {}: игрок {} ушёл ({}), его империю ведёт ИИ", game.getName(), player.getName(), why);
    }

    /**
     * Ждёт ли кто-нибудь — пункт 11: хотя бы один присутствующий человек уже закончил ход.
     * Без этого часам делать нечего: одиночная партия против ИИ не идёт сама, пока игрок
     * отошёл от стола.
     */
    private Boolean somebodyWaits(GameEntity game, List<PlayerEntity> players) {
        return presence.somebodyWaits(game, players);
    }

    /**
     * Автосохранение партии в конце хода — п. 3.
     * <p>
     * <b>Только партиям, в которых есть люди.</b> Балансовый прогон играет сотни партий по
     * сотням ходов, и слепок на каждый ход был бы работой, которой никто не увидит:
     * возвращаться в такую партию некому, а сама она удаляется за собой.
     * <p>
     * <b>Снимается ПОСЛЕ фиксации хода, а не внутри него.</b> Слепок вычитывает всю
     * галактику целиком, а запрос в базу изнутри посчитанного хода заставляет Hibernate
     * сбросить туда всё, что ход успел изменить, — на этом конец хода однажды дорос до
     * секунды (грабли про выборку «на игрока» в фазе). Те же грабли, что у рассылки
     * событий: пока транзакция открыта, для остальных не случилось ничего.
     * <p>
     * <b>Отказ автосохранения не роняет ход.</b> Ход уже посчитан и зафиксирован, игроки
     * его увидели, и падать задним числом ему некуда: беда сохранения — это строка в
     * журнале, а не потерянный ход.
     */
    private void autoSave(GameEntity game, List<PlayerEntity> players, Integer completedTurn) {
        if (players.stream().noneMatch(player -> player.getPlayerType() == PlayerType.HUMAN)) {
            return;
        }
        UUID gameId = game.getId();
        String name = game.getName();
        afterCommit(() -> autoSaves.execute(() -> {
            try {
                gameRepository.findById(gameId)
                        .ifPresent(saved -> gameSaveService.autoSave(saved, completedTurn));
            } catch (RuntimeException e) {
                log.warn("Не удалось автосохранить партию {} на конец хода {}: {}",
                        name, completedTurn, e.toString());
            }
        }));
    }

    /**
     * Кадр начала хода для зрителей — field-backlog, пункт 59. После фиксации и в своей
     * транзакции, как автосохранение; партия без зрителей не стоит ничего, отказ — строка журнала.
     */
    private void spectatorFrame(GameEntity game) {
        if (!Boolean.TRUE.equals(game.getSpectators())) {
            return;
        }
        UUID gameId = game.getId();
        afterCommit(() -> {
            try {
                spectators.record(gameId);
            } catch (RuntimeException e) {
                log.warn("Кадр зрителя партии {} не записан: {}", gameId, e.toString());
            }
        });
    }

    /**
     * Откладывает работу до фиксации транзакции, а вне транзакции делает сразу.
     * <p>
     * Тот же приём и по той же причине, что у {@code GameEventPublisher}: до фиксации
     * состояния, о котором идёт речь, в базе ещё нет.
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * Кого ещё ждёт партия: люди, не объявившие конец текущего хода.
     * <p>
     * ИИ в список не попадает — он не решает и ждать себя не заставляет. Отключившийся
     * игрок-человек партию задержит, и это видно в интерфейсе: кого ждут, там названо.
     */
    private List<UUID> waitingFor(GameEntity game, List<PlayerEntity> players) {
        return players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                // Ушедшего не ждут — его империю ведёт ИИ (backlog-promo, пункт 11). Отпускника —
                // тоже (field-backlog, пункт 177).
                .filter(player -> !Boolean.TRUE.equals(presence.away(player)))
                .filter(player -> player.getEndedTurn() < game.getTurn())
                .sorted(Comparator.comparing(PlayerEntity::getSlot))
                .map(PlayerEntity::getId)
                .toList();
    }

    /** Что изменилось за прошлый ход — п. 11.1: клиент забирает отчёт после перезагрузки. */
    @Transactional(readOnly = true)
    public TurnReportDto turnReport(UUID gameId, String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        return gameEventService.lastReport(player.getId());
    }

    /**
     * Сохранение партии по команде игрока — «Игра» → «Сохранить».
     * <p>
     * В слепок идёт состояние на конец последнего завершённого хода: расчётов внутри
     * хода MVP не ведёт, поэтому текущее состояние партии ему и равно.
     */
    @Transactional
    public GameSaveDto saveGame(UUID gameId, SaveGameRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, request.accessToken());

        GameSaveDto save = gameSaveService.save(game, game.getTurn() - 1);
        log.info("Игрок {} сохранил партию {}", player.getName(), game.getName());
        return save;
    }

    /**
     * Загрузка сохранения — «Игра» → «Загрузить».
     * <p>
     * Из слепка поднимается новая партия, а поднявший садится ЗА СВОЮ ЖЕ империю — ту, у
     * которой в слепке записана его учётная запись (п. 3, миграция 077). Раньше ему
     * доставался «первый человек по порядку мест», и в партии на двоих это значило, что
     * вернувшийся мог оказаться за соседом.
     * <p>
     * <b>Остальные возвращаются сами</b> — {@link #rejoin}: пропуска слотов выдаются
     * новые, и старые, лежащие у них в браузере, к поднятой партии не подходят. Пока они
     * не вернулись, партия их ЖДЁТ — конец хода считается, когда закончили все люди
     * (п. 11.1), — и отдельного «зала ожидания» для этого не нужно: правило уже есть.
     * <p>
     * Своей империи у поднявшего может и не быть: сделанное руками сохранение поднимает
     * кто угодно, в том числе чужую партию. Тогда он получает, как и прежде, первого
     * человека по порядку мест.
     * <p>
     * ОДНА запись вправе держать НЕСКОЛЬКО мест — так играют за одним столом и так
     * проверяет себя сквозной прогон, — и тогда вернувшийся садится за первое из своих по
     * порядку мест. Запрещать это нечем и незачем: правило автосохранения от этого не
     * страдает, потому что «те же люди» — это множество записей, а не их число.
     */
    @Transactional
    public CreateGameResponse loadSave(UUID saveId, UUID accountId) {
        return seat(gameSaveService.restore(saveId, accountId), accountId);
    }

    /** Партия из файла — field-backlog, пункт 160: садится за неё принёсший файл. */
    @Transactional
    public CreateGameResponse importSave(String content, UUID accountId) {
        return seat(gameSaveService.importFile(content, accountId), accountId);
    }

    private CreateGameResponse seat(GameEntity game, UUID accountId) {
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId());
        PlayerEntity player = players.stream()
                .filter(candidate -> candidate.getPlayerType() == PlayerType.HUMAN)
                .filter(candidate -> accountId != null && accountId.equals(candidate.getAccountId()))
                .findFirst()
                .orElseGet(() -> players.stream()
                        .filter(candidate -> candidate.getPlayerType() == PlayerType.HUMAN)
                        .findFirst()
                        .orElseThrow(() -> new ConflictException("save.noHuman")));
        return joinedResponse(game, player);
    }

    /**
     * Возврат в свою партию — п. 3: запись получает пропуск СВОЕГО места.
     * <p>
     * Нужен в двух случаях, и оба настоящие: партию подняли из автосохранения (пропуска
     * там выданы заново, и лежащий в браузере не подходит) или игрок пришёл с другой
     * машины, где хранилища браузера нет вовсе. Личностью служит учётная запись: пропуск
     * слота — это ключ, а не имя, и терять его не должно значить терять империю.
     * <p>
     * Места без хозяина не отдаются никому: партия балансового прогона и всё, начатое до
     * миграции 077, вернуть себе нельзя — некого узнавать.
     */
    @Transactional(readOnly = true)
    public CreateGameResponse rejoin(UUID gameId, UUID accountId) {
        GameEntity game = gameAccess.requireGame(gameId);
        PlayerEntity player = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId).stream()
                .filter(candidate -> accountId.equals(candidate.getAccountId()))
                .findFirst()
                .orElseThrow(() -> new ForbiddenException("game.notYourGame"));
        // Признавший поражение за империю не возвращается (field-backlog, пункт 81): её ведёт ИИ.
        if (player.getConcededTurn() != null) {
            throw new ForbiddenException("game.conceded", player.getConcededTurn());
        }
        // Вернулся — снова за столом: ИИ больше не ведёт его империю (пункт 11).
        presence.act(player.getId());
        log.info("Игрок {} вернулся в партию {}", player.getName(), game.getName());
        return joinedResponse(game, player);
    }

    /** Сколько оконченных партий показывать в «Ваших партиях»: сцена конца — не архив. */
    private static final int FINISHED_SHOWN = 5;

    /**
     * Партии, в которых у этой записи есть место, — «вернуться в партию» в главном меню.
     * <p>
     * Без такого списка возврат был бы невозможен по существу: партию, поднятую из
     * автосохранения соседом, остальные не найдут ничем — в списке открытых её нет (она
     * уже идёт), а её опознавателя им никто не сообщал.
     * <p>
     * <b>Оконченные партии тоже здесь — последние {@link #FINISHED_SHOWN}</b> (field-backlog,
     * пункт 33): сцену конца партии открывают ещё раз отсюда, а вспоминают её не в день победы.
     * Идущие стоят первыми — в них ждут.
     */
    @Transactional(readOnly = true)
    public List<GameSummaryDto> myGames(UUID accountId) {
        List<PlayerEntity> seats = playerRepository.findByAccountId(accountId);
        // Признавший поражение (пункт 81) партию для себя закончил, даже если она идёт дальше
        // без него: в списке она стоит среди оконченных и предлагает итоги, а не возврат.
        Map<UUID, Integer> conceded = new HashMap<>();
        seats.stream()
                .filter(seat -> seat.getConcededTurn() != null)
                .forEach(seat -> conceded.put(seat.getGame().getId(), seat.getConcededTurn()));
        java.util.function.Predicate<GameEntity> over = game -> game.getStatus() == GameStatus.FINISHED
                || conceded.containsKey(game.getId());
        List<GameEntity> mine = seats.stream()
                .map(PlayerEntity::getGame)
                .distinct()
                .toList();
        // Идущие — по последнему посчитанному ходу (field-backlog, пункт 194): первой стоит та,
        // где играли последней, и клиент открывает её сразу, без меню. Ход в одиночной партии
        // считается по нажатию игрока, поэтому начало хода и есть его последнее действие; у
        // партии без единого хода — время создания.
        List<GameEntity> games = new ArrayList<>(mine.stream()
                .filter(over.negate())
                .sorted(Comparator.comparing((GameEntity game) -> game.getTurnStartedAt() == null
                        ? game.getCreatedAt() : game.getTurnStartedAt()).reversed())
                .toList());
        mine.stream()
                .filter(over)
                .sorted(Comparator.comparing((GameEntity game) -> game.getFinishedAt() == null
                        ? game.getCreatedAt() : game.getFinishedAt()).reversed())
                .limit(FINISHED_SHOWN)
                .forEach(games::add);
        if (games.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<PlayerEntity>> playersByGame = playerRepository
                .findAllByGameIdInOrderBySlotAsc(games.stream().map(GameEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(player -> player.getGame().getId()));
        return games.stream()
                .map(game -> {
                    PlayerEntity seat = seats.stream()
                            .filter(candidate -> candidate.getGame().getId().equals(game.getId()))
                            .findFirst()
                            .orElse(null);
                    return gameMapper.toSummary(game, playersByGame.getOrDefault(game.getId(), List.of()))
                            .withConcededTurn(conceded.get(game.getId()))
                            .withMine(seat == null ? null : myGamesContext.context(game, seat,
                                    playersByGame.getOrDefault(game.getId(), List.of())));
                })
                .toList();
    }

    /**
     * Карта галактики для отрисовки клиентом — п. 11.3.
     * <p>
     * Звёзды отдаются все: положение светила известно из любой точки галактики. Подробности
     * — название, планеты, владелец — только по разведанным системам, а разведку определяет
     * {@link ExplorationService}: свои системы и те, куда игрок отправлял шпиона (п. 15).
     * Флаг {@code revealAll} — «Показать галактику» в интерфейсе — раскрывает подробности
     * по всей галактике.
     */
    /**
     * Администратор открыл всю галактику — field-backlog, пункт 197 (решение хозяина
     * проекта, 05.10.2026). Показ ему не отнимается: им живут отладка и проверки. Но если за
     * столом сидят ДРУГИЕ люди, партия для всех помечается «под наблюдением администратора»:
     * один видит карты остальных, и сидящие за столом вправе это знать. В своей одиночной
     * партии и в партиях одного ИИ пометки нет — смотреть там не на кого.
     *
     * @param adminAccountId запись администратора: его собственное место в партии людьми
     *                       «другими» не считается
     */
    @Transactional
    public void markAdminWatch(UUID gameId, UUID adminAccountId) {
        boolean othersAtTable = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId).stream()
                .anyMatch(player -> player.getPlayerType() == PlayerType.HUMAN
                        && player.getAccountId() != null
                        && !player.getAccountId().equals(adminAccountId));
        if (othersAtTable && gameRepository.markAdminWatched(gameId) > 0) {
            log.warn("Партия {} помечена: администратор смотрит всю галактику при других людях", gameId);
        }
    }

    @Transactional(readOnly = true)
    public GalaxyMapDto getMap(UUID gameId, String accessToken, Boolean requestedReveal) {
        GameEntity game = gameAccess.requireGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, accessToken);
        /*
          Раскрытие в конце партии — field-backlog, пункт 136: когда партия окончена, туман и
          знакомство больше ничего не защищают, и разрывы, копившиеся всю партию, закрываются
          разом — вся карта, чьи были системы, где стояли сторожа. Тот же путь, что «Показать
          галактику» администратора, только по признаку «партия окончена».
        */
        Boolean revealAll = Boolean.TRUE.equals(requestedReveal) || game.getStatus() == GameStatus.FINISHED;

        List<StarSystemEntity> systems = starSystemRepository
                .findAllByGameIdWithPlanets(gameId).stream()
                .sorted(GameOrder.SYSTEMS)
                .toList();
        if (systems.isEmpty()) {
            throw new ConflictException("game.noGalaxy");
        }

        Set<UUID> explored = Boolean.TRUE.equals(revealAll)
                ? systems.stream().map(StarSystemEntity::getId).collect(Collectors.toUnmodifiableSet())
                : explorationService.exploredBy(systems, player);

        // Сканеры добавляют к неразведанным системам один факт: есть ли там кто-то — п. 15.
        Set<UUID> scanned = explorationService.scannedBy(systems, player);
        Set<UUID> occupied = explorationService.occupiedBy(systems, player, scanned);

        // Всевидящая раса (п. 7) видит и сторожей систем, куда не летала, — п. 11.1:
        // в этом и состоит её сторона, знать галактику, не летая.
        // Чужие колонии — глазами игрока (п. 15, туман войны): правда под глазами,
        // память без них. Показ всей галактики — взгляд администратора (его проверяет
        // контроллер) — снимает и туман: им пользуются отладка и проверки партий ИИ.
        // Чьи неразведанные системы известны знакомством и сканером — п. 15 (только хозяин).
        Map<UUID, List<UUID>> knownOwners = Boolean.TRUE.equals(revealAll)
                ? Map.of()
                : explorationService.knownOwnersBy(systems, player, explored, scanned);
        GalaxyMapDto map = gameMapper.toMap(game, systems, explored, scanned, occupied, knownOwners,
                gameProperties.minStarDistanceParsecs(),
                Boolean.TRUE.equals(raceService.effects(player).omniscient()) || game.getStatus() == GameStatus.FINISHED,
                Boolean.TRUE.equals(revealAll) ? null : intelService.lensOf(player, systems),
                intelService.foreignFleets(player, systems, game.getTurn(), Boolean.TRUE.equals(revealAll)));
        // Метки наставника (пункт 184) — только у того, кто наставника назвал.
        if (player.getMentorPlayerId() == null) {
            return map;
        }
        Map<UUID, String> names = new HashMap<>();
        playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId)
                .forEach(one -> names.put(one.getId(), messages.empire(one.getName())));
        return map.withMentorMarks(mapMarks.findAllByStudentPlayerIdOrderByTurnAsc(player.getId()).stream()
                .map(row -> new com.sddnw.server.dto.MapMarkDto(row.getSystemId(), row.getCode(),
                        names.getOrDefault(row.getAuthorPlayerId(), ""), row.getTurn()))
                .toList());
    }

    /**
     * Удаление игры доступно только её создателю. Сохранения партии уходят вместе с ней:
     * без партии слепок никому не нужен, а раньше они оставались в базе навсегда.
     */
    @Transactional
    public void deleteGame(UUID gameId, String accessToken) {
        GameEntity game = gameAccess.requireGame(gameId);
        gameAccess.requireHost(game, accessToken);
        gameSaveService.deleteByGame(gameId);
        deleteBelongings(gameId);
        gameRepository.delete(game);
        log.info("Игра {} удалена вместе со своими сохранениями", game.getName());
    }

    /**
     * Всё, что принадлежит партии, но связано с ней ОДНИМ ПОЛЕМ, а не ссылкой, — п. 3.
     * <p>
     * <b>Почему это приходится делать руками.</b> У летописи, счётчиков механик, флотов,
     * проектов кораблей, боёв, событий и перевозок поле {@code game_id} объявлено простой
     * колонкой, а не связью: выборки внутри хода ходят по идентификаторам, и связь тут была
     * бы лишней загрузкой. Внешние ключи с каскадом им ставит Liquibase — и ставит их
     * только там, где changelog прошёл, то есть в Postgres. На H2, где схему строит
     * Hibernate по сущностям (режим балансового прогона), таких ключей нет НИ ОДНОГО, и
     * удаление партии оставляло в памяти всё её хозяйство.
     * <p>
     * <b>Чем это обернулось.</b> Круг 5 замедлялся по ходу дела: первые партии шли по
     * тридцать секунд, к трёхсотой — по шестьдесят, а куча к тому времени держала МИЛЛИОНЫ
     * строк H2 (гистограмма: 5,1 млн ValueUuid, 3,3 млн Sparse) при восьми живых партиях.
     * Каждая партия оставляла после себя четыре тысячи строк одной только летописи
     * (восемь империй на пятьсот ходов), и к концу прогона их набиралось два миллиона.
     * Постгресу это было не видно: там каскад отрабатывал.
     * <p>
     * Порядок удаления — от внуков к детям: у {@code fleet_ship}, {@code battle_ship} и
     * {@code ship_design_component} свои ключи на флот, бой и проект, и в Postgres они
     * настоящие.
     */
    private void deleteBelongings(UUID gameId) {
        for (String statement : List.of(
                "delete from fleet_ship where fleet_id in (select id from fleet where game_id = ?1)",
                "delete from battle_ship where battle_id in "
                        + "(select id from space_battle where game_id = ?1)",
                "delete from ship_design_component where design_id in "
                        + "(select id from ship_design where game_id = ?1)",
                "delete from space_battle where game_id = ?1",
                "delete from fleet_encounter where game_id = ?1",
                "delete from fleet where game_id = ?1",
                "delete from ship_design where game_id = ?1",
                "delete from population_transfer where game_id = ?1",
                "delete from player_event where game_id = ?1",
                "delete from empire_activity where game_id = ?1",
                "delete from empire_history where game_id = ?1",
                "delete from turn_report where game_id = ?1",
                "delete from council_vote where game_id = ?1",
                "delete from turn_action where game_id = ?1",
                "delete from diplomacy_proposal where game_id = ?1",
                "delete from game_signal where game_id = ?1",
                "delete from artifact_choice where game_id = ?1",
                // Постройки колоний: у планеты своего списка построек нет (ни одного
                // @OneToMany), и Hibernate за ней их не уносит. С новым ИИ это самая
                // толстая из оставшихся куч — зданий на партию идут тысячи.
                "delete from planet_building where planet_id in (select p.id from planet p "
                        + "join star_system s on s.id = p.star_system_id where s.game_id = ?1)",
                // То же и у игрока: изученное, разведанное, агенты, лидеры и отношения
                // висят на player_id простой колонкой.
                "delete from player_technology where player_id in "
                        + "(select id from player where game_id = ?1)",
                "delete from player_explored_system where player_id in "
                        + "(select id from player where game_id = ?1)",
                "delete from planet_intel where game_id = ?1",
                "delete from game_milestone where game_id = ?1",
                "delete from spectator_frame where game_id = ?1",
                "delete from map_mark where game_id = ?1",
                "delete from turn_undo where game_id = ?1",
                "delete from steward_decision where game_id = ?1",
                "delete from observed_tech where game_id = ?1",
                "delete from monster_intel where game_id = ?1",
                "delete from player_leader where player_id in "
                        + "(select id from player where game_id = ?1)",
                "delete from spy where owner_player_id in "
                        + "(select id from player where game_id = ?1)",
                "delete from diplomacy_relation where player_id in "
                        + "(select id from player where game_id = ?1)")) {
            entityManager.createNativeQuery(statement).setParameter(1, gameId).executeUpdate();
        }
    }

    /**
     * Убрать партию распоряжением администратора — без пропуска её хозяина.
     * <p>
     * Нужно ровно для одного: БРОШЕННЫХ партий. Прогон, оборвавшийся посередине (упал
     * скрипт, остановили сервер), оставляет партии, пропуск хозяина которых не знает уже
     * никто, — и убрать их нечем, хотя копятся они быстро: в базе однажды набралось восемь с
     * половиной сотен. Это хозяйство сервера, а не своя партия, поэтому право здесь такое
     * же, как у удаления сохранений: у брошенной партии хозяина нет, и решает администратор.
     */
    @Transactional
    public void deleteByAdmin(UUID gameId) {
        GameEntity game = gameAccess.requireGame(gameId);
        gameSaveService.deleteByGame(gameId);
        deleteBelongings(gameId);
        gameRepository.delete(game);
        log.info("Игра {} убрана администратором вместе со своими сохранениями", game.getName());
    }

    private CreateGameResponse joinedResponse(GameEntity game, PlayerEntity player) {
        GameDetailsDto details = details(game);
        return new CreateGameResponse(details.game(), details.players(), credentials(game, player));
    }

    /**
     * «Играть дальше» после победы — field-backlog, пункт 131 (решение хозяина проекта 08.10.2026):
     * победа удержанием Тессара, советом или итогом по могуществу обрывает партию посреди замысла,
     * и её можно продолжить без новых побед. После покорения играть не с кем — там продолжения нет.
     * Победа остаётся записанной: история, звания и сцена конца её уже знают.
     */
    @Transactional
    public GameDetailsDto playOn(UUID gameId, String accessToken) {
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new com.sddnw.server.web.error.NotFoundException("game.notFound"));
        PlayerEntity player = gameAccess.requirePlayer(game, accessToken);
        if (player.getPlayerType() != PlayerType.HUMAN) {
            throw new ConflictException("game.playOnNotYours");
        }
        boolean allowed = game.getStatus() == GameStatus.FINISHED && game.getVictoryKind() != null
                && java.util.Set.of(com.sddnw.server.domain.enums.VictoryKind.WARDENHOLD,
                        com.sddnw.server.domain.enums.VictoryKind.COUNCIL,
                        com.sddnw.server.domain.enums.VictoryKind.MIGHT).contains(game.getVictoryKind());
        if (!allowed) {
            throw new ConflictException("game.playOnNotAllowed");
        }
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setPlayedOn(Boolean.TRUE);
        game.setCouncilOpen(Boolean.FALSE);
        game.setTurnStartedAt(OffsetDateTime.now());
        gameRepository.save(game);
        log.info("Партию {} продолжили после победы ({})", game.getName(), game.getVictoryKind());
        return details(game);
    }

    private GameDetailsDto details(GameEntity game) {
        return details(game, playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId()));
    }

    /** Состав уже загружен: конец хода читает игроков один раз и переиспользует список. */
    private GameDetailsDto details(GameEntity game, List<PlayerEntity> players) {
        return gameMapper.toDetails(game, players, playerRoster.raceNames());
    }

    private PlayerCredentialsDto credentials(GameEntity game, PlayerEntity player) {
        return new PlayerCredentialsDto(
                game.getId(),
                player.getId(),
                player.getAccessToken(),
                player.getId().equals(game.getHostPlayerId()));
    }

    private String gameName(CreateGameRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            // Название хранится в базе и видно всем участникам, у каждого свой язык, — поэтому
            // без слова на одном из них: по-английски, как имена империй ИИ и проектов.
            return request.playerName() + "'s game";
        }
        return request.name();
    }
}
