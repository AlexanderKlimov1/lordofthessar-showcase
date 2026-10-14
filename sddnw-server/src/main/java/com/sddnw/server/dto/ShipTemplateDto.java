package com.sddnw.server.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Проект корабля из библиотеки учётной записи — field-backlog, пункт 96: составом, тем же видом,
 * что и сохранение в окне дизайна ({@link SaveShipDesignRequest.ShipComponentChoice}).
 *
 * @param id      пусто — новый проект библиотеки
 * @param hullName корпус названием на языке читателя (личный кабинет смотрит библиотеку вне партии);
 *                 в запросе не читается
 * @param parts    состав строками «название ×N» — для того же; в запросе не читается
 * @param changed части проекта, которые справочник поменял с сохранения (пункт 176), — названиями;
 *                в запросе не читается
 */
public record ShipTemplateDto(
        UUID id,
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 64) String hullCode,
        @NotEmpty @Size(max = 40) List<SaveShipDesignRequest.@Valid ShipComponentChoice> components,
        List<String> changed,
        String hullName,
        List<String> parts
) {
}
