package com.flauntik.guice;

import com.google.common.base.Preconditions;
import com.google.inject.Injector;
import io.vertx.core.Promise;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.impl.verticle.CompilingClassLoader;
import io.vertx.core.spi.VerticleFactory;
import lombok.SneakyThrows;

import java.util.concurrent.Callable;

public class GuiceVerticleFactory implements VerticleFactory {

    public static final String PREFIX = "java-guice";
    private final Injector injector;

    public GuiceVerticleFactory(Injector injector) {
        this.injector = Preconditions.checkNotNull(injector);
    }

    @Override
    public int order() {
        return VerticleFactory.super.order();
    }

    @Override
    public void init(Vertx vertx) {
        VerticleFactory.super.init(vertx);
    }

    @Override
    public void close() {
        VerticleFactory.super.close();
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @SneakyThrows
    @Override
    public void createVerticle(String verticleName, ClassLoader classLoader, Promise<Callable<Verticle>> promise) {
        verticleName = VerticleFactory.removePrefix(verticleName);

        Class clazz;
        if (verticleName.endsWith(".java")) {
            CompilingClassLoader compilingLoader = new CompilingClassLoader(classLoader, verticleName);
            String className = compilingLoader.resolveMainClassName();
            clazz = compilingLoader.loadClass(className);
        } else {
            clazz = classLoader.loadClass(verticleName);
        }
        promise.complete(() -> (Verticle) this.injector.getInstance(clazz));

    }

}


