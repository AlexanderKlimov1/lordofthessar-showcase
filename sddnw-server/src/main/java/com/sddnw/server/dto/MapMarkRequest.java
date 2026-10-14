package com.sddnw.server.dto;

import java.util.UUID;

/** Метка наставника — пункт 184: кому, у какой звезды и какой знак; пустой знак снимает метку. */
public record MapMarkRequest(UUID studentPlayerId, UUID systemId, String code) {
}
