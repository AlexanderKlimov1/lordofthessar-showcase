package com.sddnw.server.domain.enums;

import java.util.Random;

/**
 * Особенность галактики — field-backlog, пункт 94: каждая партия приносит хоть один новый узор в
 * выученную игру. Одна на партию, жребием от зерна или выбором в окне новой игры (решение хозяина
 * проекта 08.10.2026: умолчание окна — жребий). Живёт признаком партии ({@code game.galaxy_feature}),
 * ложится в слепок сохранения; гостевая партия и балансовые прогоны идут без особенностей — первая
 * учит обычной игре, вторые меряют расы, а не галактики.
 * <p>
 * Числа — реконструкция по правилу «вдвое или пополам» (field-backlog, пункт 89): особенность
 * должна быть заметна с первого взгляда на карту, иначе она не узор, а шум.
 */
public enum GalaxyFeature {
    /** Бедная галактика: половина плодородных миров вне родных звёзд — бесплодные или ядовитые. */
    POOR,
    /** Логово чудищ: сторожей вдвое больше — 40 % систем вместо 20 %. */
    LAIRS,
    /** Руины повсюду: находок втрое больше — 18 % планет вместо 6 %. */
    RUINS,
    /** Тесные соседи: родные звёзды стоят тесно, как у гостевой партии ({@code game.close_start}). */
    CLOSE,
    /** Тёмная галактика: сканеры видят вдвое ближе. */
    DARK;

    /** Код окна новой игры «выбрать жребием». */
    public static final String RANDOM = "RANDOM";
    /** Код окна новой игры «без особенности». */
    public static final String NONE = "NONE";

    public static final int LAIRS_MONSTER_PERCENT = 40;
    public static final int RUINS_FIND_PERCENT = 18;
    public static final int POOR_DOWNGRADE_PERCENT = 50;

    /**
     * Что достаётся партии по запросу: жребий — от зерна (у партии с тем же зерном и особенность
     * та же, а прогон галактики своего жребия не тратит), «нет» и пусто — без особенности.
     * Неизвестный код — тоже без особенности: запрос мимо окна новой игры ничего не ломает.
     */
    public static String resolve(String requested, Long seed) {
        if (requested == null || NONE.equals(requested)) {
            return null;
        }
        if (RANDOM.equals(requested)) {
            GalaxyFeature[] all = values();
            return all[new Random(seed ^ 0x94_94_94_94L).nextInt(all.length)].name();
        }
        GalaxyFeature feature = of(requested);
        return feature == null ? null : feature.name();
    }

    /** Особенность по коду партии; пусто — нет. */
    public static GalaxyFeature of(String code) {
        if (code == null) {
            return null;
        }
        for (GalaxyFeature feature : values()) {
            if (feature.name().equals(code)) {
                return feature;
            }
        }
        return null;
    }

    /** Есть ли у партии эта особенность. */
    public static boolean is(String code, GalaxyFeature feature) {
        return feature.name().equals(code);
    }
}
