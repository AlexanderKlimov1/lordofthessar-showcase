package com.sddnw.server.service;

import java.util.UUID;

/**
 * Какая партия на своих справочниках сейчас обрабатывается — field-backlog, пункт 42.
 * <p>
 * Справочники игры общие на весь сервер и читаются на лету; партия основателя с набором
 * справочников (моды) должна видеть свои. Спрашивать партию в каждом из сотен мест, где читают
 * справочник, значило бы протащить её через все сигнатуры, — поэтому партия ставится ОТМЕТКОЙ НА
 * ПОТОК на время запроса к ней или её хода, и справочник, спросив отметку, отдаёт набор этой
 * партии ({@link GameMods}). Отметку ставит перехватчик запросов {@code /api/games/{id}/…} и часы
 * хода вокруг каждой партии, а снимает — в конце запроса и обхода: поток из пула переходит к
 * чужому запросу, и забытая отметка подменила бы ему правила.
 * <p>
 * Стоит отметка ТОЛЬКО у партии с набором: обычная партия читает файлы, как и прежде.
 */
public final class ModScope {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private ModScope() {
    }

    /** Партия с набором, которую сейчас считает этот поток; пусто — справочники обычные. */
    public static UUID current() {
        return CURRENT.get();
    }

    public static void set(UUID gameId) {
        if (gameId == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(gameId);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Выполнить работу в отметке партии и вернуть прежнюю, какой бы она ни была. */
    public static void run(UUID gameId, Runnable work) {
        UUID before = CURRENT.get();
        set(gameId);
        try {
            work.run();
        } finally {
            set(before);
        }
    }
}
