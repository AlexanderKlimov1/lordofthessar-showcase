package com.sddnw.server.dto;

import java.util.List;

/**
 * Колония на планете — п. 4.1 и п. 10: чем заняты жители, что колония даёт за ход
 * и что она строит.
 * <p>
 * У незаселённой планеты колонии нет, и это поле в ответе отсутствует.
 * <p>
 * Выработка отдаётся и итогом, и ставками: ставка это выработка одного жителя с учётом
 * зданий, постоянная часть — то, что здания дают сами по себе. По ним клиент считает,
 * что выйдет из ещё не применённого перераспределения, не повторяя правил зданий.
 *
 * @param populationK          население в тысячах жителей: работать умеют только целые
 * @param maxPopulation        вместимость планеты со зданиями — биосферы её поднимают
 * @param growthK              прирост населения за ход в тысячах; отрицательный — колония голодает
 * @param medicineBonusPercent прибавка к рождаемости от медицинских технологий — п. 9
 * @param housingBonusPercent  прибавка к рождаемости от домов, если колония их строит
 * @param growthFlatK          постоянная прибавка к приросту от зданий, тысяч жителей
 * @param foodConsumption      сколько еды съедает колония: по единице на жителя
 * @param foodBalance          остаток еды: производство минус потребление, отрицательный — голод
 * @param foodDelivered        сколько еды довёз грузовой флот империи — п. 4.1.1; без
 *                             грузовиков ноль, и колония ест только своё
 * @param income               доход колонии в кредитах за ход, уже за вычетом содержания зданий
 * @param upkeep               содержание зданий колонии в кредитах за ход
 * @param productionPerColonist производство с каждого жителя, чем бы он ни был занят, —
 *                             п. 10: так работает рециклотрон, и эта часть не пачкает
 * @param productionDirtyFlat  твёрдое производство, которое ПАЧКАЕТ, — п. 10: роботизированный
 *                             завод (по недрам планеты); в чистую часть добычи не входит
 * @param pollution            сколько единиц производства уходит на уборку за собой — п. 10;
 *                             в {@code production} их уже нет
 * @param pollutionTolerance   сколько производства планета терпит без грязи: её размер,
 *                             поднятый зданиями
 * @param pollutionDivisor     во сколько раз чище считается производство: 1 без очистных
 *                             сооружений, 2, 4 или 8 с ними
 * @param pollutionFree        грязи нет вовсе — свалка в ядре планеты или неприхотливая
 *                             раса (п. 7)
 * @param projectCode          что колония строит; пусто — ничего, и производство пропадает
 * @param projectCost          во что обойдётся проект; у домов и товаров стоимости нет
 * @param buildings            построенные здания
 * @param available            что колония может строить прямо сейчас
 * @param repeatBuild          очередь идёт по кругу — п. 10, кнопка REPEAT BUILD
 * @param autoBuild            колония сама берёт следующее дело — п. 10, кнопка AUTO BUILD
 * @param queue                очередь стройки — п. 10: что колония заложит следом,
 *                             по порядку
 * @param buyCost              во что обойдётся выкуп недостающего по текущей стройке —
 *                             п. 10; пусто, когда выкупать нечего (дома, товары, пустая
 *                             стройка или уже оплаченное)
 * @param soldTurn             ход, когда колония в последний раз продала постройку — п. 10;
 *                             пусто — не продавала ни разу. За ход продаётся одна, и по
 *                             этому числу экран видит, что предел уже выбран
 */
public record ColonyDto(
        Integer populationK,
        Integer maxPopulation,
        Integer growthK,
        Integer medicineBonusPercent,
        Integer housingBonusPercent,
        Integer growthFlatK,

        Integer farmers,
        Integer workers,
        Integer scientists,

        Integer foodPerFarmer,
        Integer foodFlat,
        Integer productionPerWorker,
        Integer productionFlat,
        Integer productionPerColonist,
        Integer productionDirtyFlat,
        Integer researchPerScientist,
        Integer researchFlat,

        Integer food,
        Integer production,
        Integer research,
        Integer foodConsumption,
        Integer foodBalance,
        Integer foodDelivered,
        Integer income,
        Integer upkeep,

        Integer pollution,
        Integer pollutionTolerance,
        Integer pollutionDivisor,
        Boolean pollutionFree,

        String projectCode,
        String projectName,
        Integer projectPoints,
        Integer projectCost,
        List<ColonyProjectDto> buildings,
        List<ColonyProjectDto> available,
        List<ColonyProjectDto> queue,
        Boolean repeatBuild,
        Boolean autoBuild,
        /** «Жители сами» — field-backlog, пункт 91. */
        Boolean autoJobs,
        Integer buyCost,
        Integer soldTurn,

        /** Сколько жителей колонии — подданные, взятые с боем (п. 12); 0 — все свои. */
        Integer alienPopulation,
        /** Через сколько ходов один из них станет своим; пусто — подданных нет. */
        Integer assimilationTurns,

        /**
         * Мораль колонии в процентах — п. 10.1: процент ко всему, что дают жители. Уже сидит
         * в {@code foodPercent}, {@code productionPercent}, {@code researchPercent} и в доходе.
         */
        Integer moralePercent,
        /** Из чего мораль сложилась — строками подсказки. */
        List<MoralePartDto> moraleParts,
        /** Строй морали не знает (объединение) — экран говорит это словами, а не нулём. */
        Boolean moraleIgnored,

        /**
         * Проценты к выработке фермера, рабочего и учёного — п. 7, п. 10.1: строй, тяжесть,
         * мораль. Клиент пересчитывает выработку при переносе жителя сам, и без процентов его
         * число расходилось бы с серверным.
         */
        Integer foodPercent,
        Integer productionPercent,
        Integer researchPercent,
        /**
         * Из чего сложились еда, производство и наука — field-backlog, пункт 40: слагаемые теми
         * же функциями, что считают итог. Порядок — база, постройки, раса и прочие источники,
         * лидер, уборка.
         */
        List<OutputPartDto> outputParts,
        /**
         * Стоят пищевые репликаторы (п. 4.1.1): недостающую еду колония делает из производства,
         * и клиент при переносе жителя считает это тем же правилом.
         */
        Boolean foodReplicator
) {
    /**
     * Слагаемое выработки: ресурс (FOOD, PRODUCTION, RESEARCH), источник (BASE, BUILDING, RACE,
     * ARTIFACTS, FIND, GRAVITY, MORALE, TREATIES, LEADER, POLLUTION), имя постройки и вклад —
     * на жителя дела, твёрдо, процентом и на всякого жителя (переработка).
     */
    public record OutputPartDto(String resource, String source, String name, Integer perUnit,
                                Integer flat, Integer percent, Integer perColonist) {
    }


    /** Слагаемое морали: откуда ({@code MoraleRules.Source}) и сколько процентов. */
    public record MoralePartDto(String source, Integer percent) {
    }
}
