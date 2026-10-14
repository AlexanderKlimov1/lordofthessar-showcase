package com.sddnw.server.domain.save;

/**
 * Набор справочников партии в слепке — field-backlog, пункт 42: поднятая партия обязана играть
 * теми же правилами, что и сохранённая, а набор основателя к тому времени мог измениться или
 * исчезнуть. Поэтому в слепок идёт сама копия, а не ссылка на набор.
 */
public record GameModSnapshot(
        String name,
        String buildings,
        String ships,
        String technologies,
        String leaders
) {
}
