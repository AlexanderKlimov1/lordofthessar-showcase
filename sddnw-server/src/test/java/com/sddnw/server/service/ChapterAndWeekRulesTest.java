package com.sddnw.server.service;

import com.sddnw.server.domain.enums.GalaxyFeature;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Главы партии (пункт 54), особенности галактики (пункт 94) и галактика недели (пункт 146). */
class ChapterAndWeekRulesTest {

    private static ChapterRules.State state(boolean met, boolean war, boolean council, boolean finale) {
        return new ChapterRules.State(met, war, council, finale);
    }

    @Test
    void chapterFollowsStateAndNeverGoesBack() {
        assertThat(ChapterRules.chapter(1, state(false, false, false, false))).isEqualTo(ChapterRules.SETTLEMENT);
        assertThat(ChapterRules.chapter(1, state(true, false, false, false))).isEqualTo(ChapterRules.NEIGHBOURS);
        assertThat(ChapterRules.chapter(2, state(true, true, false, false))).isEqualTo(ChapterRules.WAR);
        // Совет без знакомства — не борьба: за кого голосовать, если никого не знаешь.
        assertThat(ChapterRules.chapter(1, state(false, false, true, false))).isEqualTo(ChapterRules.SETTLEMENT);
        assertThat(ChapterRules.chapter(2, state(true, false, true, false))).isEqualTo(ChapterRules.COUNCIL);
        assertThat(ChapterRules.chapter(1, state(false, false, false, true))).isEqualTo(ChapterRules.FINALE);
        // Мир после войны главу не отматывает.
        assertThat(ChapterRules.chapter(ChapterRules.WAR, state(true, false, false, false))).isEqualTo(ChapterRules.WAR);
    }

    @Test
    void finaleNearByEachPath() {
        assertThat(ChapterRules.finaleNear(10, false, MightVictoryRules.TURN_LIMIT, 100, 1, 10, 4)).isTrue();
        assertThat(ChapterRules.finaleNear(11, false, MightVictoryRules.TURN_LIMIT, 100, 1, 10, 4)).isFalse();
        assertThat(ChapterRules.finaleNear(null, true, MightVictoryRules.TURN_LIMIT, MightVictoryRules.TURN_LIMIT - 30, 1, 10, 4)).isTrue();
        assertThat(ChapterRules.finaleNear(null, false, MightVictoryRules.TURN_LIMIT, 60, 5, 10, 3)).isTrue();
        // В начале партии у каждого по колонии — «половина» у двоих ничего не значит.
        assertThat(ChapterRules.finaleNear(null, false, MightVictoryRules.TURN_LIMIT, 10, 1, 2, 2)).isFalse();
    }

    @Test
    void featureResolvesFromSeedAndCodes() {
        assertThat(GalaxyFeature.resolve(null, 1L)).isNull();
        assertThat(GalaxyFeature.resolve("NONE", 1L)).isNull();
        assertThat(GalaxyFeature.resolve("DARK", 1L)).isEqualTo("DARK");
        assertThat(GalaxyFeature.resolve("NOPE", 1L)).isNull();
        // Жребий повторяется: то же зерно — та же особенность.
        assertThat(GalaxyFeature.resolve("RANDOM", 42L)).isEqualTo(GalaxyFeature.resolve("RANDOM", 42L))
                .isNotNull();
    }

    @Test
    void weekIsIsoAndSeedIsStable() {
        assertThat(WeeklyGalaxyRules.week(LocalDate.of(2026, 10, 8))).isEqualTo(202641);
        assertThat(WeeklyGalaxyRules.monday(LocalDate.of(2026, 10, 8))).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(WeeklyGalaxyRules.seed(202641)).isEqualTo(WeeklyGalaxyRules.seed(202641))
                .isNotEqualTo(WeeklyGalaxyRules.seed(202642));
    }
}
