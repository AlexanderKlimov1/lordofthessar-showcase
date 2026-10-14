package com.sddnw.server.web;

import com.sddnw.server.dto.RulesBookDto;
import com.sddnw.server.service.RulesBookService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Справочник «Как устроено» — field-backlog, пункты 75 и 200. Открыт, как и прочие
 * справочники: правила игры не тайна, и читать их можно до всякой партии.
 */
@RestController
public class RulesBookController {

    private final RulesBookService rulesBook;

    public RulesBookController(RulesBookService rulesBook) {
        this.rulesBook = rulesBook;
    }

    /** Порядок конца хода и статьи о правилах на языке запроса. */
    @GetMapping("/api/reference/rules")
    public RulesBookDto rules() {
        return rulesBook.book();
    }
}
