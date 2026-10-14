package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.enums.AccountRole;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.dto.CreateGameRequest;
import com.sddnw.server.dto.CreateGameResponse;
import com.sddnw.server.dto.JoinGameRequest;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Сыграть снова тем же составом — field-backlog, пункт 149.
 * <p>
 * Группа, сложившаяся за партию, распадается в пустом лобби: каждый уходит в меню, и собрать их
 * снова некому. Здесь одно нажатие в сцене конца: первый нажавший заводит новую партию С ТЕМИ ЖЕ
 * НАСТРОЙКАМИ (размер, число империй, пути к победе, события, срок хода) и садится за неё хозяином,
 * у оконченной партии остаётся ссылка, и остальные участники видят в своей сцене конца «войти».
 * Входят они тем же путём, что по приглашению (пункт 61), — гостю тоже можно; заводит же новую
 * только записанный игрок: гостевая партия у гостя одна и с другими настройками.
 * <p>
 * Письмом участников не зовут: это ждёт почты, которая доходит, и согласия (пункт 113).
 */
@Service
public class RematchService {

    private static final Logger log = LoggerFactory.getLogger(RematchService.class);

    private final GameAccess gameAccess;
    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final GameService gameService;
    private final InviteService inviteService;

    public RematchService(GameAccess gameAccess, GameRepository gameRepository, PlayerRepository playerRepository,
                          GameService gameService, InviteService inviteService) {
        this.gameAccess = gameAccess;
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.gameService = gameService;
        this.inviteService = inviteService;
    }

    /**
     * Завести новую партию тем же составом или войти в уже заведённую. Оконченная партия — только
     * её участнику и только когда за столом были другие люди.
     */
    @Transactional
    public CreateGameResponse rematch(UUID gameId, AccountEntity account) {
        GameEntity game = gameAccess.requireGame(gameId);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        PlayerEntity self = players.stream().filter(player -> account.getId().equals(player.getAccountId()))
                .findFirst().orElseThrow(() -> new ForbiddenException("game.notYourGame"));
        if (game.getStatus() != GameStatus.FINISHED) {
            throw new ConflictException("rematch.notFinished");
        }
        if (players.stream().filter(player -> player.getAccountId() != null).count() < 2) {
            throw new ConflictException("rematch.alone");
        }
        if (game.getRematchGameId() != null) {
            GameEntity next = gameRepository.findById(game.getRematchGameId()).orElse(null);
            if (next != null && next.getStatus() == GameStatus.LOBBY && next.getInviteCode() != null) {
                return inviteService.join(next.getInviteCode(), account,
                        new JoinGameRequest(self.getName(), null, self.getRaceCode(), null, null, null, null, null));
            }
        }
        if (account.getRole() == AccountRole.GUEST) {
            throw new ConflictException("rematch.guest");
        }
        CreateGameRequest request = new CreateGameRequest(null, game.getGalaxySize(), self.getName(), null,
                self.getRaceCode(), null, null, null, game.getGalacticEvents(), Boolean.FALSE, game.getTotalPlayers(),
                game.getCouncil(), game.getAiActive(), null, game.getWardenholdVictory(), game.getMightVictory(),
                game.getTurnSeconds(), null, null, null, game.getSpectators(), game.getGalaxyFeature() == null ? com.sddnw.server.domain.enums.GalaxyFeature.NONE : game.getGalaxyFeature(), null,
                game.getAiSkill());
        CreateGameResponse created = gameService.createGame(request, account.getId(), Boolean.FALSE);
        inviteService.code(created.game().id(), created.credentials().accessToken());
        game.setRematchGameId(created.game().id());
        gameRepository.save(game);
        log.info("Партия {} — новая тем же составом заведена записью {}", game.getName(), account.getId());
        return created;
    }
}
