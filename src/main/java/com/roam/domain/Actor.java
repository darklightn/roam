package com.roam.domain;

import java.util.Objects;

/** The authenticated caller. Trusted identifiers are supplied by the runtime, never by model output. */
public record Actor(String userId, Role role) {

    public Actor {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }

    public static Actor employee(String userId) {
        return new Actor(userId, Role.EMPLOYEE);
    }

    public static Actor administrator(String userId) {
        return new Actor(userId, Role.ADMINISTRATOR);
    }

    public static Actor system(String component) {
        return new Actor("system:" + component, Role.SYSTEM);
    }

    public boolean isAdministrator() {
        return role == Role.ADMINISTRATOR;
    }
}
