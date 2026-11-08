package com.roam.service;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class Ids {

    private final AtomicLong sequence = new AtomicLong(1000);

    public String next(String prefix) {
        return prefix + "-" + sequence.incrementAndGet();
    }

    public void reset() {
        sequence.set(1000);
    }
}
