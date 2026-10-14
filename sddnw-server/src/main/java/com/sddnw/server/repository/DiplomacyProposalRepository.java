package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.DiplomacyProposalEntity;
import com.sddnw.server.domain.enums.DiplomacyAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Предложения, ждущие ответа человека, — field-backlog, пункт 182. */
public interface DiplomacyProposalRepository extends JpaRepository<DiplomacyProposalEntity, UUID> {

    /**
     * Ждущие в партии — одной выборкой на ход. Порядок по ходу и действию задан явно: ход
     * решает их по очереди, и партия обязана повторяться (идентификатор строки — не порядок).
     */
    List<DiplomacyProposalEntity> findAllByGameIdOrderByTurnAscActionAsc(UUID gameId);

    List<DiplomacyProposalEntity> findAllByToPlayerIdOrderByTurnAsc(UUID toPlayerId);

    List<DiplomacyProposalEntity> findAllByFromPlayerIdOrderByTurnAsc(UUID fromPlayerId);

    Boolean existsByFromPlayerIdAndToPlayerIdAndAction(UUID fromPlayerId, UUID toPlayerId,
                                                       DiplomacyAction action);
}
