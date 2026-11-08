package com.roam.service;

import com.roam.domain.IntentRequest;

/**
 * A child Agent's isolated context: only its task data and trusted runtime identifiers. It never
 * contains a sibling's context or the full MasterAgent conversation.
 */
public record TaskContext(
        String messageType,
        String contractVersion,
        String taskId,
        String intentId,
        String parentTaskId,
        String sessionId,
        String tripRequestId,
        IntentRequest intent) {

    public static TaskContext forIntent(
            String taskId, String parentTaskId, String sessionId, String tripRequestId,
            IntentRequest intent) {
        return new TaskContext(
                "TASK", "1.0", taskId, intent.id(), parentTaskId, sessionId, tripRequestId, intent);
    }
}
