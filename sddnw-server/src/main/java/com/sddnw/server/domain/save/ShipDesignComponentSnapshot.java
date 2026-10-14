package com.sddnw.server.domain.save;

/** Компонент проекта корабля в слепке партии — п. 8. */
public record ShipDesignComponentSnapshot(
        String componentCode,
        Integer count,
        /**
         * Модификации ствола кодами через запятую (03.10.2026): прежде слепок их терял, и
         * ближняя оборона и бронебойность после загрузки исчезали со всех проектов.
         */
        String modifications
) {
}
