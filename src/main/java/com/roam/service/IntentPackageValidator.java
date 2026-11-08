package com.roam.service;

import com.roam.domain.IntentPackage;
import com.roam.domain.IntentRequest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Java-side structural validation before MasterAgent dispatch. */
@Component
public class IntentPackageValidator {

    public void validate(IntentPackage pkg) {
        if (pkg == null) {
            throw RoamException.invalid("IntentPackage is required");
        }
        if (!IntentPackage.SUPPORTED_CONTRACT_VERSION.equals(pkg.contractVersion())) {
            throw new RoamException(
                    "contract_error",
                    "unsupported contract version " + pkg.contractVersion(),
                    400);
        }
        if (pkg.sessionId().isBlank()) {
            throw RoamException.invalid("sessionId is required");
        }
        if (pkg.intents().isEmpty()) {
            throw RoamException.invalid("at least one intent is required");
        }
        Set<String> ids = new HashSet<>();
        for (IntentRequest intent : pkg.intents()) {
            if (!ids.add(intent.id())) {
                throw RoamException.invalid("duplicate intent id " + intent.id());
            }
            if (intent.type() == null) {
                throw RoamException.invalid("intent " + intent.id() + " has no type");
            }
            if (intent.origin() == null || intent.origin().isBlank()
                    || intent.destination() == null || intent.destination().isBlank()) {
                throw RoamException.invalid("intent " + intent.id() + " needs origin and destination");
            }
            if (intent.startDate() == null || intent.endDate() == null) {
                throw RoamException.invalid("intent " + intent.id() + " needs dates");
            }
        }
        for (IntentRequest intent : pkg.intents()) {
            for (String dependency : intent.dependsOn()) {
                if (!ids.contains(dependency)) {
                    throw RoamException.invalid(
                            "intent " + intent.id() + " depends on unknown intent " + dependency);
                }
            }
        }
        detectCycles(pkg.intents());
    }

    private void detectCycles(List<IntentRequest> intents) {
        Map<String, IntentRequest> byId = new HashMap<>();
        intents.forEach(intent -> byId.put(intent.id(), intent));
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (IntentRequest intent : intents) {
            visit(intent, byId, visiting, visited);
        }
    }

    private void visit(
            IntentRequest intent,
            Map<String, IntentRequest> byId,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(intent.id())) {
            return;
        }
        if (!visiting.add(intent.id())) {
            throw new RoamException("contract_error", "intent dependency cycle at " + intent.id(), 400);
        }
        for (String dependency : intent.dependsOn()) {
            visit(byId.get(dependency), byId, visiting, visited);
        }
        visiting.remove(intent.id());
        visited.add(intent.id());
    }
}
