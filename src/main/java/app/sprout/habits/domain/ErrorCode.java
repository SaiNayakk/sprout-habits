package app.sprout.habits.domain;

import org.springframework.http.HttpStatus;

/** The stable error codes of the habits contract. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Invalid request"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Sign in first"),
    NO_ACCOUNT(HttpStatus.NOT_FOUND, "No Sprout account"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
    SQUAD_FULL(HttpStatus.CONFLICT, "Squad full"),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
