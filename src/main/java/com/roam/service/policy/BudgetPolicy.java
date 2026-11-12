package com.roam.service.policy;

import com.roam.domain.shared.Money;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Job-level Budget Ceiling used by planning and hard-checked before every supplier submission. */
@Component
public class BudgetPolicy {

    private final Map<String, Money> ceilings = new ConcurrentHashMap<>();
    private Money defaultCeiling = Money.cny("3000");

    public Money ceilingFor(String employeeId) {
        return ceilings.getOrDefault(employeeId, defaultCeiling);
    }

    public void setCeiling(String employeeId, Money ceiling) {
        ceilings.put(employeeId, ceiling);
    }

    public void setDefaultCeiling(Money ceiling) {
        this.defaultCeiling = ceiling;
    }

    public void reset() {
        ceilings.clear();
        defaultCeiling = Money.cny("3000");
    }
}
