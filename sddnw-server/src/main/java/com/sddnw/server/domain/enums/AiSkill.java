package com.sddnw.server.domain.enums;

import java.util.Arrays;
import java.util.List;

/**
 * Уровень сложности — field-backlog, пункт 9: КАК ХОРОШО играет империя ИИ, а не сколько ей
 * дано сверх правил.
 * <p>
 * <b>Прибавок у ИИ нет ни на одном уровне</b> (решение хозяина проекта 08.10.2026): ни к
 * производству, ни к науке, ни к росту. Сложность в 4X обычно делают подарками ИИ, и игрок
 * честно читает это жульничеством; здесь правила у всех империй одни, а уровень меняет только
 * качество решений — от «максимально глупо» до «максимально умно». Поэтому каждый уровень
 * описан ПРИЗНАКАМИ поведения, и эти же признаки показываются игроку в окне новой игры и в
 * «Инфо» (пункт 87): названное — правило, а у правила нет скрытой части.
 * <p>
 * Рычаги выбраны там, где ИИ уже РЕШАЕТ, а не там, где ему что-то начисляется:
 * <ul>
 *   <li><b>стройка</b> — окупаемость на месте ({@code AiEmpireService.worthwhile}), список
 *       без расчёта или вразнобой;</li>
 *   <li><b>наука</b> — рекомендованная технология уровня или любая;</li>
 *   <li><b>выкуп стройки</b> — сколько покупок за ход империя себе позволяет;</li>
 *   <li><b>союз против лидера</b> — собирают ли двое ближайших коалицию против сильнейшего
 *       (баланс сил, пункт 151): это самая «умная» вещь дипломатии ИИ, и против человека-лидера
 *       она главная;</li>
 *   <li><b>дебют</b> — лучшее измеренное начало своей расы ({@code AiOpeningBook}).</li>
 * </ul>
 * Обычный уровень — ровно та игра, что была до уровней: на ней мерит прибор балансировки, и
 * балансовые прогоны другой не берут (иначе мерились бы не расы, а уровни).
 */
public enum AiSkill {

    /** Новичок: стройка вразнобой, наука наугад, деньги лежат, коалиций нет. */
    BEGINNER(BuildChoice.SHUFFLED, false, 0, false, false),

    /** Лёгкий: стройка по списку без расчёта окупаемости, наука наугад, без выкупа и коалиций. */
    EASY(BuildChoice.LIST, false, 0, false, false),

    /** Обычный: игра, какой она была до уровней, — окупаемость, рекомендации, выкуп, коалиции. */
    NORMAL(BuildChoice.PAYBACK, true, 1, true, false),

    /** Сложный: всё обычного, выкуп смелее и лучший измеренный дебют своей расы. */
    HARD(BuildChoice.PAYBACK, true, 3, true, true);

    /** Как колония выбирает здание развития. */
    public enum BuildChoice {
        /** По окупаемости на месте — сколько ходов здание возвращает свою цену этой колонии. */
        PAYBACK,
        /** По общему списку развития, без расчёта. */
        LIST,
        /** Общий список, перетасованный жребием от зерна партии. */
        SHUFFLED
    }

    private final BuildChoice build;
    private final Boolean recommendedResearch;
    private final Integer purchasesPerTurn;
    private final Boolean coalitions;
    private final Boolean openingBook;

    AiSkill(BuildChoice build, Boolean recommendedResearch, Integer purchasesPerTurn,
            Boolean coalitions, Boolean openingBook) {
        this.build = build;
        this.recommendedResearch = recommendedResearch;
        this.purchasesPerTurn = purchasesPerTurn;
        this.coalitions = coalitions;
        this.openingBook = openingBook;
    }

    /** Уровень по умолчанию: API без поля, балансовые прогоны, опекун ушедшего человека. */
    public static final AiSkill DEFAULT = NORMAL;

    /** Уровень по коду; пусто или незнакомое — обычный. */
    public static AiSkill of(String code) {
        return Arrays.stream(values()).filter(skill -> skill.name().equals(code)).findFirst().orElse(DEFAULT);
    }

    /** Уровни по возрастанию — порядок окна новой игры и подсказки следующей партии (пункт 10). */
    public static List<AiSkill> ladder() {
        return List.of(values());
    }

    /** Ступенью выше; у сложного выше нет. */
    public AiSkill harder() {
        return this == HARD ? HARD : values()[ordinal() + 1];
    }

    /** Ступенью ниже; у новичка ниже нет. */
    public AiSkill easier() {
        return this == BEGINNER ? BEGINNER : values()[ordinal() - 1];
    }

    public BuildChoice getBuild() {
        return build;
    }

    public Boolean getRecommendedResearch() {
        return recommendedResearch;
    }

    public Integer getPurchasesPerTurn() {
        return purchasesPerTurn;
    }

    public Boolean getCoalitions() {
        return coalitions;
    }

    public Boolean getOpeningBook() {
        return openingBook;
    }
}
