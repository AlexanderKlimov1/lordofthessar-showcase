package com.sddnw.server.service;

import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.ShipComponent;
import com.sddnw.server.domain.WeaponModification;
import com.sddnw.server.domain.ShipHull;
import com.sddnw.server.domain.ShipStats;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.ShipDesignComponentEntity;
import com.sddnw.server.domain.entity.ShipDesignEntity;
import com.sddnw.server.domain.enums.FuelTech;
import com.sddnw.server.domain.enums.ShipComponentSlot;
import com.sddnw.server.domain.enums.ShipEffectType;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.enums.ShipMission;
import com.sddnw.server.domain.enums.ShipRole;
import com.sddnw.server.domain.enums.WeaponKind;
import com.sddnw.server.dto.SaveShipDesignRequest;
import com.sddnw.server.dto.ShipCatalogDto;
import com.sddnw.server.dto.ShipComponentDto;
import com.sddnw.server.dto.ShipDesignComponentDto;
import com.sddnw.server.dto.ShipDesignDto;
import com.sddnw.server.dto.WeaponModificationDto;
import com.sddnw.server.dto.ShipEffectDto;
import com.sddnw.server.dto.ShipHullDto;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import com.sddnw.server.repository.ShipDesignComponentRepository;
import com.sddnw.server.repository.ShipDesignRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Проекты кораблей империи — п. 8, окно Ship Design MOO II.
 * <p>
 * <b>Ячейки.</b> У империи шесть ячеек проектов. Игрок переделывает ячейку сколько
 * угодно, но построенные корабли остаются прежними: старый проект помечается
 * {@code obsolete} и продолжает жить у флота, а новый занимает ячейку и с этого хода
 * строится колониями. Так переделка проекта не меняет задним числом корабли, которые
 * давно в строю.
 * <p>
 * <b>Что можно поставить.</b> Только то, чья технология изучена. Проверка идёт здесь, а не
 * на клиенте: экран показывает недоступное серым, но верить ему нельзя.
 * <p>
 * <b>Числа.</b> Все характеристики считает {@link ShipDesignRules} из справочника
 * {@link ShipCatalog}. Здесь остаются доступ, проверки и сборка ответа.
 */
@Service
public class ShipDesignService {

    private static final Logger log = LoggerFactory.getLogger(ShipDesignService.class);

    /**
     * Ячеек проектов у империи — восемь (09.10.2026, решение хозяина проекта; в MOO II их шесть).
     * Сама игра свои проекты кладёт только в первые {@link #AUTO_DESIGNS}: две верхние остаются
     * игроку, а ИИ собирает флот так же, как прежде, — его поведение и замеры не сдвинулись.
     */
    public static final Integer MAX_DESIGNS = 8;

    /** Ячейки, которые игра держит заполненными своими проектами ({@link #ensureAutoDesigns}). */
    static final Integer AUTO_DESIGNS = 6;

    /** Имя стартового проекта: с ним империя входит в партию. */
    /**
     * Имя проекта по умолчанию — п. 3.5: ХРАНИТСЯ в базе, поэтому английское. Имя, взятое
     * на языке того, кто нажал кнопку, показывалось бы соседу по партии как есть.
     */
    private static final String DEFAULT_DESIGN_NAME = "Frigate";

    /**
     * Служебная ячейка гражданских кораблей — п. 8.
     * <p>
     * Ноль, а не одна из шести: в MOO II колониальный корабль и транспорт стоят в списке
     * стройки готовыми и ячейку дизайна не занимают. Окно дизайна показывает ячейки с
     * первой, поэтому нулевая в него и не попадает.
     */
    public static final Integer CIVIL_SLOT = 0;

    private static final String COLONY_SHIP_NAME = "Colony Ship";

    private static final String OUTPOST_SHIP_NAME = "Outpost Ship";

    private static final String TRANSPORT_NAME = "Transport";

    /** Код и технологии, и модуля противоракет — у них он один, как у баков (пункт 30). */
    private static final String ANTI_MISSILE_ROCKETS = "anti-missile-rockets";

    /** Модификации, которые игра ставит на стволы эскорта и штурмовика (ShipMission). */
    private static final String POINT_DEFENCE = "point-defense";
    private static final String ARMOUR_PIERCING = "armor-piercing";

    /**
     * Средства абордажа в порядке предпочтения ИИ (п. 8): транспортёр берёт корабль с
     * двенадцати клеток, лишь бы щит был опущен, — лучшее; шаттлы летят сами и берут без
     * условий, но их сбивает ближняя оборона; тяговый луч только держит цель для абордажа
     * вплотную. Ставится одно — первое изученное.
     */
    private static final List<String> BOARDING_MEANS = List.of("transporters", "assault-shuttles", "tractor-beam");

    /** Десантные отсеки — вдвое больше пехоты на борту (п. 8). */
    private static final String TROOP_PODS = "troop-pods";


    private final Messages messages;
    private final ShipCatalog shipCatalog;

    /**
     * Колонии владельца — перевести их стройку и очередь на новый проект ячейки
     * ({@link #retargetProjects}). Полем, а не параметром конструктора: службу собирают руками
     * тесты, которым колонии не нужны.
     */
    @org.springframework.beans.factory.annotation.Autowired
    private com.sddnw.server.repository.PlanetRepository planetRepository;
    private final ShipDesignRules shipDesignRules;
    private final ShipDesignRepository shipDesignRepository;
    private final ShipDesignComponentRepository shipDesignComponentRepository;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final RaceService raceService;

    /** Правила обороны колонии — п. 8, п. 11: какие корпуса бывают платформами и в каких ячейках. */
    private final OrbitalDefenceRules orbitalDefenceRules;

    /** Дерево технологий: по нему код нужной технологии превращается в её название. */
    private final ResearchCatalog researchCatalog;

    /**
     * Чего не хватает для кораблестроения — словами игроку, на его языке; {@code null},
     * когда строить уже можно. Список недостающего знает правило, текст — словарь.
     */
    private String shipbuildingRequirement(Set<String> technologies) {
        List<String> missing = shipDesignRules.missingShipbuildingTechnologies(technologies);
        return missing.isEmpty() ? null
                : messages.get("ship.noShipbuilding", String.join(", ", missing.stream().map(this::techName).toList()));
    }

    public ShipDesignService(ShipCatalog shipCatalog,
                             Messages messages,
                             ShipDesignRules shipDesignRules,
                             ShipDesignRepository shipDesignRepository,
                             ShipDesignComponentRepository shipDesignComponentRepository,
                             PlayerTechnologyRepository playerTechnologyRepository,
                             RaceService raceService,
                             OrbitalDefenceRules orbitalDefenceRules,
                             ResearchCatalog researchCatalog) {
        this.orbitalDefenceRules = orbitalDefenceRules;
        this.researchCatalog = researchCatalog;
        this.messages = messages;
        this.shipCatalog = shipCatalog;
        this.shipDesignRules = shipDesignRules;
        this.shipDesignRepository = shipDesignRepository;
        this.shipDesignComponentRepository = shipDesignComponentRepository;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.raceService = raceService;
    }

    /**
     * Что империя может поставить в проект прямо сейчас — п. 8.
     * <p>
     * Пока не изучены базовые уровни Power и Chemistry, окно дизайна открывать не над чем:
     * ни двигателя, ни топлива у империи нет. Справочник всё равно приходит целиком —
     * экран показывает, чего ждать, а не пустоту.
     */
    @Transactional(readOnly = true)
    public ShipCatalogDto catalog(PlayerEntity player) {
        Set<String> technologies = technologies(player);

        List<ShipHullDto> hulls = shipCatalog.hulls().stream()
                // Платформы обороны в окне дизайна не показываются вовсе: их не собирают,
                // а строят зданием колонии, и вооружает их игра сама — п. 8, п. 11.
                .filter(hull -> !Boolean.TRUE.equals(hull.platform()))
                // Мегафлюксеры (п. 8): окно дизайна получает место корпуса уже увеличенным.
                .map(hull -> enlarged(hull, technologies))
                .map(hull -> new ShipHullDto(
                        hull.code(),
                        hull.name(),
                        hull.description(),
                        hull.sortOrder(),
                        hull.space(),
                        hull.cost(),
                        hull.structure(),
                        hull.command(),
                        hull.hitChancePercent(),
                        hull.systemFactor(),
                        hull.requiredTechCode(),
                        techName(hull.requiredTechCode()),
                        researched(hull.requiredTechCode(), technologies)))
                .toList();

        List<ShipComponentDto> components = shipCatalog.components().stream()
                .map(component -> new ShipComponentDto(
                        component.code(),
                        component.name(),
                        component.description(),
                        component.slot(),
                        component.space(),
                        component.cost(),
                        component.scalesWithHull(),
                        component.weaponKind(),
                        component.envelops(),
                        component.requiredTechCode(),
                        techName(component.requiredTechCode()),
                        researched(component.requiredTechCode(), technologies),
                        effectLines(component),
                        shipCatalog.modifications().stream()
                                .filter(modification ->
                                        Boolean.TRUE.equals(component.carries(modification)))
                                .map(WeaponModification::code)
                                .toList()))
                .toList();

        List<WeaponModificationDto> modifications = shipCatalog.modifications().stream()
                .map(modification -> new WeaponModificationDto(
                        modification.code(),
                        modification.name(),
                        modification.description(),
                        modification.damagePercent(),
                        modification.attackPercent(),
                        modification.shotsPercent(),
                        modification.rangePercent(),
                        modification.costPercent(),
                        modification.spacePercent(),
                        modification.piercesArmour(),
                        modification.piercesShield(),
                        modification.envelops(),
                        modification.noRangePenalty(),
                        modification.halvesEvasion(),
                        modification.missileArmourPercent(),
                        modification.missileSpeed(),
                        modification.hitsEngine(),
                        modification.requiredTechCode(),
                        researched(modification.requiredTechCode(), technologies)))
                .toList();

        return new ShipCatalogDto(hulls, components, modifications, MAX_DESIGNS,
                shipDesignRules.shipbuildingAvailable(technologies),
                shipbuildingRequirement(technologies),
                ShipDesignRules.HULL_SIZE_WITHOUT_STAR_BASE);
    }

    /**
     * Проект корабля как вариант стройки колонии — п. 8 и п. 10.
     *
     * @param cost во что обойдётся корабль колонии, единиц производства
     */
    public record ShipBuildOption(
            UUID designId,
            String name,
            String hullName,
            /** Размер корпуса 1..6: без звёздной базы колония поднимает только первые два. */
            Integer hullSize,
            Integer cost,
            Integer attack,
            Integer defense,
            /** Задача проекта игры (ShipMission); у проекта игрока — пусто. */
            ShipMission mission,
            /**
             * Доля ракет в залпе проекта, в процентах: по ней ИИ видит, что сосед воюет
             * ракетами, и добавляет в строй эскорт (ShipMission.ESCORT).
             */
            Integer missilePercent
    ) {
    }

