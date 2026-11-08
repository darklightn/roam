package com.roam.service;

/** Structured, HTTP-mappable domain error. */
public class RoamException extends RuntimeException {

    private final String code;
    private final int status;

    public RoamException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static RoamException notFound(String what) {
        return new RoamException("not_found", what + " not found", 404);
    }

    public static RoamException forbidden(String message) {
        return new RoamException("forbidden", message, 403);
    }

    public static RoamException conflict(String message) {
        return new RoamException("conflict", message, 409);
    }

    public static RoamException invalid(String message) {
        return new RoamException("invalid_request", message, 400);
    }
}
