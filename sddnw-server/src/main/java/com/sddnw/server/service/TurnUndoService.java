package com.sddnw.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.domain.Building;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetBuildingEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.TurnUndoEntity;
import com.sddnw.server.dto.TurnUndoDto;
import com.sddnw.server.repository.PlanetBuildingRepository;
import com.sddnw.server.repository.PlanetRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.StarSystemRepository;
import com.sddnw.server.repository.TurnUndoRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Отмена в пределах хода — field-backlog, пункт 74 (решение хозяина проекта 08.10.2026): продажа
 * постройки, выкуп стройки и списание кораблей отменяются до конца хода. Наём и увольнение лидера —
 * нет: уволенный уходит насовсем, а подтверждение там остаётся.
 * <p>
 * <b>Отмена возвращает, только если есть ЧЕМ:</b> деньги, полученные за продажу, могли уйти на
 * выкуп, а единицы, вернувшиеся в стройку, — на смену стройки. Отказ тогда называет причину, а не
 * отнимает больше, чем дал. Убранное из очереди после продажи (стройка, которой без здания нечем
 * строиться) отмена не возвращает — очередь игрок видит и правит сам.
 * <p>
 * Списание трофея, принёсшего технологию на сломе, не отменяется: технологию не забрать назад.
 */
@Service
public class TurnUndoService {

    private static final Logger log = LoggerFactory.getLogger(TurnUndoService.class);

    public static final String SELL = "SELL";
    public static final String BUY = "BUY";
    public static final String SCRAP = "SCRAP";

    /** Продажа: что за здание, когда построено, сколько отдано казне и стройке. */
    public record Sold(UUID planetId, String buildingCode, Integer builtTurn, Integer paid) {
    }

    /** Выкуп: какая стройка, сколько в ней было единиц до выкупа, сколько стоил выкуп. */
    public record Bought(UUID planetId, String projectCode, UUID designId, String projectName,
                         Integer pointsBefore, Integer pointsAfter, Integer price) {
    }

    /** Списание: где стоял флот и сколько кораблей каких проектов ушло. */
    public record Scrapped(UUID systemId, Map<UUID, Integer> ships) {
    }

    private final TurnUndoRepository undos;
    private final PlanetRepository planets;
    private final PlanetBuildingRepository buildings;
    private final PlayerRepository players;
    private final StarSystemRepository systems;
    private final FleetService fleetService;
    private final BuildingCatalog catalog;
    private final GameAccess gameAccess;
    private final Messages messages;
    private final ObjectMapper json;

    public TurnUndoService(TurnUndoRepository undos, PlanetRepository planets, PlanetBuildingRepository buildings,
                           PlayerRepository players, StarSystemRepository systems, FleetService fleetService,
                           BuildingCatalog catalog, GameAccess gameAccess, Messages messages, ObjectMapper json) {
        this.undos = undos;
        this.planets = planets;
        this.buildings = buildings;
        this.players = players;
        this.systems = systems;
        this.fleetService = fleetService;
        this.catalog = catalog;
        this.gameAccess = gameAccess;
        this.messages = messages;
        this.json = json;
    }

