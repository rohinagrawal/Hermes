package com.flauntik.verticle;

import com.flauntik.config.HermesConfig;
import com.flauntik.guice.GuiceVerticleFactory;
import com.flauntik.guice.GuiceVertxDeploymentManager;
import com.flauntik.module.HermesModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResult;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import lombok.extern.log4j.Log4j2;

import static com.flauntik.constant.LoggerConstant.*;

@Log4j2
public class MainVerticle extends AbstractVerticle {
    @Override
    public void start(Promise<Void> startPromise) throws Exception {
        try {
            ConfigStoreOptions configStoreOptions = new ConfigStoreOptions().setType("env");
            ConfigRetriever configRetriever = ConfigRetriever.create(vertx, new ConfigRetrieverOptions().addStore(configStoreOptions));
            configRetriever.getConfig((config) -> {
                try {
                    startApplication(config(),startPromise, config.result());
                    startPromise.complete();
                } catch (Exception e) {
                    startPromise.fail(e);
                    log.error(e.getMessage(), e);
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            vertx.close();
            startPromise.fail(e);
        }
    }

    private void startApplication(JsonObject config, Promise<Void> startPromise, JsonObject envConfig) {
        try {
            HermesModule hermesModule = new HermesModule(vertx, config,envConfig);
            Injector injector = Guice.createInjector(hermesModule);
            GuiceVerticleFactory guiceVerticleFactory = new GuiceVerticleFactory(injector);
            vertx.registerVerticleFactory(guiceVerticleFactory);

            HermesConfig hermesConfig = hermesModule.getHermesConfig();
            GuiceVertxDeploymentManager deploymentManager = new GuiceVertxDeploymentManager(vertx);

            deploymentManager.deployVerticle(HttpVerticle.class, hermesConfig.getVerticleDeploymentOptions().get(HttpVerticle.class.getSimpleName()),
                    asyncResult -> processVerticleDeployResult(startPromise, asyncResult, HttpVerticle.class.getSimpleName()));

            deploymentManager.deployVerticle(APIVerticle.class, hermesConfig.getVerticleDeploymentOptions().get(APIVerticle.class.getSimpleName()),
                    asyncResult -> processVerticleDeployResult(startPromise, asyncResult, APIVerticle.class.getSimpleName()));

        } catch (Throwable e) {
            log.error(UNABLE_START_SERVER, e);
            startPromise.fail(e);
            vertx.close();
            System.exit(1);
        }
    }

    private void processVerticleDeployResult(Promise<Void> startPromise, AsyncResult<String> asyncResult, String verticleName) {
        if (asyncResult.failed()) {
            log.error(UNABLE_START_SERVER + ERROR_DEPLOYING_VERTICLE +
                    verticleName, asyncResult.cause());
            vertx.close();
            startPromise.fail(asyncResult.cause());
            System.exit(1);
        } else
            log.info(verticleName + DEPLOY_SUCCESS);
    }
}
