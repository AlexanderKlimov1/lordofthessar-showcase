package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Своя раса из учётной записи — field-backlog, пункты 26 и 176. В запросе читаются только
 * {@code id}, {@code name} и {@code traits}; остальное сервер отдаёт.
 *
 * @param id         пусто — новая раса
 * @param details    стороны с ценой на миг сохранения и нынешней
 * @param savedFree  сколько очков выбора оставалось свободными на миг сохранения
 * @param free       сколько свободно сейчас: больше прежнего — балансировка расе прибавила
 * @param refusal    почему расу сейчас нельзя взять как есть (бюджет, потолок, исчезнувшая
 *                   сторона) — словами; пусто — законна
 */
public record RaceTemplateDto(
        UUID id,
        @NotBlank @Size(max = 64) String name,
        @NotEmpty @Size(max = 40) List<@NotBlank @Size(max = 64) String> traits,
        List<Trait> details,
        Integer savedFree,
        Integer free,
        String refusal,
        /**
         * Чья это раса — field-backlog, пункт 99: в запросе сохранения — раса автора из ссылки
         * ({@code /?race=…&i=…}); в ответе — есть ли у расы автор. Пусто — собрана здесь.
         */
        UUID originId,
        /** Сколько людей сыграли этой расой по ссылке (пункт 99); у чужих рас пусто. */
        Plays plays
) {

    /**
     * Чужие партии этой расой — пункт 99: только числа, без имён.
     *
     * @param people   сколько людей начинали ею партию
     * @param games    сколько партий начато
     * @param finished сколько из них доиграно
     * @param wins     сколько выиграно
     */
    public record Plays(Integer people, Integer games, Integer finished, Integer wins) {
    }


    /**
     * @param savedPicks цена на миг сохранения
     * @param picks      нынешняя; пусто — стороны в справочнике больше нет
     */
    public record Trait(String code, String name, Integer savedPicks, Integer picks) {
    }
}
