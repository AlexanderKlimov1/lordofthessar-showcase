package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.TurnUndoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TurnUndoRepository extends JpaRepository<TurnUndoEntity, UUID> {

    List<TurnUndoEntity> findAllByPlayerIdAndTurnOrderByIdAsc(UUID playerId, Integer turn);

    /** Строки прошлых ходов: отменять их уже нечего. */
    @Modifying
    @Query("delete from TurnUndoEntity undo where undo.playerId = ?1 and undo.turn < ?2")
    void deleteStale(UUID playerId, Integer turn);
}
