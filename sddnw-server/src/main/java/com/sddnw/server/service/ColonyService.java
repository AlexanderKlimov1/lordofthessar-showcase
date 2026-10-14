package com.sddnw.server.service;

import com.sddnw.server.domain.Building;
import com.sddnw.server.domain.BuildingEffects;
import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.ColonyProject;
import com.sddnw.server.domain.PopulationJobs;
import com.sddnw.server.domain.enums.BuildingEffectType;
import com.sddnw.server.domain.enums.PlanetClimate;
import com.sddnw.server.domain.enums.PlanetFind;
import com.sddnw.server.domain.enums.PlanetGravity;
import com.sddnw.server.domain.entity.PlanetBuildingEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.PlayerTechnologyEntity;
import com.sddnw.server.domain.entity.PopulationTransferEntity;
import com.sddnw.server.dto.ColonyDto;
import com.sddnw.server.dto.BuildModeRequest;
import com.sddnw.server.dto.ColonyProjectDto;
import com.sddnw.server.dto.SetPopulationRequest;
import com.sddnw.server.dto.ColonizeRequest;
import com.sddnw.server.dto.SetProjectRequest;
import com.sddnw.server.repository.PlanetBuildingRepository;
import com.sddnw.server.repository.PlanetRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import com.sddnw.server.repository.PopulationTransferRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Колонии игрока — п. 4.1 и п. 10.
 * <p>
 * Как в MOO II, каждый житель занят чем-то одним — фермерством, производством или наукой,
 * — и выработка зависит и от планеты, и от построенных зданий: климат и фермы дают еду,
 * минералы и заводы производство, учёные и лаборатории науку.
 * <p>
 * Колония строит один проект за раз. Проект это либо здание, доступное после изучения
 * своей технологии, либо особый проект MOO II — дома, товары или колониальная база.
 * Дома и товары доступны с первого хода и не заканчиваются никогда; колониальная база
 * достраивается и заселяет свободную планету своей же системы.
 */
@Service
public class ColonyService {

    private static final Logger log = LoggerFactory.getLogger(ColonyService.class);

    /**
     * Особые проекты колонии — всё, что колония строит, но зданием оно не становится
     * (п. 4.1, п. 8, п. 10, п. 12, п. 13): дома, товары, шпион, грузовик, колониальная база
     * и три гражданских корабля.
     * <p>
     * <b>Здесь только то, что от языка не зависит</b>, — ключ текста и цена. Имя и описание
     * собираются НА ЗАПРОС, на языке читателя ({@link #special}). Прежде это были восемь
     * готовых строк по-русски, собранных один раз при загрузке класса, и на английском
     * экране колонии стояло «Товары», а в списке стройки — «Дома», «Шпион», «Грузовой
     * флот». Первое решение о стройке английский игрок принимал по русскому тексту — и
     * принимал его на том самом экране, куда вторым шагом посылает обучение.
     * <p>
     * Строка, собранная один раз на весь процесс, языка читателя знать не может: он
     * появляется только с запросом. Та же причина, по которой справочники держат пары
     * {@code { "en": …, "ru": … }}, а отчёт хода — ключи.
     */
    private record Special(String key, Integer cost) {
    }

    private static final Map<String, Special> SPECIALS = Map.ofEntries(
            Map.entry(ColonyProject.HOUSING, new Special("housing", null)),
            Map.entry(ColonyProject.TRADE_GOODS, new Special("tradeGoods", null)),
            Map.entry(ColonyProject.SPY, new Special("spy", ColonyProject.SPY_COST)),
            Map.entry(ColonyProject.FREIGHTER, new Special("freighter", ColonyProject.FREIGHTER_COST)),
            Map.entry(ColonyProject.COLONY_BASE, new Special("colonyBase", ColonyProject.COLONY_BASE_COST)),
            Map.entry(ColonyProject.COLONY_SHIP, new Special("colonyShip", ColonyProject.COLONY_SHIP_COST)),
            Map.entry(ColonyProject.OUTPOST_SHIP, new Special("outpostShip", ColonyProject.OUTPOST_SHIP_COST)),
            Map.entry(ColonyProject.TRANSPORT, new Special("transport", ColonyProject.TRANSPORT_COST)),
            Map.entry(ColonyProject.TERRAFORM, new Special("terraform", TerraformRules.TERRAFORM_STEP_COST)),
            Map.entry(ColonyProject.GAIA, new Special("gaia", TerraformRules.GAIA_COST)),
            Map.entry(ColonyProject.ARTIFICIAL_PLANET, new Special("artificialPlanet", TerraformRules.ARTIFICIAL_PLANET_COST)),
            Map.entry(ColonyProject.ANDROID_FARMER, new Special("androidFarmer", ColonyProject.ANDROID_COST)),
            Map.entry(ColonyProject.ANDROID_WORKER, new Special("androidWorker", ColonyProject.ANDROID_COST)),
            Map.entry(ColonyProject.ANDROID_SCIENTIST, new Special("androidScientist", ColonyProject.ANDROID_COST)));

    /** Технология каждого вида андроидов — п. 4.1 (перенос MOO II). */
    /**
     * Есть ли у колонии место ещё на одного андроида — п. 4.1: либо не исчерпан запас сверх потолка по размеру
     * планеты, либо есть свободное место жителя. Одно правило на список стройки, достройку и ИИ.
     */
    public Boolean androidRoom(PlanetEntity planet, ColonyContext context) {
        return planet.androids() < ColonyProject.androidsBeyondCap(planet.getPlanetSize())
                || maxPopulation(planet, context.effects(planet), context.race(planet)) > planet.getPopulation();
    }

    public static final Map<String, String> ANDROID_TECHS = Map.of(
            ColonyProject.ANDROID_FARMER, "android-farmers",
            ColonyProject.ANDROID_WORKER, "android-workers",
            ColonyProject.ANDROID_SCIENTIST, "android-scientists");

    /**
     * Ключи текстов особых проектов — для проверки словарей ({@code ColonyProjectTextsTest}).
     * <p>
     * Проверка идёт по САМОЙ таблице, а не по своему списку: новый особый проект, заведённый
     * без строк в словарях, иначе показал бы игроку ключ вместо имени — и ни одна проверка
     * этого бы не заметила, пока кто-нибудь не открыл экран.
     */
    static Set<String> specialTextKeys() {
        return SPECIALS.values().stream().map(Special::key).collect(Collectors.toSet());
    }

    private final PlanetRepository planetRepository;
    private final PlayerRepository playerRepository;
    private final PopulationTransferRepository transferRepository;
    private final PlanetBuildingRepository planetBuildingRepository;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final BuildingCatalog buildingCatalog;
    private final RaceService raceService;
    private final DiplomacyService diplomacyService;
    private final PopulationCalculator populationCalculator;
    private final AssimilationRules assimilationRules;
    private final ShipDesignService shipDesignService;
    private final ShipDesignRules shipDesignRules;
    private final LeaderBonusService leaderBonuses;
    private final EmpireActivityService activity;
    private final ResearchCatalog researchCatalog;

    /** Имена и описания особых проектов и кораблей — на языке запроса. */
    private final Messages messages;

    public ColonyService(PlanetRepository planetRepository,
                         PlayerRepository playerRepository,
                         PopulationTransferRepository transferRepository,
                         PlanetBuildingRepository planetBuildingRepository,
                         PlayerTechnologyRepository playerTechnologyRepository,
                         BuildingCatalog buildingCatalog,
                         RaceService raceService,
                         DiplomacyService diplomacyService,
                         PopulationCalculator populationCalculator,
                         ShipDesignService shipDesignService,
                         ShipDesignRules shipDesignRules,
                         LeaderBonusService leaderBonuses,
                         AssimilationRules assimilationRules,
                         EmpireActivityService activity,
                         ResearchCatalog researchCatalog,
                         Messages messages) {
        this.activity = activity;
        this.researchCatalog = researchCatalog;
        this.messages = messages;
        this.assimilationRules = assimilationRules;
        this.planetRepository = planetRepository;
        this.playerRepository = playerRepository;
        this.transferRepository = transferRepository;
        this.planetBuildingRepository = planetBuildingRepository;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.buildingCatalog = buildingCatalog;
        this.raceService = raceService;
        this.diplomacyService = diplomacyService;
        this.populationCalculator = populationCalculator;
        this.shipDesignService = shipDesignService;
        this.shipDesignRules = shipDesignRules;
        this.leaderBonuses = leaderBonuses;
    }

