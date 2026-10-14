package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.dto.CreateGameResponse;
import com.sddnw.server.dto.InviteDto;
import com.sddnw.server.dto.JoinGameRequest;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlanetRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Приглашение друга в партию по ссылке — field-backlog, пункты 15 и 61.
 * <p>
 * Самый дешёвый путь к связанности и к привлечению разом: каждый игрок становится каналом, а
 * пришедший по приглашению друга остаётся охотнее пришедшего по рекламе. Ссылка ведёт за стол
 * пригласившего; <b>гостю регистрация не нужна</b> (пункт 61) — в открытые чужие партии гость
 * по-прежнему не входит ({@code AccountService.requireRegistered}), но по приглашению — да:
 * его позвал знакомый, а не случайный список.
 * <p>
 * Место пришедшему: в собирающейся партии — свободное место лобби, в идущей — <b>империя, которую
 * ведёт ИИ</b> (живая, без хозяина-человека). Её состояние — всё, что ИИ успел; пришедший играет
 * её с этого хода. <b>Какую — выбирает он сам</b> (пункт 66): список с колониями и с тем, воюет ли
 * империя с пригласившим; по умолчанию предложена та, что с пригласившим не воюет. Взять
 * воюющую можно — это честная партия друг против друга, — но сказано это заранее. Правила
 * партии от пришедшего не меняются: совет, часы хода и пути к победе остаются, какими были, а
 * второй человек за столом значит, что партия ждёт и его хода. Пришедшие помечаются ({@code player.invited}) — по ним
 * считается, сколько людей приводят ссылки.
 */
