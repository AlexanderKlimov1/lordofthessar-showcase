package com.sddnw.server.service;

import com.sddnw.server.domain.ShipComponent;
import com.sddnw.server.domain.WeaponModification;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import com.sddnw.server.dto.AcquiredTechnologyDto;
import com.sddnw.server.dto.FleetGroupDto;
import com.sddnw.server.dto.FleetShipOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Разбор трофеев — п. 8, абордаж: «captured ships can be scrapped for tech» (StrategyWiki,
 * Physics). Взятый корабль, пущенный на слом, приносит одну технологию из своего состава,
 * которой у захватчика ещё нет: двигатель, броню, щит, компьютер, оружие или модификацию
 * ствола. За каждый корабль — одна, по коду технологии (порядок выводится из справочника, а
 * не из базы, и партия повторяется). Изучено всё — разбор ничего не даёт.
 * <p>
 * Стоит отдельно от {@code FleetService}: выдача технологии — дело {@code ResearchService},
 * а флоту зависеть от исследований незачем.
 */
@Service
public class SalvageService {

    private static final Logger log = LoggerFactory.getLogger(SalvageService.class);

    private final FleetService fleetService;
    private final ShipDesignService shipDesignService;
    private final ResearchService researchService;

    /** Журнал отмены в пределах хода — пункт 74. Полем: конструктор и так длинен. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TurnUndoService undo;

    public SalvageService(FleetService fleetService, ShipDesignService shipDesignService,
                          ResearchService researchService) {
        this.fleetService = fleetService;
        this.shipDesignService = shipDesignService;
        this.researchService = researchService;
    }

    /**
     * Что принесёт разбор одного трофея этого проекта — технологии его состава, которых у
     * разбирающего нет, по коду (порядок выводится из справочника). Пусто — разбирать незачем:
     * ИИ спрашивает это ДО слома, чтобы не пускать на металл боевой корабль даром.
     */
    public Set<String> fresh(ShipDesignEntity design, Set<String> known) {
        Set<String> fresh = new TreeSet<>();
        if (!Boolean.TRUE.equals(design.getTrophy())) {
            return fresh;
        }
        for (ShipDesignRules.Item item : shipDesignService.itemsOf(List.of(design))
                .getOrDefault(design.getId(), List.of())) {
            ShipComponent component = item.component();
            if (component.requiredTechCode() != null) {
                fresh.add(component.requiredTechCode());
            }
            for (WeaponModification modification : item.modifications()) {
                if (modification.requiredTechCode() != null) {
                    fresh.add(modification.requiredTechCode());
                }
            }
        }
        fresh.removeAll(known);
        return fresh;
    }

    /** Списание с разбором трофеев: сперва что они принесут, потом сам слом. */
    @Transactional
    public List<FleetGroupDto> scrap(GameEntity game, PlayerEntity player, UUID fleetId, List<FleetShipOrder> order) {
        Map<UUID, ShipDesignEntity> designs = shipDesignService.allDesignsOf(player);
        List<String> wanted = new ArrayList<>();
        Set<String> known = shipDesignService.technologiesOf(player);
        for (FleetShipOrder ship : order) {
            ShipDesignEntity design = designs.get(ship.designId());
            if (design == null || !Boolean.TRUE.equals(design.getTrophy())) {
                continue;
            }
            Set<String> fresh = fresh(design, known);
            fresh.removeAll(wanted);
            for (int i = 0; i < ship.ships() && !fresh.isEmpty(); i++) {
                String code = fresh.iterator().next();
                fresh.remove(code);
                wanted.add(code);
            }
        }
        UUID standing = fleetService.standingSystemOf(fleetId);
        List<FleetGroupDto> left = fleetService.scrap(player, fleetId, order);
        // Списание отменяется до конца хода (пункт 74) — но не трофея, принёсшего технологию:
        // её назад не забрать.
        if (undo != null && wanted.isEmpty() && standing != null) {
            Map<UUID, Integer> ships = new java.util.LinkedHashMap<>();
            order.forEach(ship -> ships.merge(ship.designId(), ship.ships(), Integer::sum));
            undo.record(game, player, TurnUndoService.SCRAP, new TurnUndoService.Scrapped(standing, ships));
        }
        if (!wanted.isEmpty()) {
            List<AcquiredTechnologyDto> got = researchService.grantByCode(player, wanted, game.getTurn());
            log.info("Игрок {} разобрал трофеи и получил технологии: {}", player.getName(),
                    got.stream().map(AcquiredTechnologyDto::optionCode).toList());
        }
        return left;
    }
}
