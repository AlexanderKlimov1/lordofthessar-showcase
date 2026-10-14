package com.sddnw.server.service;

import com.sddnw.server.domain.PopulationJobs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** «Жители сами» — field-backlog, пункт 91. */
class AutoJobsRulesTest {

    @Test
    void feedsTheColonyWithTheFewestFarmers() {
        // Фермер даёт три единицы, колония из восьми съедает восемь: трёх фермеров хватает (9 ≥ 8).
        PopulationJobs jobs = AutoJobsRules.jobs(8, new PopulationJobs(5, 3, 0), farmers -> farmers * 3, 8, true);
        assertThat(jobs.farmers()).isEqualTo(3);
        assertThat(jobs.workers() + jobs.scientists()).isEqualTo(5);
    }

    @Test
    void keepsThePlayersShareOfWorkersAndScientists() {
        // Игрок держал рабочих и учёных поровну — правило держит ту же долю у тех, кто не на поле.
        PopulationJobs jobs = AutoJobsRules.jobs(10, new PopulationJobs(2, 4, 4), farmers -> farmers * 5, 10, true);
        assertThat(jobs).isEqualTo(new PopulationJobs(2, 4, 4));
        PopulationJobs grown = AutoJobsRules.jobs(12, new PopulationJobs(2, 4, 4), farmers -> farmers * 5, 12, true);
        assertThat(grown.farmers()).isEqualTo(3);
        assertThat(grown.workers() + grown.scientists()).isEqualTo(9);
        assertThat(Math.abs(grown.workers() - grown.scientists())).isLessThanOrEqualTo(1);
    }

    @Test
    void noFarmersWhereNothingGrows() {
        PopulationJobs jobs = AutoJobsRules.jobs(4, new PopulationJobs(2, 2, 0), farmers -> 0, 4, false);
        assertThat(jobs).isEqualTo(new PopulationJobs(0, 4, 0));
    }

    @Test
    void everyoneFarmsWhenEvenThatIsNotEnough() {
        PopulationJobs jobs = AutoJobsRules.jobs(3, new PopulationJobs(0, 3, 0), farmers -> farmers, 6, true);
        assertThat(jobs).isEqualTo(new PopulationJobs(3, 0, 0));
    }

    @Test
    void allWorkersWhenThePlayerSetNoShare() {
        PopulationJobs jobs = AutoJobsRules.jobs(5, new PopulationJobs(5, 0, 0), farmers -> farmers * 4, 4, true);
        assertThat(jobs).isEqualTo(new PopulationJobs(1, 4, 0));
    }
}
