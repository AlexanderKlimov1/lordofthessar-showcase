package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Старт игры — п. 3.2. Стартовать может только создатель игры,
 * свободные слоты добираются ИИ-игроками до общего количества в 8.
 *
 * @param aiEmpires чем играют империи ИИ — этап 1 балансировки
 *                  (`balance-metrics-works.txt`): прогонам нужно задавать расы соперников,
 *                  иначе курс «очко → сила» не измерить и сборки не сравнить. Пусто —
 *                  расы раздаются как обычно, готовыми из справочника; список короче
 *                  числа империй — остальным тоже достанутся готовые.
 */
public record StartGameRequest(
        @NotBlank
        String accessToken,

        List<AiEmpireDesign> aiEmpires
) {

    /**
     * Раса одной империи ИИ для прогона.
     *
     * @param name   название расы; пусто — раса из справочника со своим именем
     * @param traits коды особенностей: то, ценность чего и меряется
     * @param skill    уровень игры этой империи ({@code AiSkill}, field-backlog, пункт 9) — прогон
     *                 сравнения уровней сажает за один стол разные; пусто — уровень партии
     * @param raceCode готовая раса этой империи — книга дебютов (пункт 9) меряет начало каждой расы,
     *                 а раздача по порядку справочника сажает за стол всегда одних и тех же; пусто —
     *                 первая свободная, как обычно
     */
    public record AiEmpireDesign(String name, List<String> traits, String skill, String raceCode) {

        /** Раса без своего уровня — уровень партии. */
        public AiEmpireDesign(String name, List<String> traits) {
            this(name, traits, null, null);
        }
    }
}
