package com.sddnw.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.config.GameProperties;
import com.sddnw.server.domain.RaceEffects;
import com.sddnw.server.domain.ShipComponent;
import com.sddnw.server.domain.ShipHull;
import com.sddnw.server.domain.ShipStats;
import com.sddnw.server.domain.enums.ShipEffectType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Особые модули кораблей MOO II, перенесённые 13.10.2026 (п. 8): фокусировщик энергии, прицел
 * дальнего боя, «Ахилл», энергопоглотитель, ремонтный модуль. Модули берутся из НАСТОЯЩЕГО
 * справочника — опечатка в коде или действии иначе молча оставила бы модуль пустым.
 */
class ShipSpecialsTest {

    private final BattleRules rules = new BattleRules();
    private final ShipDesignRules designRules = new ShipDesignRules();

    private final ShipCatalog catalog = new ShipCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    private ShipDesignRules.Item one(String code) {
        return new ShipDesignRules.Item(catalog.component(code), 1);
    }

    @Test
    @DisplayName("Модули несут числа оригинала и требуют своей технологии")
    void specialsCarryOriginalNumbers() {
        assertThat(catalog.component("high-energy-focus").amount(ShipEffectType.BEAM_DAMAGE_PERCENT)).isEqualTo(50);
        assertThat(catalog.component("rangemaster-targeting-unit").amount(ShipEffectType.LONG_RANGE_TARGETING)).isEqualTo(3);
        assertThat(catalog.component("achilles-targeting-unit").amount(ShipEffectType.ARMOUR_BYPASS)).isEqualTo(3);
        assertThat(catalog.component("energy-absorber").amount(ShipEffectType.DAMAGE_ABSORB_PERCENT)).isEqualTo(25);
        assertThat(catalog.component("security-stations").amount(ShipEffectType.MARINE_DEFENSE_PERCENT)).isEqualTo(20);
        assertThat(catalog.component("automated-repair-unit").amount(ShipEffectType.REPAIR_PERCENT)).isEqualTo(20);
        assertThat(catalog.component("warp-dissipater").amount(ShipEffectType.RETREAT_BLOCK)).isEqualTo(1);
        assertThat(catalog.component("scout-lab").amount(ShipEffectType.SCOUT_LAB)).isEqualTo(1);
        assertThat(catalog.component("hyper-x-capacitors").amount(ShipEffectType.DOUBLE_BEAM_VOLLEY)).isEqualTo(1);
        assertThat(catalog.component("fast-missile-racks").amount(ShipEffectType.DOUBLE_MISSILE_VOLLEY)).isEqualTo(1);
        for (String code : List.of("high-energy-focus", "rangemaster-targeting-unit", "achilles-targeting-unit",
                "energy-absorber", "security-stations", "automated-repair-unit", "warp-dissipater", "scout-lab")) {
            ShipComponent component = catalog.component(code);
            assertThat(component.requiredTechCode()).as(code).isEqualTo(code);
        }
    }

    @Test
    @DisplayName("Фокусировщик прибавляет половину лучам и снарядам, но не ракетам — и в бою, и в силе корабля")
    void focusRaisesBeamsOnly() {
        BattleRules.Shot laser = rules.shots(List.of(one("laser-cannon"))).get(0);
        BattleRules.Shot missile = rules.shots(List.of(one("nuclear-missile"))).get(0);
        assertThat(rules.focused(laser, 10, 50)).isEqualTo(15);
        assertThat(rules.focused(missile, 10, 50)).isEqualTo(10);

        ShipHull cruiser = catalog.hull("cruiser");
        ShipStats plain = designRules.stats(cruiser, List.of(one("laser-cannon")), RaceEffects.NONE);
        ShipStats focused = designRules.stats(cruiser, List.of(one("laser-cannon"), one("high-energy-focus")),
                RaceEffects.NONE);
        assertThat(focused.attack()).isGreaterThan(plain.attack());
    }

    @Test
    @DisplayName("Прицел дальнего боя: луч на 18 клетках бьёт как на шести")
    void rangemasterShortensDissipation() {
        BattleRules.Shot laser = rules.shots(List.of(one("laser-cannon"))).get(0);
        assertThat(rules.damageAtRange(laser, 18 / 3)).isGreaterThan(rules.damageAtRange(laser, 18));
    }

    @Test
    @DisplayName("Энергопоглотитель продлевает жизнь корабля в силе, ремонтный модуль чинит пятую часть")
    void absorberAndRepair() {
        ShipHull cruiser = catalog.hull("cruiser");
        ShipStats plain = designRules.stats(cruiser, List.of(one("laser-cannon")), RaceEffects.NONE);
        ShipStats absorbing = designRules.stats(cruiser, List.of(one("laser-cannon"), one("energy-absorber")),
                RaceEffects.NONE);
        assertThat(absorbing.damageAbsorbPercent()).isEqualTo(25);
        assertThat(designRules.power(absorbing)).isGreaterThan(designRules.power(plain));
        // Мегафлюксеры: +25 % места корпусу.
        assertThat(cruiser.withSpacePercent(25).space()).isEqualTo(cruiser.space() * 125 / 100);

        assertThat(rules.repaired(50, 100, 20)).isEqualTo(70);
        assertThat(rules.repaired(95, 100, 20)).isEqualTo(100);
        assertThat(rules.repaired(50, 100, 0)).isEqualTo(50);
    }

