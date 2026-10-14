package com.sddnw.server.repository;

import com.sddnw.server.domain.entity.SpectatorFrameEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface SpectatorFrameRepository extends JpaRepository<SpectatorFrameEntity, UUID> {

    /** Самый свежий кадр не позже хода {@code turn} — то, что зритель вправе видеть. */
    Optional<SpectatorFrameEntity> findFirstByGameIdAndTurnLessThanEqualOrderByTurnDesc(UUID gameId, Integer turn);

    Optional<SpectatorFrameEntity> findByGameIdAndTurn(UUID gameId, Integer turn);

    /** Кадры старше отставания зрителю уже не нужны. */
    @Modifying
    @Query("delete from SpectatorFrameEntity frame where frame.gameId = ?1 and frame.turn < ?2")
    void deleteOlder(UUID gameId, Integer turn);
}
