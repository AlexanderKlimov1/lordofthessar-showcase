package com.sddnw.server.service;

import org.springframework.stereotype.Service;

/**
 * Просьб о сбросе пароля с адреса — счётное число в час (backlog-promo, пункт 45): каждая шлёт
 * письмо на чужую почту, и без предела сервер стал бы рассылкой чужими руками.
 */
@Service
public class PasswordResetThrottle extends HourlyAddressLimit {

    /** Пять в час: забывший пароль просит ссылку раз-другой, а не десятками. */
    private static final int PER_HOUR = 5;

    @Override
    protected Integer limit() {
        return PER_HOUR;
    }

    @Override
    protected String refusalKey() {
        return "auth.throttle.resets";
    }

    @Override
    public void spend(String address) {
        super.spend(address);
    }
}
