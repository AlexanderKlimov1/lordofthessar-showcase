package com.sddnw.server.dto;

/**
 * Что знает сервер об уведомлениях записи — field-backlog, пункт 22.
 *
 * @param pushKey    открытый ключ сервера (VAPID) для подписки браузера
 * @param subscribed подписан ли хоть один браузер записи
 * @param notifyMail письма о ходе включены
 * @param hasEmail   есть ли куда слать письма — без почты галочка не ставится
 */
public record NotifySettingsDto(String pushKey, Boolean subscribed, Boolean notifyMail, Boolean hasEmail) {
}
