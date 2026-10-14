package com.sddnw.server.dto;

import java.util.UUID;

/**
 * Планета звёздной системы — п. 4.1.
 *
 * @param foodPerFarmer      еда с одного фермера на этом климате — п. 4.1.2
 * @param productionPerWorker продукция с одного рабочего при этом богатстве минералами — п. 4.1.3
 * @param colony             колония на планете; у незаселённой планеты её нет
 * @param gravity            тяжесть мира — п. 4.1: непривычная расе роняет производство
 * @param colonyBaseReady    колония достроила колониальную базу и ждёт, какую планету
 *                           системы заселить — п. 4.1
 * @param find               находка на планете — п. 4.1: золотые жилы, самоцветы, туземцы
 *                           или наследие ушедшей цивилизации; пусто — планета обыкновенная
 * @param findLabel          имя находки на языке запроса
 * @param findDescription    что находка даёт и при каком условии — теми же словами, какими
 *                           это объясняет оригинал
 */
public record PlanetDto(
        UUID id,
        Integer orbit,
        String name,
        String size,
        String sizeLabel,
        String climate,
        String climateLabel,
        Integer populationMultiplierPercent,
        Boolean colonizable,
        Integer foodPerFarmer,
        String gravity,
        String gravityLabel,
        /**
         * Чего стоит эта тяжесть колонии, в процентах производства — п. 4.1; ноль у
         * привычной.
         * <p>
         * Считается для расы ОБЫЧНОЙ тяжести, как и в списке планет оригинала
         * (`docs/moo2/planets.png`: под «Low G» стоит «-25% prod»): список читают, выбирая,
         * куда слать колониальный корабль, и цена там названа общая, без поправки на расу
         * смотрящего.
         */
        Integer gravityPenaltyPercent,
        String minerals,
        String mineralsLabel,
        Integer productionPerWorker,
        Integer maxPopulation,
        Integer population,
        UUID ownerPlayerId,
        Boolean homeworld,
        ColonyDto colony,
        Boolean colonyBaseReady,
        String find,
        String findLabel,
        String findDescription,
        /**
         * Оборона ЧУЖОЙ колонии, как её видит игрок (п. 15, туман войны): названия
         * платформ, батарей, щитов и казарм. У своей колонии пусто — она видна целиком
         * через {@code colony}.
         */
        java.util.List<String> defences,
        /**
         * Ход, на котором чужую колонию видели в последний раз (п. 15); пусто — видна
         * сейчас. Без глаз в системе игрок читает память, а не правду.
         */
        Integer seenTurn,
        /**
         * Радиус варп-перехватчика ЧУЖОЙ колонии в парсеках, как его видит игрок (п. 8, п. 15):
         * перехватчик виден со стороны, как оборона. Пусто — его нет или колония своя. По нему
         * клиент считает срок перелёта тем же правилом, что {@code FlightRules.travelTurns}.
         */
        Integer interdictorParsecs
) {
}