    /**
     * Всё, что нужно знать о наборе колоний, собранное разом.
     * <p>
     * Изученное владельцем и построенные здания нужны каждой колонии, поэтому берутся
     * одним запросом на весь набор, а не по запросу на планету: карта галактики строится
     * по всем планетам сразу.
     */
    public record ColonyContext(
            Map<UUID, Set<String>> technologiesByOwner,
            Map<UUID, List<Building>> buildingsByPlanet,
            Map<UUID, RaceEffects> raceEffectsByOwner,
            Map<UUID, BuildingEffects> treatyEffectsByOwner,
            Map<String, Building> catalog,
            /** Сколько еды довёз колонии грузовой флот империи — п. 4.1.1. */
            Map<UUID, Integer> deliveredFoodByPlanet,
            /** Действующие проекты кораблей владельцев колоний — п. 8. */
            Map<UUID, List<ShipDesignService.ShipBuildOption>> shipDesignsByOwner,
            /**
             * Прибавки колониальных лидеров по звёздным системам — п. 6.
             *
             * Лидер служит в системе и помогает всем её колониям сразу, поэтому ключ здесь
             * система, а не планета. Пустая карта значит «лидеров нет» — так контекст
             * собирается и там, где лидеры ни при чём (карта галактики до найма).
             */
            Map<UUID, Map<String, Integer>> leaderBonusBySystem,
            /**
             * Все населённые колонии владельцев — п. 10.1: столица одна на империю, и чтобы
             * знать, есть ли она, нужно видеть колонии, а не только переданные планеты.
             * Хозяин сверяется при каждом вопросе: колонию могли захватить посреди хода.
             */
            Map<UUID, List<PlanetEntity>> coloniesByOwner,
            /**
             * Что изученное даёт колониям владельца — п. 9 (13.10.2026, «достижения» MOO II:
             * обмен галактической валюты, продвинутая планировка городов, повышенный
             * интеллект, биоморфные грибы). Пары из поля {@code effects} дерева технологий,
             * сложенные {@link ResearchCatalog#effectsOf}.
             */
            Map<UUID, BuildingEffects> techEffectsByOwner
    ) {

        /** Контекст без действий технологий — так его собирают проверки правил столицы. */
        public ColonyContext(Map<UUID, Set<String>> technologiesByOwner,
                             Map<UUID, List<Building>> buildingsByPlanet,
                             Map<UUID, RaceEffects> raceEffectsByOwner,
                             Map<UUID, BuildingEffects> treatyEffectsByOwner,
                             Map<String, Building> catalog,
                             Map<UUID, Integer> deliveredFoodByPlanet,
                             Map<UUID, List<ShipDesignService.ShipBuildOption>> shipDesignsByOwner,
                             Map<UUID, Map<String, Integer>> leaderBonusBySystem,
                             Map<UUID, List<PlanetEntity>> coloniesByOwner) {
            this(technologiesByOwner, buildingsByPlanet, raceEffectsByOwner, treatyEffectsByOwner,
                    catalog, deliveredFoodByPlanet, shipDesignsByOwner, leaderBonusBySystem,
                    coloniesByOwner, Map.of());
        }

        /**
         * Действия изученного на ЭТОЙ колонии — п. 9. Биоморфные грибы поднимают еду фермера
         * только там, где климат её не даёт вовсе («from 0 to 1 per farmer» на токсичных,
         * облучённых и бесплодных мирах), поэтому их эффект переводится в прибавку к фермеру
         * лишь на таком климате, а на прочих отбрасывается.
         */
        public BuildingEffects technologyEffects(PlanetEntity planet) {
            BuildingEffects owned = techEffectsByOwner.getOrDefault(planet.getOwnerPlayerId(), BuildingEffects.NONE);
            if (owned.foodOnLifeless() <= 0) {
                return owned;
            }
            Map<BuildingEffectType, Integer> amounts = new java.util.EnumMap<>(BuildingEffectType.class);
            amounts.putAll(owned.amounts());
            amounts.remove(BuildingEffectType.FOOD_ON_LIFELESS);
            PlanetClimate climate = planet.getClimate();
            if (climate != null && Boolean.TRUE.equals(climate.getColonizable()) && climate.getFoodPerFarmer() == 0) {
                amounts.merge(BuildingEffectType.FOOD_PER_FARMER, Math.min(1, owned.foodOnLifeless()), Integer::sum);
            }
            return new BuildingEffects(Map.copyOf(amounts), owned.upkeep());
        }

        /**
         * Есть ли у империи столица — п. 10.1: здание «Capitol» на её населённой колонии.
         * Считается по построенному в контексте, поэтому отстроенная или потерянная этим же
         * ходом столица видна сразу (см. {@link #built}, {@link #sold}).
         */
        public Boolean hasCapitol(UUID ownerId) {
            if (ownerId == null) {
                return Boolean.FALSE;
            }
            return coloniesByOwner.getOrDefault(ownerId, List.of()).stream()
                    .filter(colony -> ownerId.equals(colony.getOwnerPlayerId()))
                    .filter(colony -> colony.getPopulation() > 0)
                    .anyMatch(colony -> buildings(colony).stream()
                            .anyMatch(building -> CapitolRules.CODE.equals(building.code())));
        }

        /**
         * Столица уже заказана на ДРУГОЙ колонии империи — п. 10.1: на стапеле или в очереди.
         * Столица одна, и вторая, заложенная параллельно, ушла бы в пустоту.
         */
        public Boolean capitolOrderedElsewhere(PlanetEntity planet) {
            UUID ownerId = planet.getOwnerPlayerId();
            return coloniesByOwner.getOrDefault(ownerId, List.of()).stream()
                    .filter(colony -> !colony.getId().equals(planet.getId()))
                    .filter(colony -> ownerId.equals(colony.getOwnerPlayerId()))
                    .anyMatch(colony -> CapitolRules.CODE.equals(colony.getProjectCode())
                            || colony.getBuildQueue().contains(CapitolRules.CODE));
        }

        /**
         * Империя без столицы, хотя строю она положена, — п. 10.1: штраф морали всем её
         * колониям и остановка новых лидеров.
         */
        public Boolean capitolLost(UUID ownerId) {
            RaceEffects government = raceEffectsByOwner.getOrDefault(ownerId, RaceEffects.NONE);
            return Boolean.TRUE.equals(government.hasCapitol()) && !Boolean.TRUE.equals(hasCapitol(ownerId));
        }

        /**
         * Процент прибавки колониального лидера этой колонии — п. 6.
         *
         * Идентификатор системы читается с ленивой ссылки планеты: сама система при этом
         * из базы не поднимается (см. «Грабли» в правилах проекта — с ленивой заглушки безопасно
         * читается только идентификатор).
         */
        public Integer leaderPercent(PlanetEntity planet, String ability) {
            if (leaderBonusBySystem.isEmpty() || planet.getStarSystem() == null) {
                return 0;
            }
            return leaderBonusBySystem
                    .getOrDefault(planet.getStarSystem().getId(), Map.of())
                    .getOrDefault(ability, 0);
        }

        /** Значение, поднятое прибавкой лидера: проценты складываются с сотней. */
        public Integer withLeader(Integer value, PlanetEntity planet, String ability) {
            Integer percent = leaderPercent(planet, ability);
            return percent == 0 ? value : value * (100 + percent) / 100;
        }

        /** Корабли, которые может строить эта колония, — проекты её империи. */
        public List<ShipDesignService.ShipBuildOption> shipDesigns(PlanetEntity planet) {
            return shipDesignsByOwner.getOrDefault(planet.getOwnerPlayerId(), List.of());
        }

        /** Подвоз еды на эту колонию; ноль — грузовиков нет или везти нечего. */
        public Integer delivered(PlanetEntity planet) {
            return deliveredFoodByPlanet.getOrDefault(planet.getId(), 0);
        }

        public List<Building> buildings(PlanetEntity planet) {
            return buildingsByPlanet.getOrDefault(planet.getId(), List.of());
        }

        /**
         * Отмечает здание, достроенное этим же ходом, — п. 10.
         * <p>
         * Контекст собирается один раз на весь ход, и без отметки он врёт всем, кто идёт
         * после производства: список доступного колонии по-прежнему предлагал бы только
         * что построенное здание. Империя ИИ на это и попалась — выбирала его снова, и
         * следующий ход падал на уникальности {@code (planet_id, building_code)}.
         */
        public void built(PlanetEntity planet, Building building) {
            buildingsByPlanet.computeIfAbsent(planet.getId(), key -> new ArrayList<>()).add(building);
        }

        /**
         * Отмечает здание, ПРОДАННОЕ этим же ходом, — п. 10, п. 11.1.
         * <p>
         * Зеркало {@link #built}: пустая казна заставляет империю распродавать постройки
         * прямо в фазе производства (ProductionPhase.payDebts), а фазы, идущие следом —
         * ИИ, наука, разведка, — считают по контексту хода. Без отметки они весь
         * оставшийся ход работали бы с содержанием и прибавками того, чего уже нет, а
         * империя ИИ вдобавок не стала бы отстраивать проданное.
         */
        public void sold(PlanetEntity planet, String buildingCode) {
            List<Building> standing = buildingsByPlanet.get(planet.getId());
            if (standing != null) {
                standing.removeIf(building -> building.code().equals(buildingCode));
            }
        }

        public Set<String> technologies(PlanetEntity planet) {
            return technologiesByOwner.getOrDefault(planet.getOwnerPlayerId(), Set.of());
        }

        /**
         * Всё, что действует на колонию: её постройки плюс особенности расы владельца
         * (п. 7). Раса работает как вечная постройка, поэтому расчёты колонии их не
         * различают — им достаточно суммы.
         */
        /**
         * Источники эффектов колонии ПО ОТДЕЛЬНОСТИ — field-backlog, пункт 40: «из чего
         * сложилось». Это те же слагаемые, что складывает {@link #effects}, в том же составе:
         * каждая постройка, раса, наследие, находка, тяжесть мира, мораль и договоры (с
         * гостевыми правилами партии). Второго свода правил нет — сумма их и есть {@link #effects}.
         */
        public List<EffectSource> effectSources(PlanetEntity planet) {
            List<EffectSource> sources = new ArrayList<>();
            for (Building building : buildings(planet)) {
                sources.add(new EffectSource("BUILDING", building.name(), BuildingEffects.sum(List.of(building))));
            }
            sources.add(new EffectSource("RACE", null, raceOfPopulation(planet)));
            sources.add(new EffectSource("ARTIFACTS", null, artifacts(planet)));
            sources.add(new EffectSource("FIND", null, find(planet)));
            sources.add(new EffectSource("GRAVITY", null, gravity(planet)));
            sources.add(new EffectSource("MORALE", null, morale(planet).effects()));
            sources.add(new EffectSource("TREATIES", null,
                    treatyEffectsByOwner.getOrDefault(planet.getOwnerPlayerId(), BuildingEffects.NONE)));
            sources.add(new EffectSource("TECHNOLOGIES", null, technologyEffects(planet)));
            sources.add(new EffectSource("ANDROIDS", null, androids(planet)));
            return sources;
        }

        public BuildingEffects effects(PlanetEntity planet) {
            return BuildingEffects.sum(buildings(planet))
                    .plus(raceOfPopulation(planet))
                    .plus(artifacts(planet))
                    .plus(find(planet))
                    .plus(gravity(planet))
                    .plus(morale(planet).effects())
                    .plus(treatyEffectsByOwner.getOrDefault(planet.getOwnerPlayerId(), BuildingEffects.NONE))
                    .plus(technologyEffects(planet))
                    .plus(androids(planet));
        }

        /**
         * Андроиды колонии — п. 4.1 (перенос MOO II): каждый даёт 3 единицы своего дела, съедает
         * единицу производства и занимает место жителя ({@code ColonyProject.ANDROID_OUTPUT}) — кроме первых,
         * которых планета держит сверх потолка по своему размеру ({@code ColonyProject.androidsBeyondCap}).
         */
        private BuildingEffects androids(PlanetEntity planet) {
            int all = planet.androids();
            if (all <= 0) {
                return BuildingEffects.NONE;
            }
            int output = ColonyProject.ANDROID_OUTPUT;
            Map<BuildingEffectType, Integer> amounts = new java.util.EnumMap<>(BuildingEffectType.class);
            amounts.put(BuildingEffectType.FOOD_FLAT, output * planet.getAndroidFarmers());
            amounts.put(BuildingEffectType.PRODUCTION_FLAT, output * planet.getAndroidWorkers() - all);
            amounts.put(BuildingEffectType.RESEARCH_FLAT, output * planet.getAndroidScientists());
            // Первые андроиды встают сверх потолка населения (ColonyProject.androidsBeyondCap).
            amounts.put(BuildingEffectType.MAX_POPULATION,
                    -ColonyProject.androidsTakingRoom(all, planet.getPlanetSize()));
            return new BuildingEffects(amounts, 0);
        }

        /**
         * Мораль колонии — п. 10.1: строй с казармами, здания досуга, достижения, духовный
         * лидер и подданные другой расы ({@link MoraleRules}). В {@link #effects} она входит
         * четырьмя процентами — к еде, производству, науке и налогу.
         * <p>
         * Считается по ПОСТРОЙКАМ, а не по {@link #effects}: иначе мораль спрашивала бы сама
         * себя. Строй — у хозяина, а не у подданных: правит колонией он.
         */
        public MoraleRules.Morale morale(PlanetEntity planet) {
            if (planet.getOwnerPlayerId() == null || planet.getPopulation() <= 0) {
                return MoraleRules.Morale.NONE;
            }
            return MoraleRules.morale(race(planet), BuildingEffects.sum(buildings(planet)),
                    technologies(planet), leaderPercent(planet, MoraleRules.SPIRITUAL_ABILITY),
                    planet.getAlienPopulation() > 0, capitolLost(planet.getOwnerPlayerId()));
        }

        /**
         * Чего стоит колонии непривычная тяжесть мира — п. 4.1, п. 7.
         * <p>
         * Штраф ложится на производство той же строкой, что и прибавки зданий: колония
         * не различает, откуда пришёл процент. Расе, привыкшей к такой тяжести, он равен
         * нулю — числа и правило в {@link PlanetGravity}.
         */
        /**
         * Штраф тяжести колонии в процентах производства, БЕЗ гравитационного генератора — п. 4.1: сколько
         * снимет генератор. Ноль — штрафа нет (или тяжесть привычна расе), прибавка лёгкого мира — тоже ноль.
         */
        public Integer gravityPenaltyPercent(PlanetEntity planet) {
            RaceEffects race = race(planet);
            return Math.min(0, PlanetGravity.of(planet.getPlanetSize(), planet.getHomeworld())
                    .productionPercent(race.gravityLow(), race.gravityHigh()));
        }

        private BuildingEffects gravity(PlanetEntity planet) {
            RaceEffects race = race(planet);
            Integer percent = PlanetGravity.of(planet.getPlanetSize(), planet.getHomeworld())
                    .productionPercent(race.gravityLow(), race.gravityHigh());
            // Гравитационный генератор снимает штраф тяжести целиком — п. 4.1 (перенос MOO II:
            // «eliminates gravity penalties»). Прибавку непривычно лёгкого мира он не трогает.
            if (percent < 0 && buildings(planet).stream()
                    .anyMatch(building -> building.effects().containsKey(BuildingEffectType.GRAVITY_NORMAL))) {
                percent = 0;
            }
            return percent == 0
                    ? BuildingEffects.NONE
                    : new BuildingEffects(Map.of(BuildingEffectType.PRODUCTION_PERCENT, percent), 0);
        }

        /**
         * Наследие ушедшей цивилизации на родном мире — п. 7: учёный даёт на нём два
         * лишних очка.
         * <p>
         * Единственная сторона расы, которая действует не на всю империю, а на одну
         * планету: мир артефактов достался игроку один. Поэтому она и не попадает
         * в общие эффекты расы, а приходит сюда отдельной прибавкой.
         */
        private BuildingEffects artifacts(PlanetEntity planet) {
            Integer bonus = race(planet).homeResearchPerScientist();
            return bonus == 0 || !Boolean.TRUE.equals(planet.getHomeworld())
                    ? BuildingEffects.NONE
                    : new BuildingEffects(Map.of(BuildingEffectType.RESEARCH_PER_SCIENTIST, bonus), 0);
        }

        /**
         * Что даёт колонии находка её планеты — п. 4.1: золотые жилы, самоцветы, туземцы
         * или наследие ушедшей цивилизации.
         * <p>
         * Находка описана теми же парами «тип эффекта — количество», что и здание
         * ({@link PlanetFind}), и приходит сюда одной строкой: колония не различает, чем
         * поднята её выработка — постройкой, расой или тем, что лежит у неё под ногами.
         * Содержания находка не требует: платить за жилу некому.
         */
        private BuildingEffects find(PlanetEntity planet) {
            return planet.getFind() == null
                    ? BuildingEffects.NONE
                    : new BuildingEffects(planet.getFind().getEffects(), 0);
        }

        /** Раса владельца колонии — п. 7; у ничьей планеты особенностей нет. */
        public RaceEffects race(PlanetEntity planet) {
            return raceEffectsByOwner.getOrDefault(planet.getOwnerPlayerId(), RaceEffects.NONE);
        }

        /** Раса подданных колонии — п. 12; пока их нет, это раса хозяина. */
        public RaceEffects alienRace(PlanetEntity planet) {
            if (planet.getAlienPopulation() <= 0 || planet.getAlienOwnerPlayerId() == null) {
                return race(planet);
            }
            return raceEffectsByOwner.getOrDefault(planet.getAlienOwnerPlayerId(), race(planet));
        }

        /**
         * Что даёт колонии её население — п. 7, п. 12.
         * <p>
         * Пока на колонии живут подданные, взятые с боем, работает она двумя расами
         * сразу: свои жители по правилам хозяина, подданные — по своим прежним. Доли
         * считаются по головам, и с каждым ассимилированным жителем колония всё больше
         * становится своей.
         */
        private BuildingEffects raceOfPopulation(PlanetEntity planet) {
            Integer aliens = Math.max(0, planet.getAlienPopulation());
            if (aliens == 0) {
                return race(planet).colony();
            }
            Integer own = Math.max(0, planet.getPopulation() - aliens);
            return race(planet).colony().blend(alienRace(planet).colony(), own, aliens);
        }
    }

    /**
     * Наука гостевой партии вдвое (GuestGameRules.RESEARCH_MULTIPLIER): прибавка к выработке
     * учёного, равная базе, — то есть удвоение ДО процентов, как и просил хозяин проекта.
     * Ложится к договорам владельца, потому что это тоже действие «на всю империю»: так её
     * видят все, кто считает науку по контексту колоний, — колония, ход, «Инфо» и ИИ.
     */
    private Map<UUID, BuildingEffects> withGuestResearch(Map<UUID, BuildingEffects> treaties,
                                                         Set<UUID> owners) {
        if (owners.isEmpty()) {
            return treaties;
        }
        List<UUID> guests = playerRepository.findIdsInRichStartGames(owners);
        if (guests.isEmpty()) {
            return treaties;
        }
        BuildingEffects bonus = new BuildingEffects(Map.of(BuildingEffectType.RESEARCH_PER_SCIENTIST,
                populationCalculator.researchPerScientist() * (GuestGameRules.RESEARCH_MULTIPLIER - 1)), 0);
        Map<UUID, BuildingEffects> result = new HashMap<>(treaties);
        for (UUID guest : guests) {
            result.merge(guest, bonus, BuildingEffects::plus);
        }
        return result;
    }

