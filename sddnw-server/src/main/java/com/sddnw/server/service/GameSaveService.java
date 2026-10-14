package com.sddnw.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.FleetShipEntity;
import com.sddnw.server.domain.entity.ShipDesignComponentEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import com.sddnw.server.domain.entity.GameSaveEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.PopulationJobs;
import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import com.sddnw.server.domain.entity.PlayerTechnologyEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.BannerColor;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.save.GameSnapshot;
import com.sddnw.server.domain.entity.PlanetBuildingEntity;
import com.sddnw.server.domain.save.PlanetBuildingSnapshot;
import com.sddnw.server.domain.save.PlanetSnapshot;
import com.sddnw.server.domain.save.PlayerSnapshot;
import com.sddnw.server.domain.save.DiplomacyRelationSnapshot;
import com.sddnw.server.domain.save.FleetShipSnapshot;
import com.sddnw.server.domain.save.ShipDesignComponentSnapshot;
import com.sddnw.server.domain.save.ShipDesignSnapshot;
import com.sddnw.server.domain.enums.ShipMission;
import com.sddnw.server.domain.enums.ShipRole;
import com.sddnw.server.domain.save.FleetSnapshot;
import com.sddnw.server.domain.save.PopulationTransferSnapshot;
import com.sddnw.server.domain.save.EmpireHistorySnapshot;
import com.sddnw.server.domain.save.PlayerTechnologySnapshot;
import com.sddnw.server.domain.save.SpySnapshot;
import com.sddnw.server.domain.save.PlanetIntelSnapshot;
import com.sddnw.server.domain.entity.PlanetIntelEntity;
import com.sddnw.server.domain.entity.PlayerExploredSystemEntity;
import java.util.LinkedHashSet;
import java.util.Set;
import com.sddnw.server.domain.entity.DiplomacyRelationEntity;
import com.sddnw.server.domain.entity.SpyEntity;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.SpyRepository;
import com.sddnw.server.domain.save.StarSystemSnapshot;
import com.sddnw.server.dto.GameSaveDto;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.domain.entity.PopulationTransferEntity;
import com.sddnw.server.repository.FleetRepository;
import com.sddnw.server.repository.FleetShipRepository;
import com.sddnw.server.repository.ShipDesignComponentRepository;
import com.sddnw.server.repository.ShipDesignRepository;
import com.sddnw.server.repository.PopulationTransferRepository;
import com.sddnw.server.repository.GameSaveRepository;
import com.sddnw.server.repository.GameSaveSummary;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.PlanetBuildingRepository;
import com.sddnw.server.repository.EmpireHistoryRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import com.sddnw.server.repository.StarSystemRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Сохранение и загрузка партий — состояние игры целиком лежит в таблице {@code game_save}.
 * <p>
 * Слепок снимается на конец завершённого хода: автоматически, когда игрок заканчивает ход,
 * и по команде «Игра» → «Сохранить» — тогда в слепок идёт состояние последнего
 * завершённого хода, потому что внутри текущего хода состояние ещё не менялось.
 * <p>
 * Автосохранение на партию одно: запись переписывается каждый ход, поэтому список загрузки
 * не растёт от игры, а в самом списке автосохранения идут первыми.
 * <p>
 * Загрузка не трогает исходную партию: из слепка создаётся новая игра со своими
 * идентификаторами и пропусками игроков, поэтому одно сохранение можно загружать
 * сколько угодно раз, и удаление исходной игры сохранению не мешает.
 */
@Service
public class GameSaveService {

    /**
     * Сколько сохранений отдаётся списком: диалог загрузки показывает последние, а всего
     * их в базе со временем накапливаются сотни.
     */
    private static final int SAVES_PAGE = 50;

    private static final Logger log = LoggerFactory.getLogger(GameSaveService.class);

    private final GameSaveRepository gameSaveRepository;
    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final StarSystemRepository starSystemRepository;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final EmpireHistoryRepository empireHistoryRepository;
    private final PlanetBuildingRepository planetBuildingRepository;
    private final SpyRepository spyRepository;
    private final com.sddnw.server.repository.PlayerExploredSystemRepository exploredRepository;
    private final com.sddnw.server.repository.PlanetIntelRepository intelRepository;
    private final com.sddnw.server.repository.ObservedTechRepository observedRepository;
    private final com.sddnw.server.repository.MonsterIntelRepository monsterIntelRepository;
    private final com.sddnw.server.repository.GameMilestoneRepository milestoneRepository;
    /** Предложения, ждущие ответа человека (field-backlog, пункт 182). */
    private final com.sddnw.server.repository.DiplomacyProposalRepository proposalRepository;
    /** Лидеры — в слепок (трек техдолга, пункт 28). */
    private final com.sddnw.server.repository.PlayerLeaderRepository leaderRepository;
    private final DiplomacyRelationRepository relationRepository;
    private final FleetRepository fleetRepository;
    private final FleetShipRepository fleetShipRepository;
    private final ShipDesignRepository shipDesignRepository;
    private final ShipDesignComponentRepository shipDesignComponentRepository;
    private final ShipDesignService shipDesignService;
    private final PopulationTransferRepository transferRepository;
    private final PlayerRoster playerRoster;
    private final ObjectMapper objectMapper;

    /** Копии наборов справочников партий — пункт 42; полем, чтобы не растить конструктор дальше. */
    @org.springframework.beans.factory.annotation.Autowired
    private com.sddnw.server.repository.GameModRepository gameModRepository;

    public GameSaveService(GameSaveRepository gameSaveRepository,
                           GameRepository gameRepository,
                           PlayerRepository playerRepository,
                           StarSystemRepository starSystemRepository,
                           PlayerTechnologyRepository playerTechnologyRepository,
                           EmpireHistoryRepository empireHistoryRepository,
                           PlanetBuildingRepository planetBuildingRepository,
                           SpyRepository spyRepository,
                           com.sddnw.server.repository.PlayerExploredSystemRepository exploredRepository,
                           com.sddnw.server.repository.PlanetIntelRepository intelRepository,
                           com.sddnw.server.repository.ObservedTechRepository observedRepository,
                           com.sddnw.server.repository.MonsterIntelRepository monsterIntelRepository,
                           com.sddnw.server.repository.GameMilestoneRepository milestoneRepository,
                           com.sddnw.server.repository.DiplomacyProposalRepository proposalRepository,
                           DiplomacyRelationRepository relationRepository,
                           FleetRepository fleetRepository,
                           FleetShipRepository fleetShipRepository,
                           ShipDesignRepository shipDesignRepository,
                           ShipDesignComponentRepository shipDesignComponentRepository,
                           ShipDesignService shipDesignService,
                           PopulationTransferRepository transferRepository,
                           PlayerRoster playerRoster,
                           ObjectMapper objectMapper,
                           com.sddnw.server.repository.PlayerLeaderRepository leaderRepository) {
        this.leaderRepository = leaderRepository;
        this.gameSaveRepository = gameSaveRepository;
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.starSystemRepository = starSystemRepository;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.empireHistoryRepository = empireHistoryRepository;
        this.planetBuildingRepository = planetBuildingRepository;
        this.spyRepository = spyRepository;
        this.exploredRepository = exploredRepository;
        this.intelRepository = intelRepository;
        this.observedRepository = observedRepository;
        this.monsterIntelRepository = monsterIntelRepository;
        this.milestoneRepository = milestoneRepository;
        this.proposalRepository = proposalRepository;
        this.relationRepository = relationRepository;
        this.fleetRepository = fleetRepository;
        this.fleetShipRepository = fleetShipRepository;
        this.shipDesignRepository = shipDesignRepository;
        this.shipDesignComponentRepository = shipDesignComponentRepository;
        this.shipDesignService = shipDesignService;
        this.transferRepository = transferRepository;
        this.playerRoster = playerRoster;
        this.objectMapper = objectMapper;
    }

    /**
     * Список сохранений для диалога загрузки: свежие сверху.
     * <p>
     * Читается проекцией и страницей: сам слепок партии весит сотню килобайт, а списку
     * нужны только название, ход и время. Раньше на один клик по «Загрузить» поднимались
     * в память все слепки разом.
     */
    @Transactional(readOnly = true)
    public List<GameSaveDto> list(UUID accountId) {
        return gameSaveRepository.findAllByOrderBySavedAtDesc(PageRequest.of(0, SAVES_PAGE)).stream()
                .map(save -> toDto(save, accountId))
                .toList();
    }

    /**
     * Кто играл в той партии: учётные записи людей одной строкой.
     * <p>
     * Пустая строка у партии без людей (балансовый прогон, партия наблюдателя) — это не то
     * же самое, что {@code null} у слепка, снятого до миграции 077: там хозяева НЕ ЗАПИСАНЫ
     * и правило к слепку не применяется, а здесь их нет вовсе, и поднимать такую партию
     * некому, кроме администратора.
     */
    private String humanAccounts(List<PlayerEntity> players) {
        return players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .map(PlayerEntity::getAccountId)
                .filter(Objects::nonNull)
                .map(UUID::toString)
                .collect(Collectors.joining(","));
    }

    /** Дозволено ли этой записи поднимать слепок — п. 3. */
    private boolean loadableBy(String humanAccounts, UUID accountId) {
        // Слепок, снятый до миграции 077: хозяев у него не записано, и запрещать нечем.
        if (humanAccounts == null) {
            return true;
        }
        // Партия без людей: поднимать её некому — ни один человек в ней не играл.
        if (humanAccounts.isEmpty()) {
            return false;
        }
        return accountId != null && List.of(humanAccounts.split(",")).contains(accountId.toString());
    }

    /** Сохранения удалённой партии: без партии слепок никому не нужен. */
    @Transactional
    public void deleteByGame(UUID gameId) {
        gameSaveRepository.deleteByGameId(gameId);
    }

    /**
     * Снимает слепок партии на конец завершённого хода.
     *
     * @param completedTurn номер завершённого хода; 0 — партия ещё не сыграла ни одного
     */
    @Transactional
    public GameSaveDto save(GameEntity game, Integer completedTurn) {
        return write(game, completedTurn, Boolean.FALSE);
    }

