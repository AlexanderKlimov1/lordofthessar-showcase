package com.sddnw.server.service.stub;

import com.sddnw.server.service.GameEventPublisher;
import com.sddnw.server.service.GameOrder;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.StarSystemEntity;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.StarSystemRepository;
import com.sddnw.server.service.ColonyService;
import com.sddnw.server.service.MilestoneService;
import com.sddnw.server.service.PlayerEventService;
import com.sddnw.server.service.TurnContext;
import com.sddnw.server.service.TurnReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Конец хода: прогон зарегистрированных фаз и переход на следующий ход.
 * <p>
 * Фазы идут по {@link TurnPhase#order()} и работают с общим {@link TurnContext}: галактика
 * и контекст колоний вычитываются один раз на весь ход, а не каждой фазой заново.
 * <p>
 * Сервис считает ход целиком и ничего не знает про то, кто его объявил: очередь игроков
 * ведёт {@code GameService} — ход считается, когда закончили все люди партии (п. 11.1).
 */
@Service
public class TurnService {

    private static final Logger log = LoggerFactory.getLogger(TurnService.class);

    private final List<TurnPhase> phases;
    private final StarSystemRepository starSystemRepository;
    private final PlayerRepository playerRepository;
    private final ColonyService colonyService;
    private final PlayerEventService playerEvents;
    private final MilestoneService milestones;
    private final GameEventPublisher eventPublisher;

    /**
     * С какого мига хода показывать его этапы — field-backlog, пункт 100: ход короче доли
     * секунды этапов не показывает вовсе, иначе слова мелькали бы быстрее, чем их прочтёшь.
     */
    static final long PHASES_SHOWN_AFTER_NANOS = 300_000_000L;

    public TurnService(List<TurnPhase> phases,
                       StarSystemRepository starSystemRepository,
                       PlayerRepository playerRepository,
                       ColonyService colonyService,
                       PlayerEventService playerEvents,
                       MilestoneService milestones,
                       GameEventPublisher eventPublisher) {
        this.milestones = milestones;
        this.eventPublisher = eventPublisher;
        this.phases = phases.stream()
                .sorted(Comparator.comparing(TurnPhase::order))
                .toList();
        this.starSystemRepository = starSystemRepository;
        this.playerRepository = playerRepository;
        this.colonyService = colonyService;
        this.playerEvents = playerEvents;
    }

    /**
     * Считает ход целиком и переводит партию на следующий.
     *
     * @return что изменилось за ход у каждого игрока
     */
    public TurnReport endTurn(GameEntity game) {
        Map<String, Long> thisTurn = new LinkedHashMap<>();
        long readStarted = System.nanoTime();
        TurnContext context = context(game);
        record(CONTEXT, System.nanoTime() - readStarted, thisTurn);

        for (TurnPhase phase : phases) {
            log.debug("Фаза конца хода: {}", phase.name());
            long started = System.nanoTime();
            // Работа конца хода — на виду (пункт 100): настоящая фаза, а не изображение работы.
            if (started - readStarted >= PHASES_SHOWN_AFTER_NANOS) {
                eventPublisher.turnPhase(game, phase.getClass().getSimpleName());
            }
            phase.apply(context);
            record(phase.name(), System.nanoTime() - started, thisTurn);
        }
        synchronized (lastTurnByGame) {
            lastTurnByGame.put(game.getId(), thisTurn);
        }
        if (turnsCounted.incrementAndGet() % PROFILE_EVERY == 0) {
            logProfile();
        }

        // То, что случилось с игроками внутри хода — чужая война, знакомство, кража,
        // захват колонии, — копилось строками и теперь ложится в те же итоги хода.
        // Иначе события соседа игрок не увидел бы вовсе: они происходят не по его нажатию.
        playerEvents.drainInto(game.getId(), context.report());
        // Главные моменты хода — в летопись партии (field-backlog, пункт 34): после очереди
        // событий, потому что знакомство, война и потерянная колония приходят именно оттуда.
        milestones.fromReport(context);

        game.setTurn(game.getTurn() + 1);
        return context.report();
    }

    /** Одна выборка на весь ход: галактика, игроки и контекст колоний. */
    private TurnContext context(GameEntity game) {
        List<StarSystemEntity> systems = starSystemRepository
                .findAllByGameIdWithPlanets(game.getId()).stream()
                .sorted(GameOrder.SYSTEMS)
                .toList();
        List<PlanetEntity> colonies = systems.stream()
                .flatMap(system -> system.getPlanets().stream())
                .filter(planet -> planet.getOwnerPlayerId() != null && planet.getPopulation() > 0)
                .toList();
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId());

        return new TurnContext(
                game,
                players,
                systems,
                colonies,
                colonyService.context(colonies),
                new TurnReport(game.getTurn()));
    }

    /**
     * Фазы ПОСЛЕДНЕГО посчитанного хода партии, в миллисекундах, в порядке хода — field-backlog,
     * пункт 166: проверка бюджета хода ({@code tools/turn_budget.py}) называет провал разбивкой
     * по фазам, а сводка раз в пятьсот ходов смешивает все партии сервера. Пусто — ход этой
     * партии с запуска сервера не считался.
     */
    public Map<String, Double> lastTurnProfile(UUID gameId) {
        Map<String, Long> row;
        synchronized (lastTurnByGame) {
            row = lastTurnByGame.get(gameId);
        }
        Map<String, Double> millis = new LinkedHashMap<>();
        if (row != null) {
            row.forEach((phase, nanos) -> millis.put(phase, nanos / 1_000_000.0));
        }
        return millis;
    }

    public List<String> registeredPhases() {
        return phases.stream().map(TurnPhase::name).toList();
    }

    /** Строка замера для выборки галактики — она не фаза, но времени берёт наравне с ними. */
    private static final String CONTEXT = "(выборка хода)";

    /**
     * Через сколько ходов замер сам ложится в журнал.
     * <p>
     * Не каждый ход: строка на ход утопила бы журнал, а по одному ходу судить всё равно
     * нельзя — фазы дорожают к концу партии (колоний больше, флотов больше). Пятьсот ходов
     * живой игрок не наберёт и за вечер, а балансовый прогон набирает их за секунды — кому
     * замер нужен, тот его и получит.
     */
    private static final long PROFILE_EVERY = 500;

    /**
     * Сколько времени какая фаза съела: имя фазы → [сколько раз, сколько наносекунд].
     * <p>
     * <b>Зачем прибор внутри хода.</b> Конец хода — самое дорогое место игры: сквозной
     * прогон тратит на него 150 секунд из 244, а балансовый прогон не делает почти ничего
     * другого — партия это сотня-другая ходов, и прогон играет их сотнями. Ускорять ход,
     * не зная, какая из девятнадцати фаз его ест, значит гадать; на этом проекте уже
     * дважды выяснялось, что «очевидная» причина медленности была не та.
     * <p>
     * Считается всегда: {@code System.nanoTime} на фазу — это двадцать вызовов на ход
     * против сотни миллисекунд самого хода, то есть тысячные доли процента. Карта общая на
     * все партии сервера и намеренно: прогон играет десять партий разом, и интересна как
     * раз сумма по всем.
     */
    private final Map<String, long[]> spentByPhase = new ConcurrentHashMap<>();

    private final AtomicLong turnsCounted = new AtomicLong();

    /** Сколько партий помнят свой последний ход: память на партию — горсть чисел, но не вечно. */
    private static final int PROFILED_GAMES = 64;

    /** Фазы последнего хода по партиям; старейшая партия вытесняется (порядок доступа). */
    private final Map<UUID, Map<String, Long>> lastTurnByGame =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Map<String, Long>> eldest) {
                    return size() > PROFILED_GAMES;
                }
            };

    private void record(String phase, long nanos, Map<String, Long> thisTurn) {
        thisTurn.merge(phase, nanos, Long::sum);
        long[] row = spentByPhase.computeIfAbsent(phase, one -> new long[2]);
        // Синхронизация по самой строке: ходы считаются в десять потоков, а два числа
        // рядом иначе разъезжаются. Спор за строку тут ничтожен — она держится наносекунды.
        synchronized (row) {
            row[0]++;
            row[1] += nanos;
        }
    }

    /** Замер в журнал: фазы по убыванию съеденного времени. */
    private void logProfile() {
        long total = spentByPhase.values().stream().mapToLong(row -> row[1]).sum();
        StringBuilder line = new StringBuilder();
        spentByPhase.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(one -> {
                    long[] row = one.getValue();
                    line.append(String.format("%n  %6.1f%%  %8.2f мс всего  %6.3f мс/ход  %s",
                            100.0 * row[1] / Math.max(1, total),
                            row[1] / 1_000_000.0,
                            row[1] / 1_000_000.0 / Math.max(1, row[0]),
                            one.getKey()));
                });
        log.info("Замер конца хода: {} ходов, {} мс всего:{}",
                turnsCounted.get(), total / 1_000_000, line);
    }
}
