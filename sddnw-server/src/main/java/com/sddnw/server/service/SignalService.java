package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.GameSignalEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.dto.GameSignalDto;
import com.sddnw.server.dto.SendSignalRequest;
import com.sddnw.server.repository.GameSignalRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.TooManyAttemptsException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Короткие знаки вместо чата — field-backlog, пункт 183.
 * <p>
 * Большая часть того, ради чего просят чат, — несколько реплик: поздороваться, предложить мир,
 * предупредить, поблагодарить, сказать «хорошая партия». Здесь они готовые, одному человеку или всем
 * людям партии; хранятся КОДОМ и переводятся словарём читателя. Свободного текста нет — нечего
 * модерировать и некого оскорбить; тон реплик — без унижения (пункт 107). Предел частоты — один
 * знак в {@link #PAUSE} с каждого игрока: реплика не должна становиться спамом.
 * <p>
 * Империям ИИ знаки не шлют: отвечать им нечем, а молчание в ответ читалось бы как поломка.
 */
@Service
public class SignalService {

    /** Готовые реплики — порядок такой же, как на экране. */
    public static final List<String> CODES = List.of("GREET", "PEACE", "WARN", "WAIT", "THANKS", "GOOD_GAME");

    /**
     * Свободный текст — field-backlog, пункт 16 (решение хозяина проекта 08.10.2026): разговор о
     * договоре и войне кодами не уложить. Цена — обязательная: предел длины и частоты, выключение
     * чужих сообщений и жалоба, которая ложится в «Сообщения о проблемах» администратора.
     */
    public static final String TEXT = "TEXT";

    /** Самая длинная реплика: разговор, а не письмо. */
    public static final int TEXT_LIMIT = 300;

    /** Текст — не чаще раза в это время: чаще уже не разговор, а поток. */
    static final Duration TEXT_PAUSE = Duration.ofSeconds(3);

    /** Не чаще одного знака с игрока за это время. */
    static final Duration PAUSE = Duration.ofSeconds(10);

    /** Сколько последних знаков отдаётся игроку. */
    static final int RECENT = 30;

    private final GameAccess gameAccess;
    private final PlayerRepository playerRepository;
    private final GameSignalRepository repository;
    private final GameEventPublisher publisher;
    private final Map<UUID, OffsetDateTime> lastSent = new ConcurrentHashMap<>();

    public SignalService(GameAccess gameAccess, PlayerRepository playerRepository, GameSignalRepository repository,
                         GameEventPublisher publisher) {
        this.gameAccess = gameAccess;
        this.playerRepository = playerRepository;
        this.repository = repository;
        this.publisher = publisher;
    }

    @Transactional
    public List<GameSignalDto> send(UUID gameId, SendSignalRequest request) {
        GameEntity game = gameAccess.requireRunningGame(gameId);
        PlayerEntity from = gameAccess.requirePlayer(game, request.accessToken());
        boolean text = TEXT.equals(request.code());
        if (!text && !CODES.contains(request.code())) {
            throw new BadRequestException("signal.unknown", request.code());
        }
        String said = text && request.text() != null ? request.text().strip() : null;
        if (text && (said == null || said.isEmpty() || said.length() > TEXT_LIMIT)) {
            throw new BadRequestException("signal.textLength", TEXT_LIMIT);
        }
        List<PlayerEntity> empires = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        List<PlayerEntity> humans = empires.stream().filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .toList();
        if (humans.size() < 2) {
            throw new ConflictException("signal.alone");
        }
        if (request.toPlayerId() != null) {
            PlayerEntity to = empires.stream().filter(player -> player.getId().equals(request.toPlayerId()))
                    .findFirst().orElseThrow(() -> new ConflictException("signal.notHuman"));
            if (to.getPlayerType() != PlayerType.HUMAN || to.getId().equals(from.getId())) {
                throw new ConflictException("signal.notHuman");
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime last = lastSent.get(from.getId());
        Duration pause = text ? TEXT_PAUSE : PAUSE;
        if (last != null && last.plus(pause).isAfter(now)) {
            throw new TooManyAttemptsException(pause, "signal.tooOften", pause.toSeconds());
        }
        lastSent.put(from.getId(), now);
        GameSignalEntity signal = new GameSignalEntity();
        signal.setGameId(gameId);
        signal.setFromPlayerId(from.getId());
        signal.setToPlayerId(request.toPlayerId());
        signal.setCode(request.code());
        signal.setText(said);
        signal.setTurn(game.getTurn());
        signal.setCreatedAt(now);
        repository.save(signal);
        publisher.signal(game, from);
        return recent(gameId, from.getId(), empires);
    }

    @Transactional(readOnly = true)
    public List<GameSignalDto> recent(UUID gameId, String accessToken) {
        UUID playerId = gameAccess.requireToken(gameId, accessToken);
        return recent(gameId, playerId, playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId));
    }

    private List<GameSignalDto> recent(UUID gameId, UUID playerId, List<PlayerEntity> empires) {
        Map<UUID, String> names = empires.stream().collect(Collectors.toMap(PlayerEntity::getId, PlayerEntity::getName));
        // Выключенных игроком (пункт 16) он не видит вовсе — ни реплик, ни знаков.
        java.util.Set<UUID> muted = empires.stream().filter(player -> player.getId().equals(playerId))
                .findFirst().map(PlayerEntity::mutedPlayerIds).orElse(java.util.Set.of());
        return repository.recentFor(gameId, playerId, PageRequest.of(0, RECENT)).stream()
                .filter(row -> !muted.contains(row.getFromPlayerId()))
                .map(row -> new GameSignalDto(row.getId(), row.getFromPlayerId(), names.get(row.getFromPlayerId()),
                        row.getToPlayerId(), row.getCode(), row.getTurn(), row.getCreatedAt(), row.getText()))
                .toList();
    }

    /** Выключить или вернуть сообщения игрока — пункт 16; в ответ — кого выключено теперь. */
    @Transactional
    public List<UUID> mute(UUID gameId, String accessToken, UUID otherId, Boolean muted) {
        PlayerEntity self = gameAccess.requirePlayer(gameAccess.requireGame(gameId), accessToken);
        java.util.Set<UUID> ids = self.mutedPlayerIds();
        if (Boolean.TRUE.equals(muted)) {
            ids.add(otherId);
        } else {
            ids.remove(otherId);
        }
        self.setMutedPlayerIds(ids);
        playerRepository.save(self);
        return List.copyOf(ids);
    }

    /** Кого игрок выключил — для пульта. */
    @Transactional(readOnly = true)
    public List<UUID> muted(UUID gameId, String accessToken) {
        return List.copyOf(gameAccess.requirePlayer(gameAccess.requireGame(gameId), accessToken).mutedPlayerIds());
    }

    /**
     * Жалоба на реплику — пункт 16: ложится в «Сообщения о проблемах» (пункт 157) с самой репликой,
     * отправителем и партией; там её и разбирает администратор. Жаловаться можно только на то, что
     * видел сам.
     */
    public void report(UUID gameId, String accessToken, String accountToken, UUID signalId,
                       ProblemReportService problemReports) {
        PlayerEntity self = gameAccess.requirePlayer(gameAccess.requireGame(gameId), accessToken);
        GameSignalEntity signal = repository.findById(signalId)
                .filter(row -> row.getGameId().equals(gameId))
                .filter(row -> row.getToPlayerId() == null || row.getToPlayerId().equals(self.getId()))
                .orElseThrow(() -> new BadRequestException("signal.notFound"));
        String from = playerRepository.findById(signal.getFromPlayerId()).map(PlayerEntity::getName).orElse("?");
        String said = signal.getText() == null ? signal.getCode() : signal.getText();
        problemReports.report(accountToken, new com.sddnw.server.dto.ProblemReportRequest(
                "Жалоба на сообщение от «" + from + "», ход " + signal.getTurn() + ": " + said,
                gameId, signal.getTurn(), null, "chat",
                "{\"signalId\":\"" + signal.getId() + "\",\"fromPlayerId\":\"" + signal.getFromPlayerId() + "\"}"));
    }
}