    /**
     * Автосохранение в конце хода — п. 3.
     * <p>
     * У партии оно ОДНО: прежнее удаляется, новое встаёт на его место. Смысл в том, чтобы
     * вернуться туда, где партию бросили, а не хранить её историю, — иначе за сотню ходов
     * партия оставила бы сотню слепков по сотне килобайт каждый.
     * <p>
     * Снимается ВНЕ транзакции хода (см. {@code GameService.endTurn}): слепок читает всю
     * галактику целиком, а выборка изнутри посчитанного хода заставляет Hibernate сбросить
     * в базу всё, что ход успел изменить, — на этом конец хода однажды дорос до секунды.
     * <p>
     * <b>Транзакция ОБЯЗАТЕЛЬНО своя</b> ({@code REQUIRES_NEW}), и это не перестраховка.
     * Зовут отсюда из {@code afterCommit}, а там синхронизация транзакции ещё жива, хотя
     * сама транзакция уже зафиксирована: обычный {@code REQUIRED} присоединяется к ней и
     * падает на записи — «no transaction is in progress». Проверено: с {@code REQUIRED}
     * автосохранение не появлялось ни разу, а в журнале оставалось ровно это.
     */
    // Снимок одного мгновения (REPEATABLE_READ): слепок пишется в фоне, за ответом игроку
    // (`GameService.autoSaves`), и следующий ход может зафиксироваться посреди чтения галактики —
    // без снимка в слепок легли бы половина одного хода и половина другого.
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public GameSaveDto autoSave(GameEntity game, Integer completedTurn) {
        gameSaveRepository.deleteAll(gameSaveRepository.findByGameIdAndAutoIsTrue(game.getId()));
        return write(game, completedTurn, Boolean.TRUE);
    }

    private GameSaveDto write(GameEntity game, Integer completedTurn, Boolean auto) {
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId());
        List<StarSystemEntity> systems = starSystemRepository
                .findAllByGameIdWithPlanets(game.getId()).stream()
                .sorted(GameOrder.SYSTEMS)
                .toList();
        if (systems.isEmpty()) {
            throw new ConflictException("save.nothingToSave");
        }

        GameSnapshot snapshot = toSnapshot(game, players, systems, completedTurn);

        GameSaveEntity save = new GameSaveEntity();
        save.setGameId(game.getId());
        save.setName(game.getName());
        save.setGalaxySize(game.getGalaxySize().name());
        save.setWidthParsecs(game.getWidthParsecs());
        save.setHeightParsecs(game.getHeightParsecs());
        save.setStarCount(systems.size());
        save.setTurn(completedTurn);
        save.setTotalPlayers(game.getTotalPlayers());
        save.setHumanPlayers((int) players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .count());
        save.setSavedAt(OffsetDateTime.now());
        save.setAuto(auto);
        save.setHumanAccounts(humanAccounts(players));
        save.setState(serialize(snapshot));

