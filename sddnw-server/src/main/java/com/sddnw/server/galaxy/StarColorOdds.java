package com.sddnw.server.galaxy;

import com.sddnw.server.domain.enums.MineralRichness;
import com.sddnw.server.domain.enums.PlanetClimate;
import com.sddnw.server.domain.enums.StarColor;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Цвет звезды — подсказка о её планетах, как в оригинале: field-backlog, пункт 132. Неразведанная
 * звезда становится малым разрывом («белая — скорее богатые недра, проверить»), а не пустой точкой.
 * <p>
 * <b>Первоисточник</b> — замер генератора карт MOO II по партиям оригинала («Master of Orion II
 * Online: The MOO2 map generator», 2006): доли климатов (галактика среднего возраста) и недр
 * по цвету звезды. Из шести цветов оригинала у нас три — красный, белый, жёлтый, — и взяты их строки.
 * <p>
 * <b>Реконструкция, а не перенос.</b> Числа оригинала как есть сделали бы пригодные миры в разы
 * реже нынешних (у жёлтой звезды Terran — 4 %, Gaia — 1 %), и игра сломалась бы в расселении и
 * балансе. Поэтому ОБЩАЯ доля климатов и недр остаётся нашей ({@code PlanetGenerator}), а цвет
 * сдвигает её во столько раз, во сколько оригинал отличает этот цвет от среднего по трём нашим
 * цветам. Ноль оригинала остаётся нулём: у красной звезды богатых недр нет вовсе — это и есть
 * самая ясная примета. Пояс астероидов и газовый гигант оригиналом в таблице не учтены — их доля
 * от цвета не зависит.
 */
public final class StarColorOdds {

    private static final Map<StarColor, Map<PlanetClimate, Double>> CLIMATES = new EnumMap<>(StarColor.class);
    private static final Map<StarColor, Map<MineralRichness, Double>> MINERALS = new EnumMap<>(StarColor.class);

    static {
        CLIMATES.put(StarColor.WHITE, climates(16.6, 36.8, 27.1, 6.0, 4.3, 1.7, 1.0, 2.6, 3.2, 0.7));
        CLIMATES.put(StarColor.YELLOW, climates(12.7, 26.8, 30.2, 5.9, 7.7, 4.4, 3.8, 3.1, 4.2, 1.2));
        CLIMATES.put(StarColor.RED, climates(16.2, 12.9, 49.7, 3.0, 6.6, 2.2, 2.3, 2.5, 4.0, 0.6));
        MINERALS.put(StarColor.WHITE, minerals(0, 19.5, 41.2, 29.4, 9.8));
        MINERALS.put(StarColor.YELLOW, minerals(0, 30.3, 40.4, 20.7, 8.5));
        MINERALS.put(StarColor.RED, minerals(18.6, 38.3, 42.3, 0, 0));
    }

    private StarColorOdds() {
    }

    /** Веса климатов у звезды этого цвета: наши веса, сдвинутые по оригиналу. */
    public static List<PlanetGenerator.Weighted<PlanetClimate>> climates(
            List<PlanetGenerator.Weighted<PlanetClimate>> base, StarColor color) {
        return tilt(base, CLIMATES, color);
    }

    /** Веса недр у звезды этого цвета. */
    public static List<PlanetGenerator.Weighted<MineralRichness>> minerals(
            List<PlanetGenerator.Weighted<MineralRichness>> base, StarColor color) {
        return tilt(base, MINERALS, color);
    }

    /**
     * Вес — наш, умноженный на отношение доли у этого цвета к средней по цветам. Веса целые, поэтому
     * всё умножено на сто: сдвиг в десятые доли не теряется на округлении.
     */
    private static <T extends Enum<T>> List<PlanetGenerator.Weighted<T>> tilt(
            List<PlanetGenerator.Weighted<T>> base, Map<StarColor, Map<T, Double>> table, StarColor color) {
        Map<T, Double> own = color == null ? null : table.get(color);
        return base.stream().map(weighted -> {
            Double share = own == null ? null : own.get(weighted.value());
            if (share == null) {
                return new PlanetGenerator.Weighted<>(weighted.value(), weighted.weight() * 100);
            }
            double mean = table.values().stream().mapToDouble(row -> row.getOrDefault(weighted.value(), 0.0))
                    .average().orElse(0);
            double factor = mean == 0 ? 1 : share / mean;
            return new PlanetGenerator.Weighted<>(weighted.value(), (int) Math.round(weighted.weight() * 100 * factor));
        }).toList();
    }

    private static Map<PlanetClimate, Double> climates(double toxic, double radiated, double barren, double desert,
                                                       double tundra, double ocean, double swamp, double arid,
                                                       double terran, double gaia) {
        Map<PlanetClimate, Double> row = new EnumMap<>(PlanetClimate.class);
        row.put(PlanetClimate.TOXIC, toxic);
        row.put(PlanetClimate.RADIATED, radiated);
        row.put(PlanetClimate.BARREN, barren);
        row.put(PlanetClimate.DESERT, desert);
        row.put(PlanetClimate.TUNDRA, tundra);
        row.put(PlanetClimate.OCEAN, ocean);
        row.put(PlanetClimate.SWAMP, swamp);
        row.put(PlanetClimate.ARID, arid);
        row.put(PlanetClimate.TERRAN, terran);
        row.put(PlanetClimate.GAIA, gaia);
        return row;
    }

    private static Map<MineralRichness, Double> minerals(double ultraPoor, double poor, double average, double rich,
                                                         double ultraRich) {
        Map<MineralRichness, Double> row = new EnumMap<>(MineralRichness.class);
        row.put(MineralRichness.ULTRA_POOR, ultraPoor);
        row.put(MineralRichness.POOR, poor);
        row.put(MineralRichness.AVERAGE, average);
        row.put(MineralRichness.RICH, rich);
        row.put(MineralRichness.ULTRA_RICH, ultraRich);
        return row;
    }
}
