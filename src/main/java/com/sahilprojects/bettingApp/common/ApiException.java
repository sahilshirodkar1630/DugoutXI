package com.sahilprojects.bettingApp.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Base class for errors the caller should see. The handler turns it into a JSON problem response. */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
