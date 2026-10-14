package com.sddnw.server.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Знамёна различимы без цвета — field-backlog, пункты 168 и 173.
 * <p>
 * Правило «никакая информация не передаётся только цветом» проверяется РАСЧЁТОМ, а не
 * взглядом: глаз проверяющего видит палитру так, как видит её он, а не тот из десяти
 * тестеров, у кого дейтеранопия. Каждая пара знамён меряется четырежды — обычным зрением и
 * симуляциями протанопии, дейтеранопии и тританопии (матрицы Machado, Oliveira и Fernandes
 * 2009, полная степень), — расстоянием CIEDE2000, и худшая пара обязана быть не ближе
 * {@link #MIN_DISTANCE}. Порог взят там, где разница уверенно видна рядом стоящими пятнами;
 * у прежней палитры худшая пара была 2,9 (синее и фиолетовое при дейтеранопии), у нынешней
 * — 13,1. Девятое знамя или новый оттенок, нарушающие порог, этот тест не пропустит.
 * <p>
 * Тот же тест держит СОВПАДЕНИЕ оттенков сервера и клиента: экран выбора знамени рисует
 * цвета из {@code ui/race/banners.ts}, а сервер хранит их у игрока, и разойдись они — игрок
 * выбрал бы одно, а получил на карте другое.
 */
class BannerPaletteTest {

    /** Наименьшее допустимое CIEDE2000 между двумя знамёнами при любом виде зрения. */
    private static final double MIN_DISTANCE = 12.0;

    /** Наименьшее допустимое расстояние знамени до фона карты: знамя не тонет в ночи. */
    private static final double MIN_TO_BACKGROUND = 30.0;

    private static final String BACKGROUND = "#05070f";

    private static final Map<String, double[][]> MACHADO = Map.of(
            "протанопия", new double[][]{
                    {0.152286, 1.052583, -0.204868}, {0.114503, 0.786281, 0.099216}, {-0.003882, -0.048116, 1.051998}},
            "дейтеранопия", new double[][]{
                    {0.367322, 0.860646, -0.227968}, {0.280085, 0.672501, 0.047413}, {-0.011820, 0.042940, 0.968881}},
            "тританопия", new double[][]{
                    {1.255528, -0.076749, -0.178779}, {-0.078411, 0.930809, 0.147602}, {0.004733, 0.691367, 0.303900}});

    @Test
    @DisplayName("Любые два знамени различимы обычным зрением и при трёх видах дальтонизма")
    void bannersStayDistinctForColourBlindness() {
        List<String> failures = new ArrayList<>();
        for (String vision : List.of("обычное", "протанопия", "дейтеранопия", "тританопия")) {
            BannerColor[] banners = BannerColor.values();
            for (int i = 0; i < banners.length; i++) {
                for (int j = i + 1; j < banners.length; j++) {
                    double distance = ciede2000(lab(see(banners[i].hex(), vision)), lab(see(banners[j].hex(), vision)));
                    if (distance < MIN_DISTANCE) {
                        failures.add("%s: %s и %s — %.1f".formatted(vision, banners[i], banners[j], distance));
                    }
                }
                double toBackground = ciede2000(lab(see(banners[i].hex(), vision)), lab(see(BACKGROUND, vision)));
                if (toBackground < MIN_TO_BACKGROUND) {
                    failures.add("%s: %s тонет в фоне — %.1f".formatted(vision, banners[i], toBackground));
                }
            }
        }
        assertThat(failures).as("пары знамён ближе порога %s", MIN_DISTANCE).isEmpty();
    }

    @Test
    @DisplayName("Прежний оттенок знамени узнаётся и переводится в нынешний")
    void legacyShadeMapsToCurrent() {
        for (BannerColor banner : BannerColor.values()) {
            assertThat(BannerColor.ofHex(banner.legacyHex().toUpperCase())).contains(banner);
            assertThat(BannerColor.current(banner.legacyHex())).isEqualTo(banner.hex());
        }
        assertThat(BannerColor.current("#47e3d5")).isEqualTo("#47e3d5");
    }

    @Test
    @DisplayName("Оттенки знамён клиента — те же, что у сервера")
    void clientPaletteMatchesServer() throws IOException {
        Path file = Path.of("..", "sddnw-client", "src", "ui", "race", "banners.ts");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        Matcher row = Pattern.compile("code: '(\\w+)', hex: '(#[0-9a-f]{6})', legacy: '(#[0-9a-f]{6})'").matcher(source);
        int seen = 0;
        while (row.find()) {
            BannerColor banner = BannerColor.valueOf(row.group(1));
            assertThat(row.group(2)).as("оттенок %s", banner).isEqualTo(banner.hex());
            assertThat(row.group(3)).as("прежний оттенок %s", banner).isEqualTo(banner.legacyHex());
            seen++;
        }
        assertThat(seen).isEqualTo(BannerColor.values().length);
    }

    /** Цвет глазами данного вида зрения — в линейном RGB. */
    private static double[] see(String hex, String vision) {
        double[] linear = new double[3];
        for (int k = 0; k < 3; k++) {
            double c = Integer.parseInt(hex.substring(1 + 2 * k, 3 + 2 * k), 16) / 255.0;
            linear[k] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        double[][] m = MACHADO.get(vision);
        if (m == null) {
            return linear;
        }
        double[] seen = new double[3];
        for (int r = 0; r < 3; r++) {
            double sum = 0;
            for (int k = 0; k < 3; k++) {
                sum += m[r][k] * linear[k];
            }
            seen[r] = Math.min(1, Math.max(0, sum));
        }
        return seen;
    }

    /** Линейный sRGB → CIELAB (белая точка D65). */
    private static double[] lab(double[] rgb) {
        double x = (0.4124 * rgb[0] + 0.3576 * rgb[1] + 0.1805 * rgb[2]) / 0.95047;
        double y = 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
        double z = (0.0193 * rgb[0] + 0.1192 * rgb[1] + 0.9505 * rgb[2]) / 1.08883;
        double fx = f(x);
        double fy = f(y);
        double fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double f(double t) {
        return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
    }

    /** Расстояние CIEDE2000 (Sharma, Wu, Dalal 2005). */
    private static double ciede2000(double[] one, double[] two) {
        double c1 = Math.hypot(one[1], one[2]);
        double c2 = Math.hypot(two[1], two[2]);
        double cMean = (c1 + c2) / 2;
        double g = 0.5 * (1 - Math.sqrt(Math.pow(cMean, 7) / (Math.pow(cMean, 7) + Math.pow(25, 7))));
        double a1 = (1 + g) * one[1];
        double a2 = (1 + g) * two[1];
        double c1p = Math.hypot(a1, one[2]);
        double c2p = Math.hypot(a2, two[2]);
        double h1p = (Math.toDegrees(Math.atan2(one[2], a1)) + 360) % 360;
        double h2p = (Math.toDegrees(Math.atan2(two[2], a2)) + 360) % 360;
        double dLp = two[0] - one[0];
        double dCp = c2p - c1p;
        double dh = h2p - h1p;
        if (c1p * c2p == 0) {
            dh = 0;
        } else if (dh > 180) {
            dh -= 360;
        } else if (dh < -180) {
            dh += 360;
        }
        double dHp = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dh / 2));
        double lMean = (one[0] + two[0]) / 2;
        double cpMean = (c1p + c2p) / 2;
        double hMean;
        if (c1p * c2p == 0) {
            hMean = h1p + h2p;
        } else if (Math.abs(h1p - h2p) <= 180) {
            hMean = (h1p + h2p) / 2;
        } else {
            hMean = h1p + h2p < 360 ? (h1p + h2p + 360) / 2 : (h1p + h2p - 360) / 2;
        }
        double t = 1 - 0.17 * Math.cos(Math.toRadians(hMean - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hMean))
                + 0.32 * Math.cos(Math.toRadians(3 * hMean + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hMean - 63));
        double dTheta = 30 * Math.exp(-Math.pow((hMean - 275) / 25, 2));
        double rc = 2 * Math.sqrt(Math.pow(cpMean, 7) / (Math.pow(cpMean, 7) + Math.pow(25, 7)));
        double sl = 1 + 0.015 * Math.pow(lMean - 50, 2) / Math.sqrt(20 + Math.pow(lMean - 50, 2));
        double sc = 1 + 0.045 * cpMean;
        double sh = 1 + 0.015 * cpMean * t;
        double rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc;
        return Math.sqrt(Math.pow(dLp / sl, 2) + Math.pow(dCp / sc, 2) + Math.pow(dHp / sh, 2)
                + rt * (dCp / sc) * (dHp / sh));
    }
}
