package com.psddev.dari.util;

/** Thrown when anything's wrong with a password. */
@SuppressWarnings("serial")
public class PasswordException extends Exception {

    public PasswordException() {
        super();
    }

    public PasswordException(String message) {
        super(message);
    }

    public PasswordException(String message, Throwable cause) {
        super(message, cause);
    }

    public PasswordException(Throwable cause) {
        super(cause);
    }
}
