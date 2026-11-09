package com.cinebot.common.persistence;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;

/** Identifies WHICH database constraint rejected a write, so each can map to its own API error. */
public final class Constraints {

    private Constraints() {
    }

    public static boolean isViolation(Throwable error, String constraintName) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve && constraintName.equals(cve.getConstraintName())) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getMessage() != null
                    && sql.getMessage().contains("\"" + constraintName + "\"")) {
                return true;
            }
        }
        return false;
    }
}
