package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.GameRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** История партий учётной записи — field-backlog, пункт 18. */
public interface GameRecordRepository extends JpaRepository<GameRecordEntity, UUID> {

    List<GameRecordEntity> findAllByAccountIdOrderByFinishedAtDesc(UUID accountId);

    /** Сыгранное в галактику недели — итоги по расам (field-backlog, пункт 146). */
    List<GameRecordEntity> findAllByWeeklyWeek(Integer weeklyWeek);

    Boolean existsByAccountIdAndGameId(UUID accountId, UUID gameId);

    /** Партии той же настройки — доля среди похожих (field-backlog, пункт 117). */
    List<GameRecordEntity> findAllByGalaxySizeAndEmpires(String galaxySize, Integer empires);

    /** Удалить историю записи вместе с ней (пункт 45). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional("historyTransactionManager")
    void deleteByAccountId(UUID accountId);

    /** Строки приглашённых — подарок за приглашение (пункты 50, 174). */
    List<GameRecordEntity> findAllByInvitedByIsNotNull();

    /** Все строки этих партий — с кем приглашённый доиграл. */
    List<GameRecordEntity> findAllByGameIdIn(java.util.Collection<UUID> gameIds);

    /** История нескольких записей разом — профиль стола подбора (пункт 119). */
    List<GameRecordEntity> findAllByAccountIdIn(java.util.Collection<UUID> accountIds);
}