@Service
public class InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteService.class);
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
    private static final int CODE_LENGTH = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GameAccess gameAccess;
    private final GameRepository gameRepository;
    private final PlayerRepository playerRepository;
    private final PlanetRepository planetRepository;
    private final GameService gameService;
    private final Messages messages;
    private final com.sddnw.server.repository.DiplomacyRelationRepository relationRepository;
    private final PlayerRoster playerRoster;

    public InviteService(GameAccess gameAccess, GameRepository gameRepository, PlayerRepository playerRepository,
                         PlanetRepository planetRepository, GameService gameService, Messages messages,
                         com.sddnw.server.repository.DiplomacyRelationRepository relationRepository,
                         PlayerRoster playerRoster) {
        this.relationRepository = relationRepository;
        this.playerRoster = playerRoster;
        this.gameAccess = gameAccess;
        this.gameRepository = gameRepository;
        this.playerRepository = playerRepository;
        this.planetRepository = planetRepository;
        this.gameService = gameService;
        this.messages = messages;
    }

    /** Код приглашения партии — любому её участнику; заводится при первой просьбе. */
    @Transactional
    public String code(UUID gameId, String accessToken) {
        gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameAccess.requireGame(gameId);
        if (game.getStatus() == GameStatus.FINISHED) {
            throw new ConflictException("invite.finished");
        }
        if (game.getInviteCode() != null) {
            return game.getInviteCode();
        }
        String code = newCode();
        gameRepository.setInviteCode(gameId, code);
        return gameRepository.findById(gameId).map(GameEntity::getInviteCode).orElse(code);
    }

    /** Что ждёт пришедшего — экран приглашения показывает это до входа. */
    @Transactional(readOnly = true)
    public InviteDto preview(String code) {
        GameEntity game = byCode(code);
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId());
        PlayerEntity host = players.stream().filter(player -> player.getId().equals(game.getHostPlayerId()))
                .findFirst().orElse(null);
        long humans = players.stream().filter(player -> player.getPlayerType() == PlayerType.HUMAN).count();
        String seat = null;
        PlayerEntity empire = null;
        List<InviteDto.Empire> choices = List.of();
        if (game.getStatus() == GameStatus.LOBBY) {
            seat = players.size() < game.getMaxHumanPlayers() ? "LOBBY" : null;
        } else if (game.getStatus() == GameStatus.IN_PROGRESS) {
            List<PlayerEntity> free = freeEmpires(players);
            java.util.Set<UUID> atWar = enemiesOf(game.getHostPlayerId());
            empire = free.stream().filter(one -> !atWar.contains(one.getId())).findFirst()
                    .orElse(free.isEmpty() ? null : free.get(0));
            seat = empire == null ? null : "EMPIRE";
            Map<String, String> races = playerRoster.raceNames();
            choices = free.stream()
                    .map(one -> new InviteDto.Empire(one.getId(), messages.empire(one.getName()), one.getColor(),
                            one.getRaceName() != null ? one.getRaceName() : races.get(one.getRaceCode()),
                            planetRepository.countByOwnerPlayerId(one.getId()), atWar.contains(one.getId())))
                    .toList();
        }
        return new InviteDto(game.getName(), game.getStatus().name(), game.getTurn(),
                game.getGalaxySize() == null ? null : game.getGalaxySize().name(), (int) humans,
                game.getTotalPlayers(), game.getTurnSeconds(), seat,
                empire == null ? null : messages.empire(empire.getName()),
                empire == null ? null : empire.getColor(),
                host == null ? null : messages.empire(host.getName()), choices);
    }

    /** С кем воюет пригласивший — пункт 66: империя в войне с ним предлагается не первой. */
    private java.util.Set<UUID> enemiesOf(UUID hostPlayerId) {
        if (hostPlayerId == null) {
            return java.util.Set.of();
        }
        return relationRepository.findAllByPlayerId(hostPlayerId).stream()
                .filter(relation -> relation.getStance() == com.sddnw.server.domain.enums.DiplomacyStance.WAR)
                .map(relation -> relation.getOtherPlayerId())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Войти по приглашению. Своё место у этой записи в партии уже есть — это возврат, а не второе
     * место. Собирающаяся партия — обычный вход в лобби; идущая — империя, которую ведёт ИИ.
     */
    @Transactional
    public CreateGameResponse join(String code, AccountEntity account, JoinGameRequest request) {
        GameEntity found = byCode(code);
        boolean seated = playerRepository.findEmpiresByGameIdOrderBySlotAsc(found.getId()).stream()
                .anyMatch(player -> account.getId().equals(player.getAccountId()));
        if (seated) {
            return gameService.rejoin(found.getId(), account.getId());
        }
        if (found.getStatus() == GameStatus.LOBBY) {
            CreateGameResponse joined = gameService.joinGame(found.getId(), request, account.getId(), Boolean.TRUE);
            playerRepository.findById(joined.credentials().playerId()).ifPresent(player -> {
                player.setInvited(Boolean.TRUE);
                playerRepository.save(player);
            });
            log.info("По приглашению в лобби партии {} вошёл {}", found.getName(), request.playerName());
            return joined;
        }
        if (found.getStatus() != GameStatus.IN_PROGRESS) {
            throw new ConflictException("invite.finished");
        }
        GameEntity game = gameRepository.lockById(found.getId())
                .orElseThrow(() -> new NotFoundException("game.notFound", found.getId()));
        List<PlayerEntity> players = playerRepository.findEmpiresByGameIdOrderBySlotAsc(game.getId());
        List<PlayerEntity> free = freeEmpires(players);
        if (free.isEmpty()) {
            throw new ConflictException("invite.full");
        }
        // Выбор пришедшего (пункт 66); без выбора — та, что не воюет с пригласившим.
        java.util.Set<UUID> atWar = enemiesOf(game.getHostPlayerId());
        PlayerEntity empire = request.empirePlayerId() == null
                ? free.stream().filter(one -> !atWar.contains(one.getId())).findFirst().orElse(free.get(0))
                : free.stream().filter(one -> one.getId().equals(request.empirePlayerId())).findFirst()
                        .orElseThrow(() -> new ConflictException("invite.empireTaken"));
        empire.setPlayerType(PlayerType.HUMAN);
        empire.setAccountId(account.getId());
        empire.setInvited(Boolean.TRUE);
        empire.setLastActionAt(OffsetDateTime.now());
        // Ход, начатый без него, считается начатым и им: иначе партия ждала бы новенького на
        // ходу, который остальные уже закончили, — а он только вошёл.
        empire.setEndedTurn(game.getTurn() - 1);
        // Новый пропуск: прежний был у ИИ, и знать его не должен никто, кроме нового хозяина.
        empire.setAccessToken(UUID.randomUUID().toString().replace("-", ""));
        playerRepository.save(empire);
        log.info("По приглашению в партию {} на ходу {} вошёл игрок записи {} — за империю {}",
                game.getName(), game.getTurn(), account.getId(), empire.getName());
        return gameService.rejoin(game.getId(), account.getId());
    }

    /**
     * Империи, которые можно взять: их ведёт ИИ, у них нет хозяина-человека (не наблюдатель, не
     * сдавшийся), и они живы — у каждой есть хоть одна колония. По месту: двое пришедших видят
     * один и тот же список, а занятую раньше империю второй получит отказом с причиной.
     */
    private List<PlayerEntity> freeEmpires(List<PlayerEntity> players) {
        Map<UUID, Integer> colonies = new java.util.HashMap<>();
        for (PlayerEntity player : players) {
            colonies.put(player.getId(), planetRepository.countByOwnerPlayerId(player.getId()));
        }
        return players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.AI && player.getAccountId() == null
                        && player.getConcededTurn() == null && colonies.getOrDefault(player.getId(), 0) > 0)
                .sorted(Comparator.comparing(PlayerEntity::getSlot))
                .toList();
    }

    private GameEntity byCode(String code) {
        return gameRepository.findByInviteCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new NotFoundException("invite.notFound"));
    }

    private static String newCode() {
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
