package com.sddnw.server.service;

import com.sddnw.server.domain.entity.ArtifactChoiceEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.PlanetFind;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.dto.AcquiredTechnologyDto;
import com.sddnw.server.dto.ArtifactChoiceDto;
import com.sddnw.server.repository.ArtifactChoiceRepository;
import com.sddnw.server.repository.PlanetRepository;
import com.sddnw.server.repository.StarSystemRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Награды разведки — п. 4.1: находки на планетах, наследие ушедших и потолок у полосы неудач.
 * <p>
 * <b>Наследие ушедших — выбор, а не жребий</b> (backlog-promo, пункт 34, приём Discover): первому
 * разведчику системы с артефактами — три неизученные технологии разных разделов, взять одну (при
 * удачном броске — две). Случайность остаётся, но последнее слово за игроком, и удача читается его
 * заслугой. Разведка случается внутри посчитанного хода, поэтому человек выбирает при чтении итогов
 * ({@link ArtifactChoiceEntity}); не выбрал до конца следующего хода — берутся первые. ИИ берёт
 * отмеченное в дереве рекомендованным, иначе первое, — и сразу.
 * <p>
 * <b>Таймер жалости</b> (пункт 36): длинная пустая полоса гасит интерес сильнее, чем редкость
 * наград. Разведав {@link #PITY_SYSTEMS} систем подряд без единой находки, империя в следующей
 * системе с годной свободной планетой находит что-то наверняка — во всей игре и от зерна партии, так
 * что прогоны балансировки по-прежнему повторяются. Порог — реконструкция: находка лежит у
 * {@link PlanetFind#SHARE_PERCENT} % планет, то есть у одной системы из пяти-шести, и пять пустых
 * подряд — уже невезение, а не норма.
 */
@Service
public class PlanetFindReward {

    private static final Logger log = LoggerFactory.getLogger(PlanetFindReward.class);

    /** Сколько пустых разведок подряд — и следующая непустая система приносит находку. */
    static final int PITY_SYSTEMS = 5;

    /** Сколько технологий на выбор у наследия ушедших. */
    static final int ARTIFACT_OPTIONS = 3;

    private final ResearchService researchService;
    private final ResearchCatalog researchCatalog;
    private final PlanetRepository planetRepository;
    private final PlayerEventService playerEvents;
    private final ArtifactChoiceRepository choiceRepository;
    private final StarSystemRepository starSystemRepository;
    private final PresenceService presence;
    private final GameAccess gameAccess;

    /**
     * Счётчик первых встреч с механиками — field-backlog, пункт 93. Полем, а не параметром
     * конструктора: службу собирают руками юнит-тесты, а им замер ни к чему (там он пуст).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EmpireActivityService activity;

    public PlanetFindReward(ResearchService researchService,
                            ResearchCatalog researchCatalog,
                            PlanetRepository planetRepository,
                            PlayerEventService playerEvents,
                            ArtifactChoiceRepository choiceRepository,
                            StarSystemRepository starSystemRepository,
                            PresenceService presence,
                            GameAccess gameAccess) {
        this.researchService = researchService;
        this.researchCatalog = researchCatalog;
        this.planetRepository = planetRepository;
        this.playerEvents = playerEvents;
        this.choiceRepository = choiceRepository;
        this.starSystemRepository = starSystemRepository;
        this.presence = presence;
        this.gameAccess = gameAccess;
    }

    /**
     * Система разведана впервые: таймер жалости, весть о находках (по ней клиент открывает сцену
     * находки, пункт 33) и награда тому, кто долетел.
     */
    public void explored(GameEntity game, PlayerEntity player, StarSystemEntity system) {
        if (activity != null && system.getPlanets().stream().anyMatch(planet -> planet.getFind() != null)) {
            activity.record(game.getId(), player.getId(), EmpireActivityService.FIND);
        }
        pity(game, player, system);
        for (PlanetEntity planet : system.getPlanets()) {
            PlanetFind find = planet.getFind();
            if (find != null && !find.rewardsExplorer() && planet.getOwnerPlayerId() == null) {
                playerEvents.record(game.getId(), player.getId(), game.getTurn(), "EXPLORATION",
                        new com.sddnw.server.service.MessageKey("turn.find.found", system.getName(), planet.getName(), find),
                        system.getId(), planet.getId());
            }
        }
        claim(game, player, system);
    }

    /** Таймер жалости — пункт 36: пустая разведка копит счёт, находка его сбрасывает. */
    private void pity(GameEntity game, PlayerEntity player, StarSystemEntity system) {
        boolean found = system.getPlanets().stream().anyMatch(planet -> planet.getFind() != null);
        if (found) {
            player.setDryExplorations(0);
            return;
        }
        int dry = player.getDryExplorations() == null ? 0 : player.getDryExplorations();
        if (dry < PITY_SYSTEMS) {
            player.setDryExplorations(dry + 1);
            return;
        }
        PlanetEntity planet = system.getPlanets().stream()
                .filter(one -> one.getOwnerPlayerId() == null && one.getMaxPopulation() != null
                        && one.getMaxPopulation() > 0)
                .min(Comparator.comparing(PlanetEntity::getOrbit))
                .orElse(null);
        if (planet == null) {
            // Селиться негде — находку класть некуда; счёт ждёт следующей системы.
            player.setDryExplorations(dry + 1);
            return;
        }
        Random random = new Random(game.getSeed() * 131L + game.getTurn() * 17L + planet.getName().hashCode());
        List<PlanetFind> fitting = PlanetFind.all().stream().filter(one -> one.fits(planet.getMaxPopulation())).toList();
        if (fitting.isEmpty()) {
            player.setDryExplorations(dry + 1);
            return;
        }
        int total = fitting.stream().mapToInt(PlanetFind::getWeight).sum();
        int roll = random.nextInt(total);
        PlanetFind chosen = fitting.get(fitting.size() - 1);
        for (PlanetFind one : fitting) {
            roll -= one.getWeight();
            if (roll < 0) {
                chosen = one;
                break;
            }
        }
        planet.setFind(chosen);
        planet.setFindClaimed(Boolean.FALSE);
        planetRepository.save(planet);
        player.setDryExplorations(0);
        log.info("Таймер жалости: игрок {} после {} пустых разведок нашёл {} на {}",
                player.getName(), dry, chosen, planet.getName());
    }

    /**
     * Отдаёт награду за разведку системы, если в ней есть чему её отдать.
     * <p>
     * Награда достаётся ОДИН раз на находку ({@code planet.find_claimed}): вторая империя,
     * добравшаяся до тех же артефактов, застанет их уже разобранными — как и в оригинале, где это
     * награда первому.
     */
    public void claim(GameEntity game, PlayerEntity player, StarSystemEntity system) {
        for (PlanetEntity planet : system.getPlanets()) {
            PlanetFind find = planet.getFind();
            if (find == null || !find.rewardsExplorer() || Boolean.TRUE.equals(planet.getFindClaimed())) {
                continue;
            }
            grant(game, player, system, planet, find);
        }
    }

    private void grant(GameEntity game, PlayerEntity player, StarSystemEntity system,
                       PlanetEntity planet, PlanetFind find) {
        // Жребий от зерна партии, хода и самой планеты: та же партия обязана считаться так же, а
        // две находки, открытые одним ходом, не должны делить один бросок.
        Random random = new Random(game.getSeed() * 97L + game.getTurn() * 31L
                + planet.getOrbit() + planet.getName().hashCode());
        int count = find.getFreeTechnologies()
                + (random.nextInt(100) < PlanetFind.SECOND_TECHNOLOGY_PERCENT ? 1 : 0);
        List<ResearchCatalog.TechnologyPlace> candidates =
                researchService.giftCandidates(player, random, Math.max(ARTIFACT_OPTIONS, count));

        // Отметка ставится и тогда, когда выдавать было нечего: империя, изучившая дерево
        // целиком, не должна оставлять находку «неразобранной» для следующего гостя.
        planet.setFindClaimed(Boolean.TRUE);
        planetRepository.save(planet);
        if (candidates.isEmpty()) {
            log.info("Артефакты планеты {} достались игроку {}, но выдавать нечего: дерево изучено",
                    planet.getName(), player.getName());
            return;
        }
        if (player.getPlayerType() == PlayerType.HUMAN && !Boolean.TRUE.equals(presence.aiDriven(player))) {
            ArtifactChoiceEntity choice = new ArtifactChoiceEntity();
            choice.setGameId(game.getId());
            choice.setPlayerId(player.getId());
            choice.setStarSystemId(system.getId());
            choice.setOptions(candidates.stream().map(place -> place.option().code()).toList());
            choice.setPicks(Math.min(count, candidates.size()));
            choice.setTurn(game.getTurn());
            choiceRepository.save(choice);
            playerEvents.record(game.getId(), player.getId(), game.getTurn(), "EXPLORATION",
                    new MessageKey("turn.find.artifactsChoice", system.getName()), system.getId(), null);
            return;
        }
        // ИИ — по рекомендации дерева, иначе по порядку жребия (пункт 34).
        List<ResearchCatalog.TechnologyPlace> ordered = candidates.stream()
                .sorted(Comparator.comparing((ResearchCatalog.TechnologyPlace place) ->
                        !Boolean.TRUE.equals(place.option().recommended())))
                .toList();
        give(game, player, system, ordered.subList(0, Math.min(count, ordered.size())), game.getTurn());
    }

    /** Выдаёт выбранное и пишет строку отчёта — один путь у ИИ, у выбора человека и у срока. */
    private List<AcquiredTechnologyDto> give(GameEntity game, PlayerEntity player, StarSystemEntity system,
                                             List<ResearchCatalog.TechnologyPlace> places, Integer turn) {
        List<AcquiredTechnologyDto> gained = new ArrayList<>();
        for (ResearchCatalog.TechnologyPlace place : places) {
            gained.addAll(researchService.grantPlace(player, place, turn));
        }
        if (!gained.isEmpty()) {
            playerEvents.record(game.getId(), player.getId(), turn, "EXPLORATION",
                    new MessageKey("turn.find.artifacts", system.getName(),
                            gained.stream().map(one -> CatalogTexts.tech(one.optionCode()))
                                    .collect(Collectors.joining(", "))),
                    system.getId(), null);
            log.info("Наследие ушедших в {} — игроку {}: {}", system.getName(), player.getName(),
                    gained.stream().map(AcquiredTechnologyDto::optionCode).toList());
        }
        return gained;
    }

    /** Ждущий выбор игрока — пункт 34: кандидаты названиями и описаниями на языке читателя. */
    @Transactional(readOnly = true)
    public List<ArtifactChoiceDto> pending(UUID gameId, String accessToken) {
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        Map<UUID, String> names = new java.util.HashMap<>();
        return choiceRepository.findAllByPlayerIdOrderByTurnAsc(playerId).stream()
                .map(choice -> new ArtifactChoiceDto(choice.getId(),
                        names.computeIfAbsent(choice.getStarSystemId(), id -> starSystemRepository.findById(id)
                                .map(StarSystemEntity::getName).orElse("")),
                        choice.getPicks(), choice.getTurn() + 1,
                        choice.optionCodes().stream().map(researchCatalog::place)
                                .map(place -> new ArtifactChoiceDto.Option(place.option().code(), place.option().name(),
                                        place.option().description(), place.categoryCode(), place.levelOrder()))
                                .toList()))
                .toList();
    }

    /** Выбор человека — пункт 34: технологии выдаются сразу, ответом на нажатие. */
    @Transactional
    public List<AcquiredTechnologyDto> pick(UUID gameId, UUID choiceId, String accessToken, List<String> codes) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, accessToken);
        ArtifactChoiceEntity choice = choiceRepository.findById(choiceId)
                .filter(found -> found.getPlayerId().equals(player.getId()))
                .orElseThrow(() -> new NotFoundException("artifact.notFound"));
        List<String> allowed = choice.optionCodes();
        List<String> chosen = codes.stream().distinct().toList();
        if (chosen.size() > choice.getPicks() || !allowed.containsAll(chosen)) {
            throw new ConflictException("artifact.badPick", choice.getPicks());
        }
        StarSystemEntity system = starSystemRepository.findById(choice.getStarSystemId()).orElseThrow();
        List<AcquiredTechnologyDto> gained = give(game, player, system,
                chosen.stream().map(researchCatalog::place).toList(), game.getTurn());
        choiceRepository.delete(choice);
        return gained;
    }

    /**
     * Срок выбора вышел — пункт 34: не выбрал до конца следующего хода — берутся первые по жребию.
     * Зовёт фаза {@link ArtifactChoicePhase}.
     */
    public void expire(TurnContext context) {
        List<ArtifactChoiceEntity> due = choiceRepository.findAllByGameIdOrderByTurnAsc(context.game().getId()).stream()
                .filter(choice -> context.turn() > choice.getTurn())
                .toList();
        if (due.isEmpty()) {
            return;
        }
        Map<UUID, PlayerEntity> players = context.players().stream()
                .collect(Collectors.toMap(PlayerEntity::getId, player -> player));
        for (ArtifactChoiceEntity choice : due) {
            PlayerEntity player = players.get(choice.getPlayerId());
            StarSystemEntity system = context.systems().stream()
                    .filter(one -> one.getId().equals(choice.getStarSystemId())).findFirst().orElse(null);
            if (player != null && system != null) {
                List<String> codes = choice.optionCodes();
                give(context.game(), player, system, codes.subList(0, Math.min(choice.getPicks(), codes.size()))
                        .stream().map(researchCatalog::place).toList(), context.turn());
            }
            choiceRepository.delete(choice);
        }
    }
}
