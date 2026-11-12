package com.roam.infrastructure.supplier;

import com.roam.shared.Redaction;
/** Fixture-compatible callback signature helper. Real integrations verify the supplier secret. */
public final class CallbackSignatures {

    private CallbackSignatures() {}

    public static String sign(String callbackId) {
        return "tuniu-" + Redaction.sha256(callbackId).substring(0, 12);
    }
}
