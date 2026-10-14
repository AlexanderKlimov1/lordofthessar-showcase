package com.sddnw.server.domain.save;

import java.util.List;

/**
 * Главный момент партии в слепке — field-backlog, пункт 34: летопись поднятой партии
 * продолжается, а не начинается заново. Ключ словаря с подстановками — как в базе.
 */
public record MilestoneSnapshot(
        Integer turn,
        String kind,
        String key,
        List<String> args,
        /** Пик ли это (пункт 138); пусто — слепок до хроники, тогда в нём одни пики. */
        Boolean peak
) {
}
