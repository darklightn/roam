package com.roam.domain.workflow;

import com.roam.domain.trip.TripRequestData;
import java.util.List;
import java.util.Objects;

/**
 * The only input accepted by the execution workflow. Java validates it before MasterAgent dispatch.
 * Trusted identifiers are injected by the runtime and are not writable by model output.
 */
public record IntentPackage(
        String packageId,
        String sessionId,
        String contractVersion,
        String tripRequestId,
        TripRequestData tripRequest,
        List<IntentRequest> intents) {

    public static final String SUPPORTED_CONTRACT_VERSION = "1.0";

    public IntentPackage {
        Objects.requireNonNull(packageId, "packageId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(tripRequest, "tripRequest");
        intents = intents == null ? List.of() : List.copyOf(intents);
    }
}
