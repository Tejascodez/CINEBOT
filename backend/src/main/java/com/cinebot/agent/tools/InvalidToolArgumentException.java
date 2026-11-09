package com.cinebot.agent.tools;

/** The model sent arguments that are missing or malformed; reported back to it as INVALID_ARGUMENTS. */
public class InvalidToolArgumentException extends RuntimeException {

    public InvalidToolArgumentException(String message) {
        super(message);
    }
}