    /**
     * Что колонии империй могут строить из кораблей — п. 8.
     * <p>
     * Спрашивается на весь набор колоний разом: список стройки собирается для каждой
     * планеты карты, и ходить за проектами по колонии — та же тройная выборка, от которой
     * ушёл конец хода.
     *
     * @param races расы владельцев: атака и защита проекта у разных рас разные — п. 7
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<ShipBuildOption>> buildOptions(Map<UUID, RaceEffects> races) {
        if (races.isEmpty()) {
            return Map.of();
        }

        // Гражданские корабли в этот список не попадают: у них своя строка стройки и своя
        // твёрдая цена — п. 8. Иначе колониальный корабль оказался бы в списке дважды,
        // причём вторым разом по цене собранного корпуса, а не по цене оригинала.
        List<ShipDesignEntity> designs =
                shipDesignRepository.findAllByOwnerPlayerIdInAndObsoleteFalseOrderBySlotAsc(races.keySet())
                        .stream()
                        .filter(design -> Boolean.TRUE.equals(design.getRole().isCombat()))
                        .toList();
        Map<UUID, List<ShipDesignRules.Item>> items = itemsOf(designs);

        Map<UUID, List<ShipBuildOption>> options = new LinkedHashMap<>();
        for (ShipDesignEntity design : designs) {
            ShipHull hull = shipCatalog.hull(design.getHullCode());
            ShipStats stats = shipDesignRules.stats(
                    hull,
                    items.getOrDefault(design.getId(), List.of()),
                    races.getOrDefault(design.getOwnerPlayerId(), RaceEffects.NONE));
            options.computeIfAbsent(design.getOwnerPlayerId(), key -> new ArrayList<>())
                    .add(new ShipBuildOption(design.getId(), messages.shown(design.getName()), hull.name(),
                            hull.sortOrder(), stats.cost(), stats.attack(), stats.defense(),
                            design.getMission(),
                            missilePercent(items.getOrDefault(design.getId(), List.of()))));
        }
        return options;
    }

    /**
     * Действующие проекты игрока — те, что стоят в шести ячейках окна дизайна.
     * <p>
     * Гражданские корабли сюда не входят: в MOO II их не собирают и не переделывают, и
     * ячейки они не занимают — п. 8.
     */
    @Transactional(readOnly = true)
    public List<ShipDesignDto> designs(PlayerEntity player) {
        List<ShipDesignEntity> designs =
                shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                        .stream()
                        .filter(design -> Boolean.TRUE.equals(design.getRole().isCombat()))
                        .toList();
        return toDtos(designs, raceService.effects(player));
    }

    /**
     * Сохраняет проект в ячейку — п. 8.
     * <p>
     * Ячейка не правится на месте: прежний проект остаётся жить у построенных кораблей,
     * а новый занимает его место. Поэтому у одной ячейки со временем накапливается
     * история проектов, из которой действующий — ровно один.
     */
    @Transactional
    public ShipDesignDto save(GameEntity game, PlayerEntity player, SaveShipDesignRequest request) {
        ShipHull hull = shipCatalog.hull(request.hullCode());
        Set<String> technologies = technologies(player);
        if (!Boolean.TRUE.equals(shipDesignRules.shipbuildingAvailable(technologies))) {
            throw new ConflictException("ship.noShipbuilding",
                    String.join(", ", shipDesignRules.missingShipbuildingTechnologies(technologies).stream()
                            .map(this::techName).toList()));
        }
        if (!researched(hull.requiredTechCode(), technologies)) {
            throw new ConflictException("ship.hullNotResearched", hull.name());
        }

        List<ShipDesignRules.Item> items = requireItems(request, technologies);
        validate(enlarged(hull, technologies), items);

        List<UUID> replacedIds = new ArrayList<>();
        shipDesignRepository.findByOwnerPlayerIdAndSlotAndObsoleteFalse(player.getId(), request.slot())
                .ifPresent(previous -> {
                    previous.setObsolete(Boolean.TRUE);
                    shipDesignRepository.save(previous);
                    replacedIds.add(previous.getId());
                });

        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(game.getId());
        design.setOwnerPlayerId(player.getId());
        design.setSlot(request.slot());
        design.setName(request.name().trim());
        design.setHullCode(hull.code());
        design.setCreatedTurn(game.getTurn());
        design.setObsolete(Boolean.FALSE);
        shipDesignRepository.saveAndFlush(design);
        saveComponents(design, items);
        replacedIds.forEach(previous -> retargetProjects(player.getId(), previous, design.getId()));

        log.info("Игрок {} сохранил проект «{}» в ячейку {}: корпус {}, {} компонентов",
                player.getName(), design.getName(), design.getSlot(), hull.name(), items.size());
        return toDto(design, items, raceService.effects(player));
    }

