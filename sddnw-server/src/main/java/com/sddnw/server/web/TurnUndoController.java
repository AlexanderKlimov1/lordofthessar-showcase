package com.sddnw.server.web;

import com.sddnw.server.dto.TurnUndoDto;
import com.sddnw.server.service.TurnUndoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Отмена в пределах хода — field-backlog, пункт 74: продажа, выкуп и списание кораблей. */
@RestController
@RequestMapping("/api/games/{gameId}/undo")
public class TurnUndoController {

    private final TurnUndoService undo;

    public TurnUndoController(TurnUndoService undo) {
        this.undo = undo;
    }

    /** Что можно отменить сейчас. */
    @GetMapping
    public List<TurnUndoDto> list(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return undo.list(gameId, accessToken);
    }

    /** Отменить одно действие этого хода; в ответ — что осталось отменить. */
    @PostMapping("/{undoId}")
    public List<TurnUndoDto> undo(@PathVariable UUID gameId, @PathVariable UUID undoId,
                                  @AccessToken String accessToken) {
        return undo.undo(gameId, accessToken, undoId);
    }
}
