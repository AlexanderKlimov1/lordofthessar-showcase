package com.sddnw.server.service;

import com.sddnw.server.domain.ColonyProject;
import com.sddnw.server.domain.enums.PlanetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Андроиды сверх потолка населения — п. 4.1, решение хозяина проекта 14.10.2026. */
class AndroidRoomTest {

    @Test
    @DisplayName("Сверх потолка планета держит андроидов по размеру: крошечная 1 … огромная 5")
    void beyondCapGrowsWithSize() {
        assertThat(ColonyProject.androidsBeyondCap(PlanetSize.TINY)).isEqualTo(1);
        assertThat(ColonyProject.androidsBeyondCap(PlanetSize.SMALL)).isEqualTo(2);
        assertThat(ColonyProject.androidsBeyondCap(PlanetSize.MEDIUM)).isEqualTo(3);
        assertThat(ColonyProject.androidsBeyondCap(PlanetSize.LARGE)).isEqualTo(4);
        assertThat(ColonyProject.androidsBeyondCap(PlanetSize.HUGE)).isEqualTo(5);
    }

    @Test
    @DisplayName("Место жителя занимают только андроиды сверх запаса планеты")
    void onlyExtraAndroidsTakeRoom() {
        assertThat(ColonyProject.androidsTakingRoom(3, PlanetSize.MEDIUM)).isZero();
        assertThat(ColonyProject.androidsTakingRoom(5, PlanetSize.MEDIUM)).isEqualTo(2);
        assertThat(ColonyProject.androidsTakingRoom(0, PlanetSize.TINY)).isZero();
    }
}
