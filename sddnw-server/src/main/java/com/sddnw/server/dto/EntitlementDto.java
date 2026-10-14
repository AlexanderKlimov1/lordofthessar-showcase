package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Право учётной записи — backlog-promo, пункт 41: для администратора, со всей историей. */
public record EntitlementDto(UUID id, String kind, String source, OffsetDateTime startsAt,
                             OffsetDateTime endsAt, String paymentId, OffsetDateTime revokedAt,
                             String note, Boolean active) {
}
