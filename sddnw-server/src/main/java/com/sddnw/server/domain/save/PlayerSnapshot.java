package com.sddnw.server.domain.save;

import com.sddnw.server.domain.enums.AiObjective;
import com.sddnw.server.domain.enums.AiPersonality;
import com.sddnw.server.domain.enums.PlayerType;

import java.util.List;

/**
 * Игрок в слепке партии.
 *
 * @param homeSystemIndex номер родной системы в списке {@link GameSnapshot#systems()}
 * @param researchPoints  очки, вложенные в текущее исследование — п. 9
 * @param technologies    изученные технологии — п. 9
 * @param credits         казна игрока — п. 10
 * @param raceName        название расы, собранной игроком — п. 7; {@code null} — раса
 *                        из справочника или слепок, снятый до конструктора расы
 * @param raceTraits      коды особенностей расы — п. 7
 * @param espionagePoints накопленные очки шпионажа — п. 13; {@code null} в слепках,
 *                        снятых до появления разведки
 * @param spies           сколько у империи шпионов — п. 13
 * @param freighters      сколько у империи грузовых кораблей — п. 4.1.1; {@code null}
 *                        в слепках, снятых до грузового флота
 * @param agents          сами шпионы с их заданиями — п. 13; {@code null} в слепках,
 *                        снятых до появления заданий
 * @param aiPersonality   характер правителя ИИ — п. 15; {@code null} у человека и в
 *                        слепках, снятых до появления характеров
 * @param aiObjective     устремление правителя ИИ — п. 15; {@code null} там же
 * @param history         летопись империи по ходам — п. 11.1, график окна «Инфо»;
 *                        {@code null} в слепках, снятых до летописи
 * @param accountId       учётная запись человека, игравшего эту империю (п. 3.1, миграция
 *                        077). По ней автосохранение и сажает вернувшихся на ИХ ЖЕ места:
 *                        имя личностью не является, а пропуск слота выдаётся новый на
 *                        каждую загрузку. {@code null} у ИИ, у партий прогона и в слепках,
 *                        снятых до записи хозяина
 * @param researchLastCategoryCode раздел последнего прорыва — п. 9, миграция 080: по нему
 *                        наука без выбранной цели идёт дальше; {@code null} в слепках,
 *                        снятых раньше, и тогда цель просто ждёт выбора, как прежде
 */
public record PlayerSnapshot(
        Integer slot,
        String name,
        PlayerType playerType,
        String raceCode,
        String color,
        Integer homeSystemIndex,
        String researchCategoryCode,
        Integer researchLevelOrder,
        String researchOptionCode,
        Integer researchPoints,
        List<PlayerTechnologySnapshot> technologies,
        Integer credits,
        String raceName,
        List<String> raceTraits,
        Integer espionagePoints,
        Integer spies,
        Integer freighters,
        List<SpySnapshot> agents,
        AiPersonality aiPersonality,
        AiObjective aiObjective,
        List<EmpireHistorySnapshot> history,
        java.util.UUID accountId,
        String researchLastCategoryCode,
        /**
         * Разведанные системы — номерами в списке систем (п. 15); {@code null} у слепков,
         * снятых до тумана войны, и тогда партия поднимается с разведанным по владению.
         */
        List<Integer> exploredSystems,
        /** Память о чужих колониях (п. 15, туман войны); {@code null} у старых слепков. */
        List<PlanetIntelSnapshot> intel,
        /** Увиденные технологии соперников (п. 15); {@code null} у старых слепков. */
        List<ObservedTechSnapshot> observedTechs,
        /** Память о сторожах систем (п. 11.1, п. 15); {@code null} у старых слепков. */
        List<MonsterIntelSnapshot> monsters,
        /** Летопись главных моментов (field-backlog, пункт 34); {@code null} у старых слепков. */
        List<MilestoneSnapshot> milestones,
        /** Ход признанного поражения (пункт 81); {@code null} — не признавал или старый слепок. */
        Integer concededTurn,
        /** Последнее действие в партии (пункт 108); {@code null} у старых слепков. */
        java.time.OffsetDateTime lastActionAt,
        /** Записка к партии (пункт 110); {@code null} — не писал или старый слепок. */
        String note,
        /** Предложения этого игрока, ждущие ответа человека (пункт 182); {@code null} у старых слепков. */
        List<ProposalSnapshot> proposals,
        /** Таймер жалости разведки (backlog-promo, пункт 36); {@code null} у старых слепков — ноль. */
        Integer dryExplorations,
        /** Глава партии (пункт 54); {@code null} у старых слепков — первая. */
        Integer chapter,
        /** Место наставника, которому ученик показал империю (пункт 184); {@code null} — никому. */
        Integer mentorSlot,
        /** Уровень игры империи ИИ (пункт 9); {@code null} — обычный или старый слепок. */
        String aiSkill
) {
}
