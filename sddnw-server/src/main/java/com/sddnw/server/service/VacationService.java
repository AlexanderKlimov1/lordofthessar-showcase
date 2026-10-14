package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Отпуск в сетевой партии — field-backlog, пункт 177.
 * <p>
 * Партия по расписанию оправдана, когда за столом люди, но у человека бывает неделя без игры.
 * Прежде отсутствие узнавалось только по пропущенным срокам — три подряд, и ИИ брал империю, а
 * соседи всё это время ждали. Отпуск говорится ЗАРАНЕЕ: на N дней империю ведёт ИИ (тот же
 * опекун, что у ушедшего, — {@link PresenceService#aiDriven}), партия игрока не ждёт и часы не
 * считают ему пропусков, а вернувшийся видит, что ИИ сделал за него (журнал опекуна, пункт 112).
 * <p>
 * Только в партии с другими людьми: одиночная партия и так ждёт игрока сколько угодно и сама
 * не идёт. Вернуться раньше срока — тот же запрос с нулём дней.
 */
@Service
public class VacationService {

    private static final Logger log = LoggerFactory.getLogger(VacationService.class);

    /** Самый долгий отпуск, дней: дольше — это уже не отпуск, а уход из партии. */
    public static final int MAX_DAYS = 14;

    private final GameAccess gameAccess;
    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final PresenceService presence;
    private final PlayerRoster playerRoster;

    public VacationService(GameAccess gameAccess, GameRepository gameRepository,
                           PlayerRepository playerRepository, PresenceService presence,
                           PlayerRoster playerRoster) {
        this.playerRoster = playerRoster;
        this.gameAccess = gameAccess;
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.presence = presence;
    }

    /** Уйти в отпуск на {@code days} дней; ноль — вернуться из отпуска сейчас. */
    @Transactional
    public void vacation(UUID gameId, String accessToken, Integer days) {
        if (days == null || days < 0 || days > MAX_DAYS) {
            throw new BadRequestException("vacation.days", MAX_DAYS);
        }
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameRepository.lockById(gameId)
                .orElseThrow(() -> new NotFoundException("game.notFound", gameId));
        gameAccess.requireInProgress(game);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        PlayerEntity player = players.stream()
                .filter(candidate -> candidate.getId().equals(playerId))
                .findFirst()
                .orElseThrow();
        if (player.getPlayerType() != PlayerType.HUMAN) {
            throw new ConflictException("vacation.notHuman");
        }
        if (days == 0) {
            player.setVacationUntil(null);
            // Вернулся — снова за столом: присутствие отмечено самим запросом.
            presence.act(player.getId());
            playerRepository.save(player);
            log.info("Игра {}: игрок {} вернулся из отпуска", game.getName(), player.getName());
            return;
        }
        boolean othersHuman = players.stream().anyMatch(other -> other != player
                && other.getPlayerType() == PlayerType.HUMAN && other.getConcededTurn() == null);
        if (!othersHuman) {
            throw new ConflictException("vacation.alone");
        }
        player.setVacationUntil(OffsetDateTime.now().plusDays(days));
        // Опекуну нужен характер правителя — тем же правилом, что ушедшему (`GameService.goAway`):
        // по нему ИИ решает встречи флотов и хозяйство, а у человека его нет.
        if (player.getAiPersonality() == null) {
            playerRoster.giveCharacter(player, game, player.getSlot());
        }
        playerRepository.save(player);
        log.info("Игра {}: игрок {} в отпуске на {} дн., до {}", game.getName(), player.getName(), days,
                player.getVacationUntil());
    }
}