    @Test
    @DisplayName("Щит оригинала: класс срезает с выстрела, запас поглощает остаток и восстанавливается на 30 % (с конденсатором 90 %)")
    void originalShield() {
        // Класс III у фрегата: срезает 3, запас 3 × 5 × 1 = 15.
        ShipStats frigate = designRules.stats(catalog.hull("frigate"), List.of(one("class-iii-shield")), RaceEffects.NONE);
        assertThat(frigate.shieldClass()).isEqualTo(3);
        assertThat(frigate.shield()).isEqualTo(15);
        // У линкора тот же класс держит вчетверо больше: множитель корпуса 4.
        ShipStats battleship = designRules.stats(catalog.hull("battleship"), List.of(one("class-iii-shield")),
                RaceEffects.NONE);
        assertThat(battleship.shield()).isEqualTo(60);

        // Выстрел в 10: 3 срезано классом, 7 ушли в запас — до брони не дошло ничего.
        BattleRules.ShieldHit first = rules.shieldHit(10, 3, 15);
        assertThat(first.through()).isZero();
        assertThat(first.pool()).isEqualTo(8);
        // Следующий в 20: 3 срезано, 8 добили запас, 9 прошли к броне — щит упал.
        BattleRules.ShieldHit second = rules.shieldHit(20, 3, first.pool());
        assertThat(second.through()).isEqualTo(9);
        assertThat(second.pool()).isZero();
        // Упавший щит всё равно срезает класс.
        assertThat(rules.shieldHit(5, 3, 0).through()).isEqualTo(2);

        // Восстановление: 30 % полного запаса за круг, с конденсатором втрое.
        assertThat(rules.recharged(0, 60, 1)).isEqualTo(18);
        assertThat(rules.recharged(0, 60, 3)).isEqualTo(54);
        assertThat(rules.recharged(50, 60, 3)).isEqualTo(60);
        assertThat(catalog.component("shield-capacitor").amount(ShipEffectType.SHIELD_RECHARGE_MULTIPLIER)).isEqualTo(3);
    }

    @Test
    @DisplayName("Маскировка, стазис и биологические бомбы — числа оригинала")
    void cloakStasisAndBio() {
        assertThat(catalog.component("stealth-field").amount(ShipEffectType.STEALTH)).isEqualTo(1);
        assertThat(catalog.component("cloaking-device").amount(ShipEffectType.CLOAK)).isEqualTo(80);
        assertThat(catalog.component("phasing-cloak").amount(ShipEffectType.PHASING)).isEqualTo(10);
        assertThat(catalog.component("stasis-field").amount(ShipEffectType.STASIS)).isEqualTo(3);
        assertThat(designRules.bioBombs(List.of(new ShipDesignRules.Item(catalog.component("death-spores"), 3))))
                .containsExactly(10, 10, 10);
        assertThat(designRules.bioBombs(List.of(one("bio-terminator")))).containsExactly(20);
        // Споры урона не дают: в бомбовый залп они не входят.
        assertThat(designRules.bombSalvo(List.of(one("death-spores")))).isZero();
        // Медицина жертвы: микробиотика вдвое, антидот вчетверо.
        assertThat(BombardmentRules.bioChancePercent(20, List.of())).isEqualTo(20);
        assertThat(BombardmentRules.bioChancePercent(20, List.of(PopulationCalculator.MICROBIOTICS))).isEqualTo(10);
        assertThat(BombardmentRules.bioChancePercent(20, List.of(PopulationCalculator.UNIVERSAL_ANTIDOTE,
                PopulationCalculator.MICROBIOTICS))).isEqualTo(5);
    }

    @Test
    @DisplayName("Звенья истребителей — таблица оригинала: перехватчики 16 ударов лучом, бомбардировщики 4 бомбы, тяжёлые 8 + 8")
    void fighterFlights() {
        assertThat(rules.fighterShots(BattleRules.INTERCEPTORS, 5, 12, 12)).hasSize(16);
        assertThat(rules.fighterShots(BattleRules.BOMBERS, 5, 12, 12)).hasSize(4);
        assertThat(rules.fighterShots(BattleRules.HEAVY_FIGHTERS, 5, 12, 12)).hasSize(16);
        // Без подходящего луча перехватчикам бить нечем.
        assertThat(rules.fighterShots(BattleRules.INTERCEPTORS, 0, 12, 12)).isEmpty();
        // Прочность растёт с бронёй (MOO II 1.50: «3 + armor multiplier»): титановый перехватчик сбивается
        // одним попаданием, корриевый — на половину труднее, тяжёлый всегда крепче перехватчика.
        assertThat(rules.fighterSturdyPercent(BattleRules.INTERCEPTORS, 1)).isZero();
        assertThat(rules.fighterSturdyPercent(BattleRules.INTERCEPTORS, 3)).isEqualTo(50);
        assertThat(rules.fighterSturdyPercent(BattleRules.HEAVY_FIGHTERS, 1)).isEqualTo(75);
        assertThat(rules.fighterShots(BattleRules.INTERCEPTORS, 5, 12, 12, 3).get(0).missileArmourPercent())
                .isEqualTo(50);
        // Скорость: перехватчик 8 + 2 × парсеки, бомбардировщик 4 + 4 × парсеки (ядерный двигатель — 2).
        List<ShipDesignRules.Item> nuclear = List.of(one("nuclear-drive"));
        assertThat(rules.fighterSpeed(nuclear, BattleRules.INTERCEPTORS)).isEqualTo(12);
        assertThat(rules.fighterSpeed(nuclear, BattleRules.BOMBERS)).isEqualTo(12);
        assertThat(catalog.component("fighter-bays").amount(ShipEffectType.FIGHTER_BAY)).isEqualTo(1);
        assertThat(catalog.component("heavy-fighters").amount(ShipEffectType.FIGHTER_BAY)).isEqualTo(3);
    }

