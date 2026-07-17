package com.flauntik.module;

import com.flauntik.config.HermesConfig;
import com.flauntik.enums.WhatsAppProviderType;
import com.flauntik.repository.SessionStore;
import com.flauntik.repository.inMemory.InMemorySessionStore;
import com.flauntik.repository.jdbc.JdbcSessionStore;
import com.flauntik.service.kafka.FlowEventPublisher;
import com.flauntik.service.kafka.KafkaFlowEventPublisher;
import com.flauntik.service.kafka.NoopFlowEventPublisher;
import com.flauntik.service.action.SlotAvailabilityHandler;
import com.flauntik.service.action.StepActionHandler;
import com.flauntik.service.channel.OutboundMessageSender;
import com.flauntik.service.channel.TwilioMessageSender;
import com.flauntik.service.channel.WhatsAppCloudApiMessageSender;
import com.flauntik.service.payment.MockPaymentProvider;
import com.flauntik.service.payment.PaymentProvider;
import com.google.common.base.Preconditions;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.name.Named;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

import static com.flauntik.constant.LoggerConstant.IO_FORK_JOIN_POOL;

@Log4j2
public class HermesModule extends AbstractModule {

    private final Vertx vertx;
    @Getter
    private HermesConfig hermesConfig;

    public HermesModule(Vertx vertx, JsonObject config, JsonObject envConfigObject) {
        this.vertx = vertx;
        Preconditions.checkNotNull(config);
        this.hermesConfig = config.mapTo(HermesConfig.class);
    }

    @Override
    protected void configure() {
        bind(Vertx.class).toInstance(vertx);
        bind(EventBus.class).toInstance(vertx.eventBus());
        bind(PaymentProvider.class).to(MockPaymentProvider.class);
        // Absent config.database -> today's in-memory-only behavior; present -> durable,
        // MySQL-backed sessions (see JdbcSessionStore).
        bind(SessionStore.class).to(hermesConfig.getDatabase() != null ? JdbcSessionStore.class : InMemorySessionStore.class);
        // Absent config.kafka -> no flow-completion events are published; present -> real
        // Kafka producer (see FlowManager).
        bind(FlowEventPublisher.class).to(hermesConfig.getKafka() != null ? KafkaFlowEventPublisher.class : NoopFlowEventPublisher.class);

        MapBinder<WhatsAppProviderType, OutboundMessageSender> senderBinder =
                MapBinder.newMapBinder(binder(), WhatsAppProviderType.class, OutboundMessageSender.class);
        senderBinder.addBinding(WhatsAppProviderType.TWILIO).to(TwilioMessageSender.class);
        senderBinder.addBinding(WhatsAppProviderType.WHATSAPP_CLOUD_API).to(WhatsAppCloudApiMessageSender.class);

        // Named in-process handlers a flow's ACTION step can invoke. Register new business
        // logic here by name; flows reference it via {"type":"action","action":"<name>"}.
        MapBinder<String, StepActionHandler> actionBinder =
                MapBinder.newMapBinder(binder(), String.class, StepActionHandler.class);
        actionBinder.addBinding("check_slot_availability").to(SlotAvailabilityHandler.class);
    }

    @Provides
    @Singleton
    public HermesConfig provideConfig() {
        return hermesConfig;
    }

    @Provides
    @Singleton
    @Named(IO_FORK_JOIN_POOL)
    public ForkJoinPool forkJoinPoolProviderForIO(HermesConfig configuration) {
        final ForkJoinPool.ForkJoinWorkerThreadFactory factory = (ForkJoinPool pool) -> {
            final ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            worker.setName("Template_IO_Pool_" + worker.getPoolIndex());
            return worker;
        };
        return new ForkJoinPool(/*configuration.getIoForkJoinPoolSize()*/1, factory, null, false);
    }

}

