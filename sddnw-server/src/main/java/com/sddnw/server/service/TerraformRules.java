package com.sddnw.server.service;

import com.sddnw.server.domain.enums.MineralRichness;
import com.sddnw.server.domain.enums.PlanetClimate;
import com.sddnw.server.domain.enums.PlanetSize;

import java.util.Optional;

/**
 * Переделка планет — п. 4.1.2.1 (13.10.2026, перенос MOO II): терраформирование, превращение в
 * Гею и искусственные планеты. Числа и переходы — оригинала (StrategyWiki, раздел Biology и
 * Engineering):
 * <ul>
 *     <li>терраформирование «Barren becomes Desert or Tundra, Desert become Arid, Tundra becomes
 *     Swamp, and Ocean, Arid, and Swamp becomes Terran», цена 250/500/750 — дороже с каждым разом,
 *     что планету уже переделывали (кто бы ни переделывал);</li>
 *     <li>превращение в Гею — «upgrades a Terran-class planet into a Gaia-class planet», 500;</li>
 *     <li>искусственная планета — «converting Gas Giants into Huge Barren Abundant planets and
 *     Asteroid Belts into Large Barren Abundant planets», 800.</li>
 * </ul>
 * <b>Реконструкция:</b> бесплодная планета становится пустыней или тундрой поровну («on average
 * 50% of Barren planets terraform to Tundra»); у нас жребий от зерна партии и имени планеты,
 * чтобы партия повторялась. Ядовитый и облучённый климаты в цепочку не входят: из них выводят
 * свои технологии, а их у нас нет.
 * <p>
 * Прежде здесь была линейная цепочка (пустыня → тундра → засушливая → …), которую не звал никто:
 * в игре её и не было.
 */
public final class TerraformRules {

    /** Код технологии терраформирования — Biology, уровень 4. */
    public static final String TERRAFORMING_TECH = "terraforming";

    /** Код превращения в Гею — Biology, уровень 8. */
    public static final String GAIA_TECH = "gaia-transformation";

    /** Код искусственных планет — Engineering, уровень 8. */
    public static final String ARTIFICIAL_PLANET_TECH = "artificial-planet-construction";

    /** Цена первого терраформирования; каждое следующее дороже на столько же, до трёх раз. */
    public static final int TERRAFORM_STEP_COST = 250;

    /** Цена превращения в Гею — число оригинала. */
    public static final int GAIA_COST = 500;

    /** Цена искусственной планеты — число оригинала. */
    public static final int ARTIFICIAL_PLANET_COST = 800;

    private TerraformRules() {
    }

    /**
     * Во что превратится климат терраформированием; пусто — терраформировать нечего.
     *
     * @param tundra бесплодная станет тундрой (иначе — пустыней): жребий у вызывающего
     */
    public static Optional<PlanetClimate> terraformed(PlanetClimate climate, boolean tundra) {
        return switch (climate) {
            case BARREN -> Optional.of(tundra ? PlanetClimate.TUNDRA : PlanetClimate.DESERT);
            case DESERT -> Optional.of(PlanetClimate.ARID);
            case TUNDRA -> Optional.of(PlanetClimate.SWAMP);
            case ARID, SWAMP, OCEAN -> Optional.of(PlanetClimate.TERRAN);
            default -> Optional.empty();
        };
    }

    /** Цена терраформирования планеты, которую уже переделывали {@code times} раз: 250, 500, 750. */
    public static int terraformCost(Integer times) {
        int done = times == null ? 0 : Math.max(0, times);
        return TERRAFORM_STEP_COST * (Math.min(done, 2) + 1);
    }

    /** Годится ли тело под искусственную планету: газовый гигант или пояс астероидов. */
    public static boolean convertible(PlanetClimate climate) {
        return climate == PlanetClimate.GAS_GIANT || climate == PlanetClimate.ASTEROID_BELT;
    }

    /** Размер искусственной планеты: огромная из газового гиганта, большая из пояса. */
    public static PlanetSize artificialSize(PlanetClimate climate) {
        return climate == PlanetClimate.GAS_GIANT ? PlanetSize.HUGE : PlanetSize.LARGE;
    }

    /** Недра искусственной планеты — обычные («Abundant»). */
    public static MineralRichness artificialMinerals() {
        return MineralRichness.AVERAGE;
    }

    /** Жребий «тундра или пустыня» от зерна партии и имени планеты — повторимо. */
    public static boolean tundraRoll(Long gameSeed, String planetName) {
        long seed = (gameSeed == null ? 0L : gameSeed) * 31L + (planetName == null ? 0 : planetName.hashCode());
        return new java.util.Random(seed).nextBoolean();
    }
}
