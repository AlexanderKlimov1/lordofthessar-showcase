package com.sddnw.server.dto;

import jakarta.validation.constraints.Size;

/**
 * Сброс и смена пароля, смена почты, удаление записи — backlog-promo, пункт 45. Поля по делу:
 * {@code email} — просьба о ссылке и новая почта, {@code token} — ссылка из письма,
 * {@code password} — нынешний пароль, {@code newPassword} — новый.
 */
public record PasswordResetRequest(@Size(max = 254) String email, @Size(max = 128) String token,
                                   @Size(max = 128) String password, @Size(max = 128) String newPassword) {
}
