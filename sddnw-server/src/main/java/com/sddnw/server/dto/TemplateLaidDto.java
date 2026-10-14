package com.sddnw.server.dto;

import java.util.List;

/**
 * Итог закладки шаблона — field-backlog, пункт 97: колония и то, что из шаблона ПРОПУЩЕНО, с
 * причиной словами. Шаблон пишут до партии, и несбыточное в нём пропускается — но молча
 * пропущенное читается как поломка: «я же записал туда завод».
 */
public record TemplateLaidDto(
        PlanetDto planet,
        List<Skipped> skipped
) {

    /** Пропущенная строка шаблона: что и почему. */
    public record Skipped(String name, String reason) {
    }
}
