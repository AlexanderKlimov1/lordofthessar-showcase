package com.sddnw.server.service;

import com.sddnw.server.domain.enums.GalaxySize;
import com.sddnw.server.dto.AdvanceTurnsRequest;
import com.sddnw.server.dto.CreateGameRequest;
import com.sddnw.server.dto.CreateGameResponse;
import com.sddnw.server.dto.StartGameRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Прогрев сервера после старта (12.10.2026, замер хозяина проекта: «ход должен передаваться почти
 * мгновенно»). Только что поднятая JVM исполняет код хода по байт-коду, и первые ходы партии
 * считались вдвое-втрое дольше (около секунды против трёх десятых на прогретом сервере) — ровно
 * когда игрок только сел за стол. Поэтому сервер сам играет короткую партию ИИ тем же концом
 * хода, что и у игрока, и удаляет её: к первому ходу человека горячие пути уже скомпилированы.
 * <p>
 * Идёт в своём потоке и не держит старт: сервер принимает запросы сразу, а прогрев лишь
 * ускоряет их. Сбой прогрева — строка журнала, а не упавший сервер. Партия заводится без
 * хозяина и без людей — её не видно ни в списках, ни в истории, она не автосохраняется.
 * Выключается {@code sddnw.warmup.enabled=false} — для прогонов, где лишняя партия при старте
 * мешала бы замеру.
 */
@Component
public class ServerWarmup {

    private static final Logger log = LoggerFactory.getLogger(ServerWarmup.class);

    /** Ходов прогрева: столько хватает, чтобы фазы хода прошли все ветки раннего хода по разу. */
    static final int TURNS = 12;

    private final GameService gameService;
    private final TurnBatchService turnBatchService;
    private final boolean enabled;

    public ServerWarmup(GameService gameService, TurnBatchService turnBatchService,
                        @Value("${sddnw.warmup.enabled:true}") boolean enabled) {
        this.gameService = gameService;
        this.turnBatchService = turnBatchService;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!enabled) {
            return;
        }
        Thread thread = new Thread(this::play, "warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private void play() {
        long started = System.nanoTime();
        UUID gameId = null;
        String token = null;
        try {
            CreateGameResponse created = gameService.createGame(new CreateGameRequest(
                    "Прогрев сервера", GalaxySize.SMALL, "Прогрев", null, null, null, null,
                    20261012L,
                    // События и ИИ включены: прогревается тот же ход, что у игрока.
                    Boolean.TRUE, Boolean.TRUE, 4,
                    // Совет, удержание Тессара и итог по могуществу выключены: партия обязана
                    // доиграть свои ходы, а не кончиться победой.
                    Boolean.FALSE, Boolean.TRUE, null, Boolean.FALSE, Boolean.FALSE, null,
                    null, null, null, null, null, null),
                    null);
            gameId = created.game().id();
            token = created.credentials().accessToken();
            gameService.startGame(gameId, new StartGameRequest(token, List.of()));
            turnBatchService.advance(gameId, new AdvanceTurnsRequest(token, TURNS));
            log.info("Сервер прогрет: {} ходов партии ИИ за {} мс", TURNS,
                    (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("Прогрев сервера не удался: {}", e.toString());
        } finally {
            if (gameId != null) {
                try {
                    gameService.deleteGame(gameId, token);
                } catch (RuntimeException e) {
                    log.warn("Партия прогрева {} не удалена: {}", gameId, e.toString());
                }
            }
        }
    }
}
