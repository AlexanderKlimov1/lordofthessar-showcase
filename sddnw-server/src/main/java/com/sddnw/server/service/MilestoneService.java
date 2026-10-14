package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameMilestoneEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.dto.TurnEventDto;
import com.sddnw.server.repository.GameMilestoneRepository;
import com.sddnw.server.repository.GameRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Летопись главных моментов партии — field-backlog, пункт 34.
 * <p>
 * Сцене конца партии (пункт 33) нужны пики: их вспоминают, решая, вернуться ли, а партию,
 * которую можно пересказать, помнят дольше. Отчёт хода живёт один ход, поэтому момент
 * пишется строкой ({@code game_milestone}), когда случается, — и ДВУМЯ дорогами, потому что
 * момент рождается в двух местах:
 * <ul>
 *   <li><b>внутри хода</b> — {@link #fromReport}: отчёт хода разбирается после того, как в него
 *   легла очередь событий игрока, и «главные» события узнаются по КЛЮЧУ словаря ({@link #BY_KEY}).
 *   Своего второго свода событий у летописи нет: что видел игрок в итогах, то и попадает;</li>
 *   <li><b>действием игрока</b> — {@link #note}: колонию основывают, берут десантом и подчиняют
 *   телепатией нажатием, а не ходом, и в отчёт хода эти события у самого игрока не попадают.</li>
 * </ul>
 * Пишется только ЛЮДЯМ: летопись читает сцена конца, а у империи ИИ читателя нет. Заодно
 * балансовые прогоны (там и наблюдатель — ИИ) не платят за неё ни одного запроса.
 * <p>
 * Часть моментов считается один раз — «первое знакомство», «первая победа» ({@link #FIRST_ONLY}):
 * сотая выигранная битва пиком уже не бывает. Взятые и потерянные колонии, совет и конец
 * пишутся каждый раз — их немного, и каждый из них поворот.
 * <p>
 * <b>Та же таблица — хроника партии</b> (field-backlog, пункт 138): к 200-му ходу узнать, КАК
 * всё было, иначе неоткуда. Хроника хранит и то, что пиком не бывает: второе и дальнейшие
 * знакомства и войны, мир, договоры и их разрывы ({@link #CHRONICLE_BY_KEY}), гибель знакомых
 * империй. Такие строки помечены «не пик» ({@code peak = false}): сцена конца их не показывает,
 * «Инфо» → «Хроника» показывает всё. Отбор «заметного» — по ключу события, как и у пиков.
 * Первый корабль нового класса в хронику не входит: строка постройки несёт имя проекта, а не
 * корпус, и узнать класс из отчёта нечем — это точка расширения на одну подстановку.
 */
@Service
public class MilestoneService {

    public static final String CONTACT = "CONTACT";
    public static final String WAR = "WAR";
    public static final String BATTLE_WON = "BATTLE_WON";
    public static final String MONSTER_SLAIN = "MONSTER_SLAIN";
    public static final String COLONY_FOUNDED = "COLONY_FOUNDED";
    public static final String COLONY_TAKEN = "COLONY_TAKEN";
    public static final String COLONY_LOST = "COLONY_LOST";
    public static final String TROPHY = "TROPHY";
    public static final String GUARDIANS = "GUARDIANS";
    public static final String COUNCIL = "COUNCIL";
    public static final String COUNCIL_REFUSED = "COUNCIL_REFUSED";
    public static final String END = "END";
    public static final String CONCEDED = "CONCEDED";
    /** Перелом — field-backlog, пункт 142: стали сильнейшими среди знакомых или потеряли первое место. */
    public static final String LEAD = "LEAD";
    public static final String LEAD_LOST = "LEAD_LOST";
    /** Только хроника (пункт 138): мир, договор и его разрыв, гибель знакомой империи. */
    public static final String PEACE = "PEACE";
    public static final String TREATY = "TREATY";
    public static final String TREATY_BROKEN = "TREATY_BROKEN";
    public static final String EMPIRE_FALLEN = "EMPIRE_FALLEN";
    /** Новая глава партии — field-backlog, пункт 54 ({@code ChapterPhase}). */
    public static final String CHAPTER = "CHAPTER";

    /** Пиком бывает только первый такой момент. */
    static final Set<String> FIRST_ONLY = Set.of(CONTACT, WAR, BATTLE_WON, MONSTER_SLAIN, COLONY_FOUNDED, TROPHY);

    /**
     * Какие события отчёта хода — главные: ключ словаря → род момента. Меняя или заводя ключ
     * такого события, поправь и эту таблицу — иначе момент молча выпадет из летописи.
     */
    static final Map<String, String> BY_KEY = Map.ofEntries(
            Map.entry("turn.diplomacy.met", CONTACT),
            Map.entry("turn.diplomacy.metYou", CONTACT),
            Map.entry("turn.diplomacy.warDeclared", WAR),
            Map.entry("turn.battle.won", BATTLE_WON),
            Map.entry("turn.battle.wonBy", BATTLE_WON),
            Map.entry("turn.battle.wonByBlasts", BATTLE_WON),
            Map.entry("turn.battle.attackedWon", BATTLE_WON),
            Map.entry("turn.battle.attackedWonBy", BATTLE_WON),
            Map.entry("turn.battle.attackedWonByBlasts", BATTLE_WON),
            Map.entry("turn.monster.killed", MONSTER_SLAIN),
            Map.entry("turn.monster.killedBy", MONSTER_SLAIN),
            Map.entry("turn.monster.slain", MONSTER_SLAIN),
            Map.entry("turn.ai.colonyFounded", COLONY_FOUNDED),
            Map.entry("turn.ai.settledByBase", COLONY_FOUNDED),
            Map.entry("turn.ai.invasionWon", COLONY_TAKEN),
            Map.entry("turn.ai.mindControlled", COLONY_TAKEN),
            Map.entry("turn.invasion.colonyLost", COLONY_LOST),
            Map.entry("turn.invasion.colonyLostSubjects", COLONY_LOST),
            Map.entry("turn.invasion.colonyLostBy", COLONY_LOST),
            Map.entry("turn.invasion.colonyLostSubjectsBy", COLONY_LOST),
            Map.entry("turn.mindControl.lost", COLONY_LOST),
            Map.entry("turn.battle.prizesTaken", TROPHY),
            Map.entry("turn.specialStar.claimed", GUARDIANS),
            Map.entry("turn.council.elected", COUNCIL),
            Map.entry("turn.council.refused", COUNCIL_REFUSED),
            Map.entry("turn.turning.first", LEAD),
            Map.entry("turn.turning.lostFirst", LEAD_LOST),
            Map.entry("turn.chapter", CHAPTER),
            Map.entry("turn.victory.won", END),
            Map.entry("turn.victory.lost", END));

    /**
     * Какие главные моменты — УСПЕХИ игрока (field-backlog, пункт 88): итоги хода ставят их своим
     * разделом сразу после угроз. Правило одно на летопись и итоги — список родов моментов, а не
     * второй список ключей: знакомство, война и потери главные, но не успехи.
     */
    static final Set<String> SUCCESS_KINDS = Set.of(BATTLE_WON, MONSTER_SLAIN, COLONY_FOUNDED, COLONY_TAKEN,
            TROPHY, GUARDIANS, LEAD);

    /** Успех ли это событие отчёта — по его ключу; победа в партии — успех, поражение — нет. */
    public static Boolean success(String key) {
        if (key == null) {
            return Boolean.FALSE;
        }
        // Неглавного события в BY_KEY нет, а Set.of не принимает null даже в contains.
        String kind = BY_KEY.get(key);
        return "turn.victory.won".equals(key) || (kind != null && SUCCESS_KINDS.contains(kind));
    }

    /**
     * События, которые идут только в хронику и пиком не бывают никогда (пункт 138). Ключи,
     * как и у {@link #BY_KEY}, держит {@code MilestoneKeysTest}.
     */
    static final Map<String, String> CHRONICLE_BY_KEY = Map.of(
            "turn.diplomacy.peaceMade", PEACE,
            "turn.diplomacy.treatySigned", TREATY,
            "turn.diplomacy.treatyBroken", TREATY_BROKEN);

    private final GameMilestoneRepository repository;
    private final GameRepository gameRepository;

    public MilestoneService(GameMilestoneRepository repository, GameRepository gameRepository) {
        this.repository = repository;
        this.gameRepository = gameRepository;
    }

    /**
     * Главные события посчитанного хода — в летопись. Зовётся {@code TurnService} после того,
     * как очередь событий игрока легла в отчёт, и до перехода на следующий ход.
     * <p>
     * Запрос в базу — один на ход и только когда есть что записать: выборка изнутри
     * посчитанного хода дорога (правила проекта, грабли), а главных событий за ход обычно ноль.
     */
    public void fromReport(TurnContext context) {
        List<GameMilestoneEntity> fresh = new ArrayList<>();
        for (PlayerEntity player : context.players()) {
            if (player.getPlayerType() != PlayerType.HUMAN) {
                continue;
            }
            for (TurnEventDto event : context.report().events(player.getId())) {
                if (event.key() == null) {
                    continue;
                }
                String kind = BY_KEY.get(event.key());
                if (kind != null) {
                    fresh.add(row(context.game().getId(), player.getId(), context.turn(), kind,
                            event.key(), event.args()));
                }
                String chronicle = CHRONICLE_BY_KEY.get(event.key());
                if (chronicle != null) {
                    GameMilestoneEntity row = row(context.game().getId(), player.getId(), context.turn(),
                            chronicle, event.key(), event.args());
                    row.setPeak(Boolean.FALSE);
                    fresh.add(row);
                }
            }
        }
        List<PlayerEntity> fallen = fallen(context);
        if (fresh.isEmpty() && fallen.isEmpty()) {
            return;
        }
        List<GameMilestoneEntity> before = repository.findAllByGameIdOrderByTurnAscPlayerIdAscKindAsc(context.game().getId());
        Set<String> had = new HashSet<>();
        for (GameMilestoneEntity row : before) {
            had.add(row.getPlayerId() + "/" + row.getKind());
        }
        List<GameMilestoneEntity> kept = new ArrayList<>();
        for (GameMilestoneEntity row : fresh) {
            String mark = row.getPlayerId() + "/" + row.getKind();
            // Повторное «первое» — уже не пик, но в хронике остаётся (пункт 138).
            if (FIRST_ONLY.contains(row.getKind()) && !had.add(mark)) {
                row.setPeak(Boolean.FALSE);
            }
            had.add(mark);
            kept.add(row);
        }
        kept.addAll(fallenRows(context, fallen, before, kept));
        repository.saveAll(kept);
    }

    /**
     * Империи без единой колонии — п. 3: флот без колоний империей не считается
     * ({@code VictoryService}), восстановиться ему негде. Колонии ход уже держит в памяти.
     */
    private static List<PlayerEntity> fallen(TurnContext context) {
        Set<UUID> owners = new HashSet<>();
        for (var planet : context.colonies()) {
            if (planet.getOwnerPlayerId() != null) {
                owners.add(planet.getOwnerPlayerId());
            }
        }
        return context.players().stream()
                .filter(player -> player.getPlayerType() != PlayerType.MONSTER)
                .filter(player -> !owners.contains(player.getId()))
                .toList();
    }

    /**
     * Гибель империи — в хронику тем, кто её знал (пункт 138), один раз. «Знал» — в хронике
     * читателя есть знакомство с ней: империя, которой игрок не встречал, для него и не жила.
     * Строки знакомств, писанные до хроники, хранят только первое знакомство — у старых партий
     * гибель прочих соседей пройдёт мимо, и это честнее выдуманного знакомства.
     */
    private static List<GameMilestoneEntity> fallenRows(TurnContext context, List<PlayerEntity> fallen,
                                                         List<GameMilestoneEntity> before,
                                                         List<GameMilestoneEntity> fresh) {
        if (fallen.isEmpty()) {
            return List.of();
        }
        Set<String> met = new HashSet<>();
        Set<String> mourned = new HashSet<>();
        for (List<GameMilestoneEntity> rows : List.of(before, fresh)) {
            for (GameMilestoneEntity row : rows) {
                String first = row.getArgs() == null || row.getArgs().isEmpty() ? "" : row.getArgs().get(0);
                if (CONTACT.equals(row.getKind())) {
                    met.add(row.getPlayerId() + "/" + first);
                } else if (EMPIRE_FALLEN.equals(row.getKind())) {
                    mourned.add(row.getPlayerId() + "/" + first);
                }
            }
        }
        List<GameMilestoneEntity> rows = new ArrayList<>();
        for (PlayerEntity reader : context.players()) {
            if (reader.getPlayerType() != PlayerType.HUMAN) {
                continue;
            }
            for (PlayerEntity gone : fallen) {
                String mark = reader.getId() + "/" + gone.getName();
                if (gone.getId().equals(reader.getId()) || !met.contains(mark) || !mourned.add(mark)) {
                    continue;
                }
                GameMilestoneEntity row = row(context.game().getId(), reader.getId(), context.turn(),
                        EMPIRE_FALLEN, "milestone.empireFallen", List.of(gone.getName()));
                row.setPeak(Boolean.FALSE);
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Момент, случившийся по нажатию игрока: основал колонию, взял её десантом, подчинил,
     * признал поражение. Ход — если вызывающий его знает; нет — берётся у партии
     * (у игрока ссылка на партию ленивая, и спрашивать ход у неё нельзя — правила проекта).
     */
    public void note(UUID gameId, PlayerEntity player, Integer turn, String kind, MessageKey message) {
        if (player == null || player.getPlayerType() != PlayerType.HUMAN) {
            return;
        }
        // Повторное «первое» — не пик, но в хронике остаётся (пункт 138).
        boolean repeat = FIRST_ONLY.contains(kind)
                && Boolean.TRUE.equals(repository.existsByPlayerIdAndKind(player.getId(), kind));
        Integer when = turn != null ? turn
                : gameRepository.findById(gameId).map(game -> game.getTurn()).orElse(0);
        GameMilestoneEntity row = row(gameId, player.getId(), when, kind, message.key(), message.storedArgs());
        row.setPeak(!repeat);
        repository.save(row);
    }

    /** Летопись одного игрока по порядку ходов — сцене конца (пики) и хронике «Инфо» (всё). */
    public List<GameMilestoneEntity> chronicle(UUID playerId) {
        return repository.findAllByPlayerIdOrderByTurnAsc(playerId);
    }

    private static GameMilestoneEntity row(UUID gameId, UUID playerId, Integer turn, String kind,
                                           String key, List<String> args) {
        GameMilestoneEntity row = new GameMilestoneEntity();
        row.setGameId(gameId);
        row.setPlayerId(playerId);
        row.setTurn(turn);
        row.setKind(kind);
        row.setMessageKey(key);
        row.setArgs(args == null ? List.of() : List.copyOf(args));
        row.setPeak(Boolean.TRUE);
        return row;
    }
}
