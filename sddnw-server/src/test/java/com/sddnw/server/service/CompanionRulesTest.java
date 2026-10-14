package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Подарок за приглашение — пункты 50 и 174: за доигранную вместе партию, не больше трёх. */
class CompanionRulesTest {

    private static GameRecordEntity row(UUID account, UUID game, UUID invitedBy, String outcome, int minutes) {
        GameRecordEntity row = new GameRecordEntity();
        row.setAccountId(account);
        row.setGameId(game);
        row.setInvitedBy(invitedBy);
        row.setOutcome(outcome);
        row.setFinishedAt(OffsetDateTime.parse("2026-10-01T10:00:00+03:00").plusMinutes(minutes));
        return row;
    }

    @Test
    void bothMustFinishTheSameGame() {
        UUID host = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        UUID game = UUID.randomUUID();
        GameRecordEntity invited = row(friend, game, host, GameRecordService.LOST, 0);
        // Позвавший признал поражение — партия вместе не доиграна, подарка нет.
        GameRecordEntity conceded = row(host, game, null, GameRecordService.CONCEDED, 0);
        assertThat(CompanionRules.companions(List.of(invited), List.of(invited, conceded))).isEmpty();

        GameRecordEntity won = row(host, game, null, GameRecordService.WON, 0);
        Map<UUID, Set<UUID>> gifts = CompanionRules.companions(List.of(invited), List.of(invited, won));
        assertThat(gifts.get(host)).containsExactly(friend);
        assertThat(gifts.get(friend)).containsExactly(host);
    }

    @Test
    void noMoreThanThreeGifts() {
        UUID host = UUID.randomUUID();
        List<GameRecordEntity> invited = new ArrayList<>();
        List<GameRecordEntity> games = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID game = UUID.randomUUID();
            GameRecordEntity friend = row(UUID.randomUUID(), game, host, GameRecordService.LOST, i);
            invited.add(friend);
            games.add(friend);
            games.add(row(host, game, null, GameRecordService.WON, i));
        }
        assertThat(CompanionRules.companions(invited, games).get(host)).hasSize(CompanionRules.LIMIT);
    }
}
