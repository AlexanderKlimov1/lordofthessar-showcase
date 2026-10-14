package com.sddnw.server.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Предел сообщений о проблеме с одной учётной записи — field-backlog, пункт 157.
 * <p>
 * Ключ здесь — запись, а не адрес: писать может и гость, а за одним адресом сидят многие
 * (дом, клуб, прокси публикации). Сообщение писем не шлёт, но ложится в базу и в список
 * хозяина, и без предела цикл в чужом скрипте завалил бы и то и другое. Пять в час
 * ({@code sddnw.feedback.reports-per-hour}) — больше живой тестер не напишет и в плохой вечер.
 */
@Service
public class ProblemReportThrottle extends HourlyAddressLimit {

    private final Integer perHour;

    public ProblemReportThrottle(@Value("${sddnw.feedback.reports-per-hour:5}") Integer perHour) {
        this.perHour = perHour;
    }

    @Override
    protected Integer limit() {
        return perHour;
    }

    @Override
    protected String refusalKey() {
        return "problem.throttle";
    }

    /** Сообщение принято — запись потратила одну попытку. */
    public void reported(String accountKey) {
        spend(accountKey);
    }
}