    @Test
    @DisplayName("«Ахилл» втрое чаще выбивает системы, но не выше сотни")
    void achillesTriplesSystemHits() {
        int plain = 0;
        int tripled = 0;
        SplittableRandom random = new SplittableRandom(7);
        SplittableRandom same = new SplittableRandom(7);
        for (int i = 0; i < 2000; i++) {
            plain += Boolean.TRUE.equals(rules.rollSystemHit(random)) ? 1 : 0;
            tripled += Boolean.TRUE.equals(rules.rollSystemHit(same, 3)) ? 1 : 0;
        }
        assertThat(tripled).isGreaterThan(plain * 2);
        assertThat(Boolean.TRUE.equals(rules.rollSystemHit(new SplittableRandom(1), 10))).isTrue();
    }

    @Test
    @DisplayName("Устройства пятой очереди несут числа оригинала MOO II")
    void devicesCarryOriginalNumbers() {
        assertThat(catalog.component("subspace-teleporter").amount(ShipEffectType.TELEPORT)).isEqualTo(20);
        assertThat(catalog.component("displacement-device").amount(ShipEffectType.DISPLACEMENT)).isEqualTo(30);
        assertThat(catalog.component("inertial-nullifier").amount(ShipEffectType.DEFENSE)).isEqualTo(100);
        assertThat(catalog.component("inertial-nullifier").amount(ShipEffectType.MISSILE_EVASION)).isEqualTo(50);
        assertThat(catalog.component("hard-shields").amount(ShipEffectType.HARD_SHIELD)).isEqualTo(3);
        assertThat(catalog.component("multi-phased-shields").amount(ShipEffectType.SHIELD_POOL_PERCENT)).isEqualTo(50);
        assertThat(catalog.component("lightning-field").amount(ShipEffectType.LIGHTNING_PERCENT)).isEqualTo(50);
        assertThat(catalog.component("pulsar").amount(ShipEffectType.PULSAR)).isEqualTo(6);
        assertThat(catalog.component("gyro-destabilizer").amount(ShipEffectType.GYRO)).isEqualTo(15);
        assertThat(catalog.component("plasma-web").amount(ShipEffectType.PLASMA_WEB)).isEqualTo(15);
        assertThat(catalog.component("time-warp-facilitator").amount(ShipEffectType.TIME_WARP)).isEqualTo(1);
        for (String code : List.of("subspace-teleporter", "displacement-device", "inertial-nullifier", "hard-shields",
                "multi-phased-shields", "lightning-field", "pulsar", "gyro-destabilizer", "plasma-web",
                "time-warp-facilitator")) {
            assertThat(catalog.component(code).requiredTechCode()).as(code).isEqualTo(code);
        }
    }

    @Test
    @DisplayName("Многофазные щиты дают запасу щита половину сверху")
    void multiPhasedShieldsGrowThePool() {
        ShipHull cruiser = catalog.hull("cruiser");
        ShipStats plain = designRules.stats(cruiser, List.of(one("class-i-shield")), RaceEffects.NONE);
        ShipStats phased = designRules.stats(cruiser, List.of(one("class-i-shield"), one("multi-phased-shields")),
                RaceEffects.NONE);
        assertThat(plain.shield()).isPositive();
        assertThat(phased.shield()).isEqualTo(plain.shield() * 3 / 2);
    }

    @Test
    @DisplayName("Урон «по размеру» растёт с классом корпуса цели")
    void sizedDamageScalesWithHullClass() {
        assertThat(rules.sizeClass(catalog.hull("frigate"))).isEqualTo(1);
        assertThat(rules.sizeClass(catalog.hull("battleship"))).isEqualTo(4);
        java.util.random.RandomGenerator random = new SplittableRandom(3);
        for (int i = 0; i < 200; i++) {
            int damage = rules.sizedDamage(random, BattleRules.GYRO_DAMAGE_PER_CLASS, 4);
            assertThat(damage).isBetween(4, 16);
            assertThat(damage % 4).isZero();
        }
    }
}
