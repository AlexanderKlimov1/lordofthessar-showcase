package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.enums.GameStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<GameEntity, UUID> {

    /** Партии, которые можно смотреть со стороны, — field-backlog, пункт 59. */
    List<GameEntity> findAllBySpectatorsTrueAndStatusIn(java.util.Collection<GameStatus> statuses);

    /** Сколько партий недели начато — галактика недели, пункт 146. */
    long countByWeeklyWeek(Integer weeklyWeek);

    /**
     * Партии с таким названием — их спрашивает только демонстрационный бой (п. 8): свою
     * прошлую партию он находит по имени и убирает, чтобы они не копились.
     */
    List<GameEntity> findAllByName(String name);

    /**
     * Пометить партию «под наблюдением администратора» — field-backlog, пункт 197. Запросом,
     * а не правкой сущности: версию строки он не трогает и с концом хода не спорит.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE GameEntity g SET g.adminWatched = TRUE WHERE g.id = :id AND g.adminWatched = FALSE")
    int markAdminWatched(UUID id);

    /**
     * Код приглашения — field-backlog, пункт 15: ставится один раз и мимо версии строки, чтобы
     * просьба о ссылке не спорила с концом хода за замок партии.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE GameEntity g SET g.inviteCode = :code WHERE g.id = :id AND g.inviteCode IS NULL")
    int setInviteCode(UUID id, String code);

    java.util.Optional<GameEntity> findByInviteCode(String inviteCode);

    /**
     * Ход партии одним числом — счёт действий (field-backlog, пункт 13) спрашивает его до замка
     * партии, а сущность, прочитанная до замка, роняла бы запись самого действия на проверке версии.
     */
    @Query("SELECT g.turn FROM GameEntity g WHERE g.id = :id")
    Integer findTurnById(UUID id);

    /**
     * Список партий страницей: их в базе накапливаются сотни, а показать нужно последние.
     * Игроки к ним берутся одним запросом отдельно — иначе выходил запрос на партию.
     */
    List<GameEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<GameEntity> findAllByStatusOrderByCreatedAtDesc(GameStatus status, Pageable pageable);

    /** Столы подбора в лобби — field-backlog, пункт 65: старшие первыми, их и наполняют. */
    List<GameEntity> findAllByStatusAndAutoStartAtNotNullOrderByCreatedAtAsc(GameStatus status);

    /** Чей срок ожидания вышел — их обходят часы ({@code TurnClock}). */
    @Query("select g.id from GameEntity g where g.status = com.sddnw.server.domain.enums.GameStatus.LOBBY"
            + " and g.autoStartAt is not null and g.autoStartAt <= :now")
    List<UUID> findAutoStartDue(@org.springframework.data.repository.query.Param("now") java.time.OffsetDateTime now);

    @Query("""
            SELECT g FROM GameEntity g
            LEFT JOIN FETCH g.players
            WHERE g.id = :id
            """)
    Optional<GameEntity> findByIdWithPlayers(UUID id);

    /**
     * Партия под замком на запись — для конца хода (п. 11.1).
     * <p>
     * Ход пересчитывает всю галактику партии, и делать это одновременно нельзя: без
     * замка два игрока, закончивших ход в одну секунду, считали каждый свой пересчёт и
     * затирали друг друга. Замок на строке партии выстраивает их в очередь, а игроки
     * разных партий друг другу не мешают — блокируется партия, а не сервер.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM GameEntity g WHERE g.id = :id")
    Optional<GameEntity> lockById(UUID id);
}