    /** Собирает контекст для набора планет: изученное владельцами и построенные здания. */
    public ColonyContext context(Collection<PlanetEntity> planets) {
        Set<UUID> owners = planets.stream()
                .map(PlanetEntity::getOwnerPlayerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Расы прежних хозяев нужны там, где на колонии ещё живут их подданные (п. 12):
        // они работают по своим правилам, пока не ассимилируются.
        Set<UUID> withAliens = planets.stream()
                .map(PlanetEntity::getAlienOwnerPlayerId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Еду империя развозит грузовым флотом между всеми своими колониями, поэтому
        // контекст всегда знает их все — даже когда его строят ради одной планеты.
        List<PlanetEntity> imperial = owners.isEmpty()
                ? List.of()
                : planetRepository.findAllByOwnerPlayerIdIn(owners).stream()
                        .sorted(GameOrder.PLANETS)
                        .filter(colony -> colony.getPopulation() > 0)
                        .toList();

        Set<UUID> planetIds = Stream.concat(planets.stream(), imperial.stream())
                .map(PlanetEntity::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<UUID, Set<String>> technologies = owners.isEmpty()
                ? Map.of()
                : playerTechnologyRepository.findAllByPlayerIdIn(owners).stream()
                        .collect(Collectors.groupingBy(
                                PlayerTechnologyEntity::getPlayerId,
                                Collectors.mapping(PlayerTechnologyEntity::getOptionCode, Collectors.toSet())));

        Map<String, Building> catalog = buildingCatalog.byCode();

        // Особенности расы берутся по владельцам колоний: их немного, и они не меняются
        // за партию, поэтому считаются один раз на весь набор планет.
        Set<UUID> races = new HashSet<>(owners);
        races.addAll(withAliens);
        Map<UUID, RaceEffects> raceEffects = raceService.effectsByPlayer(races);

        // Торговый и исследовательский договоры работают на колонии так же, как здания
        // и раса, — п. 15.
        // Одной выборкой на всех владельцев, а не запросом на игрока: контекст собирается
        // каждый ход, и три запроса на империю превращались в восемнадцать за ход изнутри
        // посчитанного хода — см. DiplomacyService.treatyEffects(Collection).
        Map<UUID, BuildingEffects> treatyEffects = withGuestResearch(diplomacyService.treatyEffects(owners), owners);

        // Карта построенного меняется по ходу: производство дописывает в неё достроенное
        // (см. ColonyContext.built), поэтому она изменяемая, а не List.of()/Map.of().
        Map<UUID, List<Building>> built = planetIds.isEmpty()
                ? new HashMap<>()
                : planetBuildingRepository.findAllByPlanetIdIn(planetIds).stream()
                        .filter(entry -> catalog.containsKey(entry.getBuildingCode()))
                        .collect(Collectors.groupingBy(
                                PlanetBuildingEntity::getPlanetId,
                                Collectors.mapping(entry -> catalog.get(entry.getBuildingCode()),
                                        Collectors.toCollection(ArrayList::new))));

        // Проекты кораблей владельцев — один запрос на весь набор колоний: список стройки
        // собирается для каждой планеты карты, а проекты у империи общие — п. 8.
        Map<UUID, List<ShipDesignService.ShipBuildOption>> shipDesigns =
                shipDesignService.buildOptions(raceEffects);

        // Лидеры владельцев колоний — п. 6: колониальный лидер помогает всем колониям
        // своей системы, и его прибавка нужна выработке, доходу и рождаемости.
        Map<UUID, Map<String, Integer>> leaderBonus = leaderBonusBySystem(owners);

        // Колонии по владельцам — п. 10.1: столица одна на империю.
        Map<UUID, List<PlanetEntity>> coloniesByOwner = imperial.stream()
                .collect(Collectors.groupingBy(PlanetEntity::getOwnerPlayerId));

        // Действия изученного — п. 9: считаются от уже взятых технологий, без новой выборки.
        Map<UUID, BuildingEffects> techEffects = new HashMap<>();
        technologies.forEach((owner, codes) -> techEffects.put(owner, researchCatalog.effectsOf(codes)));

        ColonyContext context = new ColonyContext(technologies, built, raceEffects, treatyEffects,
                catalog, Map.of(), shipDesigns, leaderBonus, coloniesByOwner, techEffects);
        return new ColonyContext(technologies, built, raceEffects, treatyEffects, catalog,
                freightDeliveries(imperial, owners, context), shipDesigns, leaderBonus, coloniesByOwner,
                techEffects);
    }

    /**
     * Прибавки колониальных лидеров владельцев этих колоний — п. 6.
     * <p>
     * Ключ карты — звёздная система: лидер служит в системе и помогает всем её колониям
     * сразу. Берётся одной выборкой на всех владельцев: контекст собирается для карты
     * целиком, и запрос «на игрока» здесь стоил бы восьми запросов за ход.
     */
    private Map<UUID, Map<String, Integer>> leaderBonusBySystem(Set<UUID> owners) {
        Map<UUID, Map<String, Integer>> merged = new HashMap<>();
        leaderBonuses.of(owners).values().forEach(bonuses -> bonuses.bySystem()
                .forEach((systemId, abilities) -> merged
                        .computeIfAbsent(systemId, id -> new HashMap<>())
                        .putAll(abilities)));
        return merged;
    }

    /**
     * Сколько еды грузовой флот довезёт каждой голодающей колонии — п. 4.1.1.
     * <p>
     * Возится излишек соседних колоний той же империи, и не больше, чем поднимает её
     * грузовой флот. Без грузовиков подвоза нет вовсе: колония ест то, что вырастила
     * сама, — как и было в игре до появления флота.
     */
    private Map<UUID, Integer> freightDeliveries(List<PlanetEntity> colonies,
                                                 Set<UUID> owners,
                                                 ColonyContext effectsSource) {
        if (colonies.isEmpty() || owners.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Integer> freighters = playerRepository.findAllById(owners).stream()
                .collect(Collectors.toMap(PlayerEntity::getId, PlayerEntity::getFreighters));

        // Грузовики, занятые перевозкой жителей, еду не возят: рейс держит по грузовику
        // на единицу населения, пока не дойдёт — п. 4.1.1.
        Map<UUID, Integer> reserved = transferRepository.findAllByOwnerPlayerIdInOrderByIdAsc(owners).stream()
                .collect(Collectors.groupingBy(PopulationTransferEntity::getOwnerPlayerId,
                        Collectors.summingInt(transfer ->
                                populationCalculator.freightersForTransfer(transfer.getPopulation()))));

        Map<UUID, Integer> delivered = new HashMap<>();

        for (UUID owner : owners) {
            Integer fleet = Math.max(0,
                    freighters.getOrDefault(owner, 0) - reserved.getOrDefault(owner, 0));
            if (fleet <= 0) {
                continue;
            }

            List<PlanetEntity> own = colonies.stream()
                    .filter(colony -> owner.equals(colony.getOwnerPlayerId()))
                    .toList();

            int surplus = 0;
            List<PlanetEntity> hungry = new ArrayList<>();
            List<Integer> deficits = new ArrayList<>();
            for (PlanetEntity colony : own) {
                int balance = foodBalance(colony, effectsSource.effects(colony), effectsSource.race(colony));
                if (balance > 0) {
                    surplus += balance;
                } else if (balance < 0) {
                    hungry.add(colony);
                    deficits.add(-balance);
                }
            }
            if (hungry.isEmpty() || surplus == 0) {
                continue;
            }

            Integer pool = Math.min(surplus, populationCalculator.freightCapacity(fleet));
            List<Integer> shares = populationCalculator.deliverFood(pool, deficits);
            for (int index = 0; index < hungry.size(); index++) {
                if (shares.get(index) > 0) {
                    delivered.put(hungry.get(index).getId(), shares.get(index));
                }
            }
        }
        return Map.copyOf(delivered);
    }

    /** Остаток еды колонии до подвоза: своя выработка минус свои едоки. */
    private Integer foodBalance(PlanetEntity planet, BuildingEffects effects, RaceEffects race) {
        return populationCalculator.food(planet.getClimate(), jobs(planet).farmers(), effects, race)
                - populationCalculator.foodConsumption(planet.getPopulation(), race);
    }

    /** Контекст для одной планеты — для ответа на действие игрока с ней. */
    public ColonyContext context(PlanetEntity planet) {
        return context(List.of(planet));
    }

    /**
     * «Жители сами» — field-backlog, пункт 91: фермеров ровно на прокорм колонии, остальные — в доле
     * рабочих и учёных, заданной игроком ({@link AutoJobsRules}). Еда считается тем же правилом, что
     * и остаток колонии ({@link #foodBalance}), — без подвоза грузовиками.
     */
    public void applyAutoJobs(PlanetEntity planet, ColonyContext context) {
        BuildingEffects effects = context.effects(planet);
        RaceEffects race = context.race(planet);
        PopulationJobs wanted = AutoJobsRules.jobs(planet.getPopulation(), jobs(planet),
                farmers -> populationCalculator.food(planet.getClimate(), farmers, effects, race),
                populationCalculator.foodConsumption(planet.getPopulation(), race),
                Boolean.TRUE.equals(canFarm(planet, context)));
        if (!wanted.equals(planet.getJobs())) {
            planet.setJobs(wanted);
        }
    }

    /** Занятия жителей планеты, выправленные по её населению. */
    public PopulationJobs jobs(PlanetEntity planet) {
        return populationCalculator.normalize(planet.getClimate(), planet.getPopulation(), planet.getJobs());
    }

    /**
     * Растят ли здесь еду — п. 4.1.2: фермер даёт хоть что-то с климата, расы и зданий. На
     * безжизненном климате (и без гидропоники) не даёт ничего, и фермер там — едок без дела.
     * Тем же сложением считает еду с фермера ответ колонии ({@code ColonyDto.foodPerFarmer}),
     * поэтому клиент видит то же правило без второго свода.
     */
    public Boolean canFarm(PlanetEntity planet, ColonyContext context) {
        return populationCalculator.foodPerFarmer(planet.getClimate(), context.race(planet))
                + context.effects(planet).foodPerFarmer() > 0;
    }

    /** Вместимость планеты со зданиями: биосферы её поднимают. */
    public Integer maxPopulation(PlanetEntity planet, BuildingEffects effects) {
        return maxPopulation(planet, effects, RaceEffects.NONE);
    }

    /**
     * Вместимость планеты со зданиями и расой хозяина — п. 7: неприхотливым, водным и
     * подземным на одной и той же планете помещается больше, чем прочим.
     * <p>
     * Расовая часть считается поверх записанной на планете, а не вместо неё: вместимость
     * планеты — её собственное свойство и меняется терраформированием, а раса приходит
     * и уходит вместе с хозяином колонии.
     */
    public Integer maxPopulation(PlanetEntity planet, BuildingEffects effects, RaceEffects race) {
        Integer capacity = planet.getMaxPopulation()
                + populationCalculator.raceCapacityBonus(planet.getPlanetSize(), planet.getClimate(), race)
                + effects.maxPopulationBonus();
        // У пригодной планеты ниже одного жителя вместимость не опускается: прибавка
        // бывает и отрицательной — туземцы (п. 4.1) занимают три места из собственной
        // вместимости планеты, — а колония, которой некуда поселить ни одного жителя,
        // сломала бы и рост, и захват. Непригодная так и остаётся нулём: газовый гигант
        // не становится обитаемым ни от чего.
        return planet.getMaxPopulation() <= 0 ? Math.max(0, capacity) : Math.max(1, capacity);
    }

    /**
     * Сколько единиц еды колонии не хватает за ход — уже с подвозом.
     * <p>
     * Своя выработка плюс то, что довёз грузовой флот империи (п. 4.1.1). Без грузовиков
     * подвоза нет, и колония ест только то, что вырастила сама, — как в MOO II до
     * постройки первых грузовиков.
     */
    public Integer foodLack(PlanetEntity planet, ColonyContext context) {
        return Math.max(0, rawFoodLack(planet, context) - replicated(planet, context));
    }

    /** Нехватка еды колонии после подвоза, но до репликаторов. */
    private Integer rawFoodLack(PlanetEntity planet, ColonyContext context) {
        return Math.max(0, -foodBalance(planet, context.effects(planet), context.race(planet))
                - context.delivered(planet));
    }

    /**
     * Сколько еды колония сделала пищевыми репликаторами — п. 4.1.1 (перенос MOO II 1.50: «1 food
     * costs 1 PP»): недостающее после подвоза, но не больше её производства. Столько же
     * производства уходит со стройки.
     */
    public Integer replicated(PlanetEntity planet, ColonyContext context) {
        BuildingEffects effects = context.effects(planet);
        if (effects.amount(BuildingEffectType.FOOD_REPLICATOR) <= 0) {
            return 0;
        }
        int lack = rawFoodLack(planet, context);
        if (lack <= 0) {
            return 0;
        }
        return Math.min(lack, net(planet, grossProduction(planet, context), effects, context.race(planet)));
    }

    /** Производство колонии за ход со всеми её зданиями. */
    public Integer production(PlanetEntity planet, BuildingEffects effects) {
        return production(planet, effects, RaceEffects.NONE);
    }

    /**
     * Производство колонии со зданиями, расой <b>и колониальным лидером</b> — п. 6.
     * <p>
     * Управляющий («Labor Leader») поднимает выработку рабочих всей своей системы. В
     * MOO II его процент касается именно рабочих, а не твёрдых прибавок от зданий, —
     * поэтому он применяется к добыче, а не к содержимому зданий. И применяется он
     * <b>до уборки</b> (п. 10): больше промышленности — больше отходов, иначе управляющий
     * давал бы чистое производство из ничего.
     */
    public Integer production(PlanetEntity planet, ColonyContext context) {
        BuildingEffects effects = context.effects(planet);
        RaceEffects race = context.race(planet);
        return net(planet, grossProduction(planet, context), effects, race) - replicated(planet, context);
    }

    /**
     * Производство колонии за ход со зданиями и расой — п. 7 и п. 10: добытое за вычетом
     * уборки и того, что проели жители.
     */
    public Integer production(PlanetEntity planet, BuildingEffects effects, RaceEffects race) {
        return net(planet, grossProduction(planet, effects), effects, race);
    }

    /**
     * Добыча колонии до уборки и прокорма — п. 10: то, что жители со зданиями подняли из
     * недр. Это же число пачкает планету, поэтому загрязнение считается от него.
     */
    public Integer grossProduction(PlanetEntity planet, BuildingEffects effects) {
        return populationCalculator.production(planet.getMinerals(), jobs(planet).workers(),
                planet.getPopulation(), effects);
    }

    /** Та же добыча, но с процентом управляющего системы — п. 6. */
    public Integer grossProduction(PlanetEntity planet, ColonyContext context) {
        return context.withLeader(grossProduction(planet, context.effects(planet)), planet, "LABOR");
    }

    /**
     * Что останется колонии от добытого — п. 7 и п. 10.
     * <p>
     * Сперва уборка за собой (загрязнение), потом прокорм: киборги питаются рудой наравне
     * с едой, и эта доля уходит до того, как колония вложит что-то в стройку. Ниже нуля
     * производство не опускается — жители просто ничего не построят.
     */
    private Integer net(PlanetEntity planet, Integer gross, BuildingEffects effects, RaceEffects race) {
        return Math.max(0, gross
                - pollution(planet, gross, effects)
                - populationCalculator.productionConsumption(planet.getPopulation(), race));
    }

    /**
     * Сколько единиц производства колония терпит без грязи — п. 10: терпимость размера
     * планеты, поднятая зданиями (наноразборщики удваивают её).
     */
    public Integer pollutionTolerance(PlanetEntity planet, BuildingEffects effects) {
        Integer base = planet.getPlanetSize().getPollutionTolerance();
        return base + base * effects.pollutionTolerancePercent() / 100;
    }

    /**
     * Сколько производства уходит на уборку за ход — п. 10.
     * <p>
     * Свалка в ядре планеты и неприхотливая раса (п. 7) снимают загрязнение целиком —
     * оба приходят одним и тем же действием, колония их не различает.
     * <p>
     * Чистая часть добычи — твёрдое производство зданий и переработка рециклотрона —
     * из грязного вычитается: чадят работники, а не механизмы.
     */
    public Integer pollution(PlanetEntity planet, Integer gross, BuildingEffects effects) {
        if (Boolean.TRUE.equals(effects.pollutionFree())) {
            return 0;
        }
        return populationCalculator.pollution(gross,
                populationCalculator.cleanProduction(planet.getPopulation(), effects),
                pollutionTolerance(planet, effects), effects.pollutionDivisor());
    }

    /** Сколько кредитов за ход приравнивается к единице производства в окупаемости — цена выкупа после половины стройки (п. 10). */
    public static final int CREDITS_PER_PRODUCTION = PopulationCalculator.BUY_RATE_HALF_BUILT;

    /**
     * Что здание СНИМЕТ или ПРИБАВИТ этой колонии сверх прямой выработки, в единицах производства за ход —
     * для окупаемости ({@code AiEmpireService.paybackTurns}, п. 10; 14.10.2026). Считается теми же функциями, что
     * колония считает себя, а не второй формулой:
     * <ul>
     *   <li>уборка грязи: уборка сейчас минус уборка с действиями здания (переработчик, преобразователь,
     *       наноразборщики);</li>
     *   <li>тяжесть: гравитационный генератор возвращает отнятое штрафом производство;</li>
     *   <li>деньги: прибавка налога от процента дохода (космопорт, биржа) за вычетом содержания, переведённая в
     *       производство ценой выкупа ({@link #CREDITS_PER_PRODUCTION}).</li>
     *   <li>мораль: процент от НАСТОЯЩЕЙ выработки и науки колонии вместо оценки «три с жителя», которую
     *       знает статическая формула, — поправка заменяет оценку, а не добавляется к ней; строю без морали
     *       (объединение, {@link MoraleRules#useless}) досуг не даёт ничего;</li>
     *   <li>вместимость (биосферы): только у колонии, упёршейся в потолок, — столько, сколько даёт её средний
     *       житель, на каждое новое место.</li>
     * </ul>
     * Поправка бывает отрицательной (мораль ниже оценки или бесполезна) — тогда она отнимает у оценки.
     * Без этого окупаемость видела только прибавку к выработке, и ИИ не строил ни одного такого здания: замер
     * {@code tools/ai_unused.py} — переработчик, преобразователь, генератор и биржу изучали шесть империй из семи.
     */
    public Integer reliefGain(PlanetEntity planet, ColonyContext context, Building building) {
        if (building == null || planet.getPopulation() <= 0) {
            return 0;
        }
        Map<BuildingEffectType, Integer> own = building.effects();
        BuildingEffects effects = context.effects(planet);
        int gain = 0;
        if (own.containsKey(BuildingEffectType.POLLUTION_HALVINGS)
                || own.containsKey(BuildingEffectType.POLLUTION_TOLERANCE_PERCENT)) {
            Integer gross = grossProduction(planet, context);
            BuildingEffects with = effects.plus(new BuildingEffects(own, 0));
            gain += Math.max(0, pollution(planet, gross, effects) - pollution(planet, gross, with));
        }
        if (own.containsKey(BuildingEffectType.GRAVITY_NORMAL)) {
            int penalty = -context.gravityPenaltyPercent(planet);
            if (penalty > 0 && penalty < 100) {
                gain += production(planet, context) * penalty / (100 - penalty);
            }
        }
        Integer incomePercent = own.get(BuildingEffectType.INCOME_PERCENT);
        if (incomePercent != null && incomePercent > 0) {
            int tax = populationCalculator.taxIncome(planet.getPopulation(), effects.incomePercent() + incomePercent)
                    - populationCalculator.taxIncome(planet.getPopulation(), effects.incomePercent());
            int upkeep = building.upkeep() == null ? 0 : building.upkeep();
            gain += Math.max(0, tax - upkeep) / CREDITS_PER_PRODUCTION;
        }
        Integer morale = own.get(BuildingEffectType.MORALE_PERCENT);
        Integer room = own.get(BuildingEffectType.MAX_POPULATION);
        if ((morale == null || morale <= 0) && (room == null || room <= 0)) {
            return gain;
        }
        // Выработка колонии считается только здесь: зовут правило на каждое здание каждой колонии ИИ.
        int workers = jobs(planet).workers();
        int scientists = jobs(planet).scientists();
        int output = production(planet, context) + populationCalculator.research(scientists, effects);
        if (morale != null && morale > 0) {
            int estimate = morale * AiEmpireService.MORALE_BASE_OUTPUT * (workers + scientists) / 100;
            int real = Boolean.TRUE.equals(MoraleRules.useless(building, context.race(planet)))
                    ? 0 : output * morale / 100;
            gain += real - estimate;
        }
        if (room != null && room > 0
                && planet.getPopulation() >= maxPopulation(planet, effects, context.race(planet))) {
            gain += room * output / Math.max(1, planet.getPopulation());
        }
        return gain;
    }

    /** Уборка колонии за ход с учётом управляющего системы — для экрана колонии. */
    public Integer pollution(PlanetEntity planet, ColonyContext context) {
        return pollution(planet, grossProduction(planet, context), context.effects(planet));
    }

    /** Прибавка к рождаемости от медицинских технологий — п. 9. */
    public Integer medicineBonusPercent(PlanetEntity planet, ColonyContext context) {
        return populationCalculator.medicineBonusPercent(context.technologies(planet));
    }

    /** Прибавка к рождаемости от домов; колония, которая их не строит, не получает ничего. */
    public Integer housingBonusPercent(PlanetEntity planet, Integer production) {
        if (!ColonyProject.HOUSING.equals(planet.getProjectCode())) {
            return 0;
        }
        return populationCalculator.housingBonusPercent(production, planet.getPopulation());
    }

    /** Прирост населения колонии за ход в тысячах жителей. */
    public Integer growthK(PlanetEntity planet, ColonyContext context) {
        BuildingEffects effects = context.effects(planet);
        RaceEffects race = context.race(planet);
        // Расовая прибавка к рождаемости (п. 7) складывается с медициной и домами —
        // в MOO II проценты рождаемости складываются между собой.
        // Врач («Medicine») ускоряет рост во всей своей системе — п. 6; проценты
        // рождаемости в MOO II складываются между собой.
        Integer bonus = medicineBonusPercent(planet, context)
                + context.leaderPercent(planet, "MEDICINE")
                + housingBonusPercent(planet, production(planet, effects, race))
                + effects.growthPercent();
        return populationCalculator.growthK(
                planet.getPopulation(),
                maxPopulation(planet, effects, race),
                bonus,
                effects.growthKBonus(),
                foodLack(planet, context));
    }

    /**
     * Доход колонии в кредитах за ход — п. 10.
     * <p>
     * Как в MOO II: налог с жителей, поднятый космопортом и биржей, плюс выручка от
     * продажи излишков еды и товаров, минус содержание зданий.
     */
    public Integer income(PlanetEntity planet, ColonyContext context) {
        return context.withLeader(baseIncome(planet, context), planet, "FINANCIAL");
    }

    /**
     * Доход колонии без прибавки финансиста — п. 10. Отдельно, потому что процент лидера
     * в MOO II касается налога, а не всей суммы вместе с продажей еды; здесь это
     * упрощено до одного множителя на доход колонии, и упрощение честнее назвать, чем
     * прятать: раздельного налога у колонии в игре пока нет.
     */
    private Integer baseIncome(PlanetEntity planet, ColonyContext context) {
        BuildingEffects effects = context.effects(planet);
        RaceEffects race = context.race(planet);
        Integer food = populationCalculator.food(planet.getClimate(), jobs(planet).farmers(), effects, race);
        Integer consumption = populationCalculator.foodConsumption(planet.getPopulation(), race);

        Integer tradeGoods = ColonyProject.TRADE_GOODS.equals(planet.getProjectCode())
                ? populationCalculator.creditsFor(production(planet, effects, race))
                : 0;

        // Прирождённые торговцы (п. 7) продают вдвое дороже — и товары, и лишнюю еду.
        // Налог с жителей под эту надбавку не попадает: в MOO II она про торговлю,
        // а не про сбор податей, для которого есть своя сторона расы.
        Integer trade = withPercent(populationCalculator.creditsFor(food - consumption) + tradeGoods,
                race.tradeIncomePercent());

        // Кредиты находки идут ПОМИМО налога и торговой надбавки: в оригинале это
        // «special income» ровно в пять (золото) или десять (самоцветы) кредитов, и ни
        // от числа жителей, ни от космопорта он не зависит — п. 4.1.
        return populationCalculator.taxIncome(planet.getPopulation(), effects.incomePercent())
                + trade
                + effects.incomeFlat()
                - effects.upkeep();
    }

    /** Надбавка в процентах к уже посчитанной сумме; ноль процентов ничего не меняет. */
    private Integer withPercent(Integer amount, Integer bonusPercent) {
        return bonusPercent == 0 ? amount : (int) ((long) amount * (100 + bonusPercent) / 100);
    }

    /** Колония планеты; {@code null} — планета не заселена, и колонии на ней нет. */
    public ColonyDto colony(PlanetEntity planet, ColonyContext context) {
        if (planet.getPopulation() <= 0) {
            return null;
        }

        List<Building> buildings = context.buildings(planet);
        BuildingEffects effects = context.effects(planet);
        RaceEffects race = context.race(planet);
        MoraleRules.Morale morale = context.morale(planet);
        PopulationJobs jobs = jobs(planet);

        // Земледелец и учёный поднимают выработку своих жителей во всей системе — п. 6.
        // Пищевые репликаторы (п. 4.1.1) делают недостающую еду из производства — она уже в числе еды.
        Integer food = context.withLeader(
                populationCalculator.food(planet.getClimate(), jobs.farmers(), effects, race),
                planet, "FARMING") + replicated(planet, context);
        Integer production = production(planet, context);
        Integer consumption = populationCalculator.foodConsumption(planet.getPopulation(), race);
        Building project = context.catalog().get(planet.getProjectCode());

        return new ColonyDto(
                planet.getPopulationK(),
                maxPopulation(planet, effects, race),
                growthK(planet, context),
                medicineBonusPercent(planet, context),
                housingBonusPercent(planet, production),
                effects.growthKBonus(),

                jobs.farmers(),
                jobs.workers(),
                jobs.scientists(),

                context.withLeader(
                        populationCalculator.foodPerFarmer(planet.getClimate(), race)
                                + effects.foodPerFarmer(), planet, "FARMING"),
                effects.foodFlat(),
                context.withLeader(
                        planet.getMinerals().getProductionPerWorker()
                                + effects.productionPerWorker(), planet, "LABOR"),
                effects.productionFlat(),
                effects.productionPerColonist(),
                effects.roboticFactories() * populationCalculator.roboticFactory(planet.getMinerals()),
                context.withLeader(
                        populationCalculator.researchPerScientist()
                                + effects.researchPerScientist(), planet, "SCIENCE"),
                effects.researchFlat(),

                food,
                production,
                context.withLeader(
                        populationCalculator.research(jobs.scientists(), effects),
                        planet, "SCIENCE"),
                consumption,
                food - consumption,
                context.delivered(planet),
                income(planet, context),
                effects.upkeep(),

                pollution(planet, context),
                pollutionTolerance(planet, effects),
                effects.pollutionDivisor(),
                effects.pollutionFree(),

                projectCode(planet),
                projectName(planet, project, context),
                planet.getProjectPoints(),
                projectCost(planet, project, context),
                buildings.stream().map(this::toProject).toList(),
                available(planet, context),
                queue(planet, context),
                planet.getRepeatBuild(),
                planet.getAutoBuild(),
                planet.getAutoJobs(),
                buyCost(planet, projectCost(planet, project, context)),
                planet.getSoldTurn(),
                planet.getAlienPopulation(),
                planet.getAlienPopulation() > 0
                        ? assimilationRules.turnsPerColonist(race, effects.alienManagement())
                                - planet.getAssimilationPoints()
                        : null,
                morale.percent(),
                morale.parts().stream()
                        .map(part -> new ColonyDto.MoralePartDto(part.source().name(), part.percent()))
                        .toList(),
                morale.ignored(),
                effects.foodPercent(),
                effects.productionPercent(),
                effects.researchPercent(),
                outputParts(planet, context, race),
                effects.amount(BuildingEffectType.FOOD_REPLICATOR) > 0);
    }

    /** Один источник эффектов колонии: род, имя (у постройки) и его эффекты — пункт 40. */
    public record EffectSource(String source, String name, BuildingEffects effects) {
    }

    /**
     * Слагаемые выработки колонии — field-backlog, пункт 40. База (климат и раса у еды, недра у
     * производства, правило учёного у науки), каждый источник эффектов со своими «на жителя»,
     * «твёрдо» и «процентом», лидер системы и уборка загрязнения. Числа берутся теми же
     * функциями, что считают итог, — клиент их только показывает.
     */
    private List<ColonyDto.OutputPartDto> outputParts(PlanetEntity planet, ColonyContext context, RaceEffects race) {
        List<ColonyDto.OutputPartDto> parts = new ArrayList<>();
        parts.add(new ColonyDto.OutputPartDto("FOOD", "BASE", null,
                populationCalculator.foodPerFarmer(planet.getClimate(), race), 0, 0, 0));
        parts.add(new ColonyDto.OutputPartDto("PRODUCTION", "BASE", null,
                planet.getMinerals().getProductionPerWorker(), 0, 0, 0));
        parts.add(new ColonyDto.OutputPartDto("RESEARCH", "BASE", null,
                populationCalculator.researchPerScientist(), 0, 0, 0));
        for (EffectSource source : context.effectSources(planet)) {
            BuildingEffects e = source.effects();
            addPart(parts, "FOOD", source, e.foodPerFarmer(), e.foodFlat(), e.foodPercent(), 0);
            // Роботизированный завод — твёрдая прибавка этого источника, посчитанная по недрам (п. 10).
            addPart(parts, "PRODUCTION", source, e.productionPerWorker(),
                    e.productionFlat() + e.roboticFactories() * populationCalculator.roboticFactory(planet.getMinerals()),
                    e.productionPercent(), e.productionPerColonist());
            addPart(parts, "RESEARCH", source, e.researchPerScientist(), e.researchFlat(), e.researchPercent(), 0);
        }
        addLeader(parts, "FOOD", context.leaderPercent(planet, "FARMING"));
        addLeader(parts, "PRODUCTION", context.leaderPercent(planet, "LABOR"));
        addLeader(parts, "RESEARCH", context.leaderPercent(planet, "SCIENCE"));
        Integer pollution = pollution(planet, context);
        if (pollution != null && pollution > 0) {
            parts.add(new ColonyDto.OutputPartDto("PRODUCTION", "POLLUTION", null, 0, -pollution, 0, 0));
        }
        return parts;
    }

    private static void addPart(List<ColonyDto.OutputPartDto> parts, String resource, EffectSource source,
                                Integer perUnit, Integer flat, Integer percent, Integer perColonist) {
        if (perUnit == 0 && flat == 0 && percent == 0 && perColonist == 0) {
            return;
        }
        parts.add(new ColonyDto.OutputPartDto(resource, source.source(), source.name(), perUnit, flat, percent,
                perColonist));
    }

    private static void addLeader(List<ColonyDto.OutputPartDto> parts, String resource, Integer percent) {
        if (percent != null && percent != 0) {
            parts.add(new ColonyDto.OutputPartDto(resource, "LEADER", null, 0, 0, percent, 0));
        }
    }

    /**
     * Что колония может строить прямо сейчас — п. 10.
     * <p>
     * Дома и товары доступны всегда, здание — когда его технология изучена, а само оно
     * ещё не построено на этой планете. Колониальная база — пока в системе есть свободная
     * планета, пригодная для колонизации, и предыдущая база уже пристроена: две базы
     * подряд колонии не нужны, селиться второй будет некуда.
     */
    public List<ColonyProjectDto> available(PlanetEntity planet, ColonyContext context) {
        // Занятое очередью из списка убирается: здание строится однажды, и предлагать его
        // второй раз значит обещать то, чего не будет, — п. 10.
        // Стоящее на стапеле — тоже (06.10.2026): здание, поставленное первым в очередь, и есть
        // текущая стройка, и в списке оно оставалось, хотя то же здание вторым местом уходило.
        // Дома и товары остаются: ими колония только занята, и вернуться к ним — выбор из списка.
        // Дома и товары, уже стоящие в очереди, второй раз не предлагаются (07.10.2026): дойдя
        // до первых, колония на них и остаётся, и вторые не начнутся никогда — «Дома ×2» в
        // очереди лишены смысла.
        Set<String> queued = new HashSet<>(planet.getBuildQueue().stream()
                .filter(code -> !Boolean.TRUE.equals(ColonyProject.repeatable(code))
                        || Boolean.TRUE.equals(ColonyProject.endless(code)))
                .toList());
        String current = planet.getProjectCode();
        if (current != null && !Boolean.TRUE.equals(ColonyProject.repeatable(current))
                && !Boolean.TRUE.equals(ColonyProject.endless(current))) {
            queued.add(current);
        }
        // Колониальных баз колония заказывает столько, сколько в системе свободных планет:
        // база повторяемая, но каждая занимает планету, и заказанная сверх числа планет
        // осталась бы без места — п. 4.1. Считаются все заказанные: готовая, на стапеле и
        // в очереди.
        boolean baseFull = !Boolean.TRUE.equals(baseHasWhereToGo(planet));
        return buildable(planet, context).stream()
                .filter(project -> !queued.contains(project.code()))
                .filter(project -> !baseFull || !ColonyProject.COLONY_BASE.equals(project.code()))
                .toList();
    }

    /**
     * Очередь стройки колонии проектами — п. 10.
     * <p>
     * Хранится она кодами, а экрану нужны названия и цены, поэтому коды разбираются по
     * тому же списку, из которого их выбирали. Проект, пропавший из списка (здание
     * успели построить, технологию отняла кража), из очереди тоже пропадает: строить его
     * всё равно нечем, а показывать мёртвую строку незачем.
     */
    public List<ColonyProjectDto> queue(PlanetEntity planet, ColonyContext context) {
        if (planet.getBuildQueue().isEmpty()) {
            return List.of();
        }
        Map<String, ColonyProjectDto> byCode = buildable(planet, context).stream()
                .collect(Collectors.toMap(ColonyProjectDto::code, project -> project,
                        (first, second) -> first));
        return planet.getBuildQueue().stream()
                .map(byCode::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Всё, что колония может заложить, — до оглядки на очередь. */
    private List<ColonyProjectDto> buildable(PlanetEntity planet, ColonyContext context) {
        Set<String> technologies = context.technologies(planet);
        Set<String> built = context.buildings(planet).stream()
                .map(Building::code)
                .collect(Collectors.toSet());

        List<ColonyProjectDto> projects = new ArrayList<>();
        projects.add(special(ColonyProject.HOUSING));
        projects.add(special(ColonyProject.TRADE_GOODS));
        // Шпион доступен любой колонии с первого хода: разведка технологий не ждёт — п. 13.
        projects.add(special(ColonyProject.SPY));
        /*
          Корабли — по проекту на строку, как в списке стройки MOO II: империя строит не
          «корабль вообще», а тот проект, который сама собрала в окне дизайна — п. 8.

          Два условия. Первое: у империи должны быть двигатель и топливо — базовые уровни
          Power и Chemistry; до них строить не из чего. Второе: колония поднимает корпус не
          больше того, что позволяет её верфь, — звёздная база на орбите. Без базы это
          фрегат и эсминец, с базой — что угодно.
        */
        if (Boolean.TRUE.equals(shipDesignRules.shipbuildingAvailable(technologies))) {
            Integer maxHullSize = shipDesignRules.maxHullSize(built.contains(ShipDesignRules.STAR_BASE));
            for (ShipDesignService.ShipBuildOption design : context.shipDesigns(planet)) {
                if (design.hullSize() <= maxHullSize) {
                    projects.add(shipProject(design));
                }
            }
        }
        // Грузовой флот — только после своей технологии, как в MOO II: до неё возить еду
        // между колониями нечем — п. 4.1.1.
        if (technologies.contains(ColonyProject.FREIGHTER_TECH)) {
            projects.add(special(ColonyProject.FREIGHTER));
        }
        // База предлагается, пока в системе есть куда селиться. Готовая, но ещё не
        // пристроенная база списку не мешает: колония вправе заложить следующую, а
        // достроит её только после того, как игрок распорядится нынешней, — иначе
        // заселение системы из нескольких планет шло бы по одной базе за раз и с
        // обязательным заходом в список стройки между ними.
        if (hasFreePlanet(planet)) {
            projects.add(special(ColonyProject.COLONY_BASE));
        }
        // Переделка планеты — п. 4.1.2.1 (13.10.2026, перенос MOO II).
        if (technologies.contains(TerraformRules.TERRAFORMING_TECH)
                && TerraformRules.terraformed(planet.getClimate(), false).isPresent()) {
            projects.add(special(ColonyProject.TERRAFORM, TerraformRules.terraformCost(planet.getTerraformCount())));
        }
        if (technologies.contains(TerraformRules.GAIA_TECH)
                && planet.getClimate() == com.sddnw.server.domain.enums.PlanetClimate.TERRAN) {
            projects.add(special(ColonyProject.GAIA));
        }
        if (technologies.contains(TerraformRules.ARTIFICIAL_PLANET_TECH) && artificialTarget(planet).isPresent()) {
            projects.add(special(ColonyProject.ARTIFICIAL_PLANET));
        }
        // Андроиды (п. 4.1, перенос MOO II) — пока в колонии есть место ещё на одного: сверх потолка
        // по размеру планеты, а дальше каждый занимает место жителя.
        if (androidRoom(planet, context)) {
            for (Map.Entry<String, String> android : List.of(
                    Map.entry(ColonyProject.ANDROID_FARMER, ANDROID_TECHS.get(ColonyProject.ANDROID_FARMER)),
                    Map.entry(ColonyProject.ANDROID_WORKER, ANDROID_TECHS.get(ColonyProject.ANDROID_WORKER)),
                    Map.entry(ColonyProject.ANDROID_SCIENTIST, ANDROID_TECHS.get(ColonyProject.ANDROID_SCIENTIST)))) {
                if (technologies.contains(android.getValue())) {
                    projects.add(special(android.getKey()));
                }
            }
        }
        /*
          Гражданские корабли — п. 4.1 и п. 12. В MOO II они стоят в списке стройки готовыми,
          со своей твёрдой ценой, и собирать их в окне дизайна нельзя. Верфи они не требуют:
          корпус у них наименьший.

          Условий два, и второе появилось не сразу: мало уметь строить корабли вообще
          (базовые Power и Chemistry) — нужна ещё сама технология корабля. Каждый из трёх
          стоит в дереве отдельной строкой (Power, уровень 2 «Cold Fusion»), и без неё
          колониальный корабль можно было заложить до того, как он изучен: империя
          расселялась технологией, которой у неё нет. Уровень общий, поэтому на деле все
          три открываются разом, но спрашивается у каждого своя.
        */
        if (Boolean.TRUE.equals(shipDesignRules.shipbuildingAvailable(technologies))) {
            if (technologies.contains(ColonyProject.COLONY_SHIP_TECH)) {
                projects.add(special(ColonyProject.COLONY_SHIP));
            }
            if (technologies.contains(ColonyProject.OUTPOST_SHIP_TECH)) {
                projects.add(special(ColonyProject.OUTPOST_SHIP));
            }
            if (technologies.contains(ColonyProject.TRANSPORT_TECH)) {
                projects.add(special(ColonyProject.TRANSPORT));
            }
        }
        for (Building building : context.catalog().values()) {
            if (!built.contains(building.code()) && researched(building, technologies)
                    && (!CapitolRules.CODE.equals(building.code()) || capitolBuildable(planet, context))) {
                projects.add(toProject(building, jobs(planet)));
            }
        }
        return projects;
    }

    /**
     * Можно ли заложить столицу на этой колонии — п. 10.1: строю она положена, у империи её
     * нет, и другая колония её не строит. В MOO II столицу строят только взамен утраченной.
     */
    private Boolean capitolBuildable(PlanetEntity planet, ColonyContext context) {
        return Boolean.TRUE.equals(context.capitolLost(planet.getOwnerPlayerId()))
                && !Boolean.TRUE.equals(context.capitolOrderedElsewhere(planet));
    }

    /**
     * Захваченная или подчинённая колония теряет столицу — п. 10.1: «if the empire's capitol
     * is captured or destroyed». Захватчику она не достаётся — престол чужой империи ему не
     * престол, а у него может быть и свой. Зовут это все пути, которыми колония меняет
     * хозяина силой: десант и подчинение телепатами.
     *
     * @param context контекст хода, если захват идёт внутри посчитанного хода; иначе пусто
     */
    public void loseCapitol(PlanetEntity planet, ColonyContext context) {
        planetBuildingRepository.findAllByPlanetId(planet.getId()).stream()
                .filter(row -> CapitolRules.CODE.equals(row.getBuildingCode()))
                .forEach(row -> {
                    planetBuildingRepository.delete(row);
                    log.info("Колония {} потеряла столицу", planet.getName());
                });
        if (context != null) {
            context.sold(planet, CapitolRules.CODE);
        }
    }

    /**
     * Перераспределение жителей колонии игроком — п. 4.1.
     * <p>
     * Сумма занятий обязана сойтись с населением: незанятых жителей в колонии нет, и
     * «потерять» человека при перераспределении нельзя.
     */
    @Transactional
    public PlanetEntity setPopulation(PlayerEntity player, UUID planetId, SetPopulationRequest request) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);

        PopulationJobs jobs = new PopulationJobs(request.farmers(), request.workers(), request.scientists());
        if (!jobs.total().equals(planet.getPopulation())) {
            throw new ConflictException("colony.jobsMismatch", planet.getName(), planet.getPopulation(), jobs.total());
        }

        planet.setJobs(jobs);
        log.info("Игрок {} перераспределил {}: {} фермеров, {} рабочих, {} учёных",
                player.getName(), planet.getName(), jobs.farmers(), jobs.workers(), jobs.scientists());
        return planet;
    }

    /**
     * Во что обойдётся выкуп текущей стройки — п. 10; {@code null}, когда выкупать нечего.
     * <p>
     * Дома и товары не кончаются никогда, и выкупать в них нечего; у оплаченной стройки
     * недостающего не осталось. Само правило цены — в {@code PopulationCalculator.buyCost}.
     */
    private Integer buyCost(PlanetEntity planet, Integer cost) {
        if (cost == null || planet.getProjectPoints() >= cost) {
            return null;
        }
        return populationCalculator.buyCost(cost, planet.getProjectPoints());
    }

    /**
     * Выкуп стройки за кредиты — п. 10.
     * <p>
     * В MOO II недостающее производство докупают, и вещь достраивается тем же ходом:
     * «construction is completed when you click the Turn button». Здесь так же — казна
     * платит за недостающие единицы, они ложатся в стройку, а достраивает её та же фаза
     * производства, что и всегда: своего пути у купленного нет.
     * <p>
     * Дома и товары не выкупаются: они не кончаются вовсе, и «достроить» их нельзя.
     */
    @Transactional
    public PlanetEntity buyProject(PlayerEntity player, UUID planetId) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        ColonyContext context = context(planet);
        Building project = context.catalog().get(planet.getProjectCode());
        Integer cost = projectCost(planet, project, context);
        if (cost == null) {
            throw new ConflictException("colony.nothingToBuy", planet.getName());
        }

        Integer price = buyCost(planet, cost);
        if (price == null) {
            throw new ConflictException("colony.alreadyPaid", planet.getName());
        }
        if (player.getCredits() < price) {
            throw new ConflictException("colony.buyNoCredits", price, player.getCredits());
        }

        player.setCredits(player.getCredits() - price);
        playerRepository.save(player);
        planet.setProjectPoints(cost);
        planetRepository.save(planet);

        log.info("Игрок {} выкупил стройку колонии {} за {} кр.",
                player.getName(), planet.getName(), price);
        return planet;
    }

    /**
     * Во что обойдётся выкуп стройки этой колонии прямо сейчас — п. 10.
     * <p>
     * Отличается от {@link #buyProject} только тем, откуда берётся контекст: здесь его даёт
     * ход ({@code TurnContext}), а не своя выборка. Нужно это империям ИИ — они решают,
     * что выкупить, внутри посчитанного хода, и ходить за контекстом колонии на каждую
     * проверку значило бы бить по всему ходу (см. «Грабли» в правилах проекта).
     * <p>
     * {@code null} — выкупать нечего: стройка пуста, бесконечна или уже оплачена.
     */
    public Integer buyPrice(PlanetEntity planet, ColonyContext context) {
        Building project = context.catalog().get(planet.getProjectCode());
        return buyCost(planet, projectCost(planet, project, context));
    }

    /**
     * Выкуп стройки из фазы хода — п. 10: та же покупка, что у игрока, но по контексту хода.
     * <p>
     * Цену считает {@link #buyPrice}, и второго свода правил выкупа в проекте нет: казна
     * платит за недостающие единицы, они ложатся в стройку, а достраивает её та же фаза
     * производства, что и обычно.
     *
     * @return {@code true} — выкуп состоялся
     */
    public Boolean buyFromTurn(PlayerEntity player, PlanetEntity planet, ColonyContext context) {
        Integer price = buyPrice(planet, context);
        if (price == null || player.getCredits() < price) {
            return Boolean.FALSE;
        }
        Building project = context.catalog().get(planet.getProjectCode());
        Integer cost = projectCost(planet, project, context);
        player.setCredits(player.getCredits() - price);
        planet.setProjectPoints(cost);
        log.info("Империя {} выкупила стройку колонии {} за {} кр.",
                player.getName(), planet.getName(), price);
        return Boolean.TRUE;
    }

    /**
     * Продажа постройки — п. 10.
     * <p>
     * В MOO II построенное можно продать: здание исчезает с планеты, казна получает
     * половину его цены в кредитах, а колония перестаёт платить за него содержание.
     * Продаётся <b>одно здание за ход и на каждой колонии своё</b> — предел оригинала;
     * общего счётчика у империи нет.
     * <p>
     * <b>Продажа рушит и планы.</b> Здание могло быть условием стройки: звёздная база
     * поднимает корпуса крупнее эсминца, и без неё заложенный дредноут строить нечем.
     * Поэтому после продажи очередь и текущая стройка сверяются со списком доступного, и
     * то, что стало невозможным, из них уходит — как в оригинале, где «продажа здания
     * убирает из очереди зависевшие от него изделия» (руководство к патчу 1.50).
     * Накопленное производство при этом остаётся колонии: оно лежит на ней, а не на
     * проекте.
     *
     * @param turn ход партии: им отмечается, что колония своё за этот ход уже продала
     */
    @Transactional
    public PlanetEntity sellBuilding(PlayerEntity player, UUID planetId,
                                     String buildingCode, Integer turn) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        // Столицу не продают — п. 10.1: в оригинале её теряют только с колонией, а проданная
        // за сотню кредитов, она обрушила бы мораль всей империи одним нажатием.
        if (CapitolRules.CODE.equals(buildingCode)) {
            throw new ConflictException("colony.capitolNotForSale", planet.getName());
        }
        if (turn.equals(planet.getSoldTurn())) {
            throw new ConflictException("colony.soldThisTurn", planet.getName());
        }

        PlanetBuildingEntity built = planetBuildingRepository.findAllByPlanetId(planetId).stream()
                .filter(row -> row.getBuildingCode().equals(buildingCode))
                .findFirst()
                .orElseThrow(() -> new ConflictException("colony.noSuchBuilding", planet.getName(),
                        projectLabel(buildingCode)));
        Building building = buildingCatalog.byCode().get(buildingCode);
        if (building == null) {
            throw new ConflictException("colony.unknownBuilding", buildingCode);
        }

        Integer paid = populationCalculator.sellValue(building.cost());
        planetBuildingRepository.delete(built);
        planet.setSoldTurn(turn);
        player.setCredits(player.getCredits() + paid);
        playerRepository.save(player);

        /*
          РАЗОБРАННОЕ ЗДАНИЕ ВОЗВРАЩАЕТ ПОЛОВИНУ ЦЕНЫ В СТРОЙКУ — отступление от MOO II,
          решение хозяина проекта (29.09.2026).

          В оригинале продажа это только деньги, и снесённая постройка уносит с собой всё
          вложенное производство. На практике это делало продажу поступком отчаяния: игрок
          не сносил неудачное здание, потому что терял вдвойне — и здание, и труд.

          Возвращается РОВНО СТОЛЬКО ЖЕ, сколько уходит в казну (`sellValue`, половина
          цены), и это не совпадение, а сама мера: здание, стоившее 60, отдаёт 30 кредитов
          и 30 единиц, то есть половина труда всё равно теряется. Отдавать полную цену
          нельзя — построить дешёвое здание и тут же продать стало бы выгодно, а печатный
          станок в игре ни к чему.

          Единицы кладутся на КОЛОНИЮ, а не в конкретный проект: накопленное здесь и так
          принадлежит колонии и переходит в следующую стройку (см. `setProject`). Поэтому
          возврат работает и тогда, когда колония сейчас не строит ничего.
         */
        planet.setProjectPoints(planet.getProjectPoints() + paid);

        dropImpossibleProjects(planet);

        log.info("Игрок {} продал {} на колонии {} за {} кр. и {} ед. в стройку",
                player.getName(), building.name(), planet.getName(), paid, paid);
        return planet;
    }

    /** Сколько единиц лежит в стройке колонии — для журнала отмены выкупа (пункт 74). */
    @Transactional(readOnly = true)
    public Integer projectPointsOf(UUID planetId) {
        return planetRepository.findById(planetId).map(PlanetEntity::getProjectPoints).orElse(0);
    }

    /** Ход постройки здания колонии — для журнала отмены продажи (пункт 74); пусто — нет такого. */
    @Transactional(readOnly = true)
    public Integer builtTurnOf(UUID planetId, String buildingCode) {
        return planetBuildingRepository.findAllByPlanetId(planetId).stream()
                .filter(row -> row.getBuildingCode().equals(buildingCode))
                .map(PlanetBuildingEntity::getBuiltTurn)
                .findFirst().orElse(0);
    }

    /**
     * Убирает из стройки и очереди то, что после продажи строить нечем, — п. 10.
     * <p>
     * Список доступного и есть последнее слово о том, что колония может построить, — по
     * нему и сверяемся. Особые проекты (дома, товары, шпион) в этом списке стоят всегда и
     * потому не пострадают; уходит то, чьё условие продано, — прежде всего крупные
     * корабли, которым нужна звёздная база.
     */
    private void dropImpossibleProjects(PlanetEntity planet) {
        // Сверяемся с полным списком строимого, а не с `available`: тот убирает уже
        // поставленное в очередь, и очередь вычистила бы сама себя до пустоты.
        Set<String> possible = buildable(planet, context(planet)).stream()
                .map(ColonyProjectDto::code)
                .collect(Collectors.toSet());

        String current = planet.getProjectCode() == null ? null
                : ColonyProject.SHIP.equals(planet.getProjectCode())
                ? ColonyProject.ship(planet.getProjectDesignId())
                : planet.getProjectCode();
        if (current != null && !possible.contains(current)) {
            // Пустой стройки у колонии не бывает: то, что строить стало нечем, сменяется
            // товарами — производство пойдёт в казну, а не пропадёт (п. 10).
            planet.setProjectCode(ColonyProject.TRADE_GOODS);
            planet.setProjectDesignId(null);
        }

        // Очередь правится копией, а не на месте: у колонки свой преобразователь, и
        // правку того же списка Hibernate не заметил бы (см. `enqueue`).
        List<String> queue = planet.getBuildQueue().stream()
                .filter(possible::contains)
                .collect(Collectors.toCollection(ArrayList::new));
        if (queue.size() != planet.getBuildQueue().size()) {
            planet.setBuildQueue(queue);
        }
    }

    /**
     * Смена проекта колонии — п. 10.
     * <p>
     * Вложенные единицы производства остаются на колонии: в MOO II накопленное не
     * пропадает при смене стройки, а идёт в следующую.
     * <p>
     * Сверяется проект со <b>списком строимого</b> ({@code buildable}), а не с
     * {@code available}: последний убирает уже поставленное в очередь, и проект из
     * очереди нельзя было бы поднять на стапель — а именно этого от списка и ждут, когда
     * передумали. Поднятый из очереди проект из неё и уходит: строить его дважды никто
     * не просил.
     */
    @Transactional
    public PlanetEntity setProject(PlayerEntity player, UUID planetId, SetProjectRequest request) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);

        ColonyProjectDto project = buildable(planet, context(planet)).stream()
                .filter(candidate -> candidate.code().equals(request.projectCode()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("colony.cannotBuild", planet.getName(),
                        projectLabel(request.projectCode())));

        // У корабля код несёт проект, а планета хранит их раздельно: код стройки остаётся
        // «SHIP», проект уходит в свою колонку. Так переделка проекта в окне дизайна не
        // трогает того, что уже стоит на стапеле, — п. 8.
        // База со стапеля тоже занимает планету: заказать её сверх числа свободных нельзя.
        // Поднятая из очереди не в счёт — она уже заказана и лишь меняет место.
        if (ColonyProject.COLONY_BASE.equals(project.code())
                && !planet.getBuildQueue().contains(ColonyProject.COLONY_BASE)) {
            requireRoomForBase(planet);
        }

        // Прежняя стройка — тем же кодом, каким она стоит в очереди (у корабля «SHIP:проект»).
        String displaced = planet.getProjectDesignId() != null
                ? ColonyProject.ship(planet.getProjectDesignId())
                : planet.getProjectCode();

        UUID designId = ColonyProject.shipDesign(project.code());
        planet.setProjectCode(designId == null ? project.code() : ColonyProject.SHIP);
        planet.setProjectDesignId(designId);
        removeFirstFromQueue(planet, project.code());

        /*
          «На первое место», а не «вместо» (01.10.2026): проект, перетащенный на текущую
          стройку, встаёт на стапель, а прежняя сдвигается первой строкой очереди. Без этого
          колониальная база, которую строили, молча пропадала из плана — игрок хотел
          переставить порядок, а не отменить базу. Бесконечная стройка (дома, товары) в
          очередь не возвращается: её не достраивают, ею только заняты.
         */
        if (Boolean.TRUE.equals(request.keepCurrent()) && displaced != null
                && !Boolean.TRUE.equals(ColonyProject.endless(displaced))
                && !displaced.equals(project.code())) {
            List<String> queue = new ArrayList<>(planet.getBuildQueue());
            queue.add(0, displaced);
            planet.setBuildQueue(queue);
        }

        log.info("Колония {} игрока {} строит {}", planet.getName(), player.getName(), project.name());
        return planet;
    }

    /**
     * Убирает из очереди первый такой проект — п. 10: поднятый на стапель из очереди
     * стоять в ней же больше не должен.
     * <p>
     * Правится очередь копией, а не на месте: у колонки свой преобразователь, и правку
     * того же списка Hibernate не заметил бы вовсе.
     */
    private void removeFirstFromQueue(PlanetEntity planet, String projectCode) {
        int at = planet.getBuildQueue().indexOf(projectCode);
        if (at < 0) {
            return;
        }
        List<String> queue = new ArrayList<>(planet.getBuildQueue());
        queue.remove(at);
        planet.setBuildQueue(queue);
    }

    /**
     * Заложить колонии шаблон стройки — п. 10, миграция 079.
     * <p>
     * Из шаблона берётся то, что колония МОЖЕТ построить сейчас, и в том порядке, в каком
     * это записано; остальное молча пропускается. Так и задумано: шаблон пишут ДО партии,
     * где ни изученного, ни построенного ещё нет, и требовать от него сбыточности значило
     * бы требовать от игрока знать будущее. Отказывать целиком тем более нельзя — один
     * неизученный завод отменил бы весь набор.
     * <p>
     * Кладётся ровно столько, сколько ВЛЕЗЕТ в очередь: предел тот же, что и у руки
     * ({@link ColonyProject#QUEUE_LIMIT}). Ничего не влезло — отказ словами, потому что
     * молчаливое «ничего не произошло» игрок прочтёт как поломку.
     *
     * @return колония с пополненной очередью
     */
    @Transactional
    public TemplateOutcome enqueueTemplate(PlayerEntity player, UUID planetId, List<String> codes) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        ColonyContext context = context(planet);
        Set<String> possible = available(planet, context).stream()
                .map(ColonyProjectDto::code)
                .collect(Collectors.toSet());

        List<String> laid = new ArrayList<>();
        // Пропущенное — с причиной (field-backlog, пункт 97): шаблон пишут до партии, и
        // несбыточное в нём не ошибка, но игроку надо сказать, ЧТО не легло и почему.
        List<Skip> skipped = new ArrayList<>();
        for (String code : codes) {
            if (planet.getBuildQueue().size() >= ColonyProject.QUEUE_LIMIT) {
                skipped.add(new Skip(projectLabel(code), new MessageKey("colony.template.queueFull")));
                continue;
            }
            if (!possible.contains(code)) {
                skipped.add(new Skip(projectLabel(code), new MessageKey(
                        isKnownProject(code) ? "colony.template.notNow" : "colony.template.gone")));
                continue;
            }
            try {
                // Через ту же дорогу, что и рука: у неё свои правила — пустой стапель
                // забирает проект сразу, неповторяемое дважды не кладётся, колониальная
                // база считает свободные планеты. Второго свода правил закладки не заводим.
                enqueue(player, planetId, new SetProjectRequest(player.getAccessToken(), code, null, null));
                laid.add(code);
            } catch (ConflictException refused) {
                /*
                  Отказ на ОДНОМ проекте шаблон не отменяет: шаблон пишут до партии, и
                  наткнуться на «это уже строится» или «селить больше некуда» он обязан
                  спокойно. Пропускаем и идём дальше — ровно так же, как пропускаем
                  неизученное.

                  Ловить отказ тут безопасно: `enqueue` зовётся СВОИМ же методом, мимо
                  прокси Spring, — значит, транзакция не помечается rollback-only, и
                  положенное до отказа остаётся положенным. (Та же особенность
                  самовызова, на которой этот проект однажды обжёгся в `turn/advance`.)
                 */
                log.info("Шаблон: {} колонии {} пропущен — {}", code, planet.getName(),
                        refused.getMessage());
                skipped.add(new Skip(projectLabel(code), refused));
            }
            // Повторяемое можно класть снова, а здание из списка уходит: обновляем.
            if (!Boolean.TRUE.equals(ColonyProject.repeatable(code))) {
                possible.remove(code);
            }
        }
        if (laid.isEmpty()) {
            throw new ConflictException("colony.templateEmpty", planet.getName());
        }
        log.info("Колонии {} заложен шаблон: {}, пропущено {}", planet.getName(), laid, skipped.size());
        return new TemplateOutcome(planet, skipped);
    }

