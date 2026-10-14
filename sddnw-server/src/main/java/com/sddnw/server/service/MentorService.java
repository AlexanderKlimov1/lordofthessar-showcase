package com.sddnw.server.service;

import com.sddnw.server.domain.entity.MapMarkEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.dto.MapMarkDto;
import com.sddnw.server.dto.MentorViewDto;
import com.sddnw.server.repository.MapMarkRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.StarSystemRepository;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Наставник — field-backlog, пункт 184: ветеран ведёт новичка в одной партии. Новичок САМ
 * называет наставника (согласие — его, и снимается оно так же одним нажатием), и тот видит его
 * империю глазами ученика — карту с его разведкой и туманом, флоты, казну — и ставит метки на
 * карте ученика: «сюда — колониальный корабль». Тот же путь, что показ галактики администратору,
 * но по одной империи и по согласию.
 * <p>
 * Метка — знак из {@link #CODES}, а не текст: реплики между людьми только кодами (пункт 183).
 * Наставником бывает только человек за тем же столом.
 */
@Service
public class MentorService {

    /** Знаки меток: что предлагает наставник у этой звезды. */
    public static final Set<String> CODES = Set.of("COLONIZE", "OUTPOST", "EXPLORE", "DEFEND", "ATTACK", "AVOID");

    private final GameAccess gameAccess;
    private final PlayerRepository players;
    private final MapMarkRepository marks;
    private final StarSystemRepository systems;
    private final GameService gameService;
    private final FleetService fleetService;
    private final Messages messages;

    public MentorService(GameAccess gameAccess, PlayerRepository players, MapMarkRepository marks,
                         StarSystemRepository systems, GameService gameService, FleetService fleetService,
                         Messages messages) {
        this.gameAccess = gameAccess;
        this.players = players;
        this.marks = marks;
        this.systems = systems;
        this.gameService = gameService;
        this.fleetService = fleetService;
        this.messages = messages;
    }

    /**
     * Ученик называет наставника или снимает согласие. Сняв, он уносит и метки: наставник больше
     * не видит его карты, и знаки на ней остались бы советами из прошлого.
     */
    @Transactional
    public void choose(UUID gameId, String studentToken, UUID mentorPlayerId) {
        PlayerEntity student = gameAccess.requirePlayerOfRunningGame(gameId, studentToken);
        if (mentorPlayerId == null) {
            student.setMentorPlayerId(null);
            players.save(student);
            marks.deleteAllByStudentPlayerId(student.getId());
            return;
        }
        PlayerEntity mentor = players.findEmpiresByGameIdOrderBySlotAsc(gameId).stream()
                .filter(player -> player.getId().equals(mentorPlayerId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("mentor.notInGame"));
        if (mentor.getId().equals(student.getId()) || mentor.getPlayerType() != PlayerType.HUMAN) {
            throw new BadRequestException("mentor.notHuman");
        }
        student.setMentorPlayerId(mentor.getId());
        players.save(student);
    }

    /** Ученики этого наставника — каждый своими глазами. */
    @Transactional(readOnly = true)
    public List<MentorViewDto> students(UUID gameId, String mentorToken) {
        PlayerEntity mentor = gameAccess.requirePlayerOfRunningGame(gameId, mentorToken);
        return players.findEmpiresByGameIdOrderBySlotAsc(gameId).stream()
                .filter(player -> mentor.getId().equals(player.getMentorPlayerId()))
                .map(student -> new MentorViewDto(student.getId(), messages.empire(student.getName()),
                        student.getColor(), student.getCredits(),
                        gameService.getMap(gameId, student.getAccessToken(), Boolean.FALSE),
                        fleetService.fleetsOf(student),
                        marksOf(student.getId(), Map.of(mentor.getId(), messages.empire(mentor.getName())))))
                .toList();
    }

    /** Поставить, сменить или снять метку на карте ученика. */
    @Transactional
    public List<MapMarkDto> mark(UUID gameId, String mentorToken, UUID studentId, UUID systemId, String code) {
        PlayerEntity mentor = gameAccess.requirePlayerOfRunningGame(gameId, mentorToken);
        PlayerEntity student = players.findEmpiresByGameIdOrderBySlotAsc(gameId).stream()
                .filter(player -> player.getId().equals(studentId))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("mentor.notInGame"));
        if (!mentor.getId().equals(student.getMentorPlayerId())) {
            throw new ForbiddenException("mentor.notYourStudent");
        }
        boolean inGame = systems.findById(systemId)
                .map(system -> system.getGame().getId().equals(gameId))
                .orElse(false);
        if (!inGame) {
            throw new BadRequestException("mentor.noSuchStar");
        }
        MapMarkEntity existing = marks.findByStudentPlayerIdAndSystemId(studentId, systemId).orElse(null);
        if (code == null || code.isBlank()) {
            if (existing != null) {
                marks.delete(existing);
            }
        } else {
            if (!CODES.contains(code)) {
                throw new ConflictException("mentor.badMark");
            }
            MapMarkEntity row = existing == null ? new MapMarkEntity() : existing;
            row.setGameId(gameId);
            row.setAuthorPlayerId(mentor.getId());
            row.setStudentPlayerId(studentId);
            row.setSystemId(systemId);
            row.setCode(code);
            row.setTurn(gameAccess.requireGame(gameId).getTurn());
            marks.save(row);
        }
        marks.flush();
        return marksOf(studentId, Map.of(mentor.getId(), messages.empire(mentor.getName())));
    }

    /** Метки на карте этого игрока — для его же карты (пункт 184). */
    @Transactional(readOnly = true)
    public List<MapMarkDto> marksFor(UUID gameId, PlayerEntity student) {
        Map<UUID, String> names = new HashMap<>();
        players.findEmpiresByGameIdOrderBySlotAsc(gameId)
                .forEach(player -> names.put(player.getId(), messages.empire(player.getName())));
        return marksOf(student.getId(), names);
    }

    private List<MapMarkDto> marksOf(UUID studentId, Map<UUID, String> names) {
        return marks.findAllByStudentPlayerIdOrderByTurnAsc(studentId).stream()
                .map(row -> new MapMarkDto(row.getSystemId(), row.getCode(),
                        names.getOrDefault(row.getAuthorPlayerId(), ""), row.getTurn()))
                .toList();
    }
}
