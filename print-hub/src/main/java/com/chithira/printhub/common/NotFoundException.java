package com.chithira.printhub.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A record (customer, user, desktop…) with the requested id does not exist; shown as 404.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {
    public NotFoundException(String what) { super(what + " not found"); }
}