    /**
     * Убирает проект из ячейки — п. 8. Корабли, построенные по нему, остаются в строю:
     * проект помечается вытесненным, а не стирается.
     */
    @Transactional
    public void delete(PlayerEntity player, UUID designId) {
        ShipDesignEntity design = requireOwn(player, designId);
        List<ShipDesignEntity> current =
                shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId());
        if (current.size() <= 1) {
            throw new ConflictException("ship.lastDesign");
        }
        design.setObsolete(Boolean.TRUE);
        shipDesignRepository.save(design);
        retargetProjects(player.getId(), design.getId(), null);
        log.info("Игрок {} убрал проект «{}» из ячейки {}", player.getName(), design.getName(), design.getSlot());
    }

    /**
     * Стартовый проект империи — п. 8: фрегат с ядерным двигателем и тем, что дано без
     * исследования (обшивку и стволы теперь изучают, 12.10.2026). Ответ на вопрос, что строить
     * колонии, пока игрок не открыл окно дизайна, — у партий, начатых до шести ячеек.
     */
    @Transactional
    public ShipDesignEntity createDefaultDesign(GameEntity game, PlayerEntity player) {
        ShipHull hull = shipCatalog.cheapestHull();
        List<ShipDesignRules.Item> items = defaultItems(hull);

        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(game.getId());
        design.setOwnerPlayerId(player.getId());
        design.setSlot(1);
        design.setName(DEFAULT_DESIGN_NAME);
        design.setHullCode(hull.code());
        design.setCreatedTurn(game.getTurn());
        design.setObsolete(Boolean.FALSE);
        shipDesignRepository.saveAndFlush(design);
        saveComponents(design, items);
        return design;
    }

    /**
     * Держит шесть ячеек дизайна заполненными — п. 8.
     * <p>
     * В MOO II ячейки не пустуют: игра сама предлагает готовые проекты под нынешний
     * уровень технологий, и в списке стройки колонии всегда стоят корабли, а не пустота.
     * Здесь так же: на каждый корпус, который империя уже вправе строить, заводится свой
     * проект — фрегат, эсминец, крейсер и дальше, сколько открыто технологиями.
     * <p>
     * Собранное игрой и собранное игроком различаются признаком {@code auto}: первое игра
     * переписывает, когда изучены новые двигатель, броня или пушка, второе не трогает
     * никогда — за него отвечает игрок.
     *
     * @return действующие проекты империи после пополнения
     */
    @Transactional
    public List<ShipDesignEntity> ensureAutoDesigns(UUID gameId, PlayerEntity player, Integer turn) {
        Set<String> technologies = technologies(player);
        List<ShipDesignEntity> designs = new ArrayList<>(
                shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                        .stream()
                        .filter(design -> Boolean.TRUE.equals(design.getRole().isCombat()))
                        .toList());

        List<ShipHull> hulls = shipCatalog.hulls().stream()
                // Платформы обороны в шесть ячеек не идут: они не корабли, а здания
                // колонии, и проект у них свой — см. ensurePlatformDesigns.
                .filter(hull -> !Boolean.TRUE.equals(hull.platform()))
                .filter(hull -> researched(hull.requiredTechCode(), technologies))
                .sorted(Comparator.comparing(ShipHull::sortOrder))
                .limit(AUTO_DESIGNS)
                .toList();
        Set<Integer> taken = designs.stream()
                .filter(design -> !Boolean.TRUE.equals(design.getAuto()))
                .map(ShipDesignEntity::getSlot)
                .filter(slot -> slot <= AUTO_DESIGNS)
                .collect(Collectors.toSet());

        // СОСТАВЫ БЕРУТСЯ ОДНОЙ ВЫБОРКОЙ, а не по проекту в цикле. Ниже каждый проект
        // сверяется с тем, что даёт нынешняя наука, и прежде эта сверка ходила в базу
        // отдельно за каждым — шесть запросов на вызов. А зовут этот метод из
        // `ResearchService.store`, то есть КАЖДЫЙ РАЗ, когда империя что-нибудь изучила, и
        // изнутри посчитанного хода, где всякий запрос вдобавок заставляет Hibernate
        // сбросить в базу всё, что ход успел изменить. Выборка стеков на круге 7 показала
        // `items` и `itemsOf` вместе почти третью всего хода.
        Map<UUID, List<ShipDesignRules.Item>> current = itemsOf(designs);

        int next = 0;
        for (Plan plan : plans(player, hulls, technologies, AUTO_DESIGNS - taken.size())) {
            ShipHull hull = plan.hull();
            // Ячейки игрока не трогаем вовсе: он их и занял. Проект игры под этот корпус
            // либо обновляется до нынешних технологий, либо заводится заново.
            next++;
            while (next <= AUTO_DESIGNS && taken.contains(next)) {
                next++;
            }
            if (next > AUTO_DESIGNS) {
                break;
            }
            final int slot = next;
            ShipDesignEntity mine = designs.stream()
                    .filter(design -> Boolean.TRUE.equals(design.getAuto()))
                    .filter(design -> design.getSlot() == slot)
                    .findFirst()
                    .orElse(null);
            List<ShipDesignRules.Item> items = plan.items();
            if (mine == null) {
                designs.add(createAutoDesign(gameId, player, turn, slot, hull, items, plan.mission()));
            } else {
                ShipDesignEntity fresh = replaceAutoDesign(gameId, player, turn, mine, hull, items,
                        current.getOrDefault(mine.getId(), List.of()), plan.mission());
                if (fresh != mine) {
                    designs.remove(mine);
                    designs.add(fresh);
                }
            }
        }
        return designs;
    }

    /**
     * Проект трофея — п. 8, абордаж: копия чужого проекта у захватчика, чтобы взятый корабль
     * встал в его флот. Копия сразу ВЫТЕСНЕННАЯ: ячеек дизайна она не занимает и в стройку
     * не идёт — трофей летает, но строить такой корабль захватчик не умеет (технологий у него
     * может и не быть). Повторный трофей того же проекта берёт уже заведённую копию.
     */
    @Transactional
    public ShipDesignEntity trophyDesign(PlayerEntity captor, UUID designId, Integer turn) {
        ShipDesignEntity source = shipDesignRepository.findById(designId)
                .orElseThrow(() -> new NotFoundException("ship.designNotFound", designId));
        String name = source.getName();
        ShipDesignEntity known = shipDesignRepository.findAllByOwnerPlayerIdOrderBySlotAsc(captor.getId()).stream()
                .filter(design -> Boolean.TRUE.equals(design.getTrophy()))
                .filter(design -> name.equals(design.getName()) && source.getHullCode().equals(design.getHullCode()))
                .filter(design -> itemsOf(List.of(design)).getOrDefault(design.getId(), List.of())
                        .equals(itemsOf(List.of(source)).getOrDefault(source.getId(), List.of())))
                .findFirst()
                .orElse(null);
        if (known != null) {
            return known;
        }
        ShipDesignEntity copy = new ShipDesignEntity();
        copy.setGameId(source.getGameId());
        copy.setOwnerPlayerId(captor.getId());
        copy.setSlot(source.getSlot());
        copy.setName(name);
        copy.setHullCode(source.getHullCode());
        copy.setCreatedTurn(turn);
        copy.setObsolete(Boolean.TRUE);
        copy.setTrophy(Boolean.TRUE);
        copy.setRole(source.getRole());
        shipDesignRepository.saveAndFlush(copy);
        saveComponents(copy, itemsOf(List.of(source)).getOrDefault(source.getId(), List.of()));
        return copy;
    }

    /** Что поставить в очередную ячейку игры: корпус, задача и состав под неё. */
    private record Plan(ShipHull hull, ShipMission mission, List<ShipDesignRules.Item> items) {
    }

    /**
     * Какие проекты держит игра в свободных ячейках — п. 8, п. 15.
     * <p>
     * У всех — линейный проект на каждый изученный корпус. У империи ИИ сверх того ещё два
     * (03.10.2026, просьба хозяина проекта): <b>эскорт</b> на втором по величине корпусе —
     * против того, кто воюет ракетами, — и <b>штурмовик</b> на самом крупном — против колонии
     * с обороной. ИИ своих проектов не собирает, и без них его флот был одинаковым против
     * любого врага. Ячеек шесть, поэтому, когда задачам не хватает места, уступают
     * линейные проекты мелких корпусов: мелкий корпус хуже всех служит и строю, и штурму.
     * Человеку игра задачи не навязывает — его ячейки он собирает сам.
     */
    private List<Plan> plans(PlayerEntity player, List<ShipHull> hulls, Set<String> technologies, int free) {
        List<Plan> plans = new ArrayList<>();
        for (ShipHull hull : hulls) {
            plans.add(new Plan(hull, ShipMission.LINE, defaultItems(hull, technologies)));
        }
        if (player.getPlayerType() != PlayerType.AI || hulls.isEmpty()) {
            return plans;
        }
        ShipHull largest = hulls.get(hulls.size() - 1);
        ShipHull second = hulls.size() >= 2 ? hulls.get(hulls.size() - 2) : largest;
        // Абордаж — на линейном проекте КАЖДОГО корпуса, где после модуля остаётся половина
        // стволов (04.10.2026): крупнейший изученный корпус строит лишь колония со звёздной
        // базой, а массовый флот ИИ — эсминцы и крейсера. С абордажем на одном линкоре партия
        // на средней галактике дала ноль трофеев: шаттлы изучены на 148-м ходу, а после этого
        // построены одиннадцать эсминцев и крейсеров и ни одного линкора.
        plans.replaceAll(plan -> plan.mission() == ShipMission.LINE
                ? new Plan(plan.hull(), ShipMission.LINE, lineWithBoarding(plan.hull(), technologies))
                : plan);
        List<ShipDesignRules.Item> escort = escortItems(second, technologies);
        if (escort != null) {
            plans.add(new Plan(second, ShipMission.ESCORT, escort));
        }
        List<ShipDesignRules.Item> assault = hulls.size() >= 2 ? assaultItems(largest, technologies) : null;
        if (assault != null) {
            plans.add(new Plan(largest, ShipMission.ASSAULT, assault));
        }
        // Бомбардировщик — на КРУПНЕЙШЕМ корпусе (03.10.2026): на втором по величине он нёс
        // горстку ранних бомб и за четыре партии ИИ трижды ударил по 84 урона — житель за
        // заход. Бомбы ценны числом стволов, а их даёт место.
        List<ShipDesignRules.Item> bomber = bomberItems(largest, technologies);
        if (bomber != null) {
            plans.add(new Plan(largest, ShipMission.BOMBER, bomber));
        }
        while (plans.size() > Math.max(0, free) && plans.stream()
                .filter(plan -> plan.mission() == ShipMission.LINE).count() > 1) {
            plans.remove(0);
        }
        return plans;
    }

    /** Оснащение корпуса без оружия — двигатель, броня, компьютер, щит и баки, как в {@link #defaultItems}. */
    private List<ShipDesignRules.Item> hullFittings(ShipHull hull, Set<String> technologies) {
        return defaultItems(hull, technologies).stream()
                .filter(item -> item.component().slot() != ShipComponentSlot.WEAPON)
                .filter(item -> !ANTI_MISSILE_ROCKETS.equals(item.component().code()))
                .toList();
    }

    /**
     * Оснащение без отсеков истребителей — штурмовику и бомбардировщику (п. 8, 14.10.2026): их дело —
     * оборона колонии и сама колония, а звено бьёт корабли. Место отсека уходит под стволы или бомбы;
     * истребители остаются линейным кораблям и эскорту.
     */
    private List<ShipDesignRules.Item> withoutFighters(List<ShipDesignRules.Item> items) {
        return items.stream()
                .filter(item -> item.component().amount(ShipEffectType.FIGHTER_BAY) <= 0)
                .toList();
    }

    private int spaceLeft(ShipHull hull, List<ShipDesignRules.Item> items) {
        return hull.space() - items.stream().mapToInt(item -> item.spaceOn(hull) * item.count()).sum();
    }

    /**
     * Эскорт (ShipMission.ESCORT): противоракеты, если изучены, и лучи или снаряды с ближней
     * обороной на остальное место — ствол выбирается по залпу, как у линейного. Ближняя
     * оборона вдвое мельче обычного ствола, поэтому стволов у эскорта вдвое больше: он бьёт
     * часто и слабо, а ракете хватает одного попадания. {@code null} — собрать нечем.
     */
    private List<ShipDesignRules.Item> escortItems(ShipHull hull, Set<String> technologies) {
        List<ShipDesignRules.Item> items = new ArrayList<>(hullFittings(hull, technologies));
        int left = spaceLeft(hull, items);
        if (technologies.contains(ANTI_MISSILE_ROCKETS)) {
            ShipComponent rockets = shipCatalog.component(ANTI_MISSILE_ROCKETS);
            if (rockets.spaceOn(hull) < left) {
                items.add(new ShipDesignRules.Item(rockets, 1));
                left -= rockets.spaceOn(hull);
            }
        }
        WeaponModification pointDefence = shipCatalog.modification(POINT_DEFENCE);
        ShipDesignRules.Item best = null;
        int bestSalvo = 0;
        for (ShipComponent weapon : shipCatalog.components(ShipComponentSlot.WEAPON)) {
            if (!researched(weapon.requiredTechCode(), technologies)
                    || weapon.weaponKind() == WeaponKind.MISSILE
                    || weapon.weaponKind() == WeaponKind.BOMB
                    || !Boolean.TRUE.equals(weapon.carries(pointDefence))
                    || !researched(pointDefence.requiredTechCode(), technologies)) {
                continue;
            }
            ShipDesignRules.Item one = new ShipDesignRules.Item(weapon, 1, List.of(pointDefence));
            int guns = left / Math.max(1, one.spaceOn(hull));
            int salvo = guns * damage(weapon);
            if (guns >= 1 && salvo > bestSalvo) {
                best = new ShipDesignRules.Item(weapon, guns, List.of(pointDefence));
                bestSalvo = salvo;
            }
        }
        if (best == null) {
            return null;
        }
        items.add(best);
        return List.copyOf(items);
    }

    /**
     * Штурмовик (ShipMission.ASSAULT): самый тяжёлый ОДИНОЧНЫЙ выстрел из лучей и снарядов и
     * бронебойность, если ствол её носит. У платформ обороны щит и броня толще корабельных, а
     * щит снимает с КАЖДОГО попадания: залп из мелких стволов о крепость рассыпается, крупный
     * её пробивает. Ракеты штурмовику не годятся — ракетная база и крепость сбивают их
     * противоракетами. {@code null} — собрать нечем.
     */
    private List<ShipDesignRules.Item> assaultItems(ShipHull hull, Set<String> technologies) {
        List<ShipDesignRules.Item> items = new ArrayList<>(withoutFighters(hullFittings(hull, technologies)));
        int left = spaceLeft(hull, items);
        /*
          Абордаж (п. 8): штурмовик идёт к чужой обороне и к подбитым кораблям — ему и брать их.
          ИИ своих проектов не собирает, и без этих строк средств абордажа у него не было бы
          никогда: транспортёры, шаттлы, тяговый луч и десантные отсеки лежали бы для его
          флота мёртвыми, а трофеи быстрого боя доставались бы одним телепатам. Модули
          ставятся, только пока на корпусе остаётся место хотя бы под половину его стволов —
          штурмовик прежде всего бьёт.
        */
        List<ShipDesignRules.Item> boarding = boardingItems(technologies);
        int boardingSpace = boarding.stream().mapToInt(item -> item.component().spaceOn(hull)).sum();
        if (boardingSpace > 0 && boardingSpace * 2 <= left) {
            items.addAll(boarding);
            left -= boardingSpace;
        }
        WeaponModification piercing = shipCatalog.modification(ARMOUR_PIERCING);
        ShipDesignRules.Item best = null;
        for (ShipComponent weapon : shipCatalog.components(ShipComponentSlot.WEAPON)) {
            if (!researched(weapon.requiredTechCode(), technologies)
                    || weapon.weaponKind() == WeaponKind.MISSILE
                    || weapon.weaponKind() == WeaponKind.BOMB) {
                continue;
            }
            List<WeaponModification> mods = Boolean.TRUE.equals(weapon.carries(piercing))
                    && researched(piercing.requiredTechCode(), technologies)
                    ? List.of(piercing) : List.of();
            ShipDesignRules.Item one = new ShipDesignRules.Item(weapon, 1, mods);
            int guns = left / Math.max(1, one.spaceOn(hull));
            if (guns < 1 && !mods.isEmpty()) {
                one = new ShipDesignRules.Item(weapon, 1);
                guns = left / Math.max(1, one.spaceOn(hull));
            }
            if (guns < 1) {
                continue;
            }
            ShipDesignRules.Item candidate = new ShipDesignRules.Item(weapon, guns, one.modifications());
            if (best == null || shotDamage(weapon) > shotDamage(best.component())
                    || (shotDamage(weapon) == shotDamage(best.component())
                        && guns * damage(weapon) > best.count() * damage(best.component()))) {
                best = candidate;
            }
        }
        if (best == null) {
            return null;
        }
        items.add(best);
        return List.copyOf(items);
    }

    /**
     * Средства абордажа, которые ставит ИИ (п. 8): первое изученное из {@link #BOARDING_MEANS}
     * и десантные отсеки к нему. Пусто — абордажу учиться не на чем.
     */
    private List<ShipDesignRules.Item> boardingItems(Set<String> technologies) {
        String means = BOARDING_MEANS.stream()
                .filter(technologies::contains)
                .findFirst()
                .orElse(null);
        if (means == null) {
            return List.of();
        }
        List<ShipDesignRules.Item> boarding = new ArrayList<>(2);
        boarding.add(new ShipDesignRules.Item(shipCatalog.component(means), 1));
        if (technologies.contains(TROOP_PODS)) {
            boarding.add(new ShipDesignRules.Item(shipCatalog.component(TROOP_PODS), 1));
        }
        return boarding;
    }

    /**
     * Линейный проект с абордажем — п. 8, для линейных корпусов ИИ. Трофеи быстрого боя
     * уводит флот, у которого есть чем брать, — а штурмовик строится, только когда цель войны
     * прикрыта обороной, и в победивших флотах его не оказывалось: за партию быстрый бой
     * выигрывался шесть десятков раз, и ни разу у победителя не было средств абордажа
     * (замер 03.10.2026). Линейный корабль — самый массовый боевой корабль партии, и средство
     * абордажа на нём делает абордаж частью флота, а не его исключением.
     * Ствол уступает место модулю, только пока остаётся хотя бы половина стволов.
     */
    private List<ShipDesignRules.Item> lineWithBoarding(ShipHull hull, Set<String> technologies) {
        List<ShipDesignRules.Item> items = new ArrayList<>(defaultItems(hull, technologies));
        List<ShipDesignRules.Item> boarding = boardingItems(technologies);
        if (boarding.isEmpty()) {
            return List.copyOf(items);
        }
        int weaponIndex = -1;
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).component().slot() == ShipComponentSlot.WEAPON) {
                weaponIndex = index;
            }
        }
        if (weaponIndex < 0) {
            return List.copyOf(items);
        }
        ShipDesignRules.Item weapon = items.get(weaponIndex);
        int perGun = Math.max(1, weapon.component().spaceOn(hull));
        int need = boarding.stream().mapToInt(item -> item.component().spaceOn(hull)).sum()
                - spaceLeft(hull, items);
        int removed = need <= 0 ? 0 : (need + perGun - 1) / perGun;
        int kept = weapon.count() - removed;
        if (kept < 1 || kept * 2 < weapon.count()) {
            return List.copyOf(items);
        }
        items.set(weaponIndex, new ShipDesignRules.Item(weapon.component(), kept, weapon.modifications()));
        items.addAll(boarding);
        return List.copyOf(items);
    }

    /** Доля ракет в залпе состава, в процентах. */
    private Integer missilePercent(List<ShipDesignRules.Item> items) {
        int total = 0;
        int missiles = 0;
        for (ShipDesignRules.Item item : items) {
            if (item.component().slot() != ShipComponentSlot.WEAPON) {
                continue;
            }
            int salvo = damage(item.component()) * item.count();
            total += salvo;
            if (item.component().weaponKind() == WeaponKind.MISSILE) {
                missiles += salvo;
            }
        }
        return total == 0 ? 0 : missiles * 100 / total;
    }

    /**
     * Бомбардировщик (ShipMission.BOMBER, пункт 25): тяжелейшая изученная бомба на всё место —
     * «выжженная земля» оригинала: колонию, которую не взять десантом, можно стереть; плюс
     * средство абордажа, если влезает. {@code null} — бомб не изучено.
     */
    private List<ShipDesignRules.Item> bomberItems(ShipHull hull, Set<String> technologies) {
        List<ShipDesignRules.Item> items = new ArrayList<>(withoutFighters(hullFittings(hull, technologies)));
        int left = spaceLeft(hull, items);
        ShipComponent bomb = shipCatalog.components(ShipComponentSlot.WEAPON).stream()
                .filter(component -> component.weaponKind() == WeaponKind.BOMB)
                .filter(component -> researched(component.requiredTechCode(), technologies))
                .max(Comparator.comparingInt(this::shotDamage))
                .orElse(null);
        if (bomb == null || bomb.spaceOn(hull) > left) {
            return null;
        }
        // Средство абордажа — и на бомбардировщике (04.10.2026): он заменил в строю часть
        // линейных кораблей, которые его несли, и трофеев стало вшестеро меньше (27 → 4 за две
        // партии). Модуль встаёт, только если после него остаётся место хотя бы под бомбу.
        List<ShipDesignRules.Item> boarding = boardingItems(technologies);
        int boardingSpace = boarding.stream().mapToInt(item -> item.spaceOn(hull) * item.count()).sum();
        if (!boarding.isEmpty() && left - boardingSpace >= bomb.spaceOn(hull)) {
            items.addAll(boarding);
            left -= boardingSpace;
        }
        items.add(new ShipDesignRules.Item(bomb, left / Math.max(1, bomb.spaceOn(hull))));
        return List.copyOf(items);
    }

    /** Урон ОДНОГО выстрела ствола — то, что решает, пробьёт ли он щит платформы. */
    private int shotDamage(ShipComponent weapon) {
        int sides = Boolean.TRUE.equals(weapon.envelops()) ? ShipDesignRules.ENVELOPING_SIDES : 1;
        return weapon.effects().getOrDefault(ShipEffectType.WEAPON_DAMAGE, 0) * sides;
    }

    /**
     * Держит в порядке проекты платформ обороны — п. 8, п. 11.
     * <p>
     * Платформа вооружается САМА и всегда по последнему изученному: в MOO II звёздная база
     * «обновляется новейшими технологиями» без всякой перестройки, и переделать её вручную
     * нельзя. Здесь это тот же механизм, что держит шесть ячеек дизайна, — {@code auto} и
     * {@link #defaultItems}, — только ячейки служебные и отрицательные
     * ({@link OrbitalDefenceRules#slotOf}), чтобы платформа не отнимала места у игрока и не
     * показывалась там, где её нельзя переделать.
     * <p>
     * Зовётся оттуда же, откуда обновляются ячейки дизайна, — из {@code ResearchService},
     * когда технологии и правда прибавились. Проекты заводятся на ВСЕ корпуса платформ
     * разом, даже если колония такого здания ещё не построила: проект сам по себе ничего
     * не стоит, а искать по колониям, кому что понадобится, значило бы ходить в базу
     * изнутри посчитанного хода.
     *
     * @return проекты платформ этой империи по коду корпуса
     */
    @Transactional
    public Map<String, ShipDesignEntity> ensurePlatformDesigns(UUID gameId, PlayerEntity player,
                                                               Integer turn) {
        Set<String> technologies = technologies(player);
        Map<String, ShipDesignEntity> mine =
                shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                        .stream()
                        .filter(design -> design.getRole() == ShipRole.PLATFORM)
                        .collect(Collectors.toMap(ShipDesignEntity::getHullCode,
                                design -> design, (first, second) -> first, LinkedHashMap::new));

        // Одной выборкой, по той же причине, что и у шести ячеек: платформ столько же,
        // и обновляются они тем же вызовом из `ResearchService.store`.
        Map<UUID, List<ShipDesignRules.Item>> current = itemsOf(mine.values());

        Map<String, ShipDesignEntity> platforms = new LinkedHashMap<>();
        for (String code : OrbitalDefenceRules.HULLS) {
            ShipHull hull = shipCatalog.hull(code);
            if (!Boolean.TRUE.equals(researched(hull.requiredTechCode(), technologies))) {
                continue;
            }
            List<ShipDesignRules.Item> items = defaultItems(hull, technologies);
            ShipDesignEntity design = mine.get(code);
            if (design == null) {
                design = createAutoDesign(gameId, player, turn,
                        orbitalDefenceRules.slotOf(code), hull, items);
                design.setRole(ShipRole.PLATFORM);
                shipDesignRepository.saveAndFlush(design);
            } else {
                refreshAutoDesign(design, hull, items,
                        current.getOrDefault(design.getId(), List.of()));
            }
            platforms.put(code, design);
        }
        return platforms;
    }

    /**
     * Проекты платформ обороны этой империи по коду корпуса — п. 8, п. 11.
     * <p>
     * Только чтение: заводит и обновляет их {@link #ensurePlatformDesigns}, которую зовут
     * исследования. Пусто — империя ещё не получила ни одной платформы (партия, начатая до
     * появления обороны, получит их с первым же прорывом).
     */
    @Transactional(readOnly = true)
    public Map<String, ShipDesignEntity> platformDesigns(PlayerEntity player) {
        return shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                .stream()
                .filter(design -> design.getRole() == ShipRole.PLATFORM)
                .collect(Collectors.toMap(ShipDesignEntity::getHullCode,
                        design -> design, (first, second) -> first, LinkedHashMap::new));
    }

    /** Заводит проект от игры: имя по корпусу, состав — лучшее из изученного. */
    private ShipDesignEntity createAutoDesign(UUID gameId, PlayerEntity player, Integer turn,
                                              Integer slot, ShipHull hull,
                                              List<ShipDesignRules.Item> items) {
        return createAutoDesign(gameId, player, turn, slot, hull, items, null);
    }

    private ShipDesignEntity createAutoDesign(UUID gameId, PlayerEntity player, Integer turn,
                                              Integer slot, ShipHull hull,
                                              List<ShipDesignRules.Item> items, ShipMission mission) {
        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(gameId);
        design.setOwnerPlayerId(player.getId());
        design.setSlot(slot);
        // Английское название корпуса, а не на языке запроса: имя проекта хранится в базе
        // (п. 3.5), и переименовать его игрок вправе сам.
        design.setName(hull.names().en());
        design.setHullCode(hull.code());
        design.setCreatedTurn(turn);
        design.setObsolete(Boolean.FALSE);
        design.setAuto(Boolean.TRUE);
        design.setMission(mission);
        shipDesignRepository.saveAndFlush(design);
        saveComponents(design, items);
        if (mission != null && mission != ShipMission.LINE) {
            // Проект под задачу — событие редкое (раз на изученную технологию), и по журналу
            // видно, что ИИ правда собирает эскорты и штурмовики.
            log.info("Империя {} получила проект-{} «{}» в ячейку {}: {}", player.getName(), mission,
                    hull.code(), slot, items.stream()
                            .map(item -> item.component().code() + "×" + item.count()
                                    + (item.modifications().isEmpty() ? "" : item.modifications().stream()
                                            .map(WeaponModification::code).collect(Collectors.joining("+", "[", "]"))))
                            .collect(Collectors.joining(", ")));
        } else {
            log.debug("Империя {} получила проект «{}» в ячейку {}", player.getName(), hull.name(), slot);
        }
        return design;
    }

    /**
     * Подтягивает проект КОРАБЛЯ к нынешним технологиям — п. 8: новой строкой, а старая
     * помечается вытесненной.
     * <p>
     * <b>Построенный корабль остаётся с тем, с чем построен.</b> В MOO II изученная пушка
     * не появляется сама на кораблях, которые уже летают: новое ставят новым кораблям, а
     * старым — переоснащением за деньги. Раньше состав правился ПРЯМО В СТРОКЕ проекта, а
     * корабли во флоте ссылаются на неё же, — и в тот же миг, когда империя изучала лучшую
     * пушку, ВЕСЬ её построенный флот получал её даром. Измерено: построенный фрегат менял
     * залп, не потратив ни единицы производства. Прибору балансировки это врало вдвойне —
     * военная сила империи росла от одного факта исследования.
     * <p>
     * Старая строка не удаляется: по ней летают корабли, и её характеристики нужны бою и
     * слепку партии. Выборки «действующие проекты» её уже отсеивают признаком
     * {@code obsolete}, а те, что считают силу флота, намеренно берут и вытесненные.
     * <p>
     * Платформы обороны так НЕ обновляются — у них своя дорога
     * ({@link #refreshAutoDesign}): звёздная база в MOO II и правда перевооружается сама.
     *
     * @return действующий проект: прежний, если менять нечего, или новый
     */
    private ShipDesignEntity replaceAutoDesign(UUID gameId, PlayerEntity player, Integer turn,
                                               ShipDesignEntity design, ShipHull hull,
                                               List<ShipDesignRules.Item> items,
                                               List<ShipDesignRules.Item> current,
                                               ShipMission mission) {
        if (current.equals(items) && hull.code().equals(design.getHullCode())
                && mission == design.getMission()) {
            return design;
        }
        design.setObsolete(Boolean.TRUE);
        shipDesignRepository.saveAndFlush(design);
        log.debug("Империя {}: проект «{}» вытеснен новым — изучено лучшее вооружение",
                player.getName(), design.getName());
        ShipDesignEntity fresh = createAutoDesign(gameId, player, turn, design.getSlot(), hull, items, mission);
        retargetProjects(player.getId(), design.getId(), fresh.getId());
        return fresh;
    }

    /**
     * Подтягивает проект ПЛАТФОРМЫ обороны к нынешним технологиям — п. 11: состав
     * собирается заново прямо в строке.
     * <p>
     * Здесь правка на месте — это и есть правило: звёздная база в MOO II «обновляется
     * новейшими технологиями» сама, без перестройки. Кораблю так нельзя (см.
     * {@link #replaceAutoDesign}), а платформе — можно и нужно: она не летает, она стоит на
     * орбите и есть часть колонии.
     */
    private void refreshAutoDesign(ShipDesignEntity design, ShipHull hull,
                                   List<ShipDesignRules.Item> items,
                                   List<ShipDesignRules.Item> current) {
        if (current.equals(items) && hull.code().equals(design.getHullCode())) {
            return;
        }
        design.setHullCode(hull.code());
        design.setName(hull.names().en());
        shipDesignComponentRepository.deleteAllByDesignId(design.getId());
        shipDesignComponentRepository.flush();
        saveComponents(design, items);
    }

    /**
     * Гражданский корабль империи — п. 8: колониальный корабль или транспорт.
     * <p>
     * В MOO II эти два корабля в окне дизайна не собираются: игра предлагает их готовыми,
     * прямо в списке стройки колонии, и цена у них твёрдая. Поэтому здесь у них своя
     * служебная ячейка ({@link #CIVIL_SLOT}) — в шести ячейках дизайна они не показываются
     * и переделке не подлежат, — а состав минимальный: корпус и двигатель. Оружия у них
     * нет, и это не упущение: гражданский корабль в бой не выходит вовсе.
     * <p>
     * Заводится по требованию, при первой стройке такого корабля: партии, начатые до
     * появления расселения, получают его тем же способом, что и новые.
     */
    @Transactional
    public ShipDesignEntity civilDesign(GameEntity game, PlayerEntity player, ShipRole role) {
        // ТРОФЕИ НЕ БЕРУТСЯ, а равные упорядочены явно (14.10.2026): трофейная копия наследует ячейку и роль
        // исходного проекта, и захваченный чужой колонист лежал в той же ячейке 0, что и свой. Упорядоченные одной
        // ячейкой, они шли в порядке строк базы — и одна и та же партия строила то свой корабль, то копию трофея;
        // на 500 ходах это разводило партию с самой собой (проверка повторимости, зёрна 1000–1002).
        return shipDesignRepository.findAllByOwnerPlayerIdOrderBySlotAsc(player.getId()).stream()
                .filter(design -> design.getRole() == role)
                .filter(design -> !Boolean.TRUE.equals(design.getTrophy()))
                .sorted(GameOrder.DESIGNS)
                .findFirst()
                .map(design -> withTanks(player, design))
                .orElseGet(() -> createCivilDesign(game, player, role));
    }

    /**
     * Дооснащает гражданский проект баками, если их изучили уже после того, как он заведён
     * (п. 8).
     * <p>
     * <b>Состав правится НА МЕСТЕ</b> — второе такое место в игре после платформ обороны, и
     * причины те же. Гражданский корабль игрок не собирает: его собирает игра, и снаряжать
     * его она обязана сама, иначе изученные баки не дали бы расселению ничего, пока партия
     * не начнётся заново. Запрет на правку в месте стоит ради БОЕВОЙ силы — построенный
     * корабль не должен получать новую пушку даром, — а здесь меняется одна дальность,
     * которой до 29.09.2026 и так хватало всем кораблям империи разом.
     * <p>
     * Вызов дешёвый: строки состава читаются один раз, и записывается что-либо только в
     * тот единственный ход, когда баки изучены, а в проекте их ещё нет.
     */
    private ShipDesignEntity withTanks(PlayerEntity player, ShipDesignEntity design) {
        ShipComponent tanks = extendedTanks(technologies(player));
        if (tanks == null) {
            return design;
        }
        List<ShipDesignComponentEntity> rows = shipDesignComponentRepository
                .findAllByDesignIdOrderBySortOrderAsc(design.getId());
        if (rows.stream().anyMatch(row -> tanks.code().equals(row.getComponentCode()))) {
            return design;
        }
        ShipHull hull = enlarged(shipCatalog.hull(design.getHullCode()), technologies(player));
        List<ShipDesignRules.Item> items = new ArrayList<>(toItems(rows));
        items.add(new ShipDesignRules.Item(tanks, 1));
        if (!shipDesignRules.stats(hull, items, RaceEffects.NONE).fits()) {
            return design;
        }
        // Состав переписывается целиком: у строк есть порядок, и дописанная в конец
        // строка с чужим номером сделала бы его неопределённым, а от него зависит
        // повторимость партии (GameOrder).
        shipDesignComponentRepository.deleteAllByDesignId(design.getId());
        saveComponents(design, List.copyOf(items));
        log.info("Проект «{}» империи {} получил дополнительные баки",
                design.getName(), player.getName());
        return design;
    }

    /**
     * Заводит гражданский проект: корпус подешевле и один двигатель — везти, а не воевать.
     * <p>
     * Баки ставятся и сюда, если изучены (п. 8): расселение держится на дальности, а
     * колониальный корабль игрок не собирает — его собирает игра, и снаряжать его она
     * обязана сама. Без этого изученные баки укоротили бы расселение вдвое, ничего не
     * спросив у игрока.
     */
    private ShipDesignEntity createCivilDesign(GameEntity game, PlayerEntity player, ShipRole role) {
        ShipHull hull = shipCatalog.cheapestHull();
        ShipComponent engine = shipCatalog.components(ShipComponentSlot.ENGINE).stream()
                .filter(component -> component.requiredTechCode() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("В справочнике нет стартового двигателя"));
        ShipComponent tanks = extendedTanks(technologies(player));

        ShipDesignEntity design = new ShipDesignEntity();
        design.setGameId(game.getId());
        design.setOwnerPlayerId(player.getId());
        design.setSlot(CIVIL_SLOT);
        design.setName(switch (role) {
            case COLONY -> COLONY_SHIP_NAME;
            case OUTPOST -> OUTPOST_SHIP_NAME;
            default -> TRANSPORT_NAME;
        });
        design.setHullCode(hull.code());
        design.setCreatedTurn(game.getTurn());
        design.setObsolete(Boolean.FALSE);
        design.setRole(role);
        shipDesignRepository.saveAndFlush(design);
        List<ShipDesignRules.Item> items = new ArrayList<>(2);
        items.add(new ShipDesignRules.Item(engine, 1));
        if (tanks != null && tanks.spaceOn(hull) + engine.spaceOn(hull) <= hull.space()) {
            items.add(new ShipDesignRules.Item(tanks, 1));
        }
        saveComponents(design, List.copyOf(items));
        log.info("Империя {} завела проект «{}»", player.getName(), design.getName());
        return design;
    }

    /**
     * Проект, который колония строит по умолчанию: первый действующий по номеру ячейки.
     * Если проектов нет вовсе — партия начата до появления подсистемы, — заводится
     * стартовый.
     */
    @Transactional
    public ShipDesignEntity defaultDesign(GameEntity game, PlayerEntity player) {
        return shipDesignRepository.findAllByOwnerPlayerIdAndObsoleteFalseOrderBySlotAsc(player.getId())
                .stream()
                .filter(design -> Boolean.TRUE.equals(design.getRole().isCombat()))
                .findFirst()
                .orElseGet(() -> createDefaultDesign(game, player));
    }

    /** Проект игрока по идентификатору; чужой проект недоступен. */
    @Transactional(readOnly = true)
    public ShipDesignEntity requireOwn(PlayerEntity player, UUID designId) {
        ShipDesignEntity design = shipDesignRepository.findById(designId)
                .orElseThrow(() -> new NotFoundException("ship.designNotFound", designId));
        if (!design.getOwnerPlayerId().equals(player.getId())) {
            throw new NotFoundException("ship.designNotFound", designId);
        }
        return design;
    }

    /** Действующий проект игрока по идентификатору — по вытесненному строить нельзя. */
    @Transactional(readOnly = true)
    public ShipDesignEntity requireBuildable(PlayerEntity player, UUID designId) {
        ShipDesignEntity design = requireOwn(player, designId);
        if (Boolean.TRUE.equals(design.getObsolete())) {
            throw new ConflictException("ship.designObsolete", design.getName());
        }
        return design;
    }

    /** Характеристики проекта с расовыми поправками — их спрашивают стройка и бой. */
    public ShipStats stats(ShipDesignEntity design, RaceEffects race) {
        return shipDesignRules.stats(shipCatalog.hull(design.getHullCode()), items(design), race);
    }

    /** Боевая сила одного корабля этого проекта — по ней сходятся флоты. */
    public Integer power(ShipDesignEntity design, RaceEffects race) {
        return shipDesignRules.power(stats(design, race));
    }

    /**
     * Характеристики сразу многих проектов: фаза встреч и экран флота спрашивают их
     * пачкой, и выбирать состав по одному проекту — та же тройная выборка, от которой
     * ушёл {@code TurnContext}.
     */
    public Map<UUID, ShipStats> statsByDesign(Collection<ShipDesignEntity> designs, RaceEffects race) {
        return statsFromItems(designs, itemsOf(designs), race);
    }

    /**
     * То же, но по УЖЕ вычитанным составам — п. 8.
     * <p>
     * Нужно тем, кому состав нужен и сам по себе: {@code FleetService.combat} собирал
     * проекты, их характеристики и составы тремя вызовами подряд, и составы уходили в базу
     * ДВАЖДЫ — один раз внутри {@link #statsByDesign}, второй раз своим {@link #itemsOf}.
     * Внутри посчитанного хода лишний запрос стоит дороже, чем кажется: он заставляет
     * Hibernate сбросить туда же всё, что ход успел изменить.
     */
    public Map<UUID, ShipStats> statsFromItems(Collection<ShipDesignEntity> designs,
                                               Map<UUID, List<ShipDesignRules.Item>> items,
                                               RaceEffects race) {
        Map<UUID, ShipStats> stats = new HashMap<>(designs.size());
        for (ShipDesignEntity design : designs) {
            stats.put(design.getId(), shipDesignRules.stats(
                    shipCatalog.hull(design.getHullCode()),
                    items.getOrDefault(design.getId(), List.of()),
                    race));
        }
        return stats;
    }

    /**
     * Характеристики всех проектов игрока, включая вытесненные, — п. 8.
     * <p>
     * Спрашивает флот: по вытесненному проекту строить нельзя, а корабли, построенные
     * раньше, по нему летают и воюют. Одна выборка на игрока — состав флотов читается
     * пачкой, как того требует конец хода.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ShipStats> statsOf(PlayerEntity player) {
        List<ShipDesignEntity> designs = shipDesignRepository.findAllByOwnerPlayerIdOrderBySlotAsc(player.getId());
        return statsByDesign(designs, raceService.effects(player));
    }

    /** Размер корпуса проекта: 1 у фрегата, 6 у Leviathan — п. 8. */
    public Integer hullSize(ShipDesignEntity design) {
        return shipCatalog.hull(design.getHullCode()).sortOrder();
    }

    /**
     * Проекты игрока по идентификатору, включая вытесненные, — для состава флота.
     * <p>
     * Пояснение и {@code @Transactional} стояли строкой выше и приклеились к вставленному
     * между ними {@link #hullSize}: аннотация принадлежит методу, а не месту в файле
     * (правила проекта). Сам {@code hullSize} в базу не ходит вовсе — он спрашивает справочник, —
     * а этот метод ходит, и пометка нужна ему.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ShipDesignEntity> allDesignsOf(PlayerEntity player) {
        return shipDesignRepository.findAllByOwnerPlayerIdOrderBySlotAsc(player.getId()).stream()
                .collect(Collectors.toMap(ShipDesignEntity::getId, design -> design));
    }

    /** Проекты партии по идентификатору — их спрашивают флоты и слепок. */
    @Transactional(readOnly = true)
    public Map<UUID, ShipDesignEntity> designsOfGame(UUID gameId) {
        return shipDesignRepository.findAllByGameId(gameId).stream()
                .sorted(GameOrder.DESIGNS)
                .collect(Collectors.toMap(ShipDesignEntity::getId, design -> design));
    }

    /** Состав проекта из базы, уже разобранный по справочнику. */
    public List<ShipDesignRules.Item> items(ShipDesignEntity design) {
        return toItems(shipDesignComponentRepository.findAllByDesignIdOrderBySortOrderAsc(design.getId()));
    }

    /** Составы сразу нескольких проектов — одной выборкой. */
    public Map<UUID, List<ShipDesignRules.Item>> itemsOf(Collection<ShipDesignEntity> designs) {
        List<UUID> ids = designs.stream().map(ShipDesignEntity::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return shipDesignComponentRepository.findAllByDesignIdInOrderBySortOrderAsc(ids).stream()
                .collect(Collectors.groupingBy(
                        ShipDesignComponentEntity::getDesignId,
                        LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), this::toItems)));
    }

    /** Готовый ответ по одному проекту — его отдают экран дизайна и стройка. */
    public ShipDesignDto toDto(ShipDesignEntity design, RaceEffects race) {
        return toDto(design, items(design), race);
    }

    /** Готовые ответы по многим проектам — одной выборкой состава. */
    public List<ShipDesignDto> toDtos(List<ShipDesignEntity> designs, RaceEffects race) {
        Map<UUID, List<ShipDesignRules.Item>> items = itemsOf(designs);
        return designs.stream()
                .map(design -> toDto(design, items.getOrDefault(design.getId(), List.of()), race))
                .toList();
    }

    private ShipDesignDto toDto(ShipDesignEntity design, List<ShipDesignRules.Item> items, RaceEffects race) {
        ShipHull hull = shipCatalog.hull(design.getHullCode());
        ShipStats stats = shipDesignRules.stats(hull, items, race);

        List<ShipDesignComponentDto> components = items.stream()
                .map(item -> new ShipDesignComponentDto(
                        item.component().code(),
                        item.component().name(),
                        item.component().slot(),
                        item.count(),
                        // Место и цена — у состава: модификация ствола меняет и то и другое.
                        item.spaceOn(hull) * item.count(),
                        item.costOn(hull) * item.count(),
                        item.modifications().stream().map(WeaponModification::code).toList(),
                        item.component().slot() == ShipComponentSlot.WEAPON ? item.damage() : null))
                .toList();

        return new ShipDesignDto(
                design.getId(),
                design.getSlot(),
                messages.shown(design.getName()),
                hull.code(),
                hull.name(),
                components,
                stats.space(),
                stats.spaceUsed(),
                stats.cost(),
                stats.structure(),
                stats.armour(),
                stats.shield(),
                stats.speed(),
                stats.combatSpeed(),
                stats.attack(),
                stats.defense(),
                stats.missileEvasion(),
                stats.troops(),
                stats.command(),
                stats.rangePercent(),
                shipDesignRules.power(stats),
                design.getObsolete());
    }

    /**
     * Разбирает состав запроса и проверяет технологии. Неизвестный код — 404 из
     * справочника, неизученный — конфликт: это разные ошибки, и клиенту полезно их различать.
     */
    private List<ShipDesignRules.Item> requireItems(SaveShipDesignRequest request, Set<String> technologies) {
        // Ключ строки состава — компонент вместе с его модификациями: тяжёлые лазеры и
        // обычные стоят в проекте разными строками, как и в окне дизайна MOO II.
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<String>> mods = new LinkedHashMap<>();
        for (SaveShipDesignRequest.ShipComponentChoice choice : request.components()) {
            List<String> chosen = choice.modifications() == null
                    ? List.of()
                    : choice.modifications().stream().distinct().sorted().toList();
            String key = choice.code() + "|" + String.join(",", chosen);
            counts.merge(key, choice.count(), Integer::sum);
            mods.putIfAbsent(key, chosen);
        }

        List<ShipDesignRules.Item> items = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String code = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            ShipComponent component = shipCatalog.component(code);
            if (!researched(component.requiredTechCode(), technologies)) {
                throw new ConflictException("ship.componentNotResearched", component.name());
            }
            List<String> chosen = mods.getOrDefault(entry.getKey(), List.of());
            if (!chosen.isEmpty() && component.slot() != ShipComponentSlot.WEAPON) {
                throw new ConflictException("ship.modificationWeaponsOnly", component.name());
            }
            List<WeaponModification> modifications = chosen.stream()
                    .map(shipCatalog::modification)
                    .toList();
            for (WeaponModification modification : modifications) {
                // Модификации у каждого ствола свои — п. 8: ближнюю оборону носит
                // ускоритель частиц, а луч смерти её не берёт вовсе, и у ракеты набор
                // другой целиком. Список носимого лежит у самого оружия в справочнике.
                if (!Boolean.TRUE.equals(component.carries(modification))) {
                    throw new ConflictException("ship.modificationNotCarried", component.name(), modification.name());
                }
                if (!researched(modification.requiredTechCode(), technologies)) {
                    throw new ConflictException("ship.modificationNotResearched", modification.name());
                }
            }
            items.add(new ShipDesignRules.Item(component, entry.getValue(), modifications));
        }
        return items;
    }

    /**
     * Проверки проекта — п. 8. Двигатель обязателен: без него корабль не полетит. Брони,
     * щита, компьютера и постановщика помех бывает по одному — так устроено окно дизайна
     * MOO II. И всё вместе должно влезать в корпус.
     */
    private void validate(ShipHull hull, List<ShipDesignRules.Item> items) {
        Map<ShipComponentSlot, Integer> bySlot = new EnumMap<>(ShipComponentSlot.class);
        for (ShipDesignRules.Item item : items) {
            bySlot.merge(item.component().slot(), item.count(), Integer::sum);
            if (Boolean.TRUE.equals(item.component().slot().single()) && item.count() > 1) {
                throw new ConflictException("ship.duplicateComponent", item.component().name());
            }
        }

        if (bySlot.getOrDefault(ShipComponentSlot.ENGINE, 0) == 0) {
            throw new ConflictException("ship.engineRequired");
        }
        for (ShipComponentSlot slot : ShipComponentSlot.values()) {
            if (Boolean.TRUE.equals(slot.single()) && bySlot.getOrDefault(slot, 0) > 1) {
                throw new ConflictException("ship.slotTwice", slot);
            }
        }
        // Особые модули не повторяются: два одинаковых усиления корпуса смысла не имеют.
        // Кроме тяговых лучей и отсеков шаттлов — они бьют числом, как стволы (п. 8, абордаж).
        for (ShipDesignRules.Item item : items) {
            boolean stacks = item.component().amount(ShipEffectType.TRACTOR) > 0
                    || item.component().amount(ShipEffectType.SHUTTLE_BAY) > 0
                    || item.component().amount(ShipEffectType.FIGHTER_BAY) > 0;
            if (item.component().slot() == ShipComponentSlot.SPECIAL && item.count() > 1 && !stacks) {
                throw new ConflictException("ship.specialOnce", item.component().name());
            }
        }

        ShipStats stats = shipDesignRules.stats(hull, items, RaceEffects.NONE);
        if (!stats.fits()) {
            throw new ConflictException("ship.noSpace", stats.spaceUsed(), stats.space());
        }
    }

    /**
     * Корпус с местом, поднятым изученным, — мегафлюксеры (п. 8, 13.10.2026, перенос MOO II):
     * действие {@code SHIP_SPACE_PERCENT} из дерева технологий.
     */
    private ShipHull enlarged(ShipHull hull, Set<String> technologies) {
        return hull.withSpacePercent(researchCatalog.effectsOf(technologies)
                .amount(com.sddnw.server.domain.enums.BuildingEffectType.SHIP_SPACE_PERCENT));
    }

    /** Состав стартового проекта: двигатель, обшивка и столько пушек, сколько влезет. */
    private List<ShipDesignRules.Item> defaultItems(ShipHull hull) {
        return defaultItems(hull, Set.of());
    }

    /**
     * Состав проекта, который игра собирает сама, — п. 8.
     * <p>
     * Берётся лучшее из изученного: двигатель, обшивка, компьютер и щит — по одному, а
     * остаток корпуса занимает оружие. Так и поступает MOO II, предлагая готовые проекты:
     * колонии всегда есть что строить, пока игрок не собрал своего. Лучшим считается
     * последнее в справочнике: он идёт по возрастанию уровня, как дерево технологий.
     */
    private List<ShipDesignRules.Item> defaultItems(ShipHull plainHull, Set<String> technologies) {
        // Мегафлюксеры (п. 8): проекты игры собираются на увеличенном месте, как и проекты игрока.
        ShipHull hull = enlarged(plainHull, technologies);
        List<ShipDesignRules.Item> items = new ArrayList<>(4);
        int left = hull.space();

        // Платформе обороны двигатель не нужен: она не ходит и никуда не летает (п. 8, п. 11).
        // Прежде она получала его вместе с баками, как корабль, — место уходило впустую, а в
        // бою у звёздной базы появлялась боевая скорость.
        boolean platform = Boolean.TRUE.equals(hull.platform());
        for (ShipComponentSlot slot : List.of(ShipComponentSlot.ENGINE, ShipComponentSlot.ARMOR,
                ShipComponentSlot.COMPUTER, ShipComponentSlot.SHIELD)) {
            if (platform && slot == ShipComponentSlot.ENGINE) {
                continue;
            }
            ShipComponent best = bestAvailable(slot, technologies);
            if (best == null || best.spaceOn(hull) > left) {
                continue;
            }
            items.add(new ShipDesignRules.Item(best, 1));
            left -= best.spaceOn(hull);
        }

        /*
          Дополнительные баки — единственный особый модуль, который игра ставит сама (п. 8).
          Причина не в том, что он лучший: до 29.09.2026 баки были свойством ИМПЕРИИ и
          дальность давали всем кораблям разом, а став модулем, они отняли бы её у каждого,
          кто собран не руками, — у готовых проектов колоний и у всего флота ИИ. Расселение
          и знакомства держатся на дальности, и молча укоротить её вдвое значило бы поменять
          не окно дизайна, а всю партию. Игра снаряжает свои корабли сама — как топливом,
          так и баками; собранное игроком не трогается никогда.
        */
        ShipComponent tanks = platform ? null : extendedTanks(technologies);
        if (tanks != null && tanks.spaceOn(hull) <= left) {
            items.add(new ShipDesignRules.Item(tanks, 1));
            left -= tanks.spaceOn(hull);
        }

        // Планетарный щит оружия не несёт вовсе — п. 11: он держит, а не бьёт. Остаток
        // корпуса у него так и остаётся пустым, и это не упущение состава.
        if (Boolean.TRUE.equals(hull.unarmed())) {
            return List.copyOf(items);
        }

        // Оружия, данного с первого хода, больше нет (12.10.2026, решение хозяина проекта):
        // масс-драйвер открывает продвинутый магнетизм, ядерную ракету — базовая химия. Пока
        // не изучено ни одного ствола, проект остаётся без оружия — так стоит звёздная база
        // родного мира в первые ходы. Кораблю это не грозит: кораблестроение открывают те же
        // базовые уровни, что дают ракету.
        ShipComponent weapon = bestWeapon(hull, left, technologies);
        if (weapon == null) {
            return List.copyOf(items);
        }
        // Особые модули (13.10.2026, перенос MOO II): не больше трети оставшегося места и только
        // если после них встаёт хоть один ствол. Без этого ИИ, своих проектов не собирающий, не
        // ставил бы их никогда, и два десятка технологий были бы для него мертвы.
        if (!platform) {
            int before = left;
            List<ShipDesignRules.Item> specials = specialsFor(hull, left, weapon, items, technologies);
            int used = 0;
            int bonus = 0;
            for (ShipDesignRules.Item special : specials) {
                used += special.component().spaceOn(hull);
                bonus += hull.space() * special.component().amount(ShipEffectType.SPACE_PERCENT) / 100;
            }
            ShipComponent rearmed = bestWeapon(hull, before - used + bonus, technologies);
            if (!specials.isEmpty() && rearmed != null && rearmed.spaceOn(hull) <= before - used + bonus) {
                items.addAll(specials);
                left = before - used + bonus;
                weapon = rearmed;
            }
        }
        int guns = Math.max(1, left / Math.max(1, weapon.spaceOn(hull)));
        items.add(new ShipDesignRules.Item(weapon, guns));

        /*
          Противоракеты (backlog-promo, пункт 30): ИИ своих проектов не собирает, и без этой
          строки не пользовался бы ими никогда. Встают они на ЛЮБОЙ корпус, где после них
          остаётся хоть один ствол, — фрегату это стоит половины залпа, но так решил хозяин
          проекта (03.10.2026): прежний порог в четверть стволов оставлял фрегаты ИИ без защиты
          от ракет вовсе. Остатка корпуса после стволов на модуль не хватает никогда — он ровно
          вдвое меньше модуля на любом корпусе, поэтому ствол уступает место всегда.
        */
        ShipComponent antiMissile = technologies.contains(ANTI_MISSILE_ROCKETS)
                ? shipCatalog.component(ANTI_MISSILE_ROCKETS)
                : null;
        if (antiMissile != null && antiMissile.spaceOn(hull) <= left) {
            int kept = (left - antiMissile.spaceOn(hull)) / Math.max(1, weapon.spaceOn(hull));
            if (kept >= 1) {
                items.set(items.size() - 1, new ShipDesignRules.Item(weapon, kept));
                items.add(new ShipDesignRules.Item(antiMissile, 1));
            }
        }
        return List.copyOf(items);
    }

    /**
     * Порядок особых модулей в проектах, которые собирает игра, — п. 8 (13.10.2026, перенос MOO II).
     * <i>Реконструкция</i>: по пользе в бою — боевые отсеки (место), лишний ход, истребители (14.10.2026:
     * стоя в конце списка, они не получали места ни разу за 250 ходов партии ИИ), поле молний и прочные щиты
     * (14.10.2026: их не было ни в одном проекте — бюджет съедали смещение и уклонение), смещение, уклонение,
     * щиты, живучесть, станции безопасности (дешёвая защита от абордажа), затем модули под вид оружия,
     * устройства, разведлаборатория (наука и меткость против чудищ) и маскировка. Из взаимоисключающих
     * (стабилизатор и нейтрализатор) берётся первый изученный.
     */
    private static final List<String> SPECIAL_PRIORITY = List.of(
            "battle-pods", "time-warp-facilitator", "heavy-fighters", "fighter-bays", "lightning-field",
            "hard-shields", "displacement-device", "inertial-nullifier", "inertial-stabilizer", "multi-phased-shields",
            "shield-capacitor", "energy-absorber", "automated-repair-unit", "heavy-armor", "reinforced-hull",
            "security-stations", "high-energy-focus", "hyper-x-capacitors", "fast-missile-racks",
            "achilles-targeting-unit", "rangemaster-targeting-unit", "battle-scanner", "augmented-engines",
            "plasma-web", "gyro-destabilizer", "pulsar", "scout-lab", "cloaking-device", "stealth-field");

    /** Изучен ли ствол, которым бьют истребители, — луч или снаряд с ближней обороной (п. 8). */
    private boolean fighterBeam(Set<String> technologies) {
        com.sddnw.server.domain.WeaponModification pointDefence = shipCatalog.modification("point-defense");
        return shipCatalog.components(ShipComponentSlot.WEAPON).stream()
                .filter(one -> one.weaponKind() == WeaponKind.BEAM || one.weaponKind() == WeaponKind.PROJECTILE)
                .filter(one -> pointDefence == null || Boolean.TRUE.equals(one.carries(pointDefence)))
                .anyMatch(one -> researched(one.requiredTechCode(), technologies));
    }

    /** Ускоритель времени — ему одному дано до двух третей места, а не треть ({@link #specialsFor}). */
    private static final String TIME_WARP = "time-warp-facilitator";

    /** Модули, которым нужен щит на корабле. */
    private static final Set<String> NEEDS_SHIELD = Set.of("hard-shields", "multi-phased-shields", "shield-capacitor");

    /** Взаимоисключающие пары: второй не берётся, если взят первый. */
    private static final Map<String, String> SUPERSEDES = Map.of(
            "inertial-stabilizer", "inertial-nullifier",
            "stealth-field", "cloaking-device");

    /**
     * Особые модули проекта, который собирает игра, — в пределах трети места после двигателя,
     * брони, компьютера, щита и баков (модули растут с корпусом, и в четверть встаёт лишь
     * мельчайший — замер 13.10.2026). Модули под вид оружия (фокусировщик и прицелы — лучам и
     * снарядам, скоростные стойки — ракетам) ставятся только под подходящий ствол.
     */
    private List<ShipDesignRules.Item> specialsFor(ShipHull hull, int left, ShipComponent weapon,
                                                   List<ShipDesignRules.Item> core, Set<String> technologies) {
        int budget = left / 3;
        int used = 0;
        boolean beams = weapon.weaponKind() == WeaponKind.BEAM || weapon.weaponKind() == WeaponKind.PROJECTILE;
        boolean missiles = weapon.weaponKind() == WeaponKind.MISSILE;
        boolean shield = core.stream().anyMatch(item -> item.component().slot() == ShipComponentSlot.SHIELD);
        Map<String, ShipComponent> catalog = new java.util.HashMap<>();
        shipCatalog.components(ShipComponentSlot.SPECIAL).forEach(one -> catalog.put(one.code(), one));
        Set<String> taken = new java.util.HashSet<>();
        List<ShipDesignRules.Item> chosen = new ArrayList<>();
        for (String code : SPECIAL_PRIORITY) {
            ShipComponent component = catalog.get(code);
            if (component == null || !researched(component.requiredTechCode(), technologies)) {
                continue;
            }
            if (NEEDS_SHIELD.contains(code) && !shield) {
                continue;
            }
            if (SUPERSEDES.containsKey(code) && taken.contains(SUPERSEDES.get(code))) {
                continue;
            }
            // Истребители бьют лучшим лучом или снарядом ИМПЕРИИ с ближней обороной (п. 8,
            // TacticalBattleService.fighterBeam), а не стволом корабля: без такого ствола перехватчикам
            // нечем стрелять. Фокусировщик и прицелы усиливают стволы самого корабля.
            if ((Set.of("fighter-bays", "heavy-fighters").contains(code) && !fighterBeam(technologies))
                    || (Set.of("high-energy-focus", "hyper-x-capacitors", "achilles-targeting-unit",
                    "rangemaster-targeting-unit").contains(code) && !beams)
                    || ("fast-missile-racks".equals(code) && !missiles)) {
                continue;
            }
            if (component.spaceOn(hull) > budget) {
                /*
                  Ускоритель времени — исключение (14.10.2026): он занимает треть корпуса на ЛЮБОМ
                  корпусе (10 клеток на множитель системы) — около 60 % места, оставшегося после
                  двигателя, брони, щита и баков, — и в треть не влезал никогда: изученная технология
                  была для ИИ мертва. Ему дано до двух третей места, если после него и уже взятого
                  остаются хотя бы четыре ствола (на деле — линкор и крупнее); остальным модулям места
                  тогда не остаётся. Лишний ход удваивает удар корабля и в силе быстрого боя
                  ({@code ShipDesignRules.power}).
                */
                int rest = left - used - component.spaceOn(hull);
                if (!TIME_WARP.equals(code) || used + component.spaceOn(hull) > left * 2 / 3
                        || rest < 4 * weapon.spaceOn(hull)) {
                    continue;
                }
                budget = 0;
                used += component.spaceOn(hull);
                taken.add(code);
                chosen.add(new ShipDesignRules.Item(component, 1));
                continue;
            }
            budget -= component.spaceOn(hull);
            used += component.spaceOn(hull);
            // Боевые отсеки прибавляют корпусу места (SPACE_PERCENT), и треть прибавки идёт модулям —
            // 14.10.2026: без этого отсеки, стоящие первыми, съедали бюджет, и истребителям не
            // оставалось места ни на одном корпусе (партия ИИ на 250 ходов — ноль носителей).
            int bonus = hull.space() * component.amount(ShipEffectType.SPACE_PERCENT) / 100;
            budget += bonus / 3;
            left += bonus;
            taken.add(code);
            chosen.add(new ShipDesignRules.Item(component, 1));
        }
        return chosen;
    }

    /**
     * Дополнительные баки, если империя их изучила, — п. 8; {@code null}, если нет.
     * <p>
     * Модуль ищется ПО КОДУ технологии, а не по действию: код у технологии и у модуля один
     * ({@code FuelTech.EXTENDED_TANKS_CODE}), и другого источника дальности в справочнике
     * нет. Появится второй — здесь и выбирать будет из чего.
     */
    private ShipComponent extendedTanks(Set<String> technologies) {
        if (!technologies.contains(FuelTech.EXTENDED_TANKS_CODE)) {
            return null;
        }
        return shipCatalog.components(ShipComponentSlot.SPECIAL).stream()
                .filter(component -> FuelTech.EXTENDED_TANKS_CODE.equals(component.code()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Прибавка к дальности у кораблей, которые игра собирает сама, — п. 8.
     * <p>
     * Ею считает свою досягаемость ИИ ({@code AiEmpireService}): его флот и его
     * гражданские корабли собраны здесь, и баки на них стоят по правилу выше. Спрашивать
     * дальность у каждого готового проекта значило бы ходить в базу изнутри посчитанного
     * хода — ровно то, от чего ушёл {@code TurnContext}.
     */
    public Integer autoRangePercent(Set<String> technologies) {
        ShipComponent tanks = extendedTanks(technologies);
        return tanks == null
                ? 0
                : tanks.effects().getOrDefault(ShipEffectType.FUEL_RANGE_PERCENT, 0);
    }

    /**
     * Лучшее изученное в гнезде; {@code null} — в гнезде у империи нет ничего. Порядок
     * справочника и есть порядок силы: он идёт снизу вверх по дереву технологий.
     */
    private ShipComponent bestAvailable(ShipComponentSlot slot, Set<String> technologies) {
        List<ShipComponent> open = shipCatalog.components(slot).stream()
                .filter(component -> researched(component.requiredTechCode(), technologies))
                .toList();
        if (open.isEmpty()) {
            return null;
        }
        // ОРУЖИЕ ЗДЕСЬ НЕ ВЫБИРАЕТСЯ — для него есть bestWeapon, и вот почему.
        //
        // Порядок справочника годится для лестниц: двигатели, броня, щиты, компьютеры и
        // помехи идут в нём одной возрастающей чередой, и последнее изученное там и есть
        // лучшее. У оружия порядок ДРУГОЙ: сперва лучи (50-61), потом ракеты (70-73),
        // потом торпеды (80-82) — три лестницы подряд. «Последнее в справочнике» выбирало
        // из них ядерную ракету (70), потому что она стоит ПОСЛЕ всех лучей, а достаётся
        // она каждой империи со стартовой Chemistry на первом же ходу. Измерено: империя
        // вооружала все свои проекты ядерными ракетами и не меняла их НИКОГДА — ни на
        // плазменную пушку, ни на луч смерти; изучение Fusion Beam не меняло залп вовсе.
        // Двенадцать лучевых технологий были для игры мертвы.
        //
        // Правильный ответ зависит от КОРПУСА, а не только от изученного: стволы кладутся
        // целыми, и звёздный конвертер (100 клеток) на фрегат не влезает вовсе. Поэтому
        // оружие выбирает bestWeapon, которому известны корпус и остаток места.
        return open.get(open.size() - 1);
    }

    /**
     * Чем вооружить этот корпус — п. 8: ствол, дающий САМЫЙ БОЛЬШОЙ ЗАЛП в оставшемся месте.
     * <p>
     * Считается фактический залп, а не урон за клетку: стволы кладутся в корпус целыми, и
     * остаток места пропадает. Виднее всего это на тяжёлых стволах: у лучей оригинала база
     * десять клеток, а у Дробителя и Копья новы — пятьдесят, поэтому фрегату с его двадцатью
     * тремя свободными клетками они не достаются вовсе, а крейсеру с девяноста двумя Копьё
     * достаётся одно (400 урона) против девяти плазменных пушек (540). Так выбор сам собой
     * ставит мелкие стволы на мелкий корпус и крупные на крупный — ровно как в MOO II.
     */
    private ShipComponent bestWeapon(ShipHull hull, Integer space, Set<String> technologies) {
        List<ShipComponent> open = shipCatalog.components(ShipComponentSlot.WEAPON).stream()
                .filter(component -> researched(component.requiredTechCode(), technologies))
                // Бомба по кораблям не бьёт (пункт 25): лучшим оружием она быть не может.
                .filter(component -> component.weaponKind() != WeaponKind.BOMB)
                .toList();
        if (open.isEmpty()) {
            return null;
        }
        return open.stream()
                .max(Comparator
                        .comparingInt((ShipComponent one) ->
                                (space / Math.max(1, one.spaceOn(hull))) * damage(one))
                        // При равном залпе берётся более убойный ствол: одним выстрелом
                        // проще пробить щит, чем двумя слабыми (п. 8).
                        .thenComparingInt(this::damage))
                .orElse(null);
    }

    /**
     * Урон ствола за залп — п. 8: сколько бьёт, сколькими выстрелами и на сколько сторон.
     * <p>
     * Обволакивающий удар считается вчетверо тем же множителем, каким его считают бой и
     * сила проекта ({@link ShipDesignRules#ENVELOPING_SIDES}): иначе «лучшее оружие» у
     * автоматического проекта значило бы не то, что «сильнее» в остальной игре.
     */
    private int damage(ShipComponent weapon) {
        int sides = Boolean.TRUE.equals(weapon.envelops()) ? ShipDesignRules.ENVELOPING_SIDES : 1;
        return weapon.effects().getOrDefault(ShipEffectType.WEAPON_DAMAGE, 0)
                * Math.max(1, weapon.effects().getOrDefault(ShipEffectType.WEAPON_SHOTS, 1))
                * sides;
    }

    private void saveComponents(ShipDesignEntity design, List<ShipDesignRules.Item> items) {
        List<ShipDesignComponentEntity> rows = new ArrayList<>(items.size());
        int order = 0;
        for (ShipDesignRules.Item item : items) {
            ShipDesignComponentEntity row = new ShipDesignComponentEntity();
            row.setDesignId(design.getId());
            row.setComponentCode(item.component().code());
            row.setCount(item.count());
            row.setSortOrder(order++);
            row.setModifications(item.modifications().isEmpty() ? null
                    : item.modifications().stream()
                            .map(WeaponModification::code)
                            .collect(Collectors.joining(",")));
            rows.add(row);
        }
        shipDesignComponentRepository.saveAll(rows);
    }

    private List<ShipDesignRules.Item> toItems(List<ShipDesignComponentEntity> rows) {
        return rows.stream()
                .map(row -> new ShipDesignRules.Item(
                        shipCatalog.component(row.getComponentCode()),
                        row.getCount(),
                        modifications(row.getModifications())))
                .toList();
    }

    /**
     * Модификации ствола из строки состава — п. 8: коды через запятую, пусто — ствол как
     * есть. Неизвестный код молча пропускается: справочник правится на лету, и модификация
     * может из него уйти, а проекты по ней уже собраны — ронять из-за этого бой нельзя.
     */
    private List<WeaponModification> modifications(String codes) {
        if (codes == null || codes.isBlank()) {
            return List.of();
        }
        List<WeaponModification> found = new ArrayList<>();
        for (String code : codes.split(",")) {
            String trimmed = code.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                found.add(shipCatalog.modification(trimmed));
            } catch (NotFoundException ignored) {
                log.debug("Модификация «{}» пропала из справочника: проект строится без неё", trimmed);
            }
        }
        return List.copyOf(found);
    }

    /** Коды изученных игроком технологий — для разбора трофеев (п. 8). */
    public Set<String> technologiesOf(PlayerEntity player) {
        return technologies(player);
    }

    /** Коды изученных игроком технологий. */
    public Set<String> technologies(PlayerEntity player) {
        return playerTechnologyRepository.findAllByPlayerIdOrderByCategoryCodeAscLevelOrderAsc(player.getId())
                .stream()
                .map(technology -> technology.getOptionCode())
                .collect(Collectors.toSet());
    }

    /** Компонент без технологии доступен с начала партии — как в MOO II. */
    private Boolean researched(String requiredTechCode, Set<String> technologies) {
        return requiredTechCode == null || technologies.contains(requiredTechCode);
    }

    /**
     * Название нужной технологии на языке читателя — п. 3.5.
     * <p>
     * Окно выбора системы писало КОД («нужна технология: electronic-computer»): код лежит
     * в базе у изученного, а игроку нужно имя. Неизвестный код возвращается как есть —
     * молчать о требовании хуже, чем показать его строкой.
     */
    private String techName(String requiredTechCode) {
        if (requiredTechCode == null) {
            return null;
        }
        var place = researchCatalog.places().get(requiredTechCode);
        return place == null ? requiredTechCode : place.option().name();
    }

    /**
     * Действия компонента для экрана: число и подпись вида «урон 6», «место 50 %».
     * <p>
     * Числа уходят на клиент потому, что окно дизайна считает проект на лету, пока игрок
     * его собирает, — сохранённого проекта ещё нет, и спрашивать сервер не о чем.
     */
    private List<ShipEffectDto> effectLines(ShipComponent component) {
        List<ShipEffectDto> lines = new ArrayList<>(component.effects().size());
        for (Map.Entry<ShipEffectType, Integer> effect : component.effects().entrySet()) {
            lines.add(new ShipEffectDto(effect.getKey(), effect.getValue(),
                    effectName(effect.getKey()) + " " + effect.getValue()
                            + (percent(effect.getKey()) ? " %" : "")));
        }
        return lines;
    }

    private Boolean percent(ShipEffectType type) {
        return type == ShipEffectType.ARMOUR_PERCENT
                || type == ShipEffectType.ATTACK_PERCENT
                || type == ShipEffectType.ANTI_MISSILE
                || type == ShipEffectType.MARINES_PERCENT
                || type == ShipEffectType.STRUCTURE_PERCENT
                || type == ShipEffectType.SPACE_PERCENT
                || type == ShipEffectType.FUEL_RANGE_PERCENT;
    }

    /**
     * Подпись действия на языке запроса — ярлык перечисления ({@code enum.ShipEffectType.*}).
     * Прежде слова были вбиты по-русски прямо здесь, и английская игра показывала «броня
     * 100 %» (трек техдолга, пункт 21): строка собирается на запрос, а значит — {@code Messages}.
     */
    private String effectName(ShipEffectType type) {
        return messages.label(type);
    }

    /**
     * Переводит стройку и очередь колоний владельца с вытесненного проекта на новый проект той же
     * ячейки — п. 8, п. 10 (09.10.2026, баг хозяина проекта). Строят КОРАБЛЬ ячейки, а не строку
     * проекта: прежде колония, заказавшая фрегат до правки проекта, оставалась на вытесненной
     * строке — экран звал её «проект снят с производства», цены у неё не было, и стройка висела
     * бесконечной. Проект убран из ячейки вовсе ({@code fresh == null}) — строить нечего: текущая
     * стройка сменяется товарами (вложенное остаётся колонии), а из очереди такие строки уходят.
     */
    void retargetProjects(UUID ownerPlayerId, UUID obsoleteId, UUID freshId) {
        String oldCode = com.sddnw.server.domain.ColonyProject.ship(obsoleteId);
        String newCode = freshId == null ? null : com.sddnw.server.domain.ColonyProject.ship(freshId);
        for (var planet : planetRepository.findAllByOwnerPlayerId(ownerPlayerId)) {
            if (obsoleteId.equals(planet.getProjectDesignId())) {
                if (freshId == null) {
                    planet.setProjectCode(com.sddnw.server.domain.ColonyProject.TRADE_GOODS);
                    planet.setProjectDesignId(null);
                } else {
                    planet.setProjectDesignId(freshId);
                }
            }
            if (planet.getBuildQueue().contains(oldCode)) {
                // Очередь правится копией: правку того же списка Hibernate не заметил бы.
                List<String> queue = new ArrayList<>();
                for (String code : planet.getBuildQueue()) {
                    if (!code.equals(oldCode)) {
                        queue.add(code);
                    } else if (newCode != null) {
                        queue.add(newCode);
                    }
                }
                planet.setBuildQueue(queue);
            }
        }
    }

    /**
     * Действующий проект на месте вытесненного — та же ячейка; пусто — ячейка освобождена. Для
     * колоний, застрявших на вытесненной строке до починки {@link #retargetProjects}.
     */
    public java.util.Optional<ShipDesignEntity> successor(ShipDesignEntity design) {
        if (!Boolean.TRUE.equals(design.getObsolete()) || design.getSlot() == null) {
            return java.util.Optional.of(design);
        }
        return shipDesignRepository.findByOwnerPlayerIdAndSlotAndObsoleteFalse(design.getOwnerPlayerId(), design.getSlot());
    }
}
