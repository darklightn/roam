package com.roam.domain;

public enum Role {
    EMPLOYEE,
    ADMINISTRATOR,
    /** Internal runtime actor, never a human-facing role. */
    SYSTEM
}
