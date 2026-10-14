package com.sddnw.server.service;

import com.sddnw.server.domain.ColonyProject;
import com.sddnw.server.domain.entity.FleetEncounterEntity;
import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.EncounterState;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.repository.FleetRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Что ИИ сделал за ушедшего человека — field-backlog, пункт 112: снимок до хода ИИ и сверка
 * после него.
 * <p>
 * <b>Сверкой, а не записью в каждом решении.</b> Решений у ИИ десятки дорог — стройка колоний,
 * очередь, выкуп, приказы флотам, отделение отряда, десант, встречи, — и строка, вписанная в
 * каждую, однажды забылась бы в новой (так в этом проекте уже пять раз молчали счётчики).
 * Сверка смотрит на СОСТОЯНИЕ: что стоит на стапеле колонии, куда летят флоты, как закрылась
 * встреча, — и любая новая дорога ИИ попадает в неё сама.
 * <p>
 * Только за людей, которых ведёт ИИ, и только когда такие есть: выборки здесь делаются изнутри
 * посчитанного хода, но ушедших людей единицы, а партии без них сверка не трогает вовсе.
 */
@Service
public class StewardRecorder {

    private final PresenceService presence;
    private final StewardService steward;
    private final EncounterService encounterService;
    private final FleetRepository fleetRepository;

    public StewardRecorder(PresenceService presence, StewardService steward,
                           EncounterService encounterService, FleetRepository fleetRepository) {
        this.presence = presence;
        this.steward = steward;
        this.encounterService = encounterService;
        this.fleetRepository = fleetRepository;
    }

    /** Снимок до хода ИИ: стройка колоний, цели флотов и ждущие встречи ушедших. */
    public record Snapshot(Set<UUID> away, Map<UUID, String> projects, Map<UUID, UUID> targets,
                           Map<FleetEncounterEntity, EncounterState> encounters) {
    }

    /** Ушедшие люди, которых сейчас ведёт ИИ. */
    private Set<UUID> away(TurnContext context) {
        return context.players().stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .filter(player -> Boolean.TRUE.equals(presence.aiDriven(player)))
                .map(PlayerEntity::getId)
                .collect(Collectors.toSet());
    }

    public Snapshot before(TurnContext context) {
        Set<UUID> away = away(context);
        if (away.isEmpty()) {
            return null;
        }
        Map<UUID, String> projects = new HashMap<>();
        Map<UUID, UUID> targets = new HashMap<>();
        for (UUID playerId : away) {
            for (PlanetEntity colony : context.coloniesOf(playerId)) {
                projects.put(colony.getId(), colony.getProjectCode());
            }
            for (FleetEntity fleet : fleetRepository.findAllByOwnerPlayerId(playerId)) {
                targets.put(fleet.getId(), fleet.getTargetSystemId());
            }
        }
        Map<FleetEncounterEntity, EncounterState> encounters = new HashMap<>();
        for (FleetEncounterEntity encounter : encounterService.pendingOf(context)) {
            if (away.contains(encounter.getFirstPlayerId()) || away.contains(encounter.getSecondPlayerId())) {
                encounters.put(encounter, encounter.getState());
            }
        }
        return new Snapshot(away, projects, targets, encounters);
    }

    public void after(TurnContext context, Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        UUID gameId = context.game().getId();
        Integer turn = context.turn();
        Map<UUID, String> systems = context.systems().stream()
                .collect(Collectors.toMap(StarSystemEntity::getId, StarSystemEntity::getName));
        Map<UUID, String> names = context.players().stream()
                .collect(Collectors.toMap(PlayerEntity::getId, PlayerEntity::getName));
        for (UUID playerId : snapshot.away()) {
            for (PlanetEntity colony : context.coloniesOf(playerId)) {
                String code = colony.getProjectCode();
                // Товары — то, на что колония встаёт сама, когда строить нечего: это не решение.
                if (code == null || ColonyProject.TRADE_GOODS.equals(code)
                        || code.equals(snapshot.projects().get(colony.getId()))) {
                    continue;
                }
                steward.record(gameId, playerId, turn, ColonyProject.isShip(code)
                        ? new MessageKey("steward.projectShip", colony.getName())
                        : new MessageKey("steward.project", colony.getName(), projectRef(code)));
            }
            List<FleetEntity> fleets = fleetRepository.findAllByOwnerPlayerId(playerId);
            for (FleetEntity fleet : fleets) {
                UUID target = fleet.getTargetSystemId();
                if (target == null || Objects.equals(target, snapshot.targets().get(fleet.getId()))) {
                    continue;
                }
                steward.record(gameId, playerId, turn, new MessageKey("steward.fleet", fleet.getShips(),
                        systems.getOrDefault(fleet.getStarSystemId(), "?"), systems.getOrDefault(target, "?")));
            }
        }
        snapshot.encounters().forEach((encounter, before) -> {
            String system = systems.getOrDefault(encounter.getStarSystemId(), "?");
            UUID first = encounter.getFirstPlayerId();
            UUID second = encounter.getSecondPlayerId();
            UUID attacker = encounter.getAttackerPlayerId();
            Boolean closed = !Boolean.TRUE.equals(encounter.getState().isPending());
            // Первый решал, если встреча ждала его; второй — если она закрылась не нападением первого.
            if (snapshot.away().contains(first) && before == EncounterState.WAITING_FIRST
                    && encounter.getState() != EncounterState.WAITING_FIRST) {
                note(gameId, turn, first, second, attacker, encounter, system, names);
            }
            if (second != null && snapshot.away().contains(second) && closed && !Objects.equals(attacker, first)) {
                note(gameId, turn, second, first, attacker, encounter, system, names);
            }
        });
    }

    private void note(UUID gameId, Integer turn, UUID decider, UUID opponent, UUID attacker,
                      FleetEncounterEntity encounter, String system, Map<UUID, String> names) {
        String key = !Objects.equals(attacker, decider) ? "steward.encounter.parted"
                : Boolean.TRUE.equals(encounter.getBombard()) ? "steward.encounter.bombed"
                : "steward.encounter.attacked";
        steward.record(gameId, decider, turn,
                new MessageKey(key, system, opponent == null ? "?" : names.getOrDefault(opponent, "?")));
    }

    /**
     * Проект стройки ссылкой, которую переведут при чтении: здание — ссылкой на справочник,
     * особый проект — ключом своего названия (тем же, что у списка стройки).
     */
    static String projectRef(String code) {
        return switch (code) {
            case ColonyProject.HOUSING -> "colony.project.housing.name";
            case ColonyProject.SPY -> "colony.project.spy.name";
            case ColonyProject.FREIGHTER -> "colony.project.freighter.name";
            case ColonyProject.COLONY_BASE -> "colony.project.colonyBase.name";
            case ColonyProject.COLONY_SHIP -> "colony.project.colonyShip.name";
            case ColonyProject.OUTPOST_SHIP -> "colony.project.outpostShip.name";
            case ColonyProject.TRANSPORT -> "colony.project.transport.name";
            default -> CatalogTexts.building(code);
        };
    }
}
