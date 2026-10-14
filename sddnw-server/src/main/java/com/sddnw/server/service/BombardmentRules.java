package com.sddnw.server.service;

/**
 * Бомбардировка колонии — пункт 25.
 * <p>
 * Правило оригинала: бомба «can only be used against planets», урон её — верхняя граница
 * вилки (ядерная 12, термоядерная 24, антиматериевая 40, нейтрониевая 60), как и у всего
 * оружия игры. Чем урон по планете оборачивается, оригинал не публикует; известно лишь, что
 * тяжёлая бомбёжка «kills everyone inhabiting it several times over» и что так «можно
 * уничтожать по планете за ход» (StrategyWiki, «Scorched Earth»).
 * <p>
 * <b>Реконструкция</b> держится на этих двух фактах: житель стоит {@link #DAMAGE_PER_COLONIST}
 * очков урона, постройка — {@link #DAMAGE_PER_BUILDING}. Крейсер с девятью ядерными бомбами
 * (108 урона) убивает за заход двоих, линкор с нейтрониевыми (1200) — колонию в двадцать
 * четыре жителя целиком. Числа правятся только здесь.
 */
public final class BombardmentRules {

    /** Сколько урона убивает одного жителя колонии. */
    public static final int DAMAGE_PER_COLONIST = 50;

    /** Сколько урона рушит одну постройку колонии. */
    public static final int DAMAGE_PER_BUILDING = 100;

    private BombardmentRules() {
    }

    /** Сколько жителей убивает бомбёжка такой силы. */
    public static int colonistsKilled(int damage) {
        return Math.max(0, damage) / DAMAGE_PER_COLONIST;
    }

    /**
     * Шанс биологической бомбы убить жителя у этой колонии — пункт 25, правило MOO II: микробиотика
     * «halves the effectiveness of Death Spores and Bio Terminators», универсальный антидот
     * «decreases the effectiveness... by 75%» (не складываются, берётся лучшее).
     */
    public static int bioChancePercent(int bombPercent, java.util.Collection<String> victimTechnologies) {
        if (victimTechnologies.contains(PopulationCalculator.UNIVERSAL_ANTIDOTE)) {
            return bombPercent * 25 / 100;
        }
        if (victimTechnologies.contains(PopulationCalculator.MICROBIOTICS)) {
            return bombPercent / 2;
        }
        return bombPercent;
    }

    /**
     * Насколько падает доверие жертвы к тому, кто применил биологическое оружие, — пункт 25:
     * «your diplomatic relations will suffer severely». Вдвое больше удавшейся диверсии
     * (реконструкция): это уже не кража, а мор.
     */
    public static final int BIO_TRUST_PENALTY = 30;

    /** Сколько построек рушит бомбёжка такой силы. */
    public static int buildingsRuined(int damage) {
        return Math.max(0, damage) / DAMAGE_PER_BUILDING;
    }
}
