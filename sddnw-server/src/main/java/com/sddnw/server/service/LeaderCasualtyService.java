package com.sddnw.server.service;

import com.sddnw.server.domain.entity.FleetEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerLeaderEntity;
import com.sddnw.server.domain.entity.PlayerTechnologyEntity;
import com.sddnw.server.domain.enums.LeaderState;
import com.sddnw.server.repository.GameRepository;
import com.sddnw.server.repository.PlayerLeaderRepository;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.repository.PlayerTechnologyRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Гибель корабельного лидера в бою — п. 6 (13.10.2026, решение хозяина проекта, правило MOO II).
 * <p>
 * В оригинале лидер стоит на корабле и гибнет вместе с ним; спасательные капсулы («Survival Pods»)
 * спасают его, «provided that at least one of your ships is present at the end of the battle».
 * У нас лидер приписан к ФЛОТУ, а не к кораблю, поэтому правило <b>реконструировано</b>: его
 * корабль — один из кораблей флота, и он гибнет с вероятностью «доля погибших кораблей», а флот,
 * разбитый целиком, уносит его наверняка. Капсулы спасают, если у флота остался хоть один корабль.
 * <p>
 * Жребий берётся от зерна партии, хода, места игрока и кода лидера — не от идентификаторов, иначе
 * партия перестала бы повторяться (см. {@code GameOrder}). Служба зависит только от хранилищ:
 * зовёт её служба флотов, и обратной зависимости быть не должно.
 */
@Service
public class LeaderCasualtyService {

    /** Достижение, спасающее лидера, — Engineering, уровень 3. */
    static final String SURVIVAL_PODS = "survival-pods";

    private final PlayerLeaderRepository leaders;
    private final PlayerTechnologyRepository technologies;
    private final GameRepository games;
    private final PlayerEventService playerEvents;
    private final PlayerRepository players;

    public LeaderCasualtyService(PlayerLeaderRepository leaders, PlayerTechnologyRepository technologies,
                                 GameRepository games, PlayerEventService playerEvents,
                                 PlayerRepository players) {
        this.players = players;
        this.leaders = leaders;
        this.technologies = technologies;
        this.games = games;
        this.playerEvents = playerEvents;
    }

    /**
     * Бой стоил флоту кораблей — решает судьбу его лидеров.
     *
     * @param before кораблей во флоте до потерь
     * @param lost   сколько из них погибло
     * @param slot   место игрока в партии — для жребия; пусто — служба узнает его сама
     */
    public void afterLosses(FleetEntity fleet, Integer before, Integer lost, Integer slot) {
        if (fleet == null || lost == null || lost <= 0 || before == null || before <= 0) {
            return;
        }
        List<PlayerLeaderEntity> aboard = leaders.findAllByPlayerId(fleet.getOwnerPlayerId()).stream()
                .filter(row -> row.getState() == LeaderState.HIRED && fleet.getId().equals(row.getFleetId()))
                .sorted(Comparator.comparing(PlayerLeaderEntity::getLeaderCode))
                .toList();
        if (aboard.isEmpty()) {
            return;
        }
        Integer place = slot != null ? slot
                : players.findById(fleet.getOwnerPlayerId()).map(p -> p.getSlot()).orElse(0);
        boolean survivors = lost < before;
        boolean pods = survivors && technologies.findAllByPlayerIdIn(List.of(fleet.getOwnerPlayerId())).stream()
                .map(PlayerTechnologyEntity::getOptionCode)
                .anyMatch(SURVIVAL_PODS::equals);
        GameEntity game = games.findById(fleet.getGameId()).orElse(null);
        long seed = game == null || game.getSeed() == null ? 0L : game.getSeed();
        int turn = game == null ? 0 : game.getTurn();
        for (PlayerLeaderEntity row : aboard) {
            Random random = new Random(seed * 1_000_003L + turn * 7_919L
                    + (place == null ? 0 : place) * 131L + row.getLeaderCode().hashCode());
            boolean hit = !survivors || random.nextInt(before) < lost;
            if (!hit) {
                continue;
            }
            if (pods) {
                playerEvents.record(fleet.getGameId(), fleet.getOwnerPlayerId(), turn, "LEADERS",
                        new MessageKey("turn.leader.savedByPods", CatalogTexts.leader(row.getLeaderCode())));
                continue;
            }
            row.setState(LeaderState.KILLED);
            row.setFleetId(null);
            leaders.save(row);
            playerEvents.record(fleet.getGameId(), fleet.getOwnerPlayerId(), turn, "LEADERS",
                    new MessageKey(survivors ? "turn.leader.killed" : "turn.leader.killedWithFleet",
                            CatalogTexts.leader(row.getLeaderCode())));
        }
    }
}
