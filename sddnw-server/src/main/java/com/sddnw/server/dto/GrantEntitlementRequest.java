package com.sddnw.server.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Выдать право руками — backlog-promo, пункт 41: для тестов, наград турниров и спорных
 * возвратов. Срок в днях; пусто — навсегда.
 */
public record GrantEntitlementRequest(@NotBlank @Size(max = 32) String kind,
                                      @Min(1) @Max(3650) Integer days,
                                      @Size(max = 256) String note) {
}
