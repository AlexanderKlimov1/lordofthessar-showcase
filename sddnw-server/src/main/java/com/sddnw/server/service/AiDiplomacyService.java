package com.sddnw.server.service;

import com.sddnw.server.domain.entity.DiplomacyRelationEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.domain.enums.AiPersonality;
import com.sddnw.server.domain.enums.DiplomacyAction;
import com.sddnw.server.domain.enums.DiplomacyStance;
import com.sddnw.server.domain.enums.DiplomacyTreaty;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.domain.entity.PlayerTechnologyEntity;
import com.sddnw.server.dto.DiplomacyActionRequest;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import com.sddnw.server.web.error.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Что империи ИИ делают в дипломатии сами — п. 15.
 * <p>
 * До этого дипломатия была улицей с односторонним движением: предлагал только игрок, а
 * соседи лишь отвечали да или нет. В MOO II наоборот — соседи приходят сами: «требования
 * иногда выдвигает союзник, но и другие империи тоже», а подарок делают, «чтобы убедить
 * более сильную империю, что мир выгоднее войны». Здесь то же самое.
 * <p>
 * <b>Решает характер правителя</b> ({@link AiPersonality}) — шесть характеров MOO II:
 * <ul>
 *   <li><b>война</b> — агрессивный «нападёт, как только окажется в выгодном положении»,
 *       беспощадный «нападает почти без повода» и лезет в драку даже слабее соперника,
 *       миролюбивый — только при подавляющем перевесе. Благородный «не нападает на тех,
 *       с кем в хороших отношениях», и щадит их при любом перевесе;</li>
 *   <li><b>мир</b> — миролюбивый «на войне быстрее просит мира»; остальные просят, когда
 *       война складывается не в их пользу;</li>
 *   <li><b>договор</b> — предлагают тем, кому доверяют; дипломат охотнее прочих;</li>
 *   <li><b>дань</b> — требуют у того, кто заметно слабее;</li>
 *   <li><b>подарок</b> — делают тому, кто заметно сильнее: покупают спокойствие.</li>
 * </ul>
 * <p>
 * <b>Одно действие за ход и не каждый ход.</b> В MOO II «империи ИИ теряют терпение и
 * какое-то время отказываются разговаривать, если досаждать им переговорами»; здесь та же
 * мера, только с другой стороны — ИИ и сам не осаждает соседей каждый ход. Жребий берётся
 * от зерна партии, хода и слота: перезагруженное сохранение должно считаться так же.
 * <p>
 * Само действие исполняет {@link DiplomacyService#act} — тот же путь, которым ходит игрок.
 * Своих правил у ИИ нет: он выбирает, что предложить, а согласие, доверие и оповещение
 * второй стороны считаются там же, где и всегда.
 * <p>
 * <b>Отступление от MOO II:</b> там предложение соседа открывает игроку окно с ответом
 * «да или нет». Здесь окна ответа ещё нет, и за игрока отвечает его же доверие к соседу
 * ({@code yourTrust}) — по тому самому правилу, по которому отвечает и сосед: решает
 * всегда тот, кому предлагают. Случившееся игрок видит в итогах хода. Точка расширения:
 * окно ответа встанет между {@link #perform} и {@link DiplomacyService#act}, отложив
 * действие до решения игрока.
 */
@Service
public class AiDiplomacyService {

    private static final Logger log = LoggerFactory.getLogger(AiDiplomacyService.class);

    /**
     * Как часто ИИ вообще заводит разговор, в процентах на ход.
     * <p>
     * <i>Реконструкция:</i> в оригинале мера названа со стороны игрока — империи «теряют
     * терпение, если досаждать им переговорами». Четверть ходов означает разговор раз в
     * четыре хода на соседа: заметно, но не превращает дипломатию в поток сообщений.
     */
    private static final int ACTION_CHANCE_PERCENT = 25;

    /** Во сколько раз надо быть сильнее, чтобы требовать дань, и слабее — чтобы дарить. */
    private static final int TRIBUTE_ADVANTAGE_PERCENT = 200;
    private static final int GIFT_WEAKNESS_PERCENT = 50;

    /** Какую часть казны ИИ отдаёт, покупая спокойствие. */
    private static final int GIFT_SHARE_PERCENT = 20;

    /** Доверие, ниже которого сосед считается врагом, и выше которого — другом. */
    private static final int HOSTILE_TRUST = 40;
    private static final int FRIENDLY_TRUST = 60;

    /**
     * Сколько ходов сосед считается новым знакомым и войны ему не объявляют —
     * реконструкция.
     * <p>
     * Десять ходов: довольно, чтобы «прилетел — и сразу война» стало невозможным, и мало
     * на фоне партии в полторы сотни ходов, чтобы войны от этого перевелись. Чисел MOO II
     * не публиковала, но известно, что сосед сперва присматривается.
     */
    private static final int ACQUAINTANCE_BEFORE_WAR = 10;

    /**
     * Агония соседа — его мощь не больше этой доли своей, в процентах (09.10.2026, разбор
     * мёртвого покорения: за 300 ходов не добита ни одна империя). Реконструкция, правленая
     * вдвое (пункт 89): при четверти правило не сработало ни разу — мощь умирающей империи с
     * четырьмя колониями всё ещё около трети сильнейшей (в мощь входят наука и население).
     */
    private static final int AGONY_PERCENT = 50;

    /**
     * Насколько охотнее нападают на отталкивающего соседа — п. 7.
     * <p>
     * <b>Вторая половина стороны, которой не было в игре вовсе.</b> Карточка обещает:
     * «с расой не договариваются: чужие империи ВРАЖДЕБНЫ», — и в оригинале это «their
     * attitude toward you is significantly worse», то есть не только отказ от переговоров.
     * Сделан был один отказ, и он ничего не стоил: измерено пробой на трёх партиях по 150
     * ходов (четверо отталкивающих против четверых обычных, прочие стороны одинаковы) —
     * договоров у отталкивающих ровно ноль против 1,83 у соседей, а колоний ВПОЛТОРА
     * БОЛЬШЕ (3,42 против 1,92) и захватов вдвое. Сторона за −6 очков мерилась силой
     * +3,03: запрет переговоров оказался не платой, а освобождением — договор в этой игре
     * запирает войну, и не иметь его выгодно.
     * <p>
     * Двадцать пять пунктов — это ступень между соседними характерами таблицы
     * ({@link AiPersonality}: 50, 90, 110, 140, 200): отталкивающий сосед сдвигает решение
     * ровно на характер в сторону войны, а не превращает миролюбивого в беспощадного.
     */
    private static final int REPULSIVE_WAR_DISCOUNT = 25;

    /**
     * Ниже перевеса самого беспощадного характера скидка не опускает — п. 15.
     * <p>
     * Иначе отталкивающий сосед делал бы миролюбивого решительнее беспощадного, а характер
     * правителя должен оставаться главной мерой: враждебность сдвигает решение, а не
     * подменяет собой нрав.
     */
    private static final int RUTHLESS_WAR_ADVANTAGE = AiPersonality.RUTHLESS.getWarAdvantagePercent();

    /**
     * БАЛАНС СИЛ — field-backlog, пункт 151 (08.10.2026, правило хозяина проекта).
     * <p>
     * Лидер — империя, которая сильнее ДВУХ БЛИЖАЙШИХ к ней империй вместе. Тогда эти двое
     * ({@code core}) сближаются против общего врага: доверие между ними растёт, они мирятся,
     * заключают союз и, когда перевес союза достаточен, объявляют лидеру войну. Следующие двое по
     * близости ({@code backers}) поддерживают их деньгами и технологиями для войны. Остальные
     * держатся с лидером осторожно-враждебно: доверие к нему не выше {@link #CAUTIOUS_TRUST}, ни
     * договоров, ни обменов, ни дани.
     * <p>
     * <i>Реконструкция:</i> MOO II такого правила не знала, его подсказывает история держав —
     * поэтому оно читается естественно, а не подкруткой. Близость меряется между родными
     * звёздами; лидер один на партию — сильнейший из подходящих. Мерило успеха — ход решённости
     * партии (пункт 150) уходит позже.
     */
    private record Bloc(UUID leader, List<UUID> core, List<UUID> backers) {
    }

    /** На сколько за ход растёт доверие двух соседей лидера друг к другу — общий враг сближает. */
    private static final int COMMON_ENEMY_TRUST = 5;

    /** Выше этого общий враг доверие не поднимает: дальше дружба держится своими делами. */
    private static final int COMMON_ENEMY_TRUST_CAP = 80;

    /** Осторожная враждебность к лидеру: доверие не выше этого — ниже порога вражды {@link #HOSTILE_TRUST}. */
    private static final int CAUTIOUS_TRUST = 35;

    /** Как часто помощник союза шлёт помощь, в процентах на ход: не каждый ход, чтобы не разориться. */
    private static final int BACKER_CHANCE_PERCENT = 50;

    /** Разделы дерева, которые воюют: лучи, ракеты и броня, щиты. Помощь технологией берётся отсюда. */
    private static final Set<String> WAR_SECTIONS = Set.of("physics", "chemistry", "force_fields");

    private final DiplomacyRelationRepository relationRepository;
    private final DiplomacyService diplomacyService;
    private final RaceService raceService;
    private final EmpireInfoService empireInfoService;
    private final PlayerTechnologyRepository playerTechnologyRepository;
    private final ResearchCatalog researchCatalog;

    public AiDiplomacyService(DiplomacyRelationRepository relationRepository,
                              DiplomacyService diplomacyService,
                              RaceService raceService,
                              EmpireInfoService empireInfoService,
                              PlayerTechnologyRepository playerTechnologyRepository,
                              ResearchCatalog researchCatalog) {
        this.relationRepository = relationRepository;
        this.diplomacyService = diplomacyService;
        this.raceService = raceService;
        this.empireInfoService = empireInfoService;
        this.playerTechnologyRepository = playerTechnologyRepository;
        this.researchCatalog = researchCatalog;
    }

    /** Ход дипломатии ИИ: каждая империя ИИ может завести один разговор — п. 15. */
    @Transactional
    public void act(TurnContext context) {
        Map<UUID, PlayerEntity> byId = context.players().stream()
                .collect(Collectors.toMap(PlayerEntity::getId, Function.identity()));
        // Мощь считается той же мерой, что рисует график окна «Инфо» (п. 11.1): игрок
        // видит на экране ровно то, по чему судит сосед.
        Map<UUID, Integer> might = empireInfoService.mightByPlayer(context);
        // Отношения всех участников — одной выборкой: запрос на каждую империю ИИ стоил
        // семи походов в базу за ход, и каждый сбрасывал туда весь посчитанный ход.
        // Переговоры идут только с теми, с кем знакомство действует (п. 15): на паузе
        // действие отказало бы «нет знакомства», а отказ изнутри хода здесь не нужен.
        Map<UUID, List<DiplomacyRelationEntity>> relationsByPlayer = relationRepository
                .findAllByPlayerIdIn(context.players().stream().map(PlayerEntity::getId).toList())
                .stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                .collect(Collectors.groupingBy(DiplomacyRelationEntity::getPlayerId));
        // Изученное — тоже ОДНОЙ выборкой на партию, и по той же причине, что отношения:
        // запрос на игрока изнутри посчитанного хода сбрасывает в базу всё, что ход успел
        // изменить. Обмен спрашивает изученное у обеих сторон каждого разговора.
        Map<UUID, Set<String>> technologies = playerTechnologyRepository
                .findAllByPlayerIdIn(context.players().stream().map(PlayerEntity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(PlayerTechnologyEntity::getPlayerId,
                        Collectors.mapping(PlayerTechnologyEntity::getOptionCode,
                                Collectors.toSet())));
        Map<String, ResearchCatalog.TechnologyPlace> places = researchCatalog.places();
        // Баланс сил (пункт 151): блок против лидера считается один раз на ход и действует
        // прежде обычных разговоров — у сближения и помощи свой ритм.
        Bloc bloc = bloc(context, might);
        if (bloc != null) {
            balanceOfPower(context, bloc, byId, relationsByPlayer, technologies, places, might);
        }

        for (PlayerEntity ai : context.players()) {
            if (ai.getPlayerType() != PlayerType.AI || ai.getAiPersonality() == null) {
                continue;
            }
            // Отталкивающая раса переговоров не ведёт вовсе — только война и мир (п. 7).
            Boolean repulsive = raceService.effects(ai).repulsive();

            Random random = new Random(
                    context.game().getSeed() * 37L + context.turn() * 41L + ai.getSlot());
            if (random.nextInt(100) >= ACTION_CHANCE_PERCENT) {
                continue;
            }

            // ПОРЯДОК ЗДЕСЬ — ЧАСТЬ ПРАВИЛ: собеседник выбирается ИНДЕКСОМ в этом списке
            // (`relations.get(random.nextInt(...))`), а выборка отношений отдаёт строки как
            // удобно базе. Значит без явного ключа ИИ в двух прогонах одной партии говорит
            // с РАЗНЫМИ соседями — и дальше расходится всё. Поймано сверкой по колониям:
            // на 104-м ходу у двух империй появлялись лишние технологии (neural-scanner,
            // heavy-armor) — следы обмена, состоявшегося только в одном прогоне.
            //
            // Ключ — МЕСТО игрока: оно задано партией и от идентификаторов строк не зависит
            // (см. GameOrder о том, почему ни id, ни строковые колонки ключом не годятся).
            List<DiplomacyRelationEntity> relations = new ArrayList<>(
                    relationsByPlayer.getOrDefault(ai.getId(), List.of()));
            relations.sort(Comparator.comparing(one -> {
                PlayerEntity other = byId.get(one.getOtherPlayerId());
                return other == null ? Integer.MAX_VALUE : other.getSlot();
            }));
            if (relations.isEmpty()) {
                continue;
            }
            DiplomacyRelationEntity mine = relations.get(random.nextInt(relations.size()));
            PlayerEntity other = byId.get(mine.getOtherPlayerId());
            if (other == null) {
                continue;
            }

            // Отталкивающей может оказаться и вторая сторона: переговоров не будет и тогда.
            // А вот ВРАЖДЕБНОСТЬ несимметрична — она направлена на отталкивающего соседа, и
            // потому его признак берётся отдельно от общего запрета переговоров.
            Boolean theirsRepulsive = raceService.effects(other).repulsive();
            Boolean noTalks = Boolean.TRUE.equals(repulsive)
                    || Boolean.TRUE.equals(theirsRepulsive);

            // Решает всегда тот, КОМУ предлагают, поэтому обмену нужна встречная строка —
            // мнение соседа о нас (п. 15). Берётся она из той же выборки отношений.
            DiplomacyRelationEntity theirs = relationsByPlayer
                    .getOrDefault(other.getId(), List.of()).stream()
                    .filter(one -> one.getOtherPlayerId().equals(ai.getId()))
                    .findFirst()
                    .orElse(null);
            Exchange trade = exchange(ai, theirs, places,
                    technologies.getOrDefault(ai.getId(), Set.of()),
                    technologies.getOrDefault(other.getId(), Set.of()));

            DiplomacyAction action = decide(ai, other, mine, noTalks, theirsRepulsive, might,
                    random, context.turn(), trade, bloc);
            // Тихая галактика (пункт 186): первым войну не начинают и договоров не рвут; войну,
            // объявленную ему, ИИ ведёт как обычно — защищается и отвечает.
            if (Boolean.TRUE.equals(context.game().getPeacefulAi())
                    && (action == DiplomacyAction.DECLARE_WAR || action == DiplomacyAction.BREAK_TREATY)) {
                action = null;
            }
            if (action != null) {
                perform(context, ai, other, mine, action, trade);
            }
        }
    }

    /**
     * Что ИИ предпримет с этим соседом — п. 15; {@code null} — на этот раз ничего.
     * <p>
     * Порядок проверок — порядок важности: сперва война и мир, они меняют положение
     * сильнее всего, потом договор, потом дань и подарок. Первое подошедшее и делается:
     * за один ход ИИ говорит об одном.
     */
    private DiplomacyAction decide(PlayerEntity ai, PlayerEntity other,
                                   DiplomacyRelationEntity mine, Boolean noTalks,
                                   Boolean theirsRepulsive,
                                   Map<UUID, Integer> might, Random random, Integer turn,
                                   Exchange trade, Bloc bloc) {
        AiPersonality character = ai.getAiPersonality();
        Integer trust = mine.getTrust();
        // Лидер блока (пункт 151): с ним не договариваются, не меняются и дани не требуют.
        Boolean againstLeader = bloc != null && bloc.leader().equals(other.getId());
        Boolean core = bloc != null && bloc.core().contains(ai.getId());

        /*
          УМИРАЮЩЕГО ДОБИВАЮТ (09.10.2026, разбор мёртвого покорения): в замере сильнейший оказался
          миролюбцем и мирился сразу после объявления войны, соседей лидера правило блока
          (пункт 151) держало от войн, и со слабыми воевали только такие же слабые — колонии
          ходили из рук в руки, а империя не гибла ни одна. Сосед, чья мощь вчетверо меньше,
          в агонии: с ним мира не просят, а войну ему объявляет и сосед лидера — спасать того,
          кто уже не встанет, коалиции не из чего. Миролюбец и благородный к другу — по нраву.
        */
        Boolean agony = !stronger(might, other, ai, 100) && stronger(might, ai, other, 10_000 / AGONY_PERCENT);
        if (mine.getStance() == DiplomacyStance.WAR && Boolean.TRUE.equals(agony)) {
            return null;
        }
        if (Boolean.TRUE.equals(agony) && !Boolean.TRUE.equals(character.seeksPeace())
                && readyToFinish(ai, other, mine, character, theirsRepulsive, turn)) {
            DiplomacyTreaty binding = mine.getTreaties().stream()
                    .filter(treaty -> Boolean.TRUE.equals(treaty.forbidsWar()))
                    .findFirst()
                    .orElse(null);
            return binding == null ? DiplomacyAction.DECLARE_WAR : DiplomacyAction.BREAK_TREATY;
        }

        if (mine.getStance() == DiplomacyStance.WAR) {
            // Мира просят миролюбивые и проигрывающие: незачем длить войну, которую не тянешь.
            // А сосед лидера — со всеми, кроме лидера (пункт 151): чтобы стоять против него.
            return Boolean.TRUE.equals(character.seeksPeace()) || losing(might, ai, other)
                    || (core && !againstLeader)
                    ? DiplomacyAction.PROPOSE_PEACE
                    : null;
        }

        // Сосед лидера войн по своему нраву не начинает: его войну с лидером ведёт блок (пункт 151).
        if (!core && readyForWar(ai, other, mine, character, theirsRepulsive, might, random, turn)) {
            // Пакт о ненападении и союз войну запрещают — их сперва РВУТ. До этой ветки ИИ
            // не рвал договоров вовсе: подписав однажды, он держал мир до конца партии, и
            // галактика запиралась наглухо — та же поломка вечного мира, только приходила
            // она не с первого хода, а с третьего десятка. Разрыв стоит доверия (это
            // считает DiplomacyService), и следующим разговором объявляется война.
            DiplomacyTreaty binding = mine.getTreaties().stream()
                    .filter(treaty -> Boolean.TRUE.equals(treaty.forbidsWar()))
                    .findFirst()
                    .orElse(null);
            return binding == null ? DiplomacyAction.DECLARE_WAR : DiplomacyAction.BREAK_TREATY;
        }
        if (Boolean.TRUE.equals(noTalks)) {
            // Дальше идут договоры, дань и подарки — с отталкивающей расой их не бывает.
            return null;
        }
        // Договор предлагается только ДРУГУ, а не всякому знакомому. Раньше он шёл, «как
        // только доверия хватает хоть на какой-то», и это запирало галактику в вечный мир:
        // знакомство начинается с половины шкалы, торговый договор стоит меньше половины,
        // поэтому договор подписывался на первом же контакте — а договор запрещает войну, и
        // ИИ его не рвёт никогда.
        //
        // Цена этому измерена: за 150 ходов восьми империй — по 4-10 договоров на каждую и
        // ОДНА воюющая империя из восьми. Ни боёв, ни десанта: прибор балансировки мерил
        // нулём не слабые стороны, а игру, в которой никто не воюет. Порог дружбы — та же
        // отметка, по которой благородный характер щадит друга: с кем дружишь, с тем и
        // договор, а со всеми подряд — это не дипломатия, а формальность знакомства.
        // Порог у КАЖДОГО договора свой (торговый 40, исследовательский 50, ненападение 55,
        // союз 75), и bestTreaty его соблюдает. Общий порог дружбы поверх этого был лишним
        // и оказался хуже той поломки, которую чинил: под него попали торговый и
        // исследовательский договоры, которые войну не запрещают вовсе. Измерено — за 150
        // ходов восьми империй НИ ОДНОГО договора: доверие начинается с половины шкалы,
        // порог дружбы выше неё, а доверие растёт только от согласия на предложение,
        // которого при таком пороге никто не делает. Замок замкнулся сам на себя, а прибор
        // балансировки объявил «отталкивающих» за −6 очков ВЫГОДНОЙ ПОКУПКОЙ (+3,90):
        // сторона, запрещающая переговоры, не отнимала ничего, потому что переговоров и так
        // не было. Теперь отметка дружбы стоит там, где ей место, — на договорах, которые
        // запрещают войну.
        // С лидером договоров не заводят: договор запрещает войну, а союз стоит против него.
        if (!againstLeader && bestTreaty(mine) != null) {
            return DiplomacyAction.PROPOSE_TREATY;
        }
        // ОБМЕН ТЕХНОЛОГИЯМИ — п. 15. Стоит он здесь, между договором и данью, потому что
        // это мирное действие, усиливающее ОБОИХ: договор весомее (он меняет саму
        // возможность войны), а дань и подарок — остаток разговора.
        //
        // Зачем это вообще заведено: обмен жил в игре только для игрока, а ИИ не выдавал
        // его НИ РАЗУ — шестой случай механики, к которой у ИИ нет пути. Цену этому платила
        // неизобретательность: в MOO II раса, которой уровень выдаёт случайную технологию,
        // выменивает пропущенное у соседей, и другого пути у неё нет. У нас этот путь был
        // закрыт, и сторона за −1 очко мерилась силой −23,6 — крупнейшая ошибка таблицы.
        // Завод и лаборатория лежат на уровнях из трёх вариантов: обычная раса берёт
        // рекомендованное всегда, неизобретательная — одно из трёх, а уровень не
        // переучивается никогда.
        if (againstLeader) {
            return null;
        }
        if (trade != null) {
            return DiplomacyAction.EXCHANGE_TECH;
        }
        if (trust < HOSTILE_TRUST && stronger(might, ai, other, TRIBUTE_ADVANTAGE_PERCENT)) {
            return DiplomacyAction.DEMAND_TRIBUTE;
        }
        if (weaker(might, ai, other) && ai.getCredits() > 0) {
            return DiplomacyAction.GIFT_CREDITS;
        }
        return null;
    }

    /**
     * Пора ли нападать — п. 15.
     * <p>
     * Три условия. Договор запрещает войну, пока не разорван, — его ИИ не рвёт: предателем
     * он становится только по решению игрока. Благородный не бьёт того, с кем в хороших
     * отношениях. И главное — перевес: у каждого характера свой порог, от беспощадного,
     * который лезет и слабее, до миролюбивого, которому нужно тройное превосходство.
     * <p>
     * Непредсказуемый вдобавок бросает монету: «в один год миролюбив, в другой пойдёт
     * войной по любому поводу» — иначе он ничем не отличался бы от прочих.
     */
    private Boolean readyForWar(PlayerEntity ai, PlayerEntity other,
                                DiplomacyRelationEntity mine, AiPersonality character,
                                Boolean theirsRepulsive,
                                Map<UUID, Integer> might, Random random, Integer turn) {
        // Договор здесь НЕ проверяется вовсе: торговый войне не мешает, а пакт и союз
        // сперва разрывают — это решает ветка выше. Прежде тут стояло «есть хоть какой-то
        // договор — не воюем», и после раскупорки дипломатии (журнал, п. 3.38) торговля с
        // соседом запирала войну наглухо.
        // ПРИЛЁТ ЧУЖОГО ФЛОТА — НЕ ПОВОД К ВОЙНЕ. Знакомство начиналось и кончалось одним
        // ходом: корабль игрока входил в систему соседа, тем самым знакомя империи, а
        // следующая же фаза дипломатии видела нового соседа без договоров, с доверием в
        // половину шкалы, и при перевесе объявляла войну. Со стороны это выглядело так,
        // будто ИИ воюет за один факт появления гостя, — а он просто считал знакомого
        // давним. Теперь у знакомства есть срок: пока он не вышел, воевать не с кем, —
        // и это правило оригинала, где сосед сперва присматривается.
        if (turn - mine.getMetTurn() < ACQUAINTANCE_BEFORE_WAR) {
            return Boolean.FALSE;
        }
        // Отталкивающего не щадит и благородный: дружбы с тем, с кем не договариваются, не
        // бывает — трудно звать другом того, кто не разговаривает.
        if (Boolean.TRUE.equals(character.sparesFriends()) && mine.getTrust() >= FRIENDLY_TRUST
                && !Boolean.TRUE.equals(theirsRepulsive)) {
            return Boolean.FALSE;
        }
        if (character == AiPersonality.ERRATIC && random.nextBoolean()) {
            return Boolean.FALSE;
        }
        // И нападают на него охотнее — вторая половина стороны (см. REPULSIVE_WAR_DISCOUNT).
        Integer advantage = character.getWarAdvantagePercent();
        if (Boolean.TRUE.equals(theirsRepulsive)) {
            advantage = Math.max(RUTHLESS_WAR_ADVANTAGE, advantage - REPULSIVE_WAR_DISCOUNT);
        }
        return stronger(might, ai, other, advantage);
    }

    /** Готов ли ИИ добить соседа в агонии: знакомство не вчерашнее, а благородный щадит друга. */
    private Boolean readyToFinish(PlayerEntity ai, PlayerEntity other, DiplomacyRelationEntity mine,
                                  AiPersonality character, Boolean theirsRepulsive, Integer turn) {
        if (turn - mine.getMetTurn() < ACQUAINTANCE_BEFORE_WAR) {
            return Boolean.FALSE;
        }
        return !(Boolean.TRUE.equals(character.sparesFriends()) && mine.getTrust() >= FRIENDLY_TRUST
                && !Boolean.TRUE.equals(theirsRepulsive));
    }

    /**
     * Лидер и его блок — пункт 151: империя, которая сильнее двух ближайших к ней империй
     * вместе; ближайшие двое — ядро союза, следующие двое — помощники. Подходят несколько —
     * берётся сильнейшая. Близость — между родными звёздами, равные расстояния решает место.
     */
    private Bloc bloc(TurnContext context, Map<UUID, Integer> might) {
        Map<UUID, StarSystemEntity> systems = context.systems().stream()
                .collect(Collectors.toMap(StarSystemEntity::getId, Function.identity()));
        List<PlayerEntity> alive = context.players().stream()
                .filter(player -> might.getOrDefault(player.getId(), 0) > 0)
                .filter(player -> player.getHomeSystemId() != null && systems.containsKey(player.getHomeSystemId()))
                .sorted(Comparator.comparing(PlayerEntity::getSlot))
                .toList();
        Bloc best = null;
        Integer bestMight = 0;
        for (PlayerEntity candidate : alive) {
            StarSystemEntity home = systems.get(candidate.getHomeSystemId());
            List<PlayerEntity> nearest = alive.stream()
                    .filter(other -> !other.getId().equals(candidate.getId()))
                    .sorted(Comparator.comparingDouble((PlayerEntity other) -> distance(home,
                                    systems.get(other.getHomeSystemId())))
                            .thenComparing(PlayerEntity::getSlot))
                    .toList();
            if (nearest.size() < 2) {
                continue;
            }
            Integer theirs = might.getOrDefault(candidate.getId(), 0);
            Integer pair = might.getOrDefault(nearest.get(0).getId(), 0)
                    + might.getOrDefault(nearest.get(1).getId(), 0);
            if (theirs > pair && theirs > bestMight) {
                bestMight = theirs;
                best = new Bloc(candidate.getId(),
                        nearest.subList(0, 2).stream().map(PlayerEntity::getId).toList(),
                        nearest.subList(2, Math.min(4, nearest.size())).stream().map(PlayerEntity::getId).toList());
            }
        }
        return best;
    }

    private static double distance(StarSystemEntity a, StarSystemEntity b) {
        return Math.hypot(a.getXParsec() - b.getXParsec(), a.getYParsec() - b.getYParsec());
    }

    /**
     * Что делает блок против лидера за ход — пункт 151. Действуют только империи ИИ; человек в
     * ядре или среди помощников получает предложения и помощь, а решает сам.
     */
    private void balanceOfPower(TurnContext context, Bloc bloc, Map<UUID, PlayerEntity> byId,
                                Map<UUID, List<DiplomacyRelationEntity>> relationsByPlayer,
                                Map<UUID, Set<String>> technologies,
                                Map<String, ResearchCatalog.TechnologyPlace> places,
                                Map<UUID, Integer> might) {
        PlayerEntity leader = byId.get(bloc.leader());
        for (PlayerEntity ai : context.players().stream().sorted(Comparator.comparing(PlayerEntity::getSlot)).toList()) {
            if (ai.getPlayerType() != PlayerType.AI || ai.getAiPersonality() == null
                    || ai.getId().equals(bloc.leader())) {
                continue;
            }
            // Коалиция против лидера — самое «умное» в дипломатии ИИ, и на лёгких уровнях её нет
            // (field-backlog, пункт 9): соседи не сговариваются, каждый играет сам за себя.
            if (!Boolean.TRUE.equals(com.sddnw.server.domain.enums.AiSkill.of(ai.getAiSkill()).getCoalitions())) {
                continue;
            }
            List<DiplomacyRelationEntity> relations = relationsByPlayer.getOrDefault(ai.getId(), List.of());
            DiplomacyRelationEntity towardLeader = relationTo(relations, bloc.leader());
            if (bloc.core().contains(ai.getId())) {
                coreTurn(context, ai, bloc, byId, relations, towardLeader, leader, might);
            } else if (bloc.backers().contains(ai.getId())) {
                backerTurn(context, ai, bloc, byId, relations, technologies, places);
            }
            // Осторожная враждебность к лидеру — у всех, кроме самого лидера.
            if (towardLeader != null && towardLeader.getTrust() > CAUTIOUS_TRUST) {
                towardLeader.setTrust(CAUTIOUS_TRUST);
            }
        }
    }

    /**
     * Ход соседа лидера: сблизиться с напарником (общий враг поднимает доверие), помириться с
     * ним, заключить союз — и, когда перевес двоих достаточен, объявить лидеру войну. Перевес
     * нужен вдвое меньший, чем характеру в одиночку (реконструкция): лидер воюет на два фронта.
     * За ход — одно действие, по порядку важности.
     */
    private void coreTurn(TurnContext context, PlayerEntity ai, Bloc bloc, Map<UUID, PlayerEntity> byId,
                          List<DiplomacyRelationEntity> relations, DiplomacyRelationEntity towardLeader,
                          PlayerEntity leader, Map<UUID, Integer> might) {
        UUID partnerId = bloc.core().stream().filter(id -> !id.equals(ai.getId())).findFirst().orElse(null);
        DiplomacyRelationEntity towardPartner = partnerId == null ? null : relationTo(relations, partnerId);
        PlayerEntity partner = partnerId == null ? null : byId.get(partnerId);
        if (towardPartner != null && towardPartner.getTrust() < COMMON_ENEMY_TRUST_CAP) {
            towardPartner.setTrust(Math.min(COMMON_ENEMY_TRUST_CAP, towardPartner.getTrust() + COMMON_ENEMY_TRUST));
        }
        if (towardPartner != null && partner != null) {
            if (towardPartner.getStance() == DiplomacyStance.WAR) {
                act(context, ai, partner, DiplomacyAction.PROPOSE_PEACE, null, null, null);
                return;
            }
            if (!towardPartner.getTreaties().contains(DiplomacyTreaty.ALLIANCE)
                    && towardPartner.getTrust() >= DiplomacyTreaty.ALLIANCE.getRequiredTrust()) {
                act(context, ai, partner, DiplomacyAction.PROPOSE_TREATY, DiplomacyTreaty.ALLIANCE, null, null);
                return;
            }
        }
        if (towardLeader == null || leader == null || towardLeader.getStance() == DiplomacyStance.WAR
                || context.turn() - towardLeader.getMetTurn() < ACQUAINTANCE_BEFORE_WAR
                // Тихая галактика (пункт 186): блок против лидера сближается, но войну не начинает.
                || Boolean.TRUE.equals(context.game().getPeacefulAi())) {
            return;
        }
        Integer coalition = bloc.core().stream().mapToInt(id -> might.getOrDefault(id, 0)).sum();
        Integer theirs = might.getOrDefault(leader.getId(), 0);
        if (coalition * 100 < theirs * ai.getAiPersonality().getWarAdvantagePercent() / 2) {
            return;
        }
        DiplomacyTreaty binding = binding(towardLeader);
        if (binding != null) {
            act(context, ai, leader, DiplomacyAction.BREAK_TREATY, binding, null, null);
            return;
        }
        act(context, ai, leader, DiplomacyAction.DECLARE_WAR, null, null, null);
    }

    /**
     * Ход помощника союза: через ход — деньгами, через ход — технологией для войны (лучи, ракеты и
     * броня, щиты), самой дорогой из тех, что у помощника есть, а у получателя нет. Получатель —
     * слабейший из ядра, с кем помощник знаком.
     */
    private void backerTurn(TurnContext context, PlayerEntity ai, Bloc bloc, Map<UUID, PlayerEntity> byId,
                            List<DiplomacyRelationEntity> relations, Map<UUID, Set<String>> technologies,
                            Map<String, ResearchCatalog.TechnologyPlace> places) {
        Random random = new Random(context.game().getSeed() * 43L + context.turn() * 47L + ai.getSlot());
        if (random.nextInt(100) >= BACKER_CHANCE_PERCENT) {
            return;
        }
        PlayerEntity recipient = bloc.core().stream()
                .filter(id -> relationTo(relations, id) != null)
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparing(PlayerEntity::getCredits).thenComparing(PlayerEntity::getSlot))
                .orElse(null);
        if (recipient == null) {
            return;
        }
        if (context.turn() % 2 == 0 && ai.getCredits() > 0) {
            act(context, ai, recipient, DiplomacyAction.GIFT_CREDITS, null, null, giftSize(ai));
            return;
        }
        Set<String> theirs = technologies.getOrDefault(recipient.getId(), Set.of());
        String gift = technologies.getOrDefault(ai.getId(), Set.of()).stream()
                .filter(code -> !theirs.contains(code))
                .map(places::get)
                .filter(java.util.Objects::nonNull)
                .filter(place -> WAR_SECTIONS.contains(place.categoryCode()))
                .max(Comparator.comparing(ResearchCatalog.TechnologyPlace::levelCost)
                        .thenComparing(place -> place.option().code()))
                .map(place -> place.option().code())
                .orElse(null);
        if (gift != null) {
            act(context, ai, recipient, DiplomacyAction.GIFT_TECH, null, gift, null);
        } else if (ai.getCredits() > 0) {
            act(context, ai, recipient, DiplomacyAction.GIFT_CREDITS, null, null, giftSize(ai));
        }
    }

    private static DiplomacyRelationEntity relationTo(List<DiplomacyRelationEntity> relations, UUID other) {
        return relations.stream().filter(one -> one.getOtherPlayerId().equals(other)).findFirst().orElse(null);
    }

    /** Действие блока — тем же путём, что у игрока; отказ — обычный ответ, а не поломка хода. */
    private void act(TurnContext context, PlayerEntity ai, PlayerEntity other, DiplomacyAction action,
                     DiplomacyTreaty treaty, String offeredTech, Integer credits) {
        DiplomacyActionRequest request = new DiplomacyActionRequest(ai.getAccessToken(), other.getId(), action,
                treaty, offeredTech, null, credits);
        try {
            diplomacyService.act(ai, request, context.turn());
            log.debug("Блок против лидера: ИИ {} → {}: {}", ai.getName(), other.getName(), action);
        } catch (ConflictException conflict) {
            log.debug("Блок против лидера: ИИ {} не смог {} с {}: {}", ai.getName(), action, other.getName(),
                    conflict.getMessage());
        }
    }

    /**
     * Сильнее ли ИИ соседа во столько-то процентов от его мощи.
     * <p>
     * Мощь — сводная величина {@link EmpireMightRules}: флот, население, выработка и
     * изученное. По одному флоту сравнивать нельзя — кораблей империи ИИ пока не строят,
     * и всякая война оказалась бы навсегда невозможной: ноль не больше нуля.
     */
    private Boolean stronger(Map<UUID, Integer> might, PlayerEntity ai, PlayerEntity other,
                             Integer percent) {
        Integer theirs = might.getOrDefault(other.getId(), 0);
        // У соседа без единой колонии и корабля перевес есть у любого, у кого хоть что-то есть.
        if (theirs <= 0) {
            return might.getOrDefault(ai.getId(), 0) > 0;
        }
        return might.getOrDefault(ai.getId(), 0) * 100 >= theirs * percent;
    }

    /** Заметно ли ИИ слабее соседа — тогда он и покупает спокойствие подарком. */
    private Boolean weaker(Map<UUID, Integer> might, PlayerEntity ai, PlayerEntity other) {
        Integer theirs = might.getOrDefault(other.getId(), 0);
        return theirs > 0
                && might.getOrDefault(ai.getId(), 0) * 100 <= theirs * GIFT_WEAKNESS_PERCENT;
    }

    /** Проигрывает ли ИИ эту войну: сосед вдвое сильнее. */
    private Boolean losing(Map<UUID, Integer> might, PlayerEntity ai, PlayerEntity other) {
        return weaker(might, ai, other);
    }

    /**
     * Исполняет решение тем же путём, которым ходит игрок, — {@link DiplomacyService#act}.
     * <p>
     * Отказ соседа это обычный ответ, а не ошибка: ИИ предложил, ему не согласились, доверие
     * подвинулось. А вот конфликт правил (договор запрещает войну, дарить нечего) означает,
     * что решение было невозможным, — такой ход просто пропускается: ронять из-за него
     * пересчёт всей галактики нельзя.
     */
    private void perform(TurnContext context, PlayerEntity ai, PlayerEntity other,
                         DiplomacyRelationEntity mine, DiplomacyAction action,
                         Exchange trade) {
        DiplomacyActionRequest request = new DiplomacyActionRequest(
                ai.getAccessToken(),
                other.getId(),
                action,
                action == DiplomacyAction.PROPOSE_TREATY ? bestTreaty(mine)
                        : action == DiplomacyAction.BREAK_TREATY ? binding(mine) : null,
                action == DiplomacyAction.EXCHANGE_TECH ? trade.offered() : null,
                action == DiplomacyAction.EXCHANGE_TECH ? trade.requested() : null,
                action == DiplomacyAction.GIFT_CREDITS ? giftSize(ai) : null);
        try {
            diplomacyService.act(ai, request, context.turn());
            log.debug("ИИ {} ({}) обратился к {}: {}", ai.getName(),
                    ai.getAiPersonality().getLabel(), other.getName(), action);
        } catch (ConflictException conflict) {
            log.debug("ИИ {} не смог {} с {}: {}", ai.getName(), action, other.getName(),
                    conflict.getMessage());
        }
    }

    /** Сделка обмена: что отдаём и что просим взамен — коды технологий, п. 15. */
    private record Exchange(String offered, String requested) {
    }

    /**
     * Что выменять у соседа — п. 15; {@code null} — меняться нечем или не на что.
     * <p>
     * <b>Правило торга списано с человека:</b> просим самое дорогое, что у соседа есть, а у
     * нас нет, и платим за него САМЫМ ДЕШЁВЫМ из того, что сосед возьмёт. Согласие считает
     * сам {@link DiplomacyService#acceptsExchange} — доверие соседа и правило оригинала
     * «отдать не дешевле, чем получить»; второго свода правил торговли у ИИ нет, и
     * предлагать заведомо невозможное он не должен: отказ стоит доверия.
     * <p>
     * <b>Порядок перебора задан явно</b> — по цене уровня, а при равной цене по коду.
     * Изученное приходит множеством, а у множества порядка нет: без явной сортировки та же
     * партия на том же зерне выменивала бы то одну технологию, то другую, и парные прогоны
     * балансировки сравнивали бы не расы, а разную удачу.
     */
    private Exchange exchange(PlayerEntity ai, DiplomacyRelationEntity theirs,
                              Map<String, ResearchCatalog.TechnologyPlace> places,
                              Set<String> ours, Set<String> yours) {
        if (theirs == null || ours.isEmpty() || yours.isEmpty()) {
            return null;
        }
        Comparator<ResearchCatalog.TechnologyPlace> cheapestFirst =
                Comparator.<ResearchCatalog.TechnologyPlace, Integer>comparing(
                                ResearchCatalog.TechnologyPlace::levelCost)
                        .thenComparing(place -> place.option().code());
        List<ResearchCatalog.TechnologyPlace> wanted = yours.stream()
                .filter(code -> !ours.contains(code))
                .map(places::get)
                .filter(place -> place != null)
                .sorted(cheapestFirst.reversed())
                .toList();
        List<ResearchCatalog.TechnologyPlace> payment = ours.stream()
                .filter(code -> !yours.contains(code))
                .map(places::get)
                .filter(place -> place != null)
                .sorted(cheapestFirst)
                .toList();
        for (ResearchCatalog.TechnologyPlace ask : wanted) {
            for (ResearchCatalog.TechnologyPlace give : payment) {
                if (Boolean.TRUE.equals(diplomacyService.acceptsExchange(ai, theirs, give, ask))) {
                    return new Exchange(give.option().code(), ask.option().code());
                }
            }
        }
        return null;
    }

    /**
     * Какой договор предложить: самый весомый из тех, что сосед потянет по доверию;
     * {@code null} — предлагать нечего (всё подписано или доверия мало даже на торговлю).
     * <p>
     * Договоры перечислены в {@link DiplomacyTreaty} по возрастанию требуемого доверия,
     * поэтому «самый весомый» — последний подходящий. Предлагать союз тому, кто едва
     * терпит, значит получить отказ и уронить доверие ещё ниже.
     */
    private DiplomacyTreaty bestTreaty(DiplomacyRelationEntity mine) {
        DiplomacyTreaty best = null;
        for (DiplomacyTreaty treaty : DiplomacyTreaty.values()) {
            if (mine.getTreaties().contains(treaty) || mine.getTrust() < treaty.getRequiredTrust()) {
                continue;
            }
            // Договор, запрещающий войну, подписывают только с ДРУГОМ — та же отметка, по
            // которой благородный характер щадит друга. Это и было починкой вечного мира:
            // ИИ такой договор не рвёт никогда, и подписанный при первом знакомстве он
            // запирал галактику. Экономические договоры под это правило не подпадают.
            if (Boolean.TRUE.equals(treaty.forbidsWar()) && mine.getTrust() < FRIENDLY_TRUST) {
                continue;
            }
            best = treaty;
        }
        return best;
    }

    /** Какой договор держит ИИ от войны: его и рвут — п. 15. */
    private DiplomacyTreaty binding(DiplomacyRelationEntity mine) {
        return mine.getTreaties().stream()
                .filter(treaty -> Boolean.TRUE.equals(treaty.forbidsWar()))
                .findFirst()
                .orElse(null);
    }

    /** Сколько ИИ отдаёт, покупая спокойствие: пятая часть казны, но хотя бы кредит. */
    private Integer giftSize(PlayerEntity ai) {
        return Math.max(1, ai.getCredits() * GIFT_SHARE_PERCENT / 100);
    }
}
