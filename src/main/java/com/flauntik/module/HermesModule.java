package com.flauntik.module;

import com.flauntik.config.HermesConfig;
import com.google.common.base.Preconditions;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
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
//    private final Map<Org, Map<DbNode,Jdbi>> orgDbNodeJdbiMap;


    public HermesModule(Vertx vertx, JsonObject config, JsonObject envConfigObject) {
        this.vertx = vertx;
        Preconditions.checkNotNull(config);
        this.hermesConfig = config.mapTo(HermesConfig.class);
//        EnvConfig envConfig = envConfigObject.mapTo(EnvConfig.class);
//        templateConfig.getAwsSecretConfig().setAwsSecretKeyId(envConfig.getAwsSecretKeyId());
//        templateConfig.getAwsSecretConfig().setAwsAccessKeyId(envConfig.getAwsAccessKeyId());
//        populateOrgSecrets(templateConfig);
//        this.orgDbNodeJdbiMap = provideOrgDbNodeJdbiMap(templateConfig);
    }

    @Override
    protected void configure() {
        bind(Vertx.class).toInstance(vertx);
        bind(EventBus.class).toInstance(vertx.eventBus());
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

    /*private void populateMySQLSecrets(Org org, Map<DbNode, MySQLNodeConfig> config, SecretCredsConfig secretCreds) {
        for (Map.Entry<DbNode, MySQLNodeConfig> entry: config.entrySet()) {
            entry.getValue().setUsername(secretCreds.getUsername());
            entry.getValue().setPassword(secretCreds.getPassword());
        }
    }

    private void populateAerospikeSecrets(Org org, AerospikeConfig config, SecretCredsConfig secretCreds) {
        config.setUsername(secretCreds.getUsername());
        config.setPassword(secretCreds.getPassword());
    }

    private void populateOrgSecrets(AgoraConfig agoraConfig) {
        log.info("Initialising {}", "Secrets");
        for (Map.Entry<Org, OrgConfig> orgConfigEntry : agoraConfig.getOrgConfigMap().entrySet()) {
            try {
                populateMySQLSecrets(orgConfigEntry.getKey(), orgConfigEntry.getValue().getMySQLConfig(), agoraConfig.getMySQLCredsConfig());
                populateAerospikeSecrets(orgConfigEntry.getKey(), orgConfigEntry.getValue().getAerospikeConfig(), agoraConfig.getAerospikeCredsConfig());
                log.info("Initialized {} for Organisation : {}", "Secrets", orgConfigEntry.getKey());
            } catch (Throwable t) {
                log.error("Unable to Initialize {} for Organisation : {}, Ignoring...", "Secrets", orgConfigEntry.getKey());
                log.error(t);
            }
        }
        log.info("{}, Initialization Done", "Secrets");
    }

    private HikariDataSource getHikariCP(MySQLNodeConfig mySQLNodeConfig) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(mySQLNodeConfig.getUrl());
        if (StringUtils.isNotEmpty(mySQLNodeConfig.getUsername()))
            hikariConfig.setUsername(mySQLNodeConfig.getUsername());
        if (StringUtils.isNotEmpty(mySQLNodeConfig.getPassword()))
            hikariConfig.setPassword(mySQLNodeConfig.getPassword());
        if (ObjectUtils.isNotEmpty(mySQLNodeConfig.getMaxConnections()))
            hikariConfig.setMaximumPoolSize(mySQLNodeConfig.getMaxConnections());
        if (ObjectUtils.isNotEmpty(mySQLNodeConfig.getMaxIdle()))
            hikariConfig.setIdleTimeout(mySQLNodeConfig.getMaxIdle());
        if (ObjectUtils.isNotEmpty(mySQLNodeConfig.getMaxWaitMillis()))
            hikariConfig.setConnectionTimeout(mySQLNodeConfig.getMaxWaitMillis());
        if (ObjectUtils.isNotEmpty(mySQLNodeConfig.getInitialSize()))
            hikariConfig.setMinimumIdle(mySQLNodeConfig.getInitialSize());
        return new HikariDataSource(hikariConfig);
    }

    private Jdbi provideJdbi(MySQLNodeConfig mySQLNodeConfig) {
        Jdbi jdbi = Jdbi.create(getHikariCP(mySQLNodeConfig));
        jdbi.installPlugin(new SqlObjectPlugin());
        jdbi.installPlugin(new Jackson2Plugin());
        return jdbi;
    }

    private Map<DbNode, Jdbi> provideDbNodeJdbiMap(Org org, AgoraConfig agoraConfig) {
        Map<DbNode, Jdbi> dbNodeJdbiMap = new HashMap<>();
        for (Map.Entry<DbNode, MySQLNodeConfig> entry: agoraConfig.getOrgConfigMap().get(org).getMySQLConfig().entrySet()) {
            try {
                dbNodeJdbiMap.put(entry.getKey(), provideJdbi(entry.getValue()));
                log.info("Initialized {} for Organisation : {} DB Node : {}", "JDBI Instance", org, entry.getKey());
            } catch (Throwable t) {
                log.error("Unable to Initialize {} for Organisation : {} DB Node : {}, Ignoring...", "JDBI Instance", org, entry.getKey());
                log.error(t);
            }
        }
        return dbNodeJdbiMap;
    }

    private Map<Org, Map<DbNode, Jdbi>> provideOrgDbNodeJdbiMap(AgoraConfig agoraConfig) {
        log.info("Initialising {}", "JDBI Instance");
        Map<Org, Map<DbNode, Jdbi>> orgDbNodeJdbiMap = new HashMap<>();
        for (Map.Entry<Org, OrgConfig> orgConfigEntry : agoraConfig.getOrgConfigMap().entrySet()) {
            try {
                orgDbNodeJdbiMap.put(orgConfigEntry.getKey(), provideDbNodeJdbiMap(orgConfigEntry.getKey(), agoraConfig));
                log.info("Initialized {} for Organisation : {}", "JDBI Instance", orgConfigEntry.getKey());
            } catch (Throwable t) {
                log.error("Unable to Initialize {} for Organisation : {}, Ignoring...", "JDBI Instance", orgConfigEntry.getKey());
                log.error(t);
            }
        }
        log.info("{}, Initialization Done", "JDBI Instance");
        return orgDbNodeJdbiMap;
    }

    private <T> Map<Org, Map<DbNode, T>> provideClassRepoMap(Class<T> clazz, Map<Org, Map<DbNode,Jdbi>> orgDbNodeJdbiMap) {
        log.info("Initialising JDBI Client Service {}", clazz);
        Map<Org, Map<DbNode, T>> orgDbNodeRepoMap = new HashMap<>();
        for (Map.Entry<Org, Map<DbNode,Jdbi>> orgDbNodeJdbiEntry : orgDbNodeJdbiMap.entrySet()) {
            Map<DbNode, T> dbNodeRepoMap = new HashMap<>();
            try {
                for (Map.Entry<DbNode,Jdbi> dbNodeJdbiEntry : orgDbNodeJdbiEntry.getValue().entrySet()) {
                    try {
                        dbNodeRepoMap.put(dbNodeJdbiEntry.getKey(), dbNodeJdbiEntry.getValue()
                                .registerArgument(new AbstractArgumentFactory<IdentifierType>(Types.TINYINT) {
                                    @Override
                                    protected Argument build(IdentifierType type, ConfigRegistry config) {
                                        return ObjectArgument.of(type.getId());
                                    }
                                })
                                .registerArgument(new AbstractArgumentFactory<ProfileType>(Types.TINYINT) {
                                    @Override
                                    protected Argument build(ProfileType type, ConfigRegistry config) {
                                        return ObjectArgument.of(type.getIntValue());
                                    }
                                })
                                .registerArgument(new AbstractArgumentFactory<Map<String, List<String>>>(Types.VARCHAR) {
                                    @Override
                                    protected Argument build(Map<String, List<String>> value, ConfigRegistry config) {
                                        try {
                                            return ObjectArgument.of(CommonUtil.mapper.writeValueAsString(value));
                                        } catch (JsonProcessingException e) {
                                            return null;
                                        }
                                    }
                                })
                                .onDemand(clazz));
                        log.info("Initialized JDBI Client Service {} for Organisation : {} DB Node : {}", clazz, orgDbNodeJdbiEntry.getKey(), dbNodeJdbiEntry.getKey());
                    } catch (Throwable t) {
                        log.error("Unable to Initialize {} for Organisation : {} DB Node : {}, Ignoring...", clazz, orgDbNodeJdbiEntry.getKey(), dbNodeJdbiEntry.getKey());
                        log.error(t);
                    }
                }
                log.info("Initialized JDBI Client Service {} for Organisation : {}", clazz, orgDbNodeJdbiEntry.getKey());
            } catch (Throwable t) {
                log.error("Unable to Initialize {} for Organisation : {}, Ignoring...", clazz, orgDbNodeJdbiEntry.getKey());
                log.error(t);
            }
            orgDbNodeRepoMap.put(orgDbNodeJdbiEntry.getKey(), dbNodeRepoMap);
        }
        log.info("JDBI Client Service {}, Initialization Done", clazz);
        return orgDbNodeRepoMap;
    }

    @Provides
    @Singleton
    public Map<Org, Map<DbNode, ActivityRepo>> provideActivityRepoMap() {
        return provideClassRepoMap(ActivityRepo.class, orgDbNodeJdbiMap);
    }

    @Provides
    @Singleton
    public Map<Org, Map<DbNode, ProgramDetailsRepo>> provideProgramRepoMap() {
        return provideClassRepoMap(ProgramDetailsRepo.class, orgDbNodeJdbiMap);
    }

    @Provides
    @Singleton
    public Map<Org, Map<DbNode, SubscriptionRepo>> provideSubscriptionRepoMap() {
        return provideClassRepoMap(SubscriptionRepo.class, orgDbNodeJdbiMap);
    }*/

}