        GameSaveEntity saved = gameSaveRepository.saveAndFlush(save);
        log.info("{} партия {} на конец хода {}: {} систем, {} игроков",
                Boolean.TRUE.equals(auto) ? "Автосохранена" : "Сохранена",
                game.getName(), completedTurn, systems.size(), players.size());
        return toDto(saved, null);
    }

    /** Удаление сохранения: слепок самодостаточен, на партию его удаление не влияет. */
    @Transactional
    public void delete(UUID saveId) {
        GameSaveEntity save = gameSaveRepository.findById(saveId)
                .orElseThrow(() -> new NotFoundException("save.notFound", saveId));
        gameSaveRepository.delete(save);
        log.info("Удалено сохранение {}: партия {}, ход {}", saveId, save.getName(), save.getTurn());
    }

    /**
     * Восстанавливает партию из сохранения новой игрой.
     *
     * @return созданная игра вместе с игроками и свежими пропусками
     */
    @Transactional
    public GameEntity restore(UUID saveId, UUID accountId) {
        GameSaveEntity save = gameSaveRepository.findById(saveId)
                .orElseThrow(() -> new NotFoundException("save.notFound", saveId));
        // ПРАВИЛО АВТОСОХРАНЕНИЯ: поднять его могут только те же люди — п. 3.
        //
        // Ограничение стоит на автосохранении и не трогает сделанные руками: те лежат общей
        // кучей и задуманы как общее добро (их видят все и поднимает любой). Автосохранение
        // же не выбирают — его снимает сама игра, каждый ход, за каждую партию на сервере, —
        // и без правила один игрок поднимал бы чужую партию на двоих и играл бы в ней за
        // соседа. Кто «те же», решает учётная запись, а не имя: имена в игре повторяются.
        if (Boolean.TRUE.equals(save.getAuto()) && !loadableBy(save.getHumanAccounts(), accountId)) {
            throw new ForbiddenException("save.notYours");
        }
        GameEntity game = restore(deserialize(save.getState()), Boolean.FALSE, accountId);
        log.info("Загружено сохранение {}: партия {} продолжается с хода {}",
                saveId, game.getName(), game.getTurn());
        return game;
    }

    /** Формат файла выгруженной партии — пункт 160: узнаётся при подъёме, чужой файл не годится. */
    public static final String FILE_FORMAT = "lord-of-thessar-save";
    public static final Integer FILE_VERSION = 1;

    /** Автосохранение партии — пункт 160: «партия сохранена на ходу N» в меню «Игра». */
    @Transactional(readOnly = true)
    public java.util.Optional<GameSaveDto> autosaveOf(UUID gameId, UUID accountId) {
        return gameSaveRepository.findByGameIdAndAutoIsTrue(gameId).stream()
                .findFirst()
                .map(save -> toDto(save, accountId));
    }

    /**
     * Слепок файлом — field-backlog, пункт 160 (решение хозяина проекта 07.10.2026): ответ на
     * «а если сервер закроется». Отдаётся тому, кто вправе этот слепок поднять, — то же правило,
     * что у загрузки; сам слепок лежит в файле как есть, а заголовок называет формат и версию.
     */
    @Transactional(readOnly = true)
    public com.fasterxml.jackson.databind.node.ObjectNode exportFile(UUID saveId, UUID accountId) {
        GameSaveEntity save = gameSaveRepository.findById(saveId)
                .orElseThrow(() -> new NotFoundException("save.notFound", saveId));
        if (Boolean.TRUE.equals(save.getAuto()) && !loadableBy(save.getHumanAccounts(), accountId)) {
            throw new ForbiddenException("save.notYours");
        }
        com.fasterxml.jackson.databind.node.ObjectNode file = objectMapper.createObjectNode();
        file.put("format", FILE_FORMAT);
        file.put("version", FILE_VERSION);
        file.put("name", save.getName());
        file.put("turn", save.getTurn());
        file.put("savedAt", save.getSavedAt().toString());
        try {
            file.set("state", objectMapper.readTree(save.getState()));
        } catch (JsonProcessingException e) {
            throw new ConflictException("save.corrupted");
        }
        return file;
    }

    /**
     * Партия из файла — пункт 160: только ОДИНОЧНАЯ (в слепке ровно один человек), и поднимает
     * её тот, кто принёс файл, — его империей. Партия помечена {@code imported}: файл мог быть
     * поправлен руками, и её итог не идёт в историю и сравнения. В список сохранений файл не
     * ложится — тот лежит общей кучей, а файл принадлежит одному игроку.
     */
    @Transactional
    public GameEntity importFile(String content, UUID accountId) {
        GameSnapshot snapshot;
        try {
            com.fasterxml.jackson.databind.JsonNode file = objectMapper.readTree(content);
            if (file == null || !FILE_FORMAT.equals(file.path("format").asText())
                    || !file.hasNonNull("state")) {
                throw new ConflictException("save.fileUnknown");
            }
            snapshot = objectMapper.treeToValue(file.get("state"), GameSnapshot.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ConflictException("save.fileUnknown");
        }
        if (snapshot == null || snapshot.players() == null || snapshot.systems() == null) {
            throw new ConflictException("save.fileUnknown");
        }
        long humans = snapshot.players().stream()
                .filter(player -> player.playerType() == PlayerType.HUMAN)
                .count();
        if (humans != 1) {
            throw new ConflictException("save.fileSingleOnly");
        }
        GameEntity game = restore(snapshot, Boolean.TRUE, accountId);
        log.info("Поднята партия {} из файла: продолжается с хода {}", game.getName(), game.getTurn());
        return game;
    }

    /**
     * Поднимает партию из слепка новой игрой. Партия из файла ({@code imported}) отдаёт своего
     * единственного человека тому, кто файл принёс: на другом сервере или другой записи прежнего
     * хозяина нет.
     */
    private GameEntity restore(GameSnapshot snapshot, Boolean imported, UUID accountId) {

        GameEntity game = new GameEntity();
        game.setName(snapshot.name());
        game.setGalaxySize(snapshot.galaxySize());
        game.setWidthParsecs(snapshot.widthParsecs());
        game.setHeightParsecs(snapshot.heightParsecs());
        game.setStarCount(snapshot.starCount());
        game.setStatus(GameStatus.IN_PROGRESS);
        // Партия продолжается с хода, следующего за завершённым.
        game.setTurn(snapshot.completedTurn() + 1);
        game.setTotalPlayers(snapshot.totalPlayers());
        game.setMaxHumanPlayers(snapshot.maxHumanPlayers());
        game.setSeed(snapshot.seed());
        // Слепки, снятые до случайных событий, о них не знают: такая партия загружается
        // с событиями — в MOO II они включены, пока их не выключили (п. 11.1).
        game.setGalacticEvents(snapshot.galacticEvents() == null
                || Boolean.TRUE.equals(snapshot.galacticEvents()));
        // То же и с советом: слепок прошлой версии о нём не знает, и партия поднимается
        // с советом — как в оригинале (п. 3).
        game.setCouncil(snapshot.council() == null || Boolean.TRUE.equals(snapshot.council()));
        game.setCouncilRefused(Boolean.TRUE.equals(snapshot.councilRefused()));
        game.setAiActive(snapshot.aiActive() == null || Boolean.TRUE.equals(snapshot.aiActive()));
        game.setAdminWatched(Boolean.TRUE.equals(snapshot.adminWatched()));
        game.setImported(Boolean.TRUE.equals(imported) || Boolean.TRUE.equals(snapshot.imported()));
        game.setTutorial(Boolean.TRUE.equals(snapshot.tutorial()));
        game.setSpectators(Boolean.TRUE.equals(snapshot.spectators()));
        game.setGalaxyFeature(snapshot.galaxyFeature());
        game.setWeeklyWeek(snapshot.weeklyWeek());
        game.setAiSkill(snapshot.aiSkill());
        game.setMightTurn(snapshot.mightTurn());
        game.setPeacefulAi(Boolean.TRUE.equals(snapshot.peacefulAi()));
        game.setPlayedOn(Boolean.TRUE.equals(snapshot.playedOn()));
        game.setCreatedAt(OffsetDateTime.now());
        game.setStartedAt(OffsetDateTime.now());
        // Поднятая партия начинает ход заново: срок, истёкший в слепке, отнял бы у
        // вернувшихся ход, которого они не видели (backlog-promo, пункт 11).
        game.setTurnStartedAt(game.getStartedAt());
        // Набор справочников (пункт 42): поднятая партия играет теми же правилами, одиночной.
        if (snapshot.mod() != null) {
            game.setModded(Boolean.TRUE);
            game.setMaxHumanPlayers(1);
            game.setInviteOnly(Boolean.TRUE);
        }
        gameRepository.saveAndFlush(game);
        if (snapshot.mod() != null) {
            com.sddnw.server.domain.entity.GameModEntity copy = new com.sddnw.server.domain.entity.GameModEntity();
            copy.setGameId(game.getId());
            copy.setName(snapshot.mod().name());
            copy.setBuildings(snapshot.mod().buildings());
            copy.setShips(snapshot.mod().ships());
            copy.setTechnologies(snapshot.mod().technologies());
            copy.setLeaders(snapshot.mod().leaders());
            gameModRepository.save(copy);
        }

        // Игроков и системы сохраняем своими репозиториями, а не через коллекции игры:
        // сохранение уже существующей игры идёт через merge, а он раздаёт идентификаторы
        // копиям объектов, и ссылки на родные миры собрать было бы не из чего.
        List<PlayerEntity> players = new ArrayList<>(snapshot.players().size());
        for (PlayerSnapshot snapshotPlayer : snapshot.players()) {
            PlayerEntity player = restorePlayer(snapshotPlayer);
            player.setGame(game);
            // Слепок мог быть снят до появления характеров: тогда соседу выдаётся свой,
            // тем же жребием, что и в начале партии, — иначе он не вёл бы дипломатии.
            if (player.getPlayerType() == PlayerType.AI && player.getAiPersonality() == null) {
                playerRoster.giveCharacter(player, game, player.getSlot());
            }
            if (Boolean.TRUE.equals(imported) && player.getPlayerType() == PlayerType.HUMAN) {
                player.setAccountId(accountId);
            }
            players.add(player);
        }
        playerRepository.saveAllAndFlush(players);
        restoreTechnologies(players, snapshot);
        restoreHistory(game, players, snapshot);

        Map<Integer, UUID> playerIdBySlot = new HashMap<>();
        for (PlayerEntity player : players) {
            playerIdBySlot.put(player.getSlot(), player.getId());
        }
        // Наставник ученика (пункт 184) хранится местом: идентификаторы игроков у поднятой партии новые.
        for (PlayerSnapshot snapshotPlayer : snapshot.players()) {
            if (snapshotPlayer.mentorSlot() != null) {
                UUID mentor = playerIdBySlot.get(snapshotPlayer.mentorSlot());
                players.stream().filter(player -> player.getSlot().equals(snapshotPlayer.slot()))
                        .findFirst().ifPresent(player -> player.setMentorPlayerId(mentor));
            }
        }

        List<StarSystemEntity> systems = new ArrayList<>(snapshot.systems().size());
        for (StarSystemSnapshot snapshotSystem : snapshot.systems()) {
            StarSystemEntity system = restoreSystem(snapshotSystem, playerIdBySlot);
            system.setGame(game);
            systems.add(system);
        }
        starSystemRepository.saveAllAndFlush(systems);

        restoreBuildings(snapshot, systems);
        restoreSpies(snapshot, playerIdBySlot);
        restoreFog(snapshot, playerIdBySlot, systems);
        restoreRelations(snapshot, playerIdBySlot);
        List<ShipDesignEntity> designs = restoreShipDesigns(snapshot, game, players, playerIdBySlot);
        Map<Integer, UUID> fleetIdByIndex = restoreFleets(snapshot, playerIdBySlot, systems, designs);
        restoreLeaders(snapshot, playerIdBySlot, systems, fleetIdByIndex);
        restoreTransfers(snapshot, playerIdBySlot, systems);
        linkHomeSystems(players, snapshot, systems);
        playerRepository.saveAllAndFlush(players);

        game.setHostPlayerId(playerIdBySlot.get(snapshot.hostSlot()));
        // Счёт удержания Wardenhold (п. 3) переживает загрузку: держатель назван местом, и
        // без него поднятая партия начинала бы двадцать ходов заново.
        game.setWardenholdVictory(Boolean.TRUE.equals(snapshot.wardenholdVictory()));
        game.setMightVictory(Boolean.TRUE.equals(snapshot.mightVictory()));
        game.setTurnSeconds(snapshot.turnSeconds());
        if (snapshot.wardenholdHolderSlot() != null) {
            game.setWardenholdHolderPlayerId(playerIdBySlot.get(snapshot.wardenholdHolderSlot()));
            game.setWardenholdSinceTurn(snapshot.wardenholdSinceTurn());
        }
        gameRepository.saveAndFlush(game);
        return game;
    }

    /**
     * Туман войны — п. 15: разведанные системы и память о чужих колониях. Слепки, снятые
     * до него, их не содержат — тогда игрок знает лишь свои системы, как и прежде.
     */
    private void restoreFog(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot,
                            List<StarSystemEntity> systems) {
        List<PlayerExploredSystemEntity> explored = new ArrayList<>();
        List<PlanetIntelEntity> intel = new ArrayList<>();
        UUID gameId = systems.isEmpty() ? null : systems.get(0).getGame().getId();
        for (PlayerSnapshot player : snapshot.players()) {
            UUID playerId = playerIdBySlot.get(player.slot());
            if (playerId == null) {
                continue;
            }
            for (Integer index : player.exploredSystems() == null ? List.<Integer>of() : player.exploredSystems()) {
                if (index == null || index < 0 || index >= systems.size()) {
                    continue;
                }
                PlayerExploredSystemEntity row = new PlayerExploredSystemEntity();
                row.setPlayerId(playerId);
                row.setStarSystemId(systems.get(index).getId());
                row.setExploredTurn(snapshot.completedTurn());
                explored.add(row);
            }
            for (PlanetIntelSnapshot source : player.intel() == null ? List.<PlanetIntelSnapshot>of() : player.intel()) {
                UUID planet = planetAt(systems, source.systemIndex(), source.planetIndex());
                UUID owner = playerIdBySlot.get(source.ownerSlot());
                if (planet == null || owner == null || gameId == null) {
                    continue;
                }
                PlanetIntelEntity row = new PlanetIntelEntity();
                row.setGameId(gameId);
                row.setPlayerId(playerId);
                row.setPlanetId(planet);
                row.setOwnerPlayerId(owner);
                row.setPopulation(source.population() == null ? 0 : source.population());
                row.setDefences(source.defences() == null ? "" : source.defences());
                row.setSeenTurn(source.seenTurn() == null ? 0 : source.seenTurn());
                intel.add(row);
            }
        }
        exploredRepository.saveAll(explored);
        intelRepository.saveAll(intel);

        List<com.sddnw.server.domain.entity.ObservedTechEntity> observed = new ArrayList<>();
        List<com.sddnw.server.domain.entity.MonsterIntelEntity> monsters = new ArrayList<>();
        for (PlayerSnapshot player : snapshot.players()) {
            UUID playerId = playerIdBySlot.get(player.slot());
            if (playerId == null || gameId == null) {
                continue;
            }
            for (com.sddnw.server.domain.save.ObservedTechSnapshot source
                    : player.observedTechs() == null ? List.<com.sddnw.server.domain.save.ObservedTechSnapshot>of()
                    : player.observedTechs()) {
                UUID other = playerIdBySlot.get(source.otherSlot());
                if (other == null) {
                    continue;
                }
                com.sddnw.server.domain.entity.ObservedTechEntity row = new com.sddnw.server.domain.entity.ObservedTechEntity();
                row.setGameId(gameId);
                row.setPlayerId(playerId);
                row.setOtherPlayerId(other);
                row.setTechCode(source.techCode());
                row.setObservedTurn(source.observedTurn() == null ? 0 : source.observedTurn());
                observed.add(row);
            }
            for (com.sddnw.server.domain.save.MonsterIntelSnapshot source
                    : player.monsters() == null ? List.<com.sddnw.server.domain.save.MonsterIntelSnapshot>of()
                    : player.monsters()) {
                if (source.systemIndex() == null || source.systemIndex() < 0 || source.systemIndex() >= systems.size()) {
                    continue;
                }
                com.sddnw.server.domain.entity.MonsterIntelEntity row = new com.sddnw.server.domain.entity.MonsterIntelEntity();
                row.setGameId(gameId);
                row.setPlayerId(playerId);
                row.setStarSystemId(systems.get(source.systemIndex()).getId());
                row.setMonster(source.monster());
                row.setStrength(source.strength() == null ? 0 : source.strength());
                row.setSeenTurn(source.seenTurn() == null ? 0 : source.seenTurn());
                monsters.add(row);
            }
        }
        observedRepository.saveAll(observed);
        monsterIntelRepository.saveAll(monsters);

        // Предложения, ждущие ответа человека (пункт 182); у старых слепков их нет.
        List<com.sddnw.server.domain.entity.DiplomacyProposalEntity> proposals = new ArrayList<>();
        for (PlayerSnapshot player : snapshot.players()) {
            UUID from = playerIdBySlot.get(player.slot());
            if (from == null || gameId == null || player.proposals() == null) {
                continue;
            }
            for (com.sddnw.server.domain.save.ProposalSnapshot source : player.proposals()) {
                UUID to = playerIdBySlot.get(source.toSlot());
                if (to == null) {
                    continue;
                }
                com.sddnw.server.domain.entity.DiplomacyProposalEntity row =
                        new com.sddnw.server.domain.entity.DiplomacyProposalEntity();
                row.setGameId(gameId);
                row.setFromPlayerId(from);
                row.setToPlayerId(to);
                row.setAction(source.action());
                row.setTreaty(source.treaty());
                row.setOfferedTech(source.offeredTech());
                row.setRequestedTech(source.requestedTech());
                row.setTurn(source.turn());
                row.setAccepted(source.accepted());
                proposals.add(row);
            }
        }
        proposalRepository.saveAll(proposals);

        // Летопись главных моментов (пункт 34): поднятая партия рассказывает себя дальше, а не
        // с середины. У слепков, снятых до летописи, её нет — партия начинает её с этого хода.
        List<com.sddnw.server.domain.entity.GameMilestoneEntity> milestones = new ArrayList<>();
        for (PlayerSnapshot player : snapshot.players()) {
            UUID playerId = playerIdBySlot.get(player.slot());
            if (playerId == null || gameId == null || player.milestones() == null) {
                continue;
            }
            for (com.sddnw.server.domain.save.MilestoneSnapshot source : player.milestones()) {
                com.sddnw.server.domain.entity.GameMilestoneEntity row = new com.sddnw.server.domain.entity.GameMilestoneEntity();
                row.setGameId(gameId);
                row.setPlayerId(playerId);
                row.setTurn(source.turn() == null ? 0 : source.turn());
                row.setKind(source.kind());
                row.setMessageKey(source.key());
                row.setArgs(source.args() == null ? List.of() : source.args());
                row.setPeak(source.peak() == null ? Boolean.TRUE : source.peak());
                milestones.add(row);
            }
        }
        milestoneRepository.saveAll(milestones);
    }

    /**
     * Шпионы игроков — п. 13. Заводятся после того, как игроки получили идентификаторы:
     * агент ссылается и на владельца, и на империю, к которой отправлен.
     */
    private void restoreSpies(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot) {
        List<SpyEntity> spies = new ArrayList<>();
        for (PlayerSnapshot player : snapshot.players()) {
            // Слепки, снятые до появления заданий, шпионов поимённо не содержат.
            if (player.agents() == null) {
                continue;
            }
            for (SpySnapshot agent : player.agents()) {
                SpyEntity spy = new SpyEntity();
                spy.setOwnerPlayerId(playerIdBySlot.get(player.slot()));
                spy.setTargetPlayerId(agent.targetSlot() == null ? null : playerIdBySlot.get(agent.targetSlot()));
                spy.setMission(agent.mission());
                spy.setPoints(agent.points() == null ? 0 : agent.points());
                spy.setCreatedTurn(agent.createdTurn() == null ? 0 : agent.createdTurn());
                spies.add(spy);
            }
        }
        spyRepository.saveAll(spies);
    }

    /**
     * Проекты кораблей — п. 8: владелец назван слотом, порядок в списке слепка и есть
     * ссылка, по которой состав флота находит свой проект.
     * <p>
     * Слепок, снятый до появления дизайна, проектов не содержит: каждой империи заводится
     * стартовый проект, и загруженные корабли достаются ему. Иначе флот остался бы без
     * состава, а безымянных кораблей игра больше не знает.
     *
     * @return проекты в том же порядке, в каком они лежат в слепке
     */
    private List<ShipDesignEntity> restoreShipDesigns(GameSnapshot snapshot,
                                                      GameEntity game,
                                                      List<PlayerEntity> players,
                                                      Map<Integer, UUID> playerIdBySlot) {
        if (snapshot.shipDesigns() == null) {
            players.forEach(player ->
                    shipDesignService.ensureAutoDesigns(game.getId(), player, game.getTurn()));
            return List.of();
        }

        List<ShipDesignEntity> restored = new ArrayList<>(snapshot.shipDesigns().size());
        // Источник каждого восстановленного проекта — парой, а не номером: проект без
        // владельца пропускается, и сверка по номеру отдавала его состав СЛЕДУЮЩЕМУ проекту.
        List<ShipDesignSnapshot> sources = new ArrayList<>(snapshot.shipDesigns().size());
        for (ShipDesignSnapshot source : snapshot.shipDesigns()) {
            UUID ownerId = playerIdBySlot.get(source.ownerSlot());
            if (ownerId == null) {
                continue;
            }
            sources.add(source);
            ShipDesignEntity design = new ShipDesignEntity();
            design.setGameId(game.getId());
            design.setOwnerPlayerId(ownerId);
            design.setSlot(source.slot());
            design.setName(source.name());
            design.setHullCode(source.hullCode());
            design.setCreatedTurn(source.createdTurn() == null ? 0 : source.createdTurn());
            design.setObsolete(Boolean.TRUE.equals(source.obsolete()));
            // Слепок мог быть снят до появления гражданских кораблей: тогда роли нет,
            // и проект боевой — других в той партии и не было.
            design.setRole(source.role() == null
                    ? ShipRole.WARSHIP
                    : ShipRole.valueOf(source.role()));
            design.setAuto(Boolean.TRUE.equals(source.auto()));
            design.setMission(source.mission() == null ? null : ShipMission.valueOf(source.mission()));
            design.setTrophy(Boolean.TRUE.equals(source.trophy()));
            design.setBuilt(source.built() == null ? 0 : source.built());
            restored.add(design);
        }
        shipDesignRepository.saveAllAndFlush(restored);

        List<ShipDesignComponentEntity> components = new ArrayList<>();
        for (int index = 0; index < restored.size(); index++) {
            ShipDesignSnapshot source = sources.get(index);
            if (source.components() == null) {
                continue;
            }
            int order = 0;
            for (ShipDesignComponentSnapshot part : source.components()) {
                ShipDesignComponentEntity component = new ShipDesignComponentEntity();
                component.setDesignId(restored.get(index).getId());
                component.setComponentCode(part.componentCode());
                component.setCount(part.count() == null ? 1 : part.count());
                component.setModifications(part.modifications());
                component.setSortOrder(order++);
                components.add(component);
            }
        }
        shipDesignComponentRepository.saveAll(components);
        return restored;
    }

    /**
     * Флоты — п. 8: владелец назван слотом, система — номером в списке слепка.
     * <p>
     * Встречи флотов в слепок не идут: встреча живёт один ход и ждёт решения игрока.
     * Загруженная партия начинается с начала хода, и флоты в ней встретятся заново.
     */
    private Map<Integer, UUID> restoreFleets(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot,
                                             List<StarSystemEntity> systems, List<ShipDesignEntity> designs) {
        // Слепки, снятые до флотов, их не содержат.
        if (snapshot.fleets() == null) {
            return Map.of();
        }
        List<FleetEntity> fleets = new ArrayList<>();
        List<FleetSnapshot> sources = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int at = 0; at < snapshot.fleets().size(); at++) {
            FleetSnapshot source = snapshot.fleets().get(at);
            UUID ownerId = playerIdBySlot.get(source.ownerSlot());
            if (ownerId == null || source.systemIndex() >= systems.size()) {
                continue;
            }
            FleetEntity fleet = new FleetEntity();
            fleet.setGameId(systems.get(source.systemIndex()).getGame().getId());
            fleet.setOwnerPlayerId(ownerId);
            fleet.setStarSystemId(systems.get(source.systemIndex()).getId());
            fleet.setShips(source.ships());
            fleet.setCreatedTurn(source.createdTurn() == null ? 0 : source.createdTurn());
            fleet.setName(source.name());
            fleet.setBattlesWon(source.battlesWon() == null ? 0 : source.battlesWon());
            fleet.setBattlesLost(source.battlesLost() == null ? 0 : source.battlesLost());
            // Флот, застигнутый сохранением в пути, продолжает лететь: маршрут в слепке
            // назван номерами систем, потому что звёзды при загрузке заводятся заново.
            // У слепков, снятых до того, как перелёт стал занимать ходы, маршрута нет.
            if (source.targetIndex() != null && source.arrivalTurn() != null
                    && source.targetIndex() < systems.size()) {
                fleet.setOriginSystemId(systems.get(source.systemIndex()).getId());
                fleet.setTargetSystemId(systems.get(source.targetIndex()).getId());
                fleet.setDepartureTurn(source.departureTurn());
                fleet.setArrivalTurn(source.arrivalTurn());
            }
            fleets.add(fleet);
            sources.add(source);
            indexes.add(at);
        }
        fleetRepository.saveAllAndFlush(fleets);
        restoreComposition(fleets, sources, designs);
        // Номер в списке слепка → новый флот: по нему лидеры находят свои флоты (пункт 28).
        Map<Integer, UUID> byIndex = new HashMap<>();
        for (int i = 0; i < fleets.size(); i++) {
            byIndex.put(indexes.get(i), fleets.get(i).getId());
        }
        return byIndex;
    }

    /**
     * Состав флотов — п. 8. У слепка, снятого до дизайна кораблей, состава нет: все
     * корабли флота приписываются стартовому проекту его империи, и флот перестаёт быть
     * безымянным счётчиком.
     */
    private void restoreComposition(List<FleetEntity> fleets, List<FleetSnapshot> sources,
                                    List<ShipDesignEntity> designs) {
        Map<UUID, ShipDesignEntity> firstDesignByOwner = new HashMap<>();
        for (ShipDesignEntity design : designs) {
            if (!Boolean.TRUE.equals(design.getObsolete())) {
                firstDesignByOwner.putIfAbsent(design.getOwnerPlayerId(), design);
            }
        }

        List<FleetShipEntity> rows = new ArrayList<>();
        for (int index = 0; index < fleets.size(); index++) {
            FleetEntity fleet = fleets.get(index);
            List<FleetShipSnapshot> composition = sources.get(index).composition();
            if (composition == null || composition.isEmpty()) {
                ShipDesignEntity design = firstDesignByOwner.get(fleet.getOwnerPlayerId());
                if (design == null) {
                    continue;
                }
                rows.add(fleetShip(fleet.getId(), design.getId(), fleet.getShips(), 0));
                continue;
            }
            for (FleetShipSnapshot part : composition) {
                if (part.designIndex() == null || part.designIndex() >= designs.size()) {
                    continue;
                }
                FleetShipEntity restored = fleetShip(fleet.getId(), designs.get(part.designIndex()).getId(),
                        part.ships(), part.colonists() == null ? 0 : part.colonists());
                restored.setExperience(part.experience());
                rows.add(restored);
            }
        }
        fleetShipRepository.saveAll(rows);
    }

    private FleetShipEntity fleetShip(UUID fleetId, UUID designId, Integer ships, Integer colonists) {
        FleetShipEntity row = new FleetShipEntity();
        row.setFleetId(fleetId);
        row.setDesignId(designId);
        row.setShips(ships);
        row.setColonists(colonists);
        return row;
    }

    /**
     * Рейсы с жителями — п. 4.1.1: планеты названы номерами системы и орбиты в списках
     * слепка. Загруженная партия продолжает возить их дальше, а грузовики остаются
     * занятыми — правило резерва от загрузки не меняется.
     */
    private void restoreTransfers(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot,
                                  List<StarSystemEntity> systems) {
        // Слепки, снятые до перевозок, рейсов не содержат.
        if (snapshot.transfers() == null) {
            return;
        }
        List<PopulationTransferEntity> transfers = new ArrayList<>();
        for (PopulationTransferSnapshot source : snapshot.transfers()) {
            UUID owner = playerIdBySlot.get(source.ownerSlot());
            UUID from = planetAt(systems, source.fromSystemIndex(), source.fromPlanetIndex());
            UUID to = planetAt(systems, source.toSystemIndex(), source.toPlanetIndex());
            if (owner == null || from == null || to == null) {
                continue;
            }
            PopulationTransferEntity transfer = new PopulationTransferEntity();
            transfer.setGameId(systems.getFirst().getGame().getId());
            transfer.setOwnerPlayerId(owner);
            transfer.setFromPlanetId(from);
            transfer.setToPlanetId(to);
            transfer.setPopulation(source.population());
            transfer.setDepartedTurn(source.departedTurn() == null ? 0 : source.departedTurn());
            transfer.setJob(source.job());
            transfers.add(transfer);
        }
        transferRepository.saveAll(transfers);
    }

    /** Планета по номерам системы и орбиты в списках слепка; {@code null} — номера негодные. */
    private UUID planetAt(List<StarSystemEntity> systems, Integer systemIndex, Integer planetIndex) {
        if (systemIndex == null || planetIndex == null
                || systemIndex < 0 || systemIndex >= systems.size()) {
            return null;
        }
        List<PlanetEntity> planets = systems.get(systemIndex).getPlanets();
        return planetIndex < 0 || planetIndex >= planets.size() ? null : planets.get(planetIndex).getId();
    }

    /** Отношения империй — п. 15: стороны названы слотами, поэтому находятся по ним. */
    private void restoreRelations(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot) {
        // Слепки, снятые до дипломатии, отношений не содержат.
        if (snapshot.relations() == null) {
            return;
        }
        List<DiplomacyRelationEntity> relations = new ArrayList<>();
        for (DiplomacyRelationSnapshot source : snapshot.relations()) {
            DiplomacyRelationEntity relation = new DiplomacyRelationEntity();
            relation.setPlayerId(playerIdBySlot.get(source.playerSlot()));
            relation.setOtherPlayerId(playerIdBySlot.get(source.otherSlot()));
            relation.setStance(source.stance());
            relation.setTrust(source.trust());
            relation.setTreaties(source.treaties() == null ? Set.of() : new LinkedHashSet<>(source.treaties()));
            relation.setMetTurn(source.metTurn());
            relation.setInContact(source.inContact() == null || Boolean.TRUE.equals(source.inContact()));
            relations.add(relation);
        }
        relationRepository.saveAll(relations);
    }

    private GameSnapshot toSnapshot(GameEntity game,
                                    List<PlayerEntity> players,
                                    List<StarSystemEntity> systems,
                                    Integer completedTurn) {
        Map<UUID, Integer> slotByPlayerId = new HashMap<>();
        for (PlayerEntity player : players) {
            slotByPlayerId.put(player.getId(), player.getSlot());
        }
        Map<UUID, Integer> indexBySystemId = new HashMap<>();
        for (int index = 0; index < systems.size(); index++) {
            indexBySystemId.put(systems.get(index).getId(), index);
        }

        // Проекты кораблей нумеруются один раз на слепок: состав флота ссылается на них
        // номером, идентификаторов слепок не хранит — п. 8.
        List<ShipDesignEntity> designs = shipDesignRepository.findAllByGameId(game.getId()).stream()
                .sorted(GameOrder.DESIGNS).toList();
        Map<UUID, Integer> designIndexes = new HashMap<>();
        for (int index = 0; index < designs.size(); index++) {
            designIndexes.put(designs.get(index).getId(), index);
        }

        Map<UUID, List<PlayerTechnologySnapshot>> technologiesByPlayerId = technologySnapshots(players);
        Map<UUID, List<EmpireHistorySnapshot>> historyByPlayerId = historySnapshots(game);
        Map<UUID, List<SpySnapshot>> spiesByPlayerId = spySnapshots(players, slotByPlayerId);
        Map<UUID, List<PlanetBuildingSnapshot>> buildingsByPlanetId = buildingSnapshots(systems);
        // Туман войны — п. 15: что игрок разведал и что помнит о чужих колониях. Без этого
        // поднятая партия открывала бы игроку одни свои системы, а чужие колонии исчезали
        // бы с его карты до нового визита.
        Map<UUID, List<Integer>> exploredByPlayerId = new HashMap<>();
        for (PlayerExploredSystemEntity row : exploredRepository.findAllByPlayerIdIn(slotByPlayerId.keySet())) {
            Integer index = indexBySystemId.get(row.getStarSystemId());
            if (index != null) {
                exploredByPlayerId.computeIfAbsent(row.getPlayerId(), key -> new ArrayList<>()).add(index);
            }
        }
        exploredByPlayerId.values().forEach(list -> list.sort(Integer::compareTo));
        Map<UUID, int[]> placeOfPlanet = new HashMap<>();
        for (StarSystemEntity system : systems) {
            List<PlanetEntity> planets = system.getPlanets();
            for (int index = 0; index < planets.size(); index++) {
                placeOfPlanet.put(planets.get(index).getId(), new int[]{indexBySystemId.get(system.getId()), index});
            }
        }
        Map<UUID, List<PlanetIntelSnapshot>> intelByPlayerId = new HashMap<>();
        for (PlanetIntelEntity row : intelRepository.findAllByGameIdOrderByPlayerIdAscPlanetIdAsc(game.getId())) {
            int[] place = placeOfPlanet.get(row.getPlanetId());
            Integer owner = slotByPlayerId.get(row.getOwnerPlayerId());
            if (place == null || owner == null) {
                continue;
            }
            intelByPlayerId.computeIfAbsent(row.getPlayerId(), key -> new ArrayList<>())
                    .add(new PlanetIntelSnapshot(place[0], place[1], owner, row.getPopulation(),
                            row.getDefences(), row.getSeenTurn()));
        }
        Map<UUID, List<com.sddnw.server.domain.save.ObservedTechSnapshot>> observedByPlayerId = new HashMap<>();
        for (com.sddnw.server.domain.entity.ObservedTechEntity row : observedRepository.findAllByGameId(game.getId())) {
            Integer other = slotByPlayerId.get(row.getOtherPlayerId());
            if (other != null) {
                observedByPlayerId.computeIfAbsent(row.getPlayerId(), key -> new ArrayList<>())
                        .add(new com.sddnw.server.domain.save.ObservedTechSnapshot(other, row.getTechCode(),
                                row.getObservedTurn()));
            }
        }
        observedByPlayerId.values().forEach(list -> list.sort(java.util.Comparator
                .comparing(com.sddnw.server.domain.save.ObservedTechSnapshot::otherSlot)
                .thenComparing(com.sddnw.server.domain.save.ObservedTechSnapshot::techCode)));
        Map<UUID, List<com.sddnw.server.domain.save.MonsterIntelSnapshot>> monstersByPlayerId = new HashMap<>();
        for (com.sddnw.server.domain.entity.MonsterIntelEntity row : monsterIntelRepository.findAllByGameId(game.getId())) {
            Integer index = indexBySystemId.get(row.getStarSystemId());
            if (index != null) {
                monstersByPlayerId.computeIfAbsent(row.getPlayerId(), key -> new ArrayList<>())
                        .add(new com.sddnw.server.domain.save.MonsterIntelSnapshot(index, row.getMonster(),
                                row.getStrength(), row.getSeenTurn()));
            }
        }
        monstersByPlayerId.values().forEach(list -> list.sort(java.util.Comparator
                .comparing(com.sddnw.server.domain.save.MonsterIntelSnapshot::systemIndex)));
        Map<UUID, List<com.sddnw.server.domain.save.MilestoneSnapshot>> milestonesByPlayerId = new HashMap<>();
        for (com.sddnw.server.domain.entity.GameMilestoneEntity row
                : milestoneRepository.findAllByGameIdOrderByTurnAscPlayerIdAscKindAsc(game.getId())) {
            milestonesByPlayerId.computeIfAbsent(row.getPlayerId(), key -> new ArrayList<>())
                    .add(new com.sddnw.server.domain.save.MilestoneSnapshot(row.getTurn(), row.getKind(),
                            row.getMessageKey(), row.getArgs(), row.isPeak()));
        }
        Map<UUID, List<com.sddnw.server.domain.save.ProposalSnapshot>> proposalsByPlayerId = new HashMap<>();
        for (com.sddnw.server.domain.entity.DiplomacyProposalEntity row
                : proposalRepository.findAllByGameIdOrderByTurnAscActionAsc(game.getId())) {
            Integer to = slotByPlayerId.get(row.getToPlayerId());
            if (to != null) {
                proposalsByPlayerId.computeIfAbsent(row.getFromPlayerId(), key -> new ArrayList<>())
                        .add(new com.sddnw.server.domain.save.ProposalSnapshot(to, row.getAction(), row.getTreaty(),
                                row.getOfferedTech(), row.getRequestedTech(), row.getTurn(), row.getAccepted()));
            }
        }
        // Порядок в слепке — по месту планеты: идентификаторы строк в другой партии другие.
        intelByPlayerId.values().forEach(list -> list.sort(java.util.Comparator
                .comparing(PlanetIntelSnapshot::systemIndex).thenComparing(PlanetIntelSnapshot::planetIndex)));
        List<PlayerSnapshot> playerSnapshots = players.stream()
                .map(player -> new PlayerSnapshot(
                        player.getSlot(),
                        player.getName(),
                        player.getPlayerType(),
                        player.getRaceCode(),
                        player.getColor(),
                        indexBySystemId.get(player.getHomeSystemId()),
                        player.getResearchCategoryCode(),
                        player.getResearchLevelOrder(),
                        player.getResearchOptionCode(),
                        player.getResearchPoints(),
                        technologiesByPlayerId.getOrDefault(player.getId(), List.of()),
                        player.getCredits(),
                        player.getRaceName(),
                        player.getRaceTraitCodes(),
                        player.getEspionagePoints(),
                        player.getSpies(),
                        player.getFreighters(),
                        spiesByPlayerId.getOrDefault(player.getId(), List.of()),
                        player.getAiPersonality(),
                        player.getAiObjective(),
                        historyByPlayerId.getOrDefault(player.getId(), List.of()),
                        player.getAccountId(),
                        player.getResearchLastCategoryCode(),
                        exploredByPlayerId.getOrDefault(player.getId(), List.of()),
                        intelByPlayerId.getOrDefault(player.getId(), List.of()),
                        observedByPlayerId.getOrDefault(player.getId(), List.of()),
                        monstersByPlayerId.getOrDefault(player.getId(), List.of()),
                        milestonesByPlayerId.getOrDefault(player.getId(), List.of()),
                        player.getConcededTurn(),
                        player.getLastActionAt(),
                        player.getNote(),
                        proposalsByPlayerId.getOrDefault(player.getId(), List.of()),
                        player.getDryExplorations(),
                        player.getChapter(),
                        player.getMentorPlayerId() == null ? null : slotByPlayerId.get(player.getMentorPlayerId()),
                        player.getAiSkill()))
                .toList();

        List<StarSystemSnapshot> systemSnapshots = systems.stream()
                .map(system -> new StarSystemSnapshot(
                        system.getName(),
                        system.getXParsec(),
                        system.getYParsec(),
                        system.getStarColor(),
                        system.getSpecial(),
                        system.getMonster(),
                        system.getMonsterStrength(),
                        system.getSpecialClaimed(),
                        planetSnapshots(system, slotByPlayerId, buildingsByPlanetId)))
                .toList();

        // Номер флота в списке флотов слепка — по нему лидер находит свой флот (пункт 28).
        Map<UUID, Integer> fleetIndexById = new HashMap<>();
        List<FleetSnapshot> fleetList = fleetSnapshots(game.getId(), slotByPlayerId, indexBySystemId,
                designIndexes, fleetIndexById);
        return new GameSnapshot(
                game.getName(),
                game.getGalaxySize(),
                game.getWidthParsecs(),
                game.getHeightParsecs(),
                game.getStarCount(),
                completedTurn,
                game.getTotalPlayers(),
                game.getMaxHumanPlayers(),
                game.getSeed(),
                slotByPlayerId.get(game.getHostPlayerId()),
                playerSnapshots,
                systemSnapshots,
                game.getGalacticEvents(),
                game.getCouncil(),
                game.getCouncilRefused(),
                relationSnapshots(players, slotByPlayerId),
                fleetList,
                shipDesignSnapshots(designs, slotByPlayerId),
                transferSnapshots(game.getId(), slotByPlayerId, systems, indexBySystemId),
                game.getAiActive(),
                game.getWardenholdVictory(),
                game.getWardenholdHolderPlayerId() == null
                        ? null : slotByPlayerId.get(game.getWardenholdHolderPlayerId()),
                game.getWardenholdSinceTurn(),
                game.getMightVictory(),
                game.getTurnSeconds(),
                game.getAdminWatched(),
                leaderSnapshots(players, slotByPlayerId, indexBySystemId, fleetIndexById),
                game.getImported(),
                game.getTutorial(),
                game.getSpectators(),
                game.getGalaxyFeature(),
                game.getWeeklyWeek(),
                game.getPlayedOn(),
                game.getAiSkill(),
                game.getMightTurn(),
                game.getPeacefulAi(),
                Boolean.TRUE.equals(game.getModded())
                        ? gameModRepository.findById(game.getId())
                                .map(mod -> new com.sddnw.server.domain.save.GameModSnapshot(mod.getName(),
                                        mod.getBuildings(), mod.getShips(), mod.getTechnologies(), mod.getLeaders()))
                                .orElse(null)
                        : null);
    }

    /** Лидеры партии — трек техдолга, пункт 28: ушедшие (DISMISSED) не нужны никому. */
    private List<com.sddnw.server.domain.save.LeaderSnapshot> leaderSnapshots(
            List<PlayerEntity> players, Map<UUID, Integer> slotByPlayerId,
            Map<UUID, Integer> indexBySystemId, Map<UUID, Integer> fleetIndexById) {
        List<com.sddnw.server.domain.save.LeaderSnapshot> list = new ArrayList<>();
        for (com.sddnw.server.domain.entity.PlayerLeaderEntity row
                : leaderRepository.findAllByPlayerIdIn(players.stream().map(PlayerEntity::getId).toList())) {
            Integer slot = slotByPlayerId.get(row.getPlayerId());
            if (slot == null || row.getState() == com.sddnw.server.domain.enums.LeaderState.DISMISSED) {
                continue;
            }
            list.add(new com.sddnw.server.domain.save.LeaderSnapshot(slot, row.getLeaderCode(),
                    row.getKind().name(), row.getState().name(), row.getExperience(), row.getOfferedTurn(),
                    row.getHiredTurn(), row.getHireCost(), row.getSalary(),
                    row.getStarSystemId() == null ? null : indexBySystemId.get(row.getStarSystemId()),
                    row.getFleetId() == null ? null : fleetIndexById.get(row.getFleetId()),
                    row.getArrivesTurn(), row.getBattlesWon(), row.getBattlesLost(),
                    row.getTermStartTurn(), row.getTermStartPopulation()));
        }
        // Порядок — по месту и коду: идентификаторы строк в другой партии другие (GameOrder).
        list.sort(java.util.Comparator.comparing(com.sddnw.server.domain.save.LeaderSnapshot::ownerSlot)
                .thenComparing(com.sddnw.server.domain.save.LeaderSnapshot::code));
        return list;
    }

    /** Лидеры поднятой партии — трек техдолга, пункт 28. */
    private void restoreLeaders(GameSnapshot snapshot, Map<Integer, UUID> playerIdBySlot,
                                List<StarSystemEntity> systems, Map<Integer, UUID> fleetIdByIndex) {
        if (snapshot.leaders() == null) {
            return;
        }
        List<com.sddnw.server.domain.entity.PlayerLeaderEntity> rows = new ArrayList<>();
        for (com.sddnw.server.domain.save.LeaderSnapshot source : snapshot.leaders()) {
            UUID playerId = playerIdBySlot.get(source.ownerSlot());
            if (playerId == null) {
                continue;
            }
            com.sddnw.server.domain.entity.PlayerLeaderEntity row = new com.sddnw.server.domain.entity.PlayerLeaderEntity();
            row.setPlayerId(playerId);
            row.setLeaderCode(source.code());
            row.setKind(com.sddnw.server.domain.enums.LeaderKind.valueOf(source.kind()));
            row.setState(com.sddnw.server.domain.enums.LeaderState.valueOf(source.state()));
            row.setExperience(source.experience() == null ? 0 : source.experience());
            row.setOfferedTurn(source.offeredTurn());
            row.setHiredTurn(source.hiredTurn());
            row.setHireCost(source.hireCost() == null ? 0 : source.hireCost());
            row.setSalary(source.salary() == null ? 0 : source.salary());
            if (source.systemIndex() != null && source.systemIndex() < systems.size()) {
                row.setStarSystemId(systems.get(source.systemIndex()).getId());
            }
            if (source.fleetIndex() != null) {
                row.setFleetId(fleetIdByIndex.get(source.fleetIndex()));
            }
            row.setArrivesTurn(source.arrivesTurn());
            row.setBattlesWon(source.battlesWon() == null ? 0 : source.battlesWon());
            row.setBattlesLost(source.battlesLost() == null ? 0 : source.battlesLost());
            row.setTermStartTurn(source.termStartTurn());
            row.setTermStartPopulation(source.termStartPopulation());
            rows.add(row);
        }
        leaderRepository.saveAll(rows);
    }

    /** Рейсы с жителями — п. 4.1.1: планеты названы номерами системы и орбиты. */
    private List<PopulationTransferSnapshot> transferSnapshots(UUID gameId,
                                                               Map<UUID, Integer> slotByPlayerId,
                                                               List<StarSystemEntity> systems,
                                                               Map<UUID, Integer> indexBySystemId) {
        Map<UUID, int[]> placeByPlanetId = new HashMap<>();
        for (StarSystemEntity system : systems) {
            Integer systemIndex = indexBySystemId.get(system.getId());
            List<PlanetEntity> planets = system.getPlanets();
            for (int index = 0; index < planets.size(); index++) {
                placeByPlanetId.put(planets.get(index).getId(), new int[]{systemIndex, index});
            }
        }

        List<PopulationTransferSnapshot> snapshots = new ArrayList<>();
        for (PopulationTransferEntity transfer : transferRepository.findAllByGameId(gameId)) {
            Integer slot = slotByPlayerId.get(transfer.getOwnerPlayerId());
            int[] from = placeByPlanetId.get(transfer.getFromPlanetId());
            int[] to = placeByPlanetId.get(transfer.getToPlanetId());
            if (slot == null || from == null || to == null) {
                continue;
            }
            snapshots.add(new PopulationTransferSnapshot(slot, from[0], from[1], to[0], to[1],
                    transfer.getPopulation(), transfer.getDepartedTurn(), transfer.getJob()));
        }
        return snapshots;
    }

    /**
     * Флоты партии — п. 8: владелец слотом, система номером в списке слепка, состав —
     * номерами проектов в списке проектов слепка.
     */
    private List<FleetSnapshot> fleetSnapshots(UUID gameId,
                                               Map<UUID, Integer> slotByPlayerId,
                                               Map<UUID, Integer> indexBySystemId,
                                               Map<UUID, Integer> designIndexes,
                                               Map<UUID, Integer> fleetIndexById) {
        List<FleetEntity> fleets = fleetRepository.findAllByGameId(gameId);
        Map<UUID, List<FleetShipEntity>> composition = new HashMap<>();
        if (!fleets.isEmpty()) {
            for (FleetShipEntity row : fleetShipRepository
                    .findAllByFleetIdIn(fleets.stream().map(FleetEntity::getId).toList())) {
                composition.computeIfAbsent(row.getFleetId(), key -> new ArrayList<>()).add(row);
            }
        }

        List<FleetSnapshot> snapshots = new ArrayList<>();
        for (FleetEntity fleet : fleets) {
            Integer slot = slotByPlayerId.get(fleet.getOwnerPlayerId());
            Integer index = indexBySystemId.get(fleet.getStarSystemId());
            if (slot == null || index == null || fleet.getShips() <= 0) {
                continue;
            }
            List<FleetShipSnapshot> ships = new ArrayList<>();
            for (FleetShipEntity row : composition.getOrDefault(fleet.getId(), List.of())) {
                Integer designIndex = designIndexes.get(row.getDesignId());
                if (designIndex != null) {
                    ships.add(new FleetShipSnapshot(designIndex, row.getShips(), row.getColonists(),
                            row.getExperience()));
                }
            }
            Integer targetIndex = fleet.getTargetSystemId() == null
                    ? null
                    : indexBySystemId.get(fleet.getTargetSystemId());
            fleetIndexById.put(fleet.getId(), snapshots.size());
            snapshots.add(new FleetSnapshot(slot, index, fleet.getShips(), fleet.getCreatedTurn(), ships,
                    targetIndex, fleet.getDepartureTurn(), fleet.getArrivalTurn(),
                    fleet.getName(), fleet.getBattlesWon(), fleet.getBattlesLost()));
        }
        return snapshots;
    }

    /**
     * Проекты кораблей партии — п. 8. Сохраняются и вытесненные: по ним летают корабли,
     * и без них состав загруженного флота остался бы безымянным.
     */
    private List<ShipDesignSnapshot> shipDesignSnapshots(List<ShipDesignEntity> designs,
                                                         Map<UUID, Integer> slotByPlayerId) {
        Map<UUID, List<ShipDesignComponentEntity>> parts = new HashMap<>();
        if (!designs.isEmpty()) {
            for (ShipDesignComponentEntity component : shipDesignComponentRepository
                    .findAllByDesignIdInOrderBySortOrderAsc(designs.stream().map(ShipDesignEntity::getId).toList())) {
                parts.computeIfAbsent(component.getDesignId(), key -> new ArrayList<>()).add(component);
            }
        }

        List<ShipDesignSnapshot> snapshots = new ArrayList<>(designs.size());
        for (ShipDesignEntity design : designs) {
            List<ShipDesignComponentSnapshot> components = parts.getOrDefault(design.getId(), List.of())
                    .stream()
                    .map(part -> new ShipDesignComponentSnapshot(part.getComponentCode(), part.getCount(),
                            part.getModifications()))
                    .toList();
            snapshots.add(new ShipDesignSnapshot(
                    slotByPlayerId.get(design.getOwnerPlayerId()),
                    design.getSlot(),
                    design.getName(),
                    design.getHullCode(),
                    design.getCreatedTurn(),
                    design.getObsolete(),
                    design.getRole().name(),
                    components,
                    design.getAuto(),
                    design.getMission() == null ? null : design.getMission().name(),
                    design.getTrophy(), design.getBuilt()));
        }
        return snapshots;
    }

    /**
     * Отношения империй — п. 15. Стороны названы слотами: при загрузке игроки заводятся
     * заново, и старые идентификаторы ни на что не указывают.
     */
    private List<DiplomacyRelationSnapshot> relationSnapshots(List<PlayerEntity> players,
                                                              Map<UUID, Integer> slotByPlayerId) {
        List<DiplomacyRelationSnapshot> snapshots = new ArrayList<>();
        for (PlayerEntity player : players) {
            for (DiplomacyRelationEntity relation : relationRepository.findAllByPlayerId(player.getId())) {
                Integer otherSlot = slotByPlayerId.get(relation.getOtherPlayerId());
                if (otherSlot == null) {
                    continue;
                }
                snapshots.add(new DiplomacyRelationSnapshot(
                        slotByPlayerId.get(player.getId()),
                        otherSlot,
                        relation.getStance(),
                        relation.getTrust(),
                        List.copyOf(relation.getTreaties()),
                        relation.getMetTurn(),
                        relation.getInContact()));
            }
        }
        return snapshots;
    }

    /** Шпионы игрока с их заданиями — п. 13: в слепке они лежат внутри своего игрока. */
    private Map<UUID, List<SpySnapshot>> spySnapshots(List<PlayerEntity> players,
                                                      Map<UUID, Integer> slotByPlayerId) {
        Map<UUID, List<SpySnapshot>> byPlayer = new HashMap<>();
        for (SpyEntity spy : spyRepository.findAllByOwnerPlayerIdIn(slotByPlayerId.keySet()).stream()
                .sorted(GameOrder.SPIES).toList()) {
            byPlayer.computeIfAbsent(spy.getOwnerPlayerId(), key -> new ArrayList<>())
                    .add(new SpySnapshot(
                            spy.getMission(),
                            spy.getPoints(),
                            slotByPlayerId.get(spy.getTargetPlayerId()),
                            spy.getCreatedTurn()));
        }
        return byPlayer;
    }

    /** Изученные технологии игроков — п. 9: в слепке они лежат внутри своего игрока. */
    private Map<UUID, List<PlayerTechnologySnapshot>> technologySnapshots(List<PlayerEntity> players) {
        List<UUID> playerIds = players.stream().map(PlayerEntity::getId).toList();
        return playerTechnologyRepository.findAllByPlayerIdIn(playerIds).stream()
                .collect(Collectors.groupingBy(
                        PlayerTechnologyEntity::getPlayerId,
                        Collectors.mapping(technology -> new PlayerTechnologySnapshot(
                                technology.getCategoryCode(),
                                technology.getLevelOrder(),
                                technology.getOptionCode(),
                                technology.getAcquiredTurn()), Collectors.toList())));
    }

    /**
     * Летопись империй — п. 11.1: в слепке она лежит внутри своего игрока.
     * <p>
     * Пересчитать её при загрузке нечем: замеры прошлых ходов больше нигде не хранятся,
     * и без них у загруженной партии окно «Инфо» открывалось бы с пустым графиком.
     */
    private Map<UUID, List<EmpireHistorySnapshot>> historySnapshots(GameEntity game) {
        return empireHistoryRepository.findAllByGameIdOrderByTurnAsc(game.getId()).stream()
                .collect(Collectors.groupingBy(
                        EmpireHistoryEntity::getPlayerId,
                        Collectors.mapping(row -> new EmpireHistorySnapshot(
                                row.getTurn(),
                                row.getPopulationK(),
                                row.getColonies(),
                                row.getBuildings(),
                                row.getProduction(),
                                row.getResearch(),
                                row.getFleetPower(),
                                row.getTechnologies(),
                                row.getCredits()), Collectors.toList())));
    }

    /** Построенные здания колоний — п. 10: в слепке они лежат внутри своей планеты. */
    private Map<UUID, List<PlanetBuildingSnapshot>> buildingSnapshots(List<StarSystemEntity> systems) {
        List<UUID> planetIds = systems.stream()
                .flatMap(system -> system.getPlanets().stream())
                .map(PlanetEntity::getId)
                .toList();
        return planetBuildingRepository.findAllByPlanetIdIn(planetIds).stream()
                .collect(Collectors.groupingBy(
                        PlanetBuildingEntity::getPlanetId,
                        Collectors.mapping(building -> new PlanetBuildingSnapshot(
                                building.getBuildingCode(), building.getBuiltTurn()), Collectors.toList())));
    }

    private List<PlanetSnapshot> planetSnapshots(StarSystemEntity system,
                                                 Map<UUID, Integer> slotByPlayerId,
                                                 Map<UUID, List<PlanetBuildingSnapshot>> buildingsByPlanetId) {
        return system.getPlanets().stream()
                .sorted(Comparator.comparing(PlanetEntity::getOrbit))
                .map(planet -> new PlanetSnapshot(
                        planet.getOrbit(),
                        planet.getName(),
                        planet.getPlanetSize(),
                        planet.getClimate(),
                        planet.getMinerals(),
                        planet.getMaxPopulation(),
                        slotByPlayerId.get(planet.getOwnerPlayerId()),
                        planet.getPopulation(),
                        planet.getPopulationK(),
                        planet.getHomeworld(),
                        planet.getJobs(),
                        planet.getProjectCode(),
                        planet.getProjectPoints(),
                        buildingsByPlanetId.getOrDefault(planet.getId(), List.of()),
                        planet.getColonyBaseReady(),
                        planet.getBuildQueue(),
                        planet.getRepeatBuild(),
                        planet.getAutoBuild(),
                        planet.getAutoJobs(),
                        planet.getSoldTurn(),
                        planet.getFind(),
                        planet.getFindClaimed(),
                        planet.getTerraformCount(),
                        planet.getAndroidFarmers(),
                        planet.getAndroidWorkers(),
                        planet.getAndroidScientists()))
                .toList();
    }

    private PlayerEntity restorePlayer(PlayerSnapshot snapshot) {
        PlayerEntity player = new PlayerEntity();
        player.setSlot(snapshot.slot());
        player.setName(snapshot.name());
        player.setPlayerType(snapshot.playerType());
        player.setRaceCode(snapshot.raceCode());
        // Слепки, снятые до конструктора расы, своей расы не содержат.
        player.setRaceName(snapshot.raceName());
        player.setRaceTraitCodes(snapshot.raceTraits() == null ? List.of() : snapshot.raceTraits());
        // Слепки, снятые до появления разведки, очков шпионажа не содержат.
        player.setEspionagePoints(snapshot.espionagePoints() == null ? 0 : snapshot.espionagePoints());
        player.setSpies(snapshot.spies() == null ? 0 : snapshot.spies());
        // Слепки, снятые до грузового флота, грузовиков не содержат.
        player.setFreighters(snapshot.freighters() == null ? 0 : snapshot.freighters());
        // Характер правителя ИИ — п. 15; в слепках, снятых до него, пусто, и тогда он
        // выдаётся заново при восстановлении партии.
        player.setAiPersonality(snapshot.aiPersonality());
        player.setAiObjective(snapshot.aiObjective());
        player.setAiSkill(snapshot.aiSkill());
        // Слепок, снятый до новой палитры знамён (пункт 168), несёт прежний оттенок.
        player.setColor(BannerColor.current(snapshot.color()));
        // Пропуск выдаётся новый: старый остался у игроков исходной партии. А вот ХОЗЯИН
        // места переезжает как есть — в этом весь смысл автосохранения: вернувшийся садится
        // за свою же империю, а не за первую попавшуюся (п. 3, миграция 077). В слепках,
        // снятых до записи хозяина, его нет, и место остаётся ничьим.
        player.setAccessToken(UUID.randomUUID().toString().replace("-", ""));
        player.setAccountId(snapshot.accountId());
        player.setConcededTurn(snapshot.concededTurn());
        player.setDryExplorations(snapshot.dryExplorations() == null ? 0 : snapshot.dryExplorations());
        player.setChapter(snapshot.chapter() == null ? 1 : snapshot.chapter());
        player.setLastActionAt(snapshot.lastActionAt());
        player.setNote(snapshot.note());
        player.setJoinedAt(OffsetDateTime.now());
        player.setResearchCategoryCode(snapshot.researchCategoryCode());
        player.setResearchLevelOrder(snapshot.researchLevelOrder());
        player.setResearchOptionCode(snapshot.researchOptionCode());
        player.setResearchLastCategoryCode(snapshot.researchLastCategoryCode());
        // Сохранения, снятые до появления исследований, поля исследований не содержат.
        player.setResearchPoints(snapshot.researchPoints() == null ? 0 : snapshot.researchPoints());
        // Сохранения, снятые до появления казны, кредитов не содержат.
        player.setCredits(snapshot.credits() == null ? 0 : snapshot.credits());
        return player;
    }

    private StarSystemEntity restoreSystem(StarSystemSnapshot snapshot, Map<Integer, UUID> playerIdBySlot) {
        StarSystemEntity system = new StarSystemEntity();
        system.setName(snapshot.name());
        system.setXParsec(snapshot.xParsec());
        system.setYParsec(snapshot.yParsec());
        system.setStarColor(snapshot.starColor());
        system.setSpecial(snapshot.special());
        // Чудище и его здоровье: без них поднятая партия теряла сторожей, и особая звезда
        // открывалась без боя. Слепок прошлой версии этих полей не несёт — приходит null,
        // и система просто оказывается чистой, как и была до починки.
        system.setMonster(snapshot.monster());
        system.setMonsterStrength(snapshot.monsterStrength());
        system.setSpecialClaimed(Boolean.TRUE.equals(snapshot.specialClaimed()));

        for (PlanetSnapshot snapshotPlanet : snapshot.planets()) {
            PlanetEntity planet = new PlanetEntity();
            planet.setOrbit(snapshotPlanet.orbit());
            planet.setName(snapshotPlanet.name());
            planet.setPlanetSize(snapshotPlanet.planetSize());
            planet.setClimate(snapshotPlanet.climate());
            planet.setMinerals(snapshotPlanet.minerals());
            planet.setMaxPopulation(snapshotPlanet.maxPopulation());
            planet.setOwnerPlayerId(playerIdBySlot.get(snapshotPlanet.ownerSlot()));
            // Слепки, снятые до появления роста, хранят только целых жителей.
            if (snapshotPlanet.populationK() == null) {
                planet.setPopulation(snapshotPlanet.population());
            } else {
                planet.setPopulationK(snapshotPlanet.populationK());
            }
            // Слепки, снятые до появления занятий, их не содержат: колония получит
            // распределение по умолчанию при первом же обращении к ней.
            planet.setJobs(snapshotPlanet.jobs() == null ? PopulationJobs.NONE : snapshotPlanet.jobs());
            planet.setProjectCode(snapshotPlanet.projectCode());
            // Слепки, снятые до появления очереди, её не содержат — п. 10.
            planet.setBuildQueue(snapshotPlanet.buildQueue());
            // Слепки, снятые до миграции 078, о повторе и автострое не знают: у них
            // придёт null, а сеттер прочтёт его как «выключено».
            planet.setRepeatBuild(snapshotPlanet.repeatBuild());
            planet.setAutoBuild(snapshotPlanet.autoBuild());
            planet.setAutoJobs(snapshotPlanet.autoJobs());
            planet.setSoldTurn(snapshotPlanet.soldTurn());
            planet.setTerraformCount(snapshotPlanet.terraformCount());
            planet.setAndroidFarmers(snapshotPlanet.androidFarmers());
            planet.setAndroidWorkers(snapshotPlanet.androidWorkers());
            planet.setAndroidScientists(snapshotPlanet.androidScientists());
            planet.setProjectPoints(snapshotPlanet.projectPoints() == null ? 0 : snapshotPlanet.projectPoints());
            // Слепки, снятые до появления колониальных баз, о них не знают.
            planet.setColonyBaseReady(Boolean.TRUE.equals(snapshotPlanet.colonyBaseReady()));
            planet.setHomeworld(snapshotPlanet.homeworld());
            // Находка планеты — п. 4.1. Слепки, снятые до её появления, о ней не знают:
            // приходит null, и планета читается обыкновенной, какой она в той партии и
            // была. Отметка о выданных технологиях переносится вместе с находкой — иначе
            // артефакты отдавали бы их заново на каждой загрузке.
            planet.setFind(snapshotPlanet.find());
            planet.setFindClaimed(Boolean.TRUE.equals(snapshotPlanet.findClaimed()));
            system.addPlanet(planet);
        }
        return system;
    }

    /**
     * Построенные здания колоний — п. 10. Как и технологии, пишутся после того, как
     * планеты получили идентификаторы: здание ссылается на планету, а не лежит внутри неё.
     */
    private void restoreBuildings(GameSnapshot snapshot, List<StarSystemEntity> systems) {
        List<PlanetBuildingEntity> buildings = new ArrayList<>();
        for (int i = 0; i < systems.size(); i++) {
            List<PlanetSnapshot> snapshotPlanets = snapshot.systems().get(i).planets();
            List<PlanetEntity> planets = systems.get(i).getPlanets().stream()
                    .sorted(Comparator.comparing(PlanetEntity::getOrbit))
                    .toList();
            for (int orbit = 0; orbit < snapshotPlanets.size(); orbit++) {
                // Слепки, снятые до появления зданий, их не содержат.
                List<PlanetBuildingSnapshot> built = snapshotPlanets.get(orbit).buildings();
                if (built == null) {
                    continue;
                }
                for (PlanetBuildingSnapshot snapshotBuilding : built) {
                    PlanetBuildingEntity building = new PlanetBuildingEntity();
                    building.setPlanetId(planets.get(orbit).getId());
                    building.setBuildingCode(snapshotBuilding.buildingCode());
                    building.setBuiltTurn(snapshotBuilding.builtTurn());
                    buildings.add(building);
                }
            }
        }
        planetBuildingRepository.saveAllAndFlush(buildings);
    }

    /**
     * Изученные технологии игроков — п. 9. Пишутся после того, как игроки получили
     * идентификаторы: технология ссылается на игрока, а не лежит внутри него.
     */
    private void restoreTechnologies(List<PlayerEntity> players, GameSnapshot snapshot) {
        Map<Integer, List<PlayerTechnologySnapshot>> bySlot = new HashMap<>();
        for (PlayerSnapshot player : snapshot.players()) {
            // Сохранения, снятые до появления исследований, изученного не содержат.
            bySlot.put(player.slot(), player.technologies() == null ? List.of() : player.technologies());
        }

        List<PlayerTechnologyEntity> technologies = new ArrayList<>();
        for (PlayerEntity player : players) {
            for (PlayerTechnologySnapshot snapshotTechnology : bySlot.getOrDefault(player.getSlot(), List.of())) {
                PlayerTechnologyEntity technology = new PlayerTechnologyEntity();
                technology.setPlayerId(player.getId());
                technology.setCategoryCode(snapshotTechnology.categoryCode());
                technology.setLevelOrder(snapshotTechnology.levelOrder());
                technology.setOptionCode(snapshotTechnology.optionCode());
                technology.setAcquiredTurn(snapshotTechnology.acquiredTurn());
                technologies.add(technology);
            }
        }
        playerTechnologyRepository.saveAllAndFlush(technologies);
    }

    /**
     * Летопись империй — п. 11.1. Пишется после игроков: замер ссылается на игрока, а
     * идентификаторы у загруженной партии свои.
     */
    private void restoreHistory(GameEntity game, List<PlayerEntity> players, GameSnapshot snapshot) {
        Map<Integer, List<EmpireHistorySnapshot>> bySlot = new HashMap<>();
        for (PlayerSnapshot player : snapshot.players()) {
            // Слепки, снятые до летописи, замеров не содержат — партия начнёт её заново.
            bySlot.put(player.slot(), player.history() == null ? List.of() : player.history());
        }

        List<EmpireHistoryEntity> rows = new ArrayList<>();
        for (PlayerEntity player : players) {
            for (EmpireHistorySnapshot point : bySlot.getOrDefault(player.getSlot(), List.of())) {
                EmpireHistoryEntity row = new EmpireHistoryEntity();
                row.setGameId(game.getId());
                row.setPlayerId(player.getId());
                row.setTurn(point.turn());
                row.setPopulationK(point.populationK());
                row.setColonies(point.colonies());
                // Слепок мог быть снят до графика построек: тогда поля нет, и линия
                // построек начнётся с хода загрузки.
                row.setBuildings(point.buildings() == null ? 0 : point.buildings());
                row.setProduction(point.production());
                row.setResearch(point.research());
                row.setFleetPower(point.fleetPower());
                row.setTechnologies(point.technologies());
                row.setCredits(point.credits());
                rows.add(row);
            }
        }
        empireHistoryRepository.saveAllAndFlush(rows);
    }

    /** Родные миры игроков — п. 4.2: в слепке система записана порядковым номером. */
    private void linkHomeSystems(List<PlayerEntity> players, GameSnapshot snapshot, List<StarSystemEntity> systems) {
        Map<Integer, Integer> homeIndexBySlot = new HashMap<>();
        for (PlayerSnapshot player : snapshot.players()) {
            if (player.homeSystemIndex() != null) {
                homeIndexBySlot.put(player.slot(), player.homeSystemIndex());
            }
        }
        for (PlayerEntity player : players) {
            Integer index = homeIndexBySlot.get(player.getSlot());
            if (index != null) {
                player.setHomeSystemId(systems.get(index).getId());
            }
        }
    }

    private GameSaveDto toDto(GameSaveEntity save, UUID accountId) {
        return new GameSaveDto(
                save.getId(),
                save.getGameId(),
                save.getName(),
                save.getGalaxySize(),
                save.getWidthParsecs(),
                save.getHeightParsecs(),
                save.getStarCount(),
                save.getTurn(),
                save.getHumanPlayers(),
                save.getTotalPlayers(),
                save.getSavedAt(),
                save.getAuto(),
                // Сделанное руками поднимает кто угодно — правило только об автосохранении.
                !Boolean.TRUE.equals(save.getAuto()) || loadableBy(save.getHumanAccounts(), accountId));
    }

    /** Тот же заголовок, но из проекции: списку сам слепок не нужен. */
    private GameSaveDto toDto(GameSaveSummary save, UUID accountId) {
        return new GameSaveDto(
                save.getId(),
                save.getGameId(),
                save.getName(),
                save.getGalaxySize(),
                save.getWidthParsecs(),
                save.getHeightParsecs(),
                save.getStarCount(),
                save.getTurn(),
                save.getHumanPlayers(),
                save.getTotalPlayers(),
                save.getSavedAt(),
                save.getAuto(),
                // Сделанное руками поднимает кто угодно — правило только об автосохранении.
                !Boolean.TRUE.equals(save.getAuto()) || loadableBy(save.getHumanAccounts(), accountId));
    }

    private String serialize(GameSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать состояние партии", e);
        }
    }

    private GameSnapshot deserialize(String state) {
        try {
            return objectMapper.readValue(state, GameSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new ConflictException("save.corrupted");
        }
    }
}
