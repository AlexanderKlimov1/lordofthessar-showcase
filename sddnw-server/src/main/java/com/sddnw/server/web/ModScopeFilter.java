package com.sddnw.server.web;

import com.sddnw.server.service.GameMods;
import com.sddnw.server.service.ModScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Отметка партии на своих справочниках — field-backlog, пункт 42 (см. {@link ModScope}).
 * <p>
 * Запрос к партии ({@code /api/games/{id}/…}) с набором справочников ставит отметку на поток,
 * и всё, что этот запрос читает из справочников — список стройки, бой, конец хода, — видит набор
 * партии. Фильтр, а не обработчик контроллера: многие действия партии саму партию не читают
 * (только пропуск), а отметка нужна до первого обращения к справочнику. Снимается она в
 * {@code finally}: поток из пула уйдёт к чужому запросу.
 */
@Component
public class ModScopeFilter extends OncePerRequestFilter {

    private static final Pattern GAME_PATH = Pattern.compile("^/api/games/([0-9a-fA-F-]{36})(?:/|$)");

    private final GameMods gameMods;

    public ModScopeFilter(GameMods gameMods) {
        this.gameMods = gameMods;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Matcher matcher = GAME_PATH.matcher(request.getRequestURI());
        UUID gameId = null;
        if (matcher.find()) {
            try {
                UUID candidate = UUID.fromString(matcher.group(1));
                gameId = Boolean.TRUE.equals(gameMods.modded(candidate)) ? candidate : null;
            } catch (IllegalArgumentException ignored) {
                gameId = null;
            }
        }
        ModScope.set(gameId);
        try {
            chain.doFilter(request, response);
        } finally {
            ModScope.clear();
        }
    }
}