    /**
     * Строка шаблона, не легшая в очередь: что (название или общее слово) и почему —
     * ключом или самим отказом; на язык читателя их переводит {@code Messages}.
     */
    public record Skip(Object name, Object reason) {
    }

    /** Итог закладки шаблона — пункт 97: колония и пропущенное. */
    public record TemplateOutcome(PlanetEntity planet, List<Skip> skipped) {
    }

    /**
     * Знает ли игра такой проект вообще: здание справочника или особый проект колонии. Иначе
     * строка шаблона ссылается на то, чего в игре больше нет, — и так и говорится.
     */
    private boolean isKnownProject(String code) {
        return buildingCatalog.byCode().containsKey(code) || Boolean.TRUE.equals(ColonyProject.repeatable(code));
    }

    /**
     * Как колония ведёт очередь — п. 10, кнопки {@code REPEAT BUILD} и {@code AUTO BUILD}.
     * <p>
     * Признаки переключаются по одному: пустое поле значит «оставить как было», а не
     * «выключить». Иначе окно, знающее об одной кнопке, гасило бы вторую.
     * <p>
     * Ничего, кроме признаков, здесь не происходит: круг очереди и выбор автостроя — дело
     * фазы производства ({@code ProductionPhase.advanceQueue}), и происходит оно в конце
     * хода, а не в миг нажатия.
     */
    @Transactional
    public PlanetEntity setBuildMode(PlayerEntity player, UUID planetId, BuildModeRequest request) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        if (request.repeatBuild() != null) {
            planet.setRepeatBuild(request.repeatBuild());
        }
        if (request.autoBuild() != null) {
            planet.setAutoBuild(request.autoBuild());
        }
        if (request.autoJobs() != null) {
            planet.setAutoJobs(request.autoJobs());
            // Включённое правило действует сразу, а не с конца хода: игрок видит, как колония
            // встала, и решает, годится ли ему это.
            if (Boolean.TRUE.equals(planet.getAutoJobs())) {
                applyAutoJobs(planet, context(planet));
            }
        }
        log.info("Колония {} игрока {}: повтор очереди {}, автострой {}",
                planet.getName(), player.getName(), planet.getRepeatBuild(), planet.getAutoBuild());
        return planet;
    }

    /**
     * Добавить проект в очередь стройки — п. 10.
     * <p>
     * Очередь MOO II: колония строит одно, а что за ним — решено заранее, и производство
     * не простаивает ход, пока игрок выбирает. Если строить нечего вовсе, проект встаёт
     * не в очередь, а прямо на стапель: очередь за пустой стройкой ждала бы вечно —
     * забирают из неё только тогда, когда предыдущее достроено.
     * <p>
     * Больше {@link ColonyProject#QUEUE_LIMIT} проектов очередь не принимает, как и в
     * оригинале.
     */
    @Transactional
    public PlanetEntity enqueue(PlayerEntity player, UUID planetId, SetProjectRequest request) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        // Про базу отказываем своими словами: общий отказ списка стройки назвал бы причиной
        // технологию, а дело в том, что селить больше некуда — п. 4.1.
        if (ColonyProject.COLONY_BASE.equals(request.projectCode())) {
            requireRoomForBase(planet);
        }
        // Стапеля не держат ни пустая стройка, ни бесконечная: дома и товары не кончаются
        // никогда, а очередь забирают только за достроенным — поставленное за домами ждало
        // бы вечно, и колония на глазах игрока «не слушалась» бы вовсе. Поэтому новый
        // проект встаёт на их место сразу.
        //
        // Сами дома и товары в очередь встают как любая стройка (30.09.2026): дойдя до
        // них, колония остаётся на них, пока игрок не сменит стройку (ProductionPhase).
        if (planet.getProjectCode() == null
                || Boolean.TRUE.equals(ColonyProject.endless(planet.getProjectCode()))) {
            return setProject(player, planetId, request);
        }

        // То, что уже на стапеле, в очередь не ставят: здание строится однажды, и очередь
        // просто отменила бы его на следующем ходу. Корабли и шпионов это не касается —
        // их строят сколько угодно раз. Спрашивается ДО списка доступного: стоящее на
        // стапеле из него убрано, и отказ иначе говорил бы «нельзя», а не «уже строится».
        if (!Boolean.TRUE.equals(ColonyProject.repeatable(request.projectCode()))
                && request.projectCode().equals(planet.getProjectCode())) {
            throw new ConflictException("colony.alreadyBuilding", planet.getName(),
                    projectLabel(request.projectCode()));
        }
        if (Boolean.TRUE.equals(ColonyProject.endless(request.projectCode()))
                && planet.getBuildQueue().contains(request.projectCode())) {
            throw new ConflictException("colony.alreadyQueued", planet.getName(),
                    projectLabel(request.projectCode()));
        }
        ColonyProjectDto project = available(planet, context(planet)).stream()
                .filter(candidate -> candidate.code().equals(request.projectCode()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("colony.cannotQueue", planet.getName(),
                        projectLabel(request.projectCode())));
        if (planet.getBuildQueue().size() >= ColonyProject.QUEUE_LIMIT) {
            throw new ConflictException("colony.queueFull", planet.getName(), ColonyProject.QUEUE_LIMIT);
        }

        // Очередь правится копией, а не на месте: у колонки свой преобразователь, и
        // Hibernate сравнивает поле с тем же самым списком, который отдал при загрузке.
        // Правка на месте меняет и его — изменения просто не видно, и в базу оно не идёт.
        List<String> queue = new ArrayList<>(planet.getBuildQueue());
        if (Boolean.TRUE.equals(request.top())) {
            // В голову очереди — так ставит список колоний: там проект задают сразу многим
            // колониям, и ждать за всем, что у каждой уже набрано, он не может.
            queue.add(0, project.code());
        } else {
            queue.add(project.code());
        }
        planet.setBuildQueue(queue);

        log.info("Колония {} игрока {} поставила в очередь {}{}",
                planet.getName(), player.getName(), project.name(),
                Boolean.TRUE.equals(request.top()) ? " первым" : "");
        return planet;
    }

    /**
     * Поставить один и тот же проект в очередь сразу многим колониям — п. 10.
     * <p>
     * Так распоряжаются империей из списка колоний: изучив автолабораторию, её закладывают
     * всем разом, а не обходят колонии по одной. Проект должен быть доступен <b>каждой</b>
     * колонии набора — иначе отказ с именем той, которой он не по силам: наполовину
     * выполненный приказ хуже невыполненного, игрок не увидит, кому он не достался.
     * <p>
     * Порядок разбирается в {@link #enqueue}: из списка колоний проект идёт в голову
     * очереди.
     */
    @Transactional
    public List<PlanetEntity> enqueueAll(PlayerEntity player, List<UUID> planetIds,
                                         SetProjectRequest request) {
        List<PlanetEntity> planets = new ArrayList<>(planetIds.size());
        for (UUID planetId : planetIds) {
            planets.add(enqueue(player, planetId, request));
        }
        return planets;
    }

    /**
     * Убрать проект из очереди — п. 10.
     * <p>
     * Вложенного производства при этом не теряется: единицы лежат на самой колонии, а не
     * на проекте, и уходят в то, что колония строит следующим.
     */
    @Transactional
    public PlanetEntity removeFromQueue(PlayerEntity player, UUID planetId, Integer index) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        requireQueueIndex(planet, index);

        List<String> queue = new ArrayList<>(planet.getBuildQueue());
        String removed = queue.remove(index.intValue());
        planet.setBuildQueue(queue);

        log.info("Колония {} игрока {} убрала из очереди {}",
                planet.getName(), player.getName(), removed);
        return planet;
    }

    /**
     * Переставить проект в очереди — п. 10: в оригинале очередь не только набирают, но и
     * переупорядочивают, когда становится ясно, что нужнее.
     */
    @Transactional
    public PlanetEntity moveInQueue(PlayerEntity player, UUID planetId,
                                    Integer index, Integer toIndex) {
        PlanetEntity planet = requireOwnPlanet(player, planetId);
        requireQueueIndex(planet, index);
        requireQueueIndex(planet, toIndex);

        List<String> queue = new ArrayList<>(planet.getBuildQueue());
        String moved = queue.remove(index.intValue());
        queue.add(toIndex, moved);
        planet.setBuildQueue(queue);

        log.info("Колония {} игрока {} передвинула {} с {} на {} место",
                planet.getName(), player.getName(), moved, index + 1, toIndex + 1);
        return planet;
    }

    /** Место в очереди должно существовать: пустого хвоста у неё нет. */
    private void requireQueueIndex(PlanetEntity planet, Integer index) {
        if (index < 0 || index >= planet.getBuildQueue().size()) {
            throw new ConflictException("colony.queueNoIndex", planet.getName(), (index + 1), planet.getBuildQueue().size());
        }
    }

    /**
     * Заселение планеты готовой колониальной базой — п. 4.1.
     * <p>
     * Базу строит колония, а заселяет она соседнюю планету своей системы: лететь базе
     * никуда не нужно, поэтому выбор ограничен системой. Новая колония начинается с
     * одного жителя, как в MOO II, и с распределения по умолчанию — дальше это дело
     * игрока. Стройка ей достаётся та же, с какой начинают родные миры: колония без
     * стройки производит впустую.
     */
    @Transactional
    public PlanetEntity colonize(PlayerEntity player, UUID planetId, ColonizeRequest request) {
        PlanetEntity base = requireOwnPlanet(player, planetId);
        if (!Boolean.TRUE.equals(base.getColonyBaseReady())) {
            throw new ConflictException("colony.baseNotReady", base.getName());
        }

        PlanetEntity target = planetRepository.findById(request.targetPlanetId())
                .orElseThrow(() -> new NotFoundException("planet.notFound", request.targetPlanetId()));
        if (!target.getStarSystem().getId().equals(base.getStarSystem().getId())) {
            throw new ConflictException("colony.baseSameSystem");
        }
        if (!free(target)) {
            throw new ConflictException("colony.cannotSettle", target.getName());
        }

        target.setOwnerPlayerId(player.getId());
        target.setPopulation(1);
        target.setJobs(populationCalculator.defaultJobs(target.getClimate(), target.getPopulation()));
        target.setProjectCode(ColonyProject.TRADE_GOODS);
        target.setProjectPoints(0);
        base.setColonyBaseReady(Boolean.FALSE);
        planetRepository.saveAll(List.of(base, target));

        // Колониальная база — второй способ основать колонию, и для замера он не менее
        // важен, чем корабль: в тесной галактике ИИ расселяется почти только ею, и
        // счётчик, знавший лишь о корабле, показывал ноль колоний на партии, где их было
        // с десяток.
        activity.record(player.getGame().getId(), player.getId(), EmpireActivityService.COLONIZED);
        activity.moment(player.getGame().getId(), player, null, MilestoneService.COLONY_FOUNDED,
                new MessageKey("milestone.colonyFounded", target.getName()));
        log.info("Игрок {} заселил {} колониальной базой с {}",
                player.getName(), target.getName(), base.getName());
        return target;
    }

    /** Планета по идентификатору — для сборки ответа после действий над ней. */
    public PlanetEntity planet(UUID planetId) {
        return planetRepository.findById(planetId)
                .orElseThrow(() -> new NotFoundException("planet.notFound", planetId));
    }

    /** Есть ли в системе планеты, которые колониальной базе есть смысл заселять. */
    private Boolean hasFreePlanet(PlanetEntity planet) {
        return freePlanets(planet) > 0;
    }

    /** Сколько в системе планет, которые колониальной базе есть смысл заселять. */
    private Integer freePlanets(PlanetEntity planet) {
        return (int) planet.getStarSystem().getPlanets().stream().filter(this::free).count();
    }

    /**
     * Сколько колониальных баз колония уже заказала — п. 4.1: готовая, строящаяся и
     * стоящие в очереди.
     * <p>
     * Каждая из них займёт по свободной планете, и больше, чем таких планет в системе,
     * заказывать нечего: лишняя база осталась бы без места, а производство на неё ушло бы.
     */
    private Integer basesOrdered(PlanetEntity planet) {
        int ready = Boolean.TRUE.equals(planet.getColonyBaseReady()) ? 1 : 0;
        int building = ColonyProject.COLONY_BASE.equals(planet.getProjectCode()) ? 1 : 0;
        int queued = (int) planet.getBuildQueue().stream()
                .filter(ColonyProject.COLONY_BASE::equals)
                .count();
        return ready + building + queued;
    }

    /** Есть ли куда девать ещё одну базу: свободных планет больше, чем уже заказано баз. */
    private Boolean baseHasWhereToGo(PlanetEntity planet) {
        return basesOrdered(planet) < freePlanets(planet);
    }

    /**
     * Отказ с причиной, когда база очередной колонии уже не нужна, — п. 4.1.
     * <p>
     * Общий отказ списка стройки («здание уже построено или его технология не изучена»)
     * про базу врёт: дело не в технологии, а в том, что заселять больше нечего.
     */
    private void requireRoomForBase(PlanetEntity planet) {
        if (Boolean.TRUE.equals(baseHasWhereToGo(planet))) {
            return;
        }
        Integer free = freePlanets(planet);
        throw free == 0
                ? new ConflictException("colony.noRoomForBase", planet.getName())
                : new ConflictException("colony.basesAlreadyOrdered", planet.getName(), free);
    }

    /** Планета свободна: ничья, незаселённая и пригодная для жизни — п. 4.1.2. */
    private Boolean free(PlanetEntity planet) {
        return planet.getOwnerPlayerId() == null
                && planet.getPopulation() <= 0
                && Boolean.TRUE.equals(planet.getClimate().getColonizable());
    }

    private PlanetEntity requireOwnPlanet(PlayerEntity player, UUID planetId) {
        PlanetEntity planet = planetRepository.findById(planetId)
                .orElseThrow(() -> new NotFoundException("planet.notFound", planetId));
        if (!player.getId().equals(planet.getOwnerPlayerId())) {
            throw new ForbiddenException("planet.notYours", planet.getName());
        }
        return planet;
    }

    /** Здание без технологии доступно сразу; с технологией — только после её изучения. */
    private Boolean researched(Building building, Set<String> technologies) {
        return building.requiredTechCode() == null || technologies.contains(building.requiredTechCode());
    }

    /**
     * Код стройки для клиента. У корабля он собирается обратно из кода и проекта: список
     * доступного различает корабли именно так, и текущая стройка должна совпадать с
     * одной из его строк.
     */
    private String projectCode(PlanetEntity planet) {
        if (ColonyProject.SHIP.equals(planet.getProjectCode()) && planet.getProjectDesignId() != null) {
            return ColonyProject.ship(planet.getProjectDesignId());
        }
        return planet.getProjectCode();
    }

    /**
     * Чем назвать проект в отказе: здание — его названием на языке читателя, прочее (корабль,
     * база, шпион) — общим словом. Код проекта («biospheres», «SHIP:…») — служебный язык, и в
     * отказ он не попадает (field-backlog, пункт 198): отказ стройки игрок читает прямо в окне.
     */
    private Object projectLabel(String projectCode) {
        Building building = buildingCatalog.byCode().get(projectCode);
        return building != null ? building.name() : new MessageKey("colony.thisProject");
    }

    private String projectName(PlanetEntity planet, Building building, ColonyContext context) {
        String projectCode = planet.getProjectCode();
        if (ColonyProject.isShip(projectCode)) {
            return messages.get("colony.project.ship.name", shipDesign(planet, context)
                    .map(ShipDesignService.ShipBuildOption::name)
                    .orElseGet(() -> messages.get("colony.project.ship.withdrawn")));
        }
        if (SPECIALS.containsKey(projectCode)) {
            return special(projectCode).name();
        }
        return building == null ? null : building.name();
    }

    /**
     * Во что обойдётся стройка. У домов и товаров стоимости нет вовсе — они не
     * заканчиваются; у колониальной базы она своя, в справочнике зданий её нет.
     */
    private Integer projectCost(PlanetEntity planet, Building building, ColonyContext context) {
        String projectCode = planet.getProjectCode();
        if (ColonyProject.isShip(projectCode)) {
            // Цена корабля — цена его проекта. Проект могли убрать из ячейки, пока корабль
            // строился: тогда цены нет, и стройку игрок выберет заново.
            return shipDesign(planet, context)
                    .map(ShipDesignService.ShipBuildOption::cost)
                    .orElse(null);
        }
        if (ColonyProject.COLONY_BASE.equals(projectCode)) {
            return ColonyProject.COLONY_BASE_COST;
        }
        if (ColonyProject.SPY.equals(projectCode)) {
            return ColonyProject.SPY_COST;
        }
        if (ColonyProject.FREIGHTER.equals(projectCode)) {
            return ColonyProject.FREIGHTER_COST;
        }
        if (ColonyProject.COLONY_SHIP.equals(projectCode)) {
            return ColonyProject.COLONY_SHIP_COST;
        }
        if (ColonyProject.OUTPOST_SHIP.equals(projectCode)) {
            return ColonyProject.OUTPOST_SHIP_COST;
        }
        if (ColonyProject.TRANSPORT.equals(projectCode)) {
            return ColonyProject.TRANSPORT_COST;
        }
        if (ColonyProject.isReshape(projectCode)) {
            return reshapeCost(planet);
        }
        if (ColonyProject.isAndroid(projectCode)) {
            return ColonyProject.ANDROID_COST;
        }
        return building == null ? null : building.cost();
    }

    /**
     * Проект корабля строкой списка стройки — п. 8. Код несёт с собой идентификатор
     * проекта: кораблей у империи столько же, сколько проектов, и различать их в списке
     * больше нечем.
     */
    private ColonyProjectDto shipProject(ShipDesignService.ShipBuildOption design) {
        // Числа уходят в подстановку строками: MessageFormat расставил бы в них
        // разделители разрядов по языку читателя, а залп в «1,200» читается как дробь.
        return new ColonyProjectDto(
                ColonyProject.ship(design.designId()),
                messages.get("colony.project.ship.name", design.name()),
                messages.get("colony.project.ship.description", design.hullName(),
                        String.valueOf(design.attack()), String.valueOf(design.defense())),
                design.cost(), 0, Boolean.TRUE, null,
                ColonyProject.repeatable(ColonyProject.SHIP), null, null);
    }

    /**
     * Особый проект строкой списка стройки — на языке читателя (см. {@link #SPECIALS}).
     * <p>
     * Собирается на каждый запрос, и это недорого: две выборки из словаря на проект,
     * а словарь сообщений держится в памяти. Внутри посчитанного хода (стройка ИИ) язык
     * запроса неизвестен и текст выходит английским — но ИИ решает по кодам, а не по
     * именам, и текст ему не нужен вовсе.
     */
    private ColonyProjectDto special(String code) {
        return special(code, SPECIALS.get(code).cost());
    }

    /** Особый проект с ценой, которая зависит от планеты, — терраформирование дорожает (п. 4.1.2.1). */
    private ColonyProjectDto special(String code, Integer cost) {
        Special special = SPECIALS.get(code);
        return new ColonyProjectDto(
                code,
                messages.get("colony.project." + special.key() + ".name"),
                messages.get("colony.project." + special.key() + ".description"),
                cost, 0, Boolean.TRUE, null,
                ColonyProject.repeatable(code), null, null);
    }

    /** Цена переделки планеты, которую она строит сейчас, — п. 4.1.2.1. */
    public Integer reshapeCost(PlanetEntity planet) {
        String code = planet.getProjectCode();
        if (ColonyProject.TERRAFORM.equals(code)) {
            return TerraformRules.terraformCost(planet.getTerraformCount());
        }
        if (ColonyProject.GAIA.equals(code)) {
            return TerraformRules.GAIA_COST;
        }
        return TerraformRules.ARTIFICIAL_PLANET_COST;
    }

    /**
     * Тело своей системы, из которого можно сделать планету, — п. 4.1: газовый гигант или пояс
     * астероидов, где никто не живёт (своя застава не мешает). Берётся ближайшее к звезде.
     */
    public Optional<PlanetEntity> artificialTarget(PlanetEntity planet) {
        if (planet.getStarSystem() == null) {
            return Optional.empty();
        }
        return planet.getStarSystem().getPlanets().stream()
                .filter(body -> TerraformRules.convertible(body.getClimate()))
                .filter(body -> body.getPopulation() <= 0)
                .filter(body -> body.getOwnerPlayerId() == null
                        || body.getOwnerPlayerId().equals(planet.getOwnerPlayerId()))
                .min(java.util.Comparator.comparing(PlanetEntity::getOrbit));
    }

    /**
     * Переделка планеты достроена — п. 4.1.2.1: климат на ступень лучше (или Гея), либо тело
     * системы становится планетой. Вместимость записана на планете и меняется вместе с
     * климатом, как в событии смены климата; лишние жители уходят сразу.
     *
     * @return ключ строки итогов хода; пусто — переделывать уже нечего (планету переделали раньше)
     */
    public Optional<MessageKey> reshape(PlanetEntity planet, Long gameSeed) {
        String code = planet.getProjectCode();
        if (ColonyProject.ARTIFICIAL_PLANET.equals(code)) {
            return artificialTarget(planet).map(body -> {
                String was = body.getClimate().name();
                body.setPlanetSize(TerraformRules.artificialSize(body.getClimate()));
                body.setClimate(com.sddnw.server.domain.enums.PlanetClimate.BARREN);
                body.setMinerals(TerraformRules.artificialMinerals());
                body.setMaxPopulation(populationCalculator.maxPopulation(body.getPlanetSize(), body.getClimate()));
                return new MessageKey("turn.build.artificialPlanet", planet.getName(), body.getName(),
                        "enum.PlanetClimate." + was);
            });
        }
        Optional<com.sddnw.server.domain.enums.PlanetClimate> next = ColonyProject.GAIA.equals(code)
                ? (planet.getClimate() == com.sddnw.server.domain.enums.PlanetClimate.TERRAN
                        ? Optional.of(com.sddnw.server.domain.enums.PlanetClimate.GAIA) : Optional.empty())
                : TerraformRules.terraformed(planet.getClimate(),
                        TerraformRules.tundraRoll(gameSeed, planet.getName()));
        return next.map(climate -> {
            planet.setClimate(climate);
            planet.setMaxPopulation(populationCalculator.maxPopulation(planet.getPlanetSize(), climate));
            if (ColonyProject.TERRAFORM.equals(code)) {
                planet.setTerraformCount(planet.getTerraformCount() + 1);
            }
            return new MessageKey(ColonyProject.GAIA.equals(code) ? "turn.build.gaia" : "turn.build.terraformed",
                    planet.getName(), "enum.PlanetClimate." + climate.name());
        });
    }

    /** Проект корабля, который строит колония; пусто — проект убран из ячейки. */
    private Optional<ShipDesignService.ShipBuildOption> shipDesign(PlanetEntity planet,
                                                                  ColonyContext context) {
        UUID designId = planet.getProjectDesignId();
        if (designId == null) {
            return Optional.empty();
        }
        return context.shipDesigns(planet).stream()
                .filter(option -> option.designId().equals(designId))
                .findFirst();
    }

    /**
     * Здание строкой списка — п. 10. Цена продажи считается здесь же: игрок видит её и в
     * окне продажи, и заранее, выбирая, что строить, — правило одно, и живёт оно на
     * сервере ({@code PopulationCalculator.sellValue}).
     */
    private ColonyProjectDto toProject(Building building) {
        return toProject(building, null);
    }

    /**
     * Здание строкой списка стройки — с окупаемостью на этой колонии (field-backlog, пункт 122).
     * Число то же, по которому выбирают ИИ и автострой: второго расчёта нет. Построенным
     * зданиям ({@code jobs == null}) окупаемость не считается — выбирать там нечего.
     */
    private ColonyProjectDto toProject(Building building, PopulationJobs jobs) {
        Integer payback = jobs == null ? null : AiEmpireService.paybackTurns(building, jobs);
        return new ColonyProjectDto(
                building.code(),
                building.name(),
                building.description(),
                building.cost(),
                building.upkeep(),
                Boolean.FALSE,
                populationCalculator.sellValue(building.cost()),
                // Здание на планете одно: второй раз его не построить, и в очередь второй
                // раз оно не встаёт.
                Boolean.FALSE,
                payback,
                jobs == null || payback != null ? null : purpose(building));
    }

    /**
     * Ради чего строят здание без окупаемости в производстве — пункт 122: у обороны, щитов,
     * казарм, ферм, биржи и морали срока нет, и выдумывать его нельзя, но назвать назначение
     * можно — по его эффектам. Здания без эффектов — оборона: платформы и щиты бьются в бою, а
     * не прибавляют колонии ничего.
     */
    static String purpose(Building building) {
        java.util.Set<BuildingEffectType> types = building.effects().keySet();
        if (types.isEmpty() || types.contains(BuildingEffectType.GROUND_DEFENCE_PERCENT)
                || types.contains(BuildingEffectType.BARRACKS)) {
            return "DEFENCE";
        }
        if (types.contains(BuildingEffectType.FOOD_FLAT) || types.contains(BuildingEffectType.FOOD_PER_FARMER)
                || types.contains(BuildingEffectType.FOOD_PERCENT)) {
            return "FOOD";
        }
        if (types.contains(BuildingEffectType.INCOME_PERCENT) || types.contains(BuildingEffectType.INCOME_FLAT)) {
            return "INCOME";
        }
        if (types.contains(BuildingEffectType.MAX_POPULATION) || types.contains(BuildingEffectType.POPULATION_GROWTH_K)
                || types.contains(BuildingEffectType.GROWTH_PERCENT)) {
            return "GROWTH";
        }
        if (types.contains(BuildingEffectType.POLLUTION_HALVINGS) || types.contains(BuildingEffectType.POLLUTION_NONE)
                || types.contains(BuildingEffectType.POLLUTION_TOLERANCE_PERCENT)) {
            return "POLLUTION";
        }
        if (types.contains(BuildingEffectType.MORALE_PERCENT)) {
            return "MORALE";
        }
        if (types.contains(BuildingEffectType.CAPITOL) || types.contains(BuildingEffectType.ALIEN_MANAGEMENT)) {
            return "GOVERNANCE";
        }
        return "OTHER";
    }
}
