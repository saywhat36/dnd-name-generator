package com.dndnamegen.namegen.config;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Explicit executor for {@code @Async} pool replenishment, rather than relying on
 * Spring's {@code SimpleAsyncTaskExecutor} default (unbounded thread creation, no
 * queueing). Also registers a logging {@link AsyncUncaughtExceptionHandler} as a
 * defense-in-depth backstop -- the primary "log every attempt" contract for
 * replenishment lives in PoolReplenishmentService's own try/catch/finally around
 * generation_log writes, since @Async only surfaces exceptions from void methods
 * to this handler, never to the caller.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    private final int corePoolSize;
    private final int maxPoolSize;
    private final int queueCapacity;

    public AsyncConfig(
            @Value("${app.async.pool-replenishment.core-pool-size:2}") int corePoolSize,
            @Value("${app.async.pool-replenishment.max-pool-size:4}") int maxPoolSize,
            @Value("${app.async.pool-replenishment.queue-capacity:50}") int queueCapacity) {
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.queueCapacity = queueCapacity;
    }

    @Bean(name = "poolReplenishmentExecutor")
    @Override
    public ThreadPoolTaskExecutor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("pool-replenishment-");
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return this::logUncaughtAsyncException;
    }

    private void logUncaughtAsyncException(Throwable ex, Method method, Object... params) {
        log.error("Uncaught exception in @Async method {} -- this indicates a gap in that method's own"
                + " try/catch handling, since replenishment failures are expected to be caught and logged"
                + " to generation_log directly", method, ex);
    }
}
