package com.sddnw.server.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Каждый лучший в чём-то — field-backlog, пункт 116: первые места партии по многим осям.
 * <p>
 * Отставать болезненно там, что важно для самооценки, — значит, осей сравнения должно быть
 * много: проигравший по мощи часто первый в чём-то другом и уходит с этим, а не с одним
 * «проиграл». Правило одно и строгое: <b>только настоящие первые места из записанных чисел</b>
 * — летописи империй и счётчиков партии. Ось, где у всех ноль, первого места не даёт; равные
 * делят его — это тоже правда. Выдуманная награда «за участие» обесценила бы остальные.
 * <p>
 * «Первым дошёл до пятого уровня науки» сюда не входит: ход изучения уровня нигде не записан,
 * а летопись знает лишь число технологий. Точка расширения — ось из летописи по ходу.
 */
public final class FirstPlaceRules {

    /** Оси по порядку показа: сперва летопись «Инфо», потом счётчики партии. */
    public static final List<String> AXES = List.of(
            "POPULATION", "PRODUCTION", "RESEARCH", "FLEET", "TECHNOLOGIES", "COLONIES",
            "COLONIZED", "BATTLES", "CAPTURED", "MONSTERS");

    private FirstPlaceRules() {
    }

    /** Первое место на оси: кто его держит (равные — все) и с каким числом. */
    public record Place(String axis, List<UUID> holders, Integer value) {
    }

    /**
     * @param values по оси — значение каждой империи; отсутствующая империя считается нулём
     */
    public static List<Place> firsts(Map<String, Map<UUID, Integer>> values) {
        List<Place> places = new ArrayList<>();
        for (String axis : AXES) {
            Map<UUID, Integer> byEmpire = values.getOrDefault(axis, Map.of());
            int best = byEmpire.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (best <= 0) {
                continue;
            }
            List<UUID> holders = byEmpire.entrySet().stream()
                    .filter(entry -> entry.getValue() == best)
                    .map(Map.Entry::getKey)
                    .toList();
            places.add(new Place(axis, holders, best));
        }
        return places;
    }

    /** Пустая таблица осей в порядке показа — заполняет её вызывающий. */
    public static Map<String, Map<UUID, Integer>> table() {
        Map<String, Map<UUID, Integer>> table = new LinkedHashMap<>();
        for (String axis : AXES) {
            table.put(axis, new LinkedHashMap<>());
        }
        return table;
    }
}
