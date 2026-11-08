package com.roam.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService workflowExecutor() {
        return Executors.newFixedThreadPool(4);
    }
}
