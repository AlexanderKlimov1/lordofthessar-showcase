package com.sddnw.server.service;

import com.sddnw.server.domain.ColonyProject;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.DiplomacyStance;
import com.sddnw.server.domain.enums.FuelTech;
import com.sddnw.server.dto.AdviceDto;
import com.sddnw.server.dto.ColonyProjectDto;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.PlanetRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Советник — field-backlog, пункт 23: что выбрал бы ИИ на месте игрока и почему, одной фразой.
 * <p>
 * <b>Правило одно с ИИ, второго свода нет.</b> Наука — те же нужды
 * ({@link AiEmpireService#wantedTechnologies}) и тот же шаг к ним ({@link ResearchService#advise}),
 * что у империи ИИ; стройка — тот же выбор по окупаемости ({@link AutoBuildRules#worthwhile}), что
 * у автостроя и ИИ. Советник ничего не меняет: решает по-прежнему игрок. Включён ли советник,
 * знает клиент — это настройка учётной записи, а в гостевой партии он включён по умолчанию.
 * <p>
 * <b>Стройку советует только зданиями</b> — как и автострой: корабли, базы и шпионы — решения о
 * флоте, расселении и разведке, а не о хозяйстве колонии, и одной фразы «почему» у них нет.
 */
@Service
public class AdvisorService {

    private final GameAccess gameAccess;
    private final ResearchService researchService;
    private final ResearchCatalog researchCatalog;
    private final AiEmpireService aiEmpireService;
    private final ColonyService colonyService;
    private final BuildingCatalog buildingCatalog;
    private final PlanetRepository planetRepository;
    private final PlayerTechnologyRepository technologyRepository;
    private final DiplomacyRelationRepository relationRepository;
    private final Messages messages;

    public AdvisorService(GameAccess gameAccess, ResearchService researchService, ResearchCatalog researchCatalog,
                          AiEmpireService aiEmpireService, ColonyService colonyService,
                          BuildingCatalog buildingCatalog, PlanetRepository planetRepository,
                          PlayerTechnologyRepository technologyRepository,
                          DiplomacyRelationRepository relationRepository, Messages messages) {
        this.gameAccess = gameAccess;
        this.researchService = researchService;
        this.researchCatalog = researchCatalog;
        this.aiEmpireService = aiEmpireService;
        this.colonyService = colonyService;
        this.buildingCatalog = buildingCatalog;
        this.planetRepository = planetRepository;
        this.technologyRepository = technologyRepository;
        this.relationRepository = relationRepository;
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public AdviceDto advice(UUID gameId, String accessToken) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity player = gameAccess.requirePlayer(game, accessToken);
        return new AdviceDto(research(game, player), colonies(player));
    }

    private AdviceDto.Research research(GameEntity game, PlayerEntity player) {
        Set<String> known = technologyRepository
                .findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId()).stream()
                .map(technology -> technology.getOptionCode())
                .collect(Collectors.toSet());
        Boolean atWar = relationRepository.findAllByPlayerId(player.getId()).stream()
                .anyMatch(relation -> relation.getStance() == DiplomacyStance.WAR);
        ResearchService.ResearchAdvice advice = researchService.advise(player,
                aiEmpireService.wantedTechnologies(known, atWar, game.getWardenholdVictory()));
        if (advice == null) {
            return null;
        }
        String optionName = researchCatalog.place(advice.optionCode()).option().name();
        String wantedName = advice.wantedCode() == null ? null
                : researchCatalog.place(advice.wantedCode()).option().name();
        String reason = advice.wantedCode() == null
                ? messages.get("advisor.why.recommended")
                : messages.get(whyKey(advice.wantedCode()), wantedName);
        return new AdviceDto.Research(advice.categoryCode(), advice.levelOrder(), advice.optionCode(), optionName,
                advice.optionCode().equals(advice.wantedCode()) ? null : wantedName, reason,
                advice.optionCode().equals(player.getResearchOptionCode()));
    }

    /** Почему эта нужда — по её месту в списке нужд ИИ ({@link AiEmpireService#wantedTechnologies}). */
    static String whyKey(String wanted) {
        if (ShipDesignRules.DRIVE_TECH.equals(wanted) || ShipDesignRules.FUEL_TECH.equals(wanted)) {
            return "advisor.why.ships";
        }
        if (ColonyProject.COLONY_SHIP_TECH.equals(wanted)) {
            return "advisor.why.colony";
        }
        if (ColonyProject.OUTPOST_SHIP_TECH.equals(wanted)) {
            return "advisor.why.outpost";
        }
        if (ColonyProject.TRANSPORT_TECH.equals(wanted)) {
            return "advisor.why.transport";
        }
        if (FuelTech.EXTENDED_TANKS_CODE.equals(wanted) || FuelTech.DEUTERIUM_FUEL_CELLS.getCode().equals(wanted)
                || FuelTech.IRIDIUM_FUEL_CELLS.getCode().equals(wanted)) {
            return "advisor.why.range";
        }
        if (AiEmpireService.BOARDING_TECH.equals(wanted)) {
            return "advisor.why.boarding";
        }
        return "advisor.why.economy";
    }

    private List<AdviceDto.Colony> colonies(PlayerEntity player) {
        List<PlanetEntity> colonies = planetRepository.findAllByOwnerPlayerId(player.getId()).stream()
                .filter(planet -> planet.getPopulation() != null && planet.getPopulation() > 0)
                .sorted(Comparator.comparing(PlanetEntity::getName))
                .toList();
        if (colonies.isEmpty()) {
            return List.of();
        }
        ColonyService.ColonyContext context = colonyService.context(colonies);
        List<AdviceDto.Colony> result = new ArrayList<>();
        for (PlanetEntity colony : colonies) {
            List<ColonyProjectDto> buildings = colonyService.available(colony, context).stream()
                    .filter(project -> buildingCatalog.byCode().containsKey(project.code()))
                    .filter(project -> !Boolean.TRUE.equals(ColonyProject.endless(project.code())))
                    .toList();
            String code = AutoBuildRules.worthwhile(buildingCatalog.byCode(), colonyService.jobs(colony),
                    buildings, null, building -> colonyService.reliefGain(colony, context, building));
            if (code == null) {
                continue;
            }
            ColonyProjectDto project = buildings.stream().filter(one -> one.code().equals(code))
                    .findFirst().orElseThrow();
            Boolean idle = colony.getProjectCode() == null
                    || ColonyProject.TRADE_GOODS.equals(colony.getProjectCode());
            result.add(new AdviceDto.Colony(colony.getId(), colony.getName(), code, project.name(),
                    project.paybackTurns(), idle, messages.get("advisor.why.payback", project.paybackTurns())));
        }
        return result;
    }
}
