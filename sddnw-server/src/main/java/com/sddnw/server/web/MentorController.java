package com.sddnw.server.web;

import com.sddnw.server.dto.MapMarkDto;
import com.sddnw.server.dto.MapMarkRequest;
import com.sddnw.server.dto.MentorRequest;
import com.sddnw.server.dto.MentorViewDto;
import com.sddnw.server.service.MentorService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Наставник — field-backlog, пункт 184: ученик называет наставника, наставник видит его империю и
 * ставит метки на его карте.
 */
@RestController
@RequestMapping("/api/games/{gameId}/mentor")
public class MentorController {

    private final MentorService mentors;

    public MentorController(MentorService mentors) {
        this.mentors = mentors;
    }

    /** Ученик называет наставника; пусто — снимает согласие. */
    @PutMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void choose(@PathVariable UUID gameId, @RequestBody MentorRequest request,
                       @AccessToken String accessToken) {
        mentors.choose(gameId, accessToken, request.mentorPlayerId());
    }

    /** Ученики наставника — каждый своими глазами. */
    @GetMapping("/students")
    public List<MentorViewDto> students(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return mentors.students(gameId, accessToken);
    }

    /** Поставить или снять метку на карте ученика. */
    @PutMapping("/marks")
    public List<MapMarkDto> mark(@PathVariable UUID gameId, @RequestBody MapMarkRequest request,
                                 @AccessToken String accessToken) {
        return mentors.mark(gameId, accessToken, request.studentPlayerId(), request.systemId(), request.code());
    }
}
