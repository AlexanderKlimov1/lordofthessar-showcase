package com.sddnw.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Подписка браузера на Web Push — field-backlog, пункт 22: то, что отдаёт
 * {@code PushSubscription.toJSON()} — адрес службы и два ключа браузера.
 */
public record PushSubscriptionRequest(
        @NotBlank @Size(max = 1024) String endpoint,
        @Size(max = 256) String p256dh,
        @Size(max = 64) String auth
) {
}
