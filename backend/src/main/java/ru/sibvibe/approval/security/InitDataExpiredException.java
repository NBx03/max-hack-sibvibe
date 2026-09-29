package ru.sibvibe.approval.security;

/** Подпись верна, но данные запуска старше разрешённого срока. */
public class InitDataExpiredException extends RuntimeException {
    public InitDataExpiredException() {
        super("MAX initData expired");
    }
}
