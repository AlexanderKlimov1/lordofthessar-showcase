package com.sddnw.server.service;

import com.sddnw.server.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Часы хода — backlog-promo, пункт 11: раз в несколько секунд обходят партии, где кто-то
 * ждёт, и дают каждой {@link GameService#tick} — отметить ушедших и посчитать ход, если
 * срок вышел или ждать больше некого.
 * <p>
 * Пять секунд — точность срока: ход в полторы минуты, посчитанный на пять секунд позже,
 * игрок не заметит, а обход чаще только грузил бы базу. Каждая партия — своей
 * транзакцией: сбой одной не должен останавливать часы у остальных.
 */
@Component
public class TurnClock {

    private static final Logger log = LoggerFactory.getLogger(TurnClock.class);

    private final PlayerRepository playerRepository;
    private final GameService gameService;
    private final com.sddnw.server.repository.GameRepository gameRepository;
    private final GameMods gameMods;

    public TurnClock(PlayerRepository playerRepository, GameService gameService,
                     com.sddnw.server.repository.GameRepository gameRepository, GameMods gameMods) {
        this.gameRepository = gameRepository;
        this.gameMods = gameMods;
        this.playerRepository = playerRepository;
        this.gameService = gameService;
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 5_000)
    public void tick() {
        // Столы подбора, чей срок ожидания вышел, — field-backlog, пункт 65.
        for (UUID gameId : gameRepository.findAutoStartDue(java.time.OffsetDateTime.now())) {
            try {
                gameService.autoStart(gameId);
            } catch (RuntimeException e) {
                log.warn("Подбор: стол {} не стартовал: {}", gameId, e.toString());
            }
        }
        for (UUID gameId : playerRepository.findGameIdsWithSomebodyWaiting()) {
            try {
                // Партия на своих справочниках (пункт 42) считается в своей отметке — как и запрос к ней.
                ModScope.run(Boolean.TRUE.equals(gameMods.modded(gameId)) ? gameId : null,
                        () -> gameService.tick(gameId));
            } catch (RuntimeException e) {
                log.warn("Часы хода: партия {} не посчиталась: {}", gameId, e.toString());
            }
        }
    }
}
