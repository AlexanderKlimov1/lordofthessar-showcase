package com.sddnw.server.service;

import com.sddnw.server.domain.PopulationJobs;

import java.util.function.IntUnaryOperator;

/**
 * «Жители сами» — field-backlog, пункт 91: колония, которой игрок это доверил, каждый ход держит
 * ровно столько фермеров, сколько нужно, чтобы прокормить себя, а остальных делит между заводом и
 * лабораторией В ТОЙ ЖЕ ДОЛЕ, что задал игрок.
 * <p>
 * <b>Зачем.</b> Самая частая рутина MOO II — переставлять жителей каждый ход: колония растёт, новый
 * житель встаёт не туда, еды становится то мало, то лишку, и игрок поправляет это руками в каждой
 * колонии каждый ход. Лучшая игра тут требует нудной работы — ровно то, от чего пункт 91 велит
 * лечить автоматизацией, а не запретом (пункт 95). Решение остаётся за игроком: долю рабочих и
 * учёных он задаёт сам, расстановкой, а правило только держит её и кормит колонию.
 * <p>
 * <b>Колония кормит себя сама</b>, без расчёта на подвоз грузовиками: подвоз — решение империи, а
 * не колонии, и колония, рассчитавшая на чужую еду, голодала бы, стоит грузовикам уйти в рейс.
 * Империя-специалист (кормовые миры и голодные заводы) переставляет жителей руками — правило для
 * неё не годится, и выключить его можно в один щелчок. Где еду не растят вовсе, фермеров нет.
 * <p>
 * Числа берутся у тех же правил, что считают колонию ({@link PopulationCalculator#food}), — второго
 * свода нет. Правило статическое и проверяется без базы ({@code AutoJobsRulesTest}).
 */
public final class AutoJobsRules {

    private AutoJobsRules() {
    }

    /**
     * Занятия колонии с «жители сами».
     *
     * @param population жителей
     * @param current    нынешние занятия — по ним берётся доля рабочих и учёных
     * @param foodAt     еда колонии при данном числе фермеров
     * @param need       сколько колония съедает
     * @param canFarm    растят ли здесь еду вообще
     */
    public static PopulationJobs jobs(int population, PopulationJobs current, IntUnaryOperator foodAt,
                                      int need, boolean canFarm) {
        int farmers = 0;
        if (canFarm) {
            farmers = population;
            for (int count = 0; count <= population; count++) {
                if (foodAt.applyAsInt(count) >= need) {
                    farmers = count;
                    break;
                }
            }
        }
        int rest = population - farmers;
        int workers = current.workers();
        int scientists = current.scientists();
        int share = workers + scientists == 0 ? 0 : Math.round(rest * (float) scientists / (workers + scientists));
        return new PopulationJobs(farmers, rest - share, share);
    }
}