    /** Записать отменяемое действие — зовут службы продажи, выкупа и списания. */
    @Transactional
    public void record(GameEntity game, PlayerEntity player, String kind, Object payload) {
        try {
            undos.save(new TurnUndoEntity(game.getId(), player.getId(), game.getTurn(), kind,
                    json.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            // Не записалось — отменить будет нечего, но само действие уже сделано и цело.
            log.warn("Отмена {} игрока {} не записана: {}", kind, player.getName(), e.toString());
        }
    }

    /** Что этот игрок может отменить сейчас; строки прошлых ходов убираются тут же. */
    @Transactional
    public List<TurnUndoDto> list(UUID gameId, String accessToken) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        Integer turn = gameAccess.requireGame(gameId).getTurn();
        undos.deleteStale(player.getId(), turn);
        return undos.findAllByPlayerIdAndTurnOrderByIdAsc(player.getId(), turn).stream()
                .map(this::toDto)
                .toList();
    }

    /** Отменить одно действие этого хода. */
    @Transactional
    public List<TurnUndoDto> undo(UUID gameId, String accessToken, UUID undoId) {
        PlayerEntity player = gameAccess.requirePlayerOfRunningGame(gameId, accessToken);
        GameEntity game = gameAccess.requireGame(gameId);
        TurnUndoEntity row = undos.findById(undoId)
                .filter(one -> one.getPlayerId().equals(player.getId()) && one.getTurn().equals(game.getTurn()))
                .orElseThrow(() -> new NotFoundException("undo.notFound"));
        switch (row.getKind()) {
            case SELL -> unsell(player, read(row, Sold.class), game.getTurn());
            case BUY -> unbuy(player, read(row, Bought.class));
            case SCRAP -> unscrap(game, player, read(row, Scrapped.class));
            default -> throw new NotFoundException("undo.notFound");
        }
        undos.delete(row);
        players.save(player);
        log.info("Игрок {} отменил {} на ходу {}", player.getName(), row.getKind(), game.getTurn());
        return list(gameId, accessToken);
    }

    private void unsell(PlayerEntity player, Sold sold, Integer turn) {
        PlanetEntity planet = ownPlanet(player, sold.planetId());
        if (player.getCredits() < sold.paid()) {
            throw new ConflictException("undo.creditsSpent", sold.paid(), player.getCredits());
        }
        if (planet.getProjectPoints() < sold.paid()) {
            throw new ConflictException("undo.pointsSpent", planet.getName(), sold.paid());
        }
        PlanetBuildingEntity back = new PlanetBuildingEntity();
        back.setPlanetId(planet.getId());
        back.setBuildingCode(sold.buildingCode());
        back.setBuiltTurn(sold.builtTurn());
        buildings.save(back);
        player.setCredits(player.getCredits() - sold.paid());
        planet.setProjectPoints(planet.getProjectPoints() - sold.paid());
        // Продажа этого хода отменена — колония снова вправе продать одно здание.
        if (turn.equals(planet.getSoldTurn())) {
            planet.setSoldTurn(null);
        }
        planets.save(planet);
    }

    private void unbuy(PlayerEntity player, Bought bought) {
        PlanetEntity planet = ownPlanet(player, bought.planetId());
        boolean same = java.util.Objects.equals(planet.getProjectCode(), bought.projectCode())
                && java.util.Objects.equals(planet.getProjectDesignId(), bought.designId())
                && planet.getProjectPoints() >= bought.pointsAfter();
        if (!same) {
            throw new ConflictException("undo.projectChanged", planet.getName());
        }
        player.setCredits(player.getCredits() + bought.price());
        planet.setProjectPoints(planet.getProjectPoints() - (bought.pointsAfter() - bought.pointsBefore()));
        planets.save(planet);
    }

    private void unscrap(GameEntity game, PlayerEntity player, Scrapped scrapped) {
        for (Map.Entry<UUID, Integer> entry : scrapped.ships().entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                fleetService.addShip(game.getId(), player.getId(), scrapped.systemId(), game.getTurn(), entry.getKey());
            }
        }
    }

    private PlanetEntity ownPlanet(PlayerEntity player, UUID planetId) {
        PlanetEntity planet = planets.findById(planetId)
                .orElseThrow(() -> new NotFoundException("undo.notFound"));
        if (!player.getId().equals(planet.getOwnerPlayerId())) {
            throw new ConflictException("undo.colonyLost", planet.getName());
        }
        return planet;
    }

    private TurnUndoDto toDto(TurnUndoEntity row) {
        return switch (row.getKind()) {
            case SELL -> {
                Sold sold = read(row, Sold.class);
                Building building = catalog.byCode().get(sold.buildingCode());
                yield new TurnUndoDto(row.getId(), SELL, building == null ? sold.buildingCode() : building.name(),
                        planets.findById(sold.planetId()).map(PlanetEntity::getName).orElse("?"), sold.paid());
            }
            case BUY -> {
                Bought bought = read(row, Bought.class);
                yield new TurnUndoDto(row.getId(), BUY, bought.projectName(),
                        planets.findById(bought.planetId()).map(PlanetEntity::getName).orElse("?"), bought.price());
            }
            default -> {
                Scrapped scrapped = read(row, Scrapped.class);
                int ships = scrapped.ships().values().stream().mapToInt(Integer::intValue).sum();
                yield new TurnUndoDto(row.getId(), SCRAP, messages.get("undo.ships", ships),
                        systems.findById(scrapped.systemId()).map(system -> system.getName()).orElse("?"), null);
            }
        };
    }

    private <T> T read(TurnUndoEntity row, Class<T> type) {
        try {
            return json.readValue(row.getPayload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Строка отмены не читается: " + e.getMessage(), e);
        }
    }
}
