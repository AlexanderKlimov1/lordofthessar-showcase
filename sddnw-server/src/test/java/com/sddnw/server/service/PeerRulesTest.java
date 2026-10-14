package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Сравнение с людьми — field-backlog, пункты 19, 117, 118, 119. */
class PeerRulesTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-01T10:00:00+03:00");

    private static GameRecordEntity row(UUID account, UUID game, int minutes, String outcome, int place,
                                        int colonies, int turn, boolean tutorial) {
        GameRecordEntity row = new GameRecordEntity();
        row.setAccountId(account);
        row.setGameId(game);
        row.setGameName("g" + minutes);
        row.setFinishedAt(T0.plusMinutes(minutes));
        row.setOutcome(outcome);
        row.setPlace(place);
        row.setPeakColonies(colonies);
        row.setTurn(turn);
        row.setGalaxySize("SMALL");
        row.setEmpires(4);
        row.setTutorial(tutorial);
        return row;
    }

    @Test
    void ratingCountsOnlyGamesWithPeople() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID duel = UUID.randomUUID();
        List<GameRecordEntity> all = List.of(
                // Партия против одного ИИ — в рейтинг не идёт.
                row(a, UUID.randomUUID(), 0, GameRecordService.WON, 1, 5, 100, false),
                row(a, duel, 10, GameRecordService.WON, 1, 5, 100, false),
                row(b, duel, 10, GameRecordService.LOST, 2, 3, 100, false));
        Map<UUID, PeerRules.Rating> ratings = PeerRules.ratings(all);
        assertThat(ratings.get(a).value()).isEqualTo(PeerRules.START_RATING + PeerRules.K / 2);
        assertThat(ratings.get(b).value()).isEqualTo(PeerRules.START_RATING - PeerRules.K / 2);
        assertThat(ratings.get(a).games()).isEqualTo(1);
        assertThat(ratings.get(a).provisional()).isTrue();
        assertThat(ratings.get(a).deltaByGame().get(duel)).isEqualTo(PeerRules.K / 2);
    }

    @Test
    void guestGamesAreNotRated() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID game = UUID.randomUUID();
        Map<UUID, PeerRules.Rating> ratings = PeerRules.ratings(List.of(
                row(a, game, 0, GameRecordService.WON, 1, 5, 100, true),
                row(b, game, 0, GameRecordService.LOST, 2, 3, 100, true)));
        assertThat(ratings).isEmpty();
    }

    @Test
    void manyPlayersShareOneStep() {
        // Партия на четверых весит столько же, сколько дуэль: первый над тремя равными получает K·(3·½)/3.
        UUID game = UUID.randomUUID();
        List<GameRecordEntity> all = new ArrayList<>();
        UUID first = UUID.randomUUID();
        all.add(row(first, game, 0, GameRecordService.LOST, 1, 5, 100, false));
        for (int place = 2; place <= 4; place++) {
            all.add(row(UUID.randomUUID(), game, 0, GameRecordService.LOST, place, 3, 100, false));
        }
        assertThat(PeerRules.ratings(all).get(first).value()).isEqualTo(PeerRules.START_RATING + PeerRules.K / 2);
    }

    @Test
    void allyWhoSharedVictoryDoesNotLoseToPartner() {
        UUID game = UUID.randomUUID();
        GameRecordEntity strong = row(UUID.randomUUID(), game, 0, GameRecordService.WON, 1, 5, 100, false);
        GameRecordEntity weak = row(UUID.randomUUID(), game, 0, GameRecordService.WON, 2, 3, 100, false);
        GameRecordEntity loser = row(UUID.randomUUID(), game, 0, GameRecordService.LOST, 3, 3, 100, false);
        assertThat(PeerRules.score(weak, loser)).isEqualTo(1.0);
        assertThat(PeerRules.score(weak, strong)).isEqualTo(0.0);
    }

    @Test
    void shareWaitsForOwnGamesAndEnoughSimilar() {
        UUID me = UUID.randomUUID();
        GameRecordEntity mine = row(me, UUID.randomUUID(), 0, GameRecordService.WON, 1, 6, 120, false);
        List<GameRecordEntity> others = new ArrayList<>();
        for (int i = 0; i < PeerRules.MIN_SIMILAR_GAMES; i++) {
            // Десять похожих чужих побед с колониями 0…9 и ходами 100…290, остальные — поражения.
            others.add(row(UUID.randomUUID(), UUID.randomUUID(), i, i < 10 ? GameRecordService.WON : GameRecordService.LOST,
                    2, i % 10, 100 + 10 * (i % 20), false));
        }
        // Новичок (пункт 118): двух своих партий мало.
        assertThat(PeerRules.share(mine, PeerRules.MIN_OWN_GAMES - 1, others)).isNull();
        PeerRules.Share share = PeerRules.share(mine, PeerRules.MIN_OWN_GAMES, others);
        assertThat(share.games()).isEqualTo(PeerRules.MIN_SIMILAR_GAMES);
        // Колоний меньше шести — у 0…5 в каждом десятке: 18 из 30.
        assertThat(share.coloniesAbove()).isEqualTo(60);
        // Побед мало — доли по скорости нет.
        assertThat(share.fasterThan()).isNull();
        // Гостевые партии с обычными не сравниваются.
        others.forEach(other -> other.setTutorial(true));
        assertThat(PeerRules.share(mine, PeerRules.MIN_OWN_GAMES, others)).isNull();
    }

    @Test
    void closestTableIsTheSimilarOne() {
        PeerRules.Profile novice = new PeerRules.Profile(1, PeerRules.START_RATING);
        PeerRules.Profile veteran = new PeerRules.Profile(60, 1800);
        PeerRules.Profile another = new PeerRules.Profile(2, 1490);
        assertThat(PeerRules.distanceToTable(novice, List.of(another)))
                .isLessThan(PeerRules.distanceToTable(novice, List.of(veteran)));
    }
}
