package com.sddnw.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.FleetShipEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import com.sddnw.server.domain.entity.SpectatorFrameEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.dto.SpectatableGameDto;
import com.sddnw.server.dto.SpectatorFrameDto;
import com.sddnw.server.repository.EmpireHistoryRepository;
import com.sddnw.server.repository.FleetRepository;
import com.sddnw.server.repository.FleetShipRepository;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.SpectatorFrameRepository;
import com.sddnw.server.repository.StarSystemRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Режим зрителя — field-backlog, пункт 59: новинку перенимают, когда видят, как в неё играют
 * другие, а партию со стороны прежде не видел никто, кроме администратора.
 * <p>
 * Правил три, и каждое закрывает свою дыру:
 * <ul>
 *   <li><b>Согласие</b> — признак партии {@code spectators}: его ставит создатель, а входящий за
 *       стол видит его до входа. Без признака партию не смотрит никто.</li>
 *   <li><b>Отставание</b> — зритель видит галактику на {@link #DELAY_TURNS} ходов раньше, чем она
 *       есть: без тумана войны, но то, что он видит, уже прошло. Иначе зритель стал бы разведчиком
 *       для кого-то за столом — достаточно позвать друга смотреть. Кончившуюся партию смотрят без
 *       отставания: разведывать в ней больше нечего.</li>
 *   <li><b>Свою партию со стороны не смотрят</b> — сидящий за столом увидел бы чужие флоты.</li>
 * </ul>
 * Прошлое партии пересчитать нечем, поэтому кадр ({@link SpectatorFrameEntity}) пишется в начале
 * каждого хода, после фиксации, и живёт, пока не станет старше отставания.
 */
@Service
public class SpectatorService {

    private static final Logger log = LoggerFactory.getLogger(SpectatorService.class);

    /** На сколько ходов зритель отстаёт от партии — решение хозяина проекта (08.10.2026). */
    public static final int DELAY_TURNS = 5;

    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final StarSystemRepository starSystemRepository;
    private final FleetRepository fleetRepository;
    private final FleetShipRepository fleetShipRepository;
    private final ShipDesignService shipDesignService;
    private final EmpireHistoryRepository historyRepository;
    private final EmpireMightRules mightRules;
    private final SpectatorFrameRepository frames;
    private final AccountService accountService;
    private final ObjectMapper json;

    public SpectatorService(GameRepository gameRepository,
                            PlayerRepository playerRepository,
                            StarSystemRepository starSystemRepository,
                            FleetRepository fleetRepository,
                            FleetShipRepository fleetShipRepository,
                            ShipDesignService shipDesignService,
                            EmpireHistoryRepository historyRepository,
                            EmpireMightRules mightRules,
                            SpectatorFrameRepository frames,
                            AccountService accountService,
                            ObjectMapper json) {
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.starSystemRepository = starSystemRepository;
        this.fleetRepository = fleetRepository;
        this.fleetShipRepository = fleetShipRepository;
        this.shipDesignService = shipDesignService;
        this.historyRepository = historyRepository;
        this.mightRules = mightRules;
        this.frames = frames;
        this.accountService = accountService;
        this.json = json;
    }

    /**
     * Снять кадр начала текущего хода. Зовётся после фиксации хода, поэтому в СВОЕЙ транзакции
     * (те же грабли, что у автосохранения); отказ — строка журнала, а не потерянный ход.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID gameId) {
        GameEntity game = gameRepository.findById(gameId).orElse(null);
        if (game == null || !Boolean.TRUE.equals(game.getSpectators())) {
            return;
        }
        try {
            String frame = json.writeValueAsString(build(game));
            frames.findByGameIdAndTurn(gameId, game.getTurn()).ifPresent(frames::delete);
            frames.flush();
            frames.save(new SpectatorFrameEntity(gameId, game.getTurn(), frame));
            frames.deleteOlder(gameId, game.getTurn() - DELAY_TURNS);
        } catch (JsonProcessingException e) {
            log.warn("Кадр зрителя партии {} на ход {} не собрался: {}", game.getName(), game.getTurn(), e.toString());
        }
    }

    /** Партии, которые можно смотреть: идущие и кончившиеся, с согласия создателя. */
    @Transactional(readOnly = true)
    public List<SpectatableGameDto> list(String accountToken) {
        UUID accountId = accountService.require(accountToken).getId();
        List<SpectatableGameDto> result = new ArrayList<>();
        for (GameEntity game : gameRepository.findAllBySpectatorsTrueAndStatusIn(
                List.of(GameStatus.IN_PROGRESS, GameStatus.FINISHED))) {
            List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId());
            boolean yours = players.stream().anyMatch(player -> accountId.equals(player.getAccountId()));
            Integer viewTurn = frames.findFirstByGameIdAndTurnLessThanEqualOrderByTurnDesc(game.getId(), visibleTurn(game))
                    .map(SpectatorFrameEntity::getTurn)
                    .orElse(null);
            result.add(new SpectatableGameDto(game.getId(), game.getName(), game.getGalaxySize().name(),
                    game.getTurn(), players.size(),
                    (int) players.stream().filter(player -> player.getPlayerType() == PlayerType.HUMAN).count(),
                    viewTurn, yours));
        }
        // Сперва идущие, потом по свежести хода — смотреть приходят туда, где что-то происходит.
        result.sort(Comparator.comparing((SpectatableGameDto row) -> row.viewTurn() == null)
                .thenComparing(SpectatableGameDto::turn, Comparator.reverseOrder())
                .thenComparing(SpectatableGameDto::name));
        return result;
    }

    /** Кадр, который зритель вправе видеть сейчас. */
    @Transactional(readOnly = true)
    public SpectatorFrameDto frame(UUID gameId, String accountToken) {
        UUID accountId = accountService.require(accountToken).getId();
        GameEntity game = gameRepository.findById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound"));
        if (!Boolean.TRUE.equals(game.getSpectators())) {
            throw new ForbiddenException("spectate.closed");
        }
        if (playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId).stream()
                .anyMatch(player -> accountId.equals(player.getAccountId()))) {
            throw new ForbiddenException("spectate.own");
        }
        SpectatorFrameEntity frame = frames
                .findFirstByGameIdAndTurnLessThanEqualOrderByTurnDesc(gameId, visibleTurn(game))
                .orElseThrow(() -> new ConflictException("spectate.young", visibleFrom()));
        try {
            SpectatorFrameDto stored = json.readValue(frame.getFrame(), SpectatorFrameDto.class);
            // Ход партии — нынешний, а не того кадра: зритель видит, насколько отстаёт.
            return new SpectatorFrameDto(stored.gameId(), stored.name(), stored.turn(), game.getTurn(),
                    stored.widthParsecs(), stored.heightParsecs(), stored.empires(), stored.stars(), stored.fleets());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Кадр зрителя не читается: " + e.getMessage(), e);
        }
    }

    /** Самый поздний ход, который зритель вправе видеть. */
    private static Integer visibleTurn(GameEntity game) {
        return game.getStatus() == GameStatus.FINISHED ? game.getTurn() : game.getTurn() - DELAY_TURNS;
    }

    /** С какого хода партии зрителю откроется первый кадр (первый пишется на старте, на ходу 1). */
    private static Integer visibleFrom() {
        return 1 + DELAY_TURNS;
    }

    /** Галактика целиком, как её видел бы всевидящий, — одной выборкой на партию. */
    private SpectatorFrameDto build(GameEntity game) {
        UUID gameId = game.getId();
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        Map<UUID, Integer> slotOf = new HashMap<>();
        players.forEach(player -> slotOf.put(player.getId(), player.getSlot()));

        List<StarSystemEntity> systems = starSystemRepository.findAllByGameIdWithPlanets(gameId).stream()
                .sorted(GameOrder.SYSTEMS)
                .toList();
        Map<UUID, Integer> colonies = new HashMap<>();
        Map<UUID, Integer> population = new HashMap<>();
        List<SpectatorFrameDto.StarDto> stars = new ArrayList<>(systems.size());
        for (StarSystemEntity system : systems) {
            List<Integer> owners = new ArrayList<>();
            for (PlanetEntity planet : system.getPlanets()) {
                UUID owner = planet.getOwnerPlayerId();
                if (owner == null || !slotOf.containsKey(owner)) {
                    continue;
                }
                if (!owners.contains(slotOf.get(owner))) {
                    owners.add(slotOf.get(owner));
                }
                if (planet.getPopulationK() != null && planet.getPopulationK() > 0) {
                    colonies.merge(owner, 1, Integer::sum);
                    population.merge(owner, planet.getPopulationK(), Integer::sum);
                }
            }
            owners.sort(Comparator.naturalOrder());
            stars.add(new SpectatorFrameDto.StarDto(system.getId(), system.getName(), system.getXParsec(),
                    system.getYParsec(), system.getStarColor() == null ? null : system.getStarColor().name(),
                    system.getSpecial(), owners,
                    system.getMonster() == null ? null : system.getMonster().name()));
        }

        // Мощь — последний замер летописи: её пишет фаза хода, и зрителю незачем считать заново.
        Map<UUID, Integer> might = new HashMap<>();
        for (EmpireHistoryEntity row : historyRepository.findAllByGameIdAndTurn(gameId, game.getTurn() - 1)) {
            might.put(row.getPlayerId(), mightRules.might(row.getFleetPower(), row.getPopulationK(),
                    row.getProduction(), row.getResearch(), row.getTechnologies()));
        }
        List<SpectatorFrameDto.EmpireDto> empires = players.stream()
                .map(player -> new SpectatorFrameDto.EmpireDto(player.getSlot(), player.getName(), player.getRaceName(),
                        player.getColor(), player.getPlayerType() != PlayerType.HUMAN,
                        colonies.getOrDefault(player.getId(), 0), population.getOrDefault(player.getId(), 0),
                        might.get(player.getId()), colonies.getOrDefault(player.getId(), 0) > 0))
                .toList();

        List<FleetEntity> fleets = fleetRepository.findAllByGameId(gameId).stream()
                .filter(fleet -> slotOf.containsKey(fleet.getOwnerPlayerId()))
                .filter(fleet -> fleet.getShips() > 0)
                .toList();
        Map<UUID, List<FleetShipEntity>> composition = fleetShipRepository
                .findAllByFleetIdIn(fleets.stream().map(FleetEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(FleetShipEntity::getFleetId));
        Map<UUID, ShipDesignEntity> designs = shipDesignService.designsOfGame(gameId);
        Set<UUID> combat = designs.values().stream()
                .filter(design -> Boolean.TRUE.equals(design.getRole().isCombat()))
                .map(ShipDesignEntity::getId)
                .collect(Collectors.toSet());
        List<SpectatorFrameDto.FleetDto> fleetRows = fleets.stream()
                .map(fleet -> {
                    boolean flying = Boolean.TRUE.equals(fleet.isInFlight());
                    boolean armed = composition.getOrDefault(fleet.getId(), List.of()).stream()
                            .anyMatch(row -> row.getShips() > 0 && combat.contains(row.getDesignId()));
                    return new SpectatorFrameDto.FleetDto(slotOf.get(fleet.getOwnerPlayerId()),
                            flying ? null : fleet.getStarSystemId(),
                            flying ? fleet.getOriginSystemId() : null,
                            flying ? fleet.getTargetSystemId() : null,
                            flying ? fleet.getDepartureTurn() : null,
                            flying ? fleet.getArrivalTurn() : null,
                            fleet.getShips(), armed);
                })
                .sorted(Comparator.comparing(SpectatorFrameDto.FleetDto::slot)
                        .thenComparing(row -> String.valueOf(row.systemId() == null ? row.originId() : row.systemId())))
                .toList();

        return new SpectatorFrameDto(gameId, game.getName(), game.getTurn(), game.getTurn(),
                game.getWidthParsecs(), game.getHeightParsecs(), empires, stars, fleetRows);
    }
}
