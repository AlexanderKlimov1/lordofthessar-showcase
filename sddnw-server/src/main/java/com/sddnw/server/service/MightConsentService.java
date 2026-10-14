package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.UUID;

/**
 * Согласие с итогом по могуществу (09.10.2026, решение хозяина проекта; п. 3, {@link MightVictoryRules}).
 * <p>
 * Итог по могуществу — не победа на поле, а приговор по числам, и навязывать его живым людям за
 * столом нечестно: у отстающего может быть свой план на следующие ходы. Поэтому на сроке партия
 * не кончается сама, а СПРАШИВАЕТ: сильнейшая империя названа, каждый другой человек за столом
 * отвечает «согласен» или «нет» до конца следующего хода. Хоть один отказ — итога нет, путь
 * снимается, и партия идёт дальше иными путями; молчание — согласие, иначе ушедший игрок держал
 * бы партию вечно. Без людей, кроме самого сильнейшего, спрашивать некого — итог как прежде.
 * <p>
 * Ход считается на сервере целиком, поэтому спрос идёт в два хода, как открытый совет
 * ({@link CouncilService}): на сроке записан кандидат ({@code game.might_pending_player_id}), итог
 * подводит {@link VictoryService} в конце следующего хода. Ответ — строкой игрока
 * ({@code player.might_answer}): AGREE или REFUSE.
 */
@Service
public class MightConsentService {

    private static final Logger log = LoggerFactory.getLogger(MightConsentService.class);

    public static final String AGREE = "AGREE";
    public static final String REFUSE = "REFUSE";

    private final GameAccess gameAccess;
    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;

    public MightConsentService(GameAccess gameAccess, GameRepository gameRepository,
                               PlayerRepository playerRepository) {
        this.gameAccess = gameAccess;
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
    }

    /**
     * Кого спрашивают: люди за столом, кроме сильнейшего и признавших поражение. Ушедшего тоже
     * спрашивают — он вправе ответить, вернувшись до конца следующего хода.
     */
    public static boolean asked(PlayerEntity player, UUID candidateId) {
        return player.getPlayerType() == PlayerType.HUMAN
                && player.getConcededTurn() == null
                && !player.getId().equals(candidateId);
    }

    /** Есть ли кого спросить. */
    public static boolean anyoneAsked(Collection<PlayerEntity> players, UUID candidateId) {
        return players.stream().anyMatch(player -> asked(player, candidateId));
    }

    /** Ответ игрока: согласен ли он с итогом. До конца хода ответ можно переменить. */
    @Transactional
    public void answer(UUID gameId, String accessToken, Boolean agree) {
        // Игру до замка не читаем: её версия устарела бы, и своя же запись упала бы на проверке.
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        gameAccess.requireInProgress(game);
        UUID candidate = game.getMightPendingPlayerId();
        if (candidate == null) {
            throw new ConflictException("might.notPending");
        }
        PlayerEntity player = playerRepository.findById(playerId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        if (!asked(player, candidate)) {
            throw new ConflictException("might.notAsked");
        }
        player.setMightAnswer(Boolean.TRUE.equals(agree) ? AGREE : REFUSE);
        playerRepository.save(player);
        log.info("Игрок {} на итог по могуществу ответил {}", player.getName(), player.getMightAnswer());
    }
}
