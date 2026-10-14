package com.sddnw.server.dto;

import java.util.UUID;

/** Кому ученик показывает свою империю — пункт 184; пусто — больше никому. */
public record MentorRequest(UUID mentorPlayerId) {
}
