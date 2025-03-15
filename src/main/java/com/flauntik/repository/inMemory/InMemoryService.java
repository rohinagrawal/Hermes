package com.flauntik.repository.inMemory;

import com.flauntik.config.HermesConfig;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import lombok.extern.log4j.Log4j2;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.flauntik.constant.LoggerConstant.IO_FORK_JOIN_POOL;

@Singleton
@Log4j2
public class InMemoryService {

    private final HermesConfig hermesConfig;
    private final AtomicBoolean lock;
    private InMemoryStore inMemoryStore;
    private final ForkJoinPool ioForkJoinPool;

    @Inject
    public InMemoryService(HermesConfig hermesConfig, @Named(IO_FORK_JOIN_POOL) ForkJoinPool ioForkJoinPool) {
        this.hermesConfig = hermesConfig;
        this.lock = initialiseLock(this.hermesConfig);
        this.inMemoryStore = populateInMemoryStore(this.hermesConfig);
        this.ioForkJoinPool = ioForkJoinPool;
    }

    private AtomicBoolean initialiseLock(HermesConfig hermesConfig) {
        return new AtomicBoolean(false);
    }

    private InMemoryStore populateInMemoryStore(HermesConfig hermesConfig) {
        if (Objects.isNull(hermesConfig))
            return null;
        InMemoryStore inMemoryStore = new InMemoryStore();
        return inMemoryStore;
    }

    public InMemoryStore getInMemoryStoreMap() {
        updateInMemoryStore();
        return this.inMemoryStore;
    }

    private void updateInMemoryStore() {
        if (this.lock.compareAndSet(false, true)) {
            try {
                log.info("Initialising Update of In Memory Cache {}", this.getClass());
                CompletableFuture.runAsync(() -> {
                    try {
                        this.inMemoryStore = populateInMemoryStore(this.hermesConfig);
                        log.info("Updated In Memory Cache {}", this.getClass());
                    } catch (Exception e) {
                        log.error("Unable to Update {} In Memory Cache, Ignoring...", this.getClass());
                        log.error(e);
                    } finally {
                        this.lock.compareAndSet(true, false);
                    }
                }, ioForkJoinPool);
            } catch (Throwable t) {
                this.lock.compareAndSet(true, false);
            }
        }
    }
}

