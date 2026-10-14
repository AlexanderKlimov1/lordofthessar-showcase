package com.sddnw.server.dto;

import java.util.List;
import java.util.UUID;

/**
 * Империя ученика глазами наставника — field-backlog, пункт 184: карта, какой её видит ученик
 * (его разведка и туман войны, не больше), его флоты, казна и метки, которые наставник уже
 * поставил. Видно ровно то, что ученик сам показал, назвав наставника.
 */
public record MentorViewDto(UUID studentPlayerId, String name, String color, Integer credits,
                            GalaxyMapDto map, List<FleetGroupDto> fleets, List<MapMarkDto> marks) {
}
