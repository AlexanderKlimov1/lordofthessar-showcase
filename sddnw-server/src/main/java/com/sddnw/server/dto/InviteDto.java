package com.sddnw.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Что ждёт пришедшего по приглашению — field-backlog, пункты 15, 61 и 66.
 *
 * @param seat     LOBBY — место в лобби, EMPIRE — империя ИИ в идущей партии, пусто — мест нет
 * @param empire   империя, которую приглашение предлагает (её возьмёт вход без выбора)
 * @param empires  все империи ИИ, которые можно взять (пункт 66): пришедший выбирает сам
 */
public record InviteDto(String gameName, String status, Integer turn, String galaxySize,
                        Integer humanPlayers, Integer totalPlayers, Integer turnSeconds,
                        String seat, String empire, String color, String host,
                        List<Empire> empires) {

    /**
     * Империя, которую можно взять, — пункт 66: выбирают не вслепую, а по колониям и по тому,
     * воюет ли она с пригласившим.
     *
     * @param atWarWithHost в войне с пригласившим: взять можно, но играть придётся против друга
     */
    public record Empire(UUID playerId, String name, String color, String raceName, Integer colonies,
                         Boolean atWarWithHost) {
    }
}
