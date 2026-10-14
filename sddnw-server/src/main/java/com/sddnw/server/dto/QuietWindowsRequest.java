package com.sddnw.server.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Окна, которые не открываются сами, — field-backlog, пункт 164: список целиком. Пустой —
 * «открывать все», как по умолчанию.
 */
public record QuietWindowsRequest(
        @NotNull List<String> quietWindows
) {
}
