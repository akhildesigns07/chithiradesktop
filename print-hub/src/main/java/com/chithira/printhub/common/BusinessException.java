package com.chithira.printhub.common;

/** A rule was broken (e.g. removing the last admin); the message is shown to the user. */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
}
