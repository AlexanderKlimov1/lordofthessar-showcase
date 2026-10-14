package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Страница пакета основателя — целиком, на обоих языках.
 * <p>
 * Оба языка едут разом, а не по {@code Accept-Language}: страница одна и короткая, а
 * переключатель языка на клиенте должен менять её сразу, без нового запроса. Ей же
 * администратор правит страницу — правка тоже идёт целиком.
 *
 * @param price      цена строкой («$100»): валюту и запись числа решает хозяин, а не сервер
 * @param seatsTotal сколько мест основателей всего
 * @param seatsTaken сколько уже занято — правит администратор по мере продаж
 * @param published  видна ли страница игрокам; черновик видит только администратор
 * @param buyUrl     куда ведёт кнопка покупки; пусто — продажа ещё не открыта
 * @param updatedAt  когда правили последний раз; у страницы по умолчанию — пусто
 */
public record FounderPageDto(
        String price,
        Integer seatsTotal,
        Integer seatsTaken,
        Boolean published,
        String buyUrl,
        Text en,
        Text ru,
        OffsetDateTime updatedAt) {

    /** Текст страницы на одном языке. */
    public record Text(
            String title,
            String tagline,
            String intro,
            List<Section> sections,
            List<Question> faq,
            String note) {
    }

    /** Раздел: заголовок и пункты списком. */
    public record Section(String title, List<String> items) {
    }

    /** Частый вопрос и ответ. */
    public record Question(String question, String answer) {
    }
}
