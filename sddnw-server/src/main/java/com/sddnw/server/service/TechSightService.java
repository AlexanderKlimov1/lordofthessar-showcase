package com.sddnw.server.service;

import com.sddnw.server.domain.Building;
import com.sddnw.server.domain.ShipComponent;
import com.sddnw.server.domain.WeaponModification;
import com.sddnw.server.domain.entity.ObservedTechEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import com.sddnw.server.domain.enums.ScannerTech;
import com.sddnw.server.repository.ObservedTechRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Что игрок знает о технологиях соперника — п. 15, туман войны (решение хозяина проекта,
 * 03.10.2026, в отступление от оригинала: там доклад о расе перечислял изученное соседом).
 * <p>
 * Знают только УВИДЕННОЕ, и путей увидеть два:
 * <ul>
 *   <li><b>бой</b> — флоты встретились в одной системе, и у игрока была возможность нажать
 *   «осмотр» на корабли противника (нажимал он или нет): открывается всё, что стояло на
 *   любом из кораблей и платформ противника в этом бою, а если бой шёл у его колонии — и
 *   технологии её построек ({@link #observeBattle});</li>
 *   <li><b>сканеры флота</b> — боя не было, но свой стоящий флот в одной системе с чужим флотом
 *   или колонией: открываются технологии их кораблей и построек, но лишь до того уровня
 *   дерева, что позволяют сканеры ({@link #scannerTier}, {@link #levelLimit}). То же правило
 *   открывает и то, чего противник в бою не применял.</li>
 * </ul>
 * Числа уровней — реконструкция по просьбе хозяина «уровень открываемых технологий напрямую
 * зависит от уровня сканеров»: каждая ступень сканера раскрывает на два уровня дерева больше.
 * <p>
 * Увиденное читают доклад о расе и обмен технологиями (просить можно только то, о чём
 * знаешь), а ИИ — оценивая силу защитников колонии.
 */
@Service
public class TechSightService {

    /**
     * Сколько уровней дерева раскрывает ступень сканеров: без сканеров ничего, космический
     * сканер — до третьего уровня, тахионный — до пятого, нейтронный — до седьмого, «Сенсоры» —
     * до девятого; боевой сканер на корабле флота добавляет ступень, и с ним при «Сенсорах»
     * видно всё. Реконструкция — см. javadoc класса.
     */
    private static final int[] LEVEL_LIMIT = {0, 3, 5, 7, 9, Integer.MAX_VALUE};

    /** Модуль корабля, который добавляет флоту ступень сканеров. */
    static final String BATTLE_SCANNER = "battle-scanner";

    private final ObservedTechRepository repository;
    private final ShipDesignService shipDesignService;
    private final ShipCatalog shipCatalog;
    private final ResearchCatalog researchCatalog;
    private final com.sddnw.server.repository.PlayerTechnologyRepository playerTechnologyRepository;

    public TechSightService(ObservedTechRepository repository, ShipDesignService shipDesignService,
                            ShipCatalog shipCatalog, ResearchCatalog researchCatalog,
                            com.sddnw.server.repository.PlayerTechnologyRepository playerTechnologyRepository) {
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.repository = repository;
        this.shipDesignService = shipDesignService;
        this.shipCatalog = shipCatalog;
        this.researchCatalog = researchCatalog;
    }

    /** Ступень сканеров флота: изученный сканер империи плюс боевой сканер на борту. */
    public int scannerTier(Set<String> technologies, Boolean battleScanner) {
        int tier = 0;
        ScannerTech[] all = ScannerTech.values();
        for (int index = 0; index < all.length; index++) {
            if (technologies.contains(all[index].getCode())) {
                tier = index + 1;
            }
        }
        if (Boolean.TRUE.equals(battleScanner)) {
            tier++;
        }
        return Math.min(tier, LEVEL_LIMIT.length - 1);
    }

    /** До какого уровня дерева ступень сканеров раскрывает технологии. */
    public int levelLimit(int tier) {
        return LEVEL_LIMIT[Math.max(0, Math.min(tier, LEVEL_LIMIT.length - 1))];
    }

    /** Есть ли во флоте боевой сканер — по составам его проектов. */
    public Boolean carriesBattleScanner(Collection<List<ShipDesignRules.Item>> designs) {
        return designs.stream().flatMap(List::stream)
                .anyMatch(item -> BATTLE_SCANNER.equals(item.component().code()));
    }

    /** Технологии, стоящие на проектах: корпус, системы, модификации стволов. */
    public Set<String> designTechs(Collection<ShipDesignEntity> designs) {
        Set<String> codes = new TreeSet<>();
        Map<UUID, List<ShipDesignRules.Item>> items = shipDesignService.itemsOf(designs);
        for (ShipDesignEntity design : designs) {
            String hull = shipCatalog.hull(design.getHullCode()).requiredTechCode();
            if (hull != null) {
                codes.add(hull);
            }
            for (ShipDesignRules.Item item : items.getOrDefault(design.getId(), List.of())) {
                ShipComponent component = item.component();
                if (component.requiredTechCode() != null) {
                    codes.add(component.requiredTechCode());
                }
                for (WeaponModification modification : item.modifications()) {
                    if (modification.requiredTechCode() != null) {
                        codes.add(modification.requiredTechCode());
                    }
                }
            }
        }
        return codes;
    }

    /** Технологии построек колонии. */
    public Set<String> buildingTechs(Collection<Building> buildings) {
        Set<String> codes = new TreeSet<>();
        for (Building building : buildings) {
            if (building.requiredTechCode() != null) {
                codes.add(building.requiredTechCode());
            }
        }
        return codes;
    }

    /** Только те технологии, что ступень сканеров позволяет разглядеть. */
    public Set<String> withinScanners(Set<String> codes, int tier) {
        int limit = levelLimit(tier);
        if (limit <= 0) {
            return Set.of();
        }
        Map<String, ResearchCatalog.TechnologyPlace> places = researchCatalog.places();
        Set<String> seen = new TreeSet<>();
        for (String code : codes) {
            ResearchCatalog.TechnologyPlace place = places.get(code);
            if (place != null && place.levelOrder() <= limit) {
                seen.add(code);
            }
        }
        return seen;
    }

    /**
     * Записывает увиденное: игрок {@code viewerId} узнал эти технологии соперника. Уже
     * известное не дублируется.
     */
    @Transactional
    public List<String> observe(UUID gameId, UUID viewerId, UUID otherId, Collection<String> codes, Integer turn) {
        if (codes.isEmpty() || viewerId.equals(otherId)) {
            return List.of();
        }
        Set<String> known = new HashSet<>();
        repository.findAllByPlayerIdAndOtherPlayerId(viewerId, otherId)
                .forEach(row -> known.add(row.getTechCode()));
        List<ObservedTechEntity> fresh = new ArrayList<>();
        for (String code : new TreeSet<>(codes)) {
            if (known.add(code)) {
                ObservedTechEntity row = new ObservedTechEntity();
                row.setGameId(gameId);
                row.setPlayerId(viewerId);
                row.setOtherPlayerId(otherId);
                row.setTechCode(code);
                row.setObservedTurn(turn);
                fresh.add(row);
            }
        }
        repository.saveAll(fresh);
        return fresh.stream().map(ObservedTechEntity::getTechCode).toList();
    }

    /**
     * Бой — п. 8, п. 15: каждая сторона видит всё, что стояло на кораблях и платформах
     * другой, и постройки колонии, у которой шёл бой. Видит независимо от того, нажимал ли
     * игрок «осмотр»: возможность была — значит, знание есть.
     *
     * @param sides    игрок → проекты его кораблей и платформ в бою
     * @param colonies игрок → постройки его колоний в системе боя
     */
    @Transactional
    public Map<UUID, Set<String>> observeBattle(UUID gameId, Integer turn, Map<UUID, Collection<ShipDesignEntity>> sides,
                                               Map<UUID, Collection<Building>> colonies) {
        // Впервые увиденное каждым смотрящим — для строки «у врага замечено» (field-backlog, пункт 134).
        Map<UUID, Set<String>> freshBy = new HashMap<>();
        Map<UUID, Set<String>> shown = new HashMap<>();
        sides.forEach((owner, designs) -> shown.computeIfAbsent(owner, key -> new TreeSet<>())
                .addAll(designTechs(designs)));
        colonies.forEach((owner, buildings) -> shown.computeIfAbsent(owner, key -> new TreeSet<>())
                .addAll(buildingTechs(buildings)));
        for (UUID viewer : shown.keySet()) {
            for (Map.Entry<UUID, Set<String>> other : shown.entrySet()) {
                if (!other.getKey().equals(viewer)) {
                    freshBy.computeIfAbsent(viewer, key -> new TreeSet<>())
                            .addAll(observe(gameId, viewer, other.getKey(), other.getValue(), turn));
                }
            }
        }
        return freshBy;
    }

    /** Что изучено самим игроком — для строки «у врага замечено то, чего нет у вас» (пункт 134). */
    @Transactional(readOnly = true)
    public Set<String> knownBy(UUID playerId) {
        Set<String> codes = new TreeSet<>();
        playerTechnologyRepository.findAllByPlayerIdIn(List.of(playerId))
                .forEach(row -> codes.add(row.getOptionCode()));
        return codes;
    }

    /** Что игрок знает о технологиях соперника. */
    @Transactional(readOnly = true)
    public Set<String> observedBy(UUID viewerId, UUID otherId) {
        Set<String> codes = new TreeSet<>();
        repository.findAllByPlayerIdAndOtherPlayerId(viewerId, otherId).forEach(row -> codes.add(row.getTechCode()));
        return codes;
    }

    /** Всё увиденное в партии: смотрящий → соперник → технологии. Одна выборка — для фазы ИИ. */
    public Map<UUID, Map<UUID, Set<String>>> observedInGame(UUID gameId) {
        Map<UUID, Map<UUID, Set<String>>> all = new HashMap<>();
        for (ObservedTechEntity row : repository.findAllByGameId(gameId)) {
            all.computeIfAbsent(row.getPlayerId(), key -> new HashMap<>())
                    .computeIfAbsent(row.getOtherPlayerId(), key -> new TreeSet<>())
                    .add(row.getTechCode());
        }
        return all;
    }
}
