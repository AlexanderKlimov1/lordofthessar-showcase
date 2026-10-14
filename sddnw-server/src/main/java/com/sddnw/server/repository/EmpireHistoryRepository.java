package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Летопись империй по ходам — п. 11.1, для графика окна «Инфо». */
public interface EmpireHistoryRepository extends JpaRepository<EmpireHistoryEntity, UUID> {

    /**
     * Вся летопись партии по возрастанию хода.
     * <p>
     * Экран строит линии по всем империям разом, и порядок ходов ему нужен готовым:
     * сортировать на клиенте значило бы повторять там правило, которое и так есть здесь.
     */
    List<EmpireHistoryEntity> findAllByGameIdOrderByTurnAsc(UUID gameId);

    /** Замер одного хода — место в «Ваших партиях» (field-backlog, пункт 109). */
    List<EmpireHistoryEntity> findAllByGameIdAndTurn(UUID gameId, Integer turn);

    /** Летопись нескольких последних ходов — для переломов (field-backlog, пункт 142). */
    List<EmpireHistoryEntity> findAllByGameIdAndTurnBetween(UUID gameId, Integer from, Integer to);
}
