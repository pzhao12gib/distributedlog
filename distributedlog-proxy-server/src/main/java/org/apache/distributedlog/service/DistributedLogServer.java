/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.distributedlog.service;

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.distributedlog.DistributedLogConfiguration;
import org.apache.distributedlog.client.routing.RoutingService;
import org.apache.distributedlog.config.DynamicConfigurationFactory;
import org.apache.distributedlog.config.DynamicDistributedLogConfiguration;
import org.apache.distributedlog.service.announcer.Announcer;
import org.apache.distributedlog.service.announcer.NOPAnnouncer;
import org.apache.distributedlog.service.announcer.ServerSetAnnouncer;
import org.apache.distributedlog.service.config.DefaultStreamConfigProvider;
import org.apache.distributedlog.service.config.NullStreamConfigProvider;
import org.apache.distributedlog.service.config.ServerConfiguration;
import org.apache.distributedlog.service.config.ServiceStreamConfigProvider;
import org.apache.distributedlog.service.config.StreamConfigProvider;
import org.apache.distributedlog.service.placement.EqualLoadAppraiser;
import org.apache.distributedlog.service.placement.LoadAppraiser;
import org.apache.distributedlog.service.streamset.IdentityStreamPartitionConverter;
import org.apache.distributedlog.service.streamset.StreamPartitionConverter;
import org.apache.distributedlog.thrift.service.DistributedLogService;
import org.apache.distributedlog.util.ConfUtils;
import org.apache.distributedlog.common.util.SchedulerUtils;
import com.twitter.finagle.Stack;
import com.twitter.finagle.ThriftMuxServer$;
import com.twitter.finagle.builder.Server;
import com.twitter.finagle.builder.ServerBuilder;
import com.twitter.finagle.stats.NullStatsReceiver;
import com.twitter.finagle.stats.StatsReceiver;
import com.twitter.finagle.thrift.ClientIdRequiredFilter;
import com.twitter.finagle.thrift.ThriftServerFramedCodec;
import com.twitter.finagle.transport.Transport;
import com.twitter.util.Duration;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.bookkeeper.stats.NullStatsLogger;
import org.apache.bookkeeper.stats.StatsLogger;
import org.apache.bookkeeper.stats.StatsProvider;
import org.apache.bookkeeper.util.ReflectionUtils;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Option;
import scala.Tuple2;

/**
 * Running the distributedlog proxy server.
 */
public class DistributedLogServer {

    private static final Logger logger = LoggerFactory.getLogger(DistributedLogServer.class);
    private static final String DEFAULT_LOAD_APPRIASER = EqualLoadAppraiser.class.getCanonicalName();

    private DistributedLogServiceImpl dlService = null;
    private Server server = null;
    private RoutingService routingService;
    private StatsProvider statsProvider;
    private Announcer announcer = null;
    private ScheduledExecutorService configExecutorService;
    private long gracefulShutdownMs = 0L;

    private final StatsReceiver statsReceiver;
    private final CountDownLatch keepAliveLatch = new CountDownLatch(1);
    private final Optional<String> uri;
    private final Optional<String> conf;
    private final Optional<String> streamConf;
    private final Optional<Integer> port;
    private final Optional<Integer> statsPort;
    private final Optional<Integer> shardId;
    private final Optional<Boolean> announceServerSet;
    private final Optional<String> loadAppraiserClassStr;
    private final Optional<Boolean> thriftmux;

    DistributedLogServer(Optional<String> uri,
                         Optional<String> conf,
                         Optional<String> streamConf,
                         Optional<Integer> port,
                         Optional<Integer> statsPort,
                         Optional<Integer> shardId,
                         Optional<Boolean> announceServerSet,
                         Optional<String> loadAppraiserClass,
                         Optional<Boolean> thriftmux,
                         RoutingService routingService,
                         StatsReceiver statsReceiver,
                         StatsProvider statsProvider) {
        this.uri = uri;
        this.conf = conf;
        this.streamConf = streamConf;
        this.port = port;
        this.statsPort = statsPort;
        this.shardId = shardId;
        this.announceServerSet = announceServerSet;
        this.thriftmux = thriftmux;
        this.routingService = routingService;
        this.statsReceiver = statsReceiver;
        this.statsProvider = statsProvider;
        this.loadAppraiserClassStr = loadAppraiserClass;
    }

    public void runServer()
        throws ConfigurationException, IllegalArgumentException, IOException, ClassNotFoundException {
        if (!uri.isPresent()) {
            throw new IllegalArgumentException("No distributedlog uri provided.");
        }
        URI dlUri = URI.create(uri.get());
        DistributedLogConfiguration dlConf = new DistributedLogConfiguration();
        if (conf.isPresent()) {
            String configFile = conf.get();
            try {
                dlConf.loadConf(new File(configFile).toURI().toURL());
            } catch (ConfigurationException e) {
                throw new IllegalArgumentException("Failed to load distributedlog configuration from "
                    + configFile + ".");
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Failed to load distributedlog configuration from malformed "
                        + configFile + ".");
            }
        }

        this.configExecutorService = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DistributedLogService-Dyncfg-%d")
                        .setDaemon(true)
                        .build());

        // server configuration and dynamic configuration
        ServerConfiguration serverConf = new ServerConfiguration();
        serverConf.loadConf(dlConf);

        // overwrite the shard id if it is provided in the args
        if (shardId.isPresent()) {
            serverConf.setServerShardId(shardId.get());
        }

        serverConf.validate();

        DynamicDistributedLogConfiguration dynDlConf = getServiceDynConf(dlConf);

        logger.info("Starting stats provider : {}", statsProvider.getClass());
        statsProvider.start(dlConf);

        if (announceServerSet.isPresent() && announceServerSet.get()) {
            announcer = new ServerSetAnnouncer(
                    dlUri,
                    port.or(0),
                    statsPort.or(0),
                    shardId.or(0));
        } else {
            announcer = new NOPAnnouncer();
        }

        // Build the stream partition converter
        StreamPartitionConverter converter;
        try {
            converter = ReflectionUtils.newInstance(serverConf.getStreamPartitionConverterClass());
        } catch (ConfigurationException e) {
            logger.warn("Failed to load configured stream-to-partition converter. Fallback to use {}",
                    IdentityStreamPartitionConverter.class.getName());
            converter = new IdentityStreamPartitionConverter();
        }
        Class loadAppraiserClass = Class.forName(loadAppraiserClassStr.or(DEFAULT_LOAD_APPRIASER));
        LoadAppraiser loadAppraiser = (LoadAppraiser) ReflectionUtils.newInstance(loadAppraiserClass);
        logger.info("Load appraiser class is " + loadAppraiserClassStr.or("not specified.") + " Instantiated "
                + loadAppraiser.getClass().getCanonicalName());

        StreamConfigProvider streamConfProvider =
                getStreamConfigProvider(dlConf, converter);

        // pre-run
        preRun(dlConf, serverConf);

        Pair<DistributedLogServiceImpl, Server> serverPair = runServer(
                serverConf,
                dlConf,
                dynDlConf,
                dlUri,
                converter,
                routingService,
                statsProvider,
                port.or(0),
                keepAliveLatch,
                statsReceiver,
                thriftmux.isPresent(),
                streamConfProvider,
                loadAppraiser);

        this.dlService = serverPair.getLeft();
        this.server = serverPair.getRight();

        // announce the service
        announcer.announce();
        // start the routing service after announced
        routingService.startService();
        logger.info("Started the routing service.");
        dlService.startPlacementPolicy();
        logger.info("Started the placement policy.");
    }

    protected void preRun(DistributedLogConfiguration conf, ServerConfiguration serverConf) {
        this.gracefulShutdownMs = serverConf.getGracefulShutdownPeriodMs();
        if (!serverConf.isDurableWriteEnabled()) {
            conf.setDurableWriteEnabled(false);
        }
    }

    private DynamicDistributedLogConfiguration getServiceDynConf(DistributedLogConfiguration dlConf)
        throws ConfigurationException {
        Optional<DynamicDistributedLogConfiguration> dynConf = Optional.absent();
        if (conf.isPresent()) {
            DynamicConfigurationFactory configFactory = new DynamicConfigurationFactory(
                    configExecutorService, dlConf.getDynamicConfigReloadIntervalSec(), TimeUnit.SECONDS);
            dynConf = configFactory.getDynamicConfiguration(conf.get());
        }
        if (dynConf.isPresent()) {
            return dynConf.get();
        } else {
            return ConfUtils.getConstDynConf(dlConf);
        }
    }

    private StreamConfigProvider getStreamConfigProvider(DistributedLogConfiguration dlConf,
                                                         StreamPartitionConverter partitionConverter)
            throws ConfigurationException {
        StreamConfigProvider streamConfProvider = new NullStreamConfigProvider();
        if (streamConf.isPresent() && conf.isPresent()) {
            String dynConfigPath = streamConf.get();
            String defaultConfigFile = conf.get();
            streamConfProvider = new ServiceStreamConfigProvider(
                    dynConfigPath,
                    defaultConfigFile,
                    partitionConverter,
                    configExecutorService,
                    dlConf.getDynamicConfigReloadIntervalSec(),
                    TimeUnit.SECONDS);
        } else if (conf.isPresent()) {
            String configFile = conf.get();
            streamConfProvider = new DefaultStreamConfigProvider(configFile, configExecutorService,
                    dlConf.getDynamicConfigReloadIntervalSec(), TimeUnit.SECONDS);
        }
        return streamConfProvider;
    }

    static Pair<DistributedLogServiceImpl, Server> runServer(
            ServerConfiguration serverConf,
            DistributedLogConfiguration dlConf,
            URI dlUri,
            StreamPartitionConverter converter,
            RoutingService routingService,
            StatsProvider provider,
            int port,
            boolean thriftmux,
            LoadAppraiser loadAppraiser) throws IOException {

        return runServer(serverConf,
                dlConf,
                ConfUtils.getConstDynConf(dlConf),
                dlUri,
                converter,
                routingService,
                provider,
                port,
                new CountDownLatch(0),
                new NullStatsReceiver(),
                thriftmux,
                new NullStreamConfigProvider(),
                loadAppraiser);
    }

    static Pair<DistributedLogServiceImpl, Server> runServer(
            ServerConfiguration serverConf,
            DistributedLogConfiguration dlConf,
            DynamicDistributedLogConfiguration dynDlConf,
            URI dlUri,
            StreamPartitionConverter partitionConverter,
            RoutingService routingService,
            StatsProvider provider,
            int port,
            CountDownLatch keepAliveLatch,
            StatsReceiver statsReceiver,
            boolean thriftmux,
            StreamConfigProvider streamConfProvider,
            LoadAppraiser loadAppraiser) throws IOException {
        logger.info("Running server @ uri {}.", dlUri);

        boolean perStreamStatsEnabled = serverConf.isPerStreamStatEnabled();
        StatsLogger perStreamStatsLogger;
        if (perStreamStatsEnabled) {
            perStreamStatsLogger = provider.getStatsLogger("stream");
        } else {
            perStreamStatsLogger = NullStatsLogger.INSTANCE;
        }

        // dl service
        DistributedLogServiceImpl dlService = new DistributedLogServiceImpl(
            serverConf,
            dlConf,
            dynDlConf,
            streamConfProvider,
            dlUri,
            partitionConverter,
            routingService,
            provider.getStatsLogger(""),
            perStreamStatsLogger,
            keepAliveLatch,
            loadAppraiser);

        StatsReceiver serviceStatsReceiver = statsReceiver.scope("service");
        StatsLogger serviceStatsLogger = provider.getStatsLogger("service");

        ServerBuilder serverBuilder = ServerBuilder.get()
                .name("DistributedLogServer")
                .codec(ThriftServerFramedCodec.get())
                .reportTo(statsReceiver)
                .keepAlive(true)
                .bindTo(new InetSocketAddress(port));

        if (thriftmux) {
            logger.info("Using thriftmux.");
            Tuple2<Transport.Liveness, Stack.Param<Transport.Liveness>> livenessParam = new Transport.Liveness(
                    Duration.Top(), Duration.Top(), Option.apply((Object) Boolean.valueOf(true))).mk();
            serverBuilder = serverBuilder.stack(
                ThriftMuxServer$.MODULE$.configured(livenessParam._1(), livenessParam._2()));
        }

        logger.info("DistributedLogServer running with the following configuration : \n{}", dlConf.getPropsAsString());

        // starts dl server
        Server server = ServerBuilder.safeBuild(
                new ClientIdRequiredFilter<byte[], byte[]>(serviceStatsReceiver).andThen(
                    new StatsFilter<byte[], byte[]>(serviceStatsLogger).andThen(
                        new DistributedLogService.Service(dlService, new TBinaryProtocol.Factory()))),
                serverBuilder);

        logger.info("Started DistributedLog Server.");
        return Pair.of(dlService, server);
    }

    static void closeServer(Pair<DistributedLogServiceImpl, Server> pair,
                            long gracefulShutdownPeriod,
                            TimeUnit timeUnit) {
        if (null != pair.getLeft()) {
            pair.getLeft().shutdown();
            if (gracefulShutdownPeriod > 0) {
                try {
                    timeUnit.sleep(gracefulShutdownPeriod);
                } catch (InterruptedException e) {
                    logger.info("Interrupted on waiting service shutting down state propagated to all clients : ", e);
                }
            }
        }
        if (null != pair.getRight()) {
            logger.info("Closing dl thrift server.");
            pair.getRight().close();
            logger.info("Closed dl thrift server.");
        }
    }

    /**
     * Close the server.
     */
    public void close() {
        if (null != announcer) {
            try {
                announcer.unannounce();
            } catch (IOException e) {
                logger.warn("Error on unannouncing service : ", e);
            }
            announcer.close();
        }
        closeServer(Pair.of(dlService, server), gracefulShutdownMs, TimeUnit.MILLISECONDS);
        routingService.stopService();
        if (null != statsProvider) {
            statsProvider.stop();
        }
        SchedulerUtils.shutdownScheduler(configExecutorService, 60, TimeUnit.SECONDS);
        keepAliveLatch.countDown();
    }

    public void join() throws InterruptedException {
        keepAliveLatch.await();
    }

    /**
     * Running distributedlog server.
     *
     * @param uri distributedlog namespace
     * @param conf distributedlog configuration file location
     * @param streamConf per stream configuration dir location
     * @param port listen port
     * @param statsPort stats port
     * @param shardId shard id
     * @param announceServerSet whether to announce itself to server set
     * @param thriftmux flag to enable thrift mux
     * @param statsReceiver receiver to receive finagle stats
     * @param statsProvider provider to receive dl stats
     * @return distributedlog server
     * @throws ConfigurationException
     * @throws IllegalArgumentException
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public static DistributedLogServer runServer(
               Optional<String> uri,
               Optional<String> conf,
               Optional<String> streamConf,
               Optional<Integer> port,
               Optional<Integer> statsPort,
               Optional<Integer> shardId,
               Optional<Boolean> announceServerSet,
               Optional<String> loadAppraiserClass,
               Optional<Boolean> thriftmux,
               RoutingService routingService,
               StatsReceiver statsReceiver,
               StatsProvider statsProvider)
        throws ConfigurationException, IllegalArgumentException, IOException, ClassNotFoundException {

        final DistributedLogServer server = new DistributedLogServer(
                uri,
                conf,
                streamConf,
                port,
                statsPort,
                shardId,
                announceServerSet,
                loadAppraiserClass,
                thriftmux,
                routingService,
                statsReceiver,
                statsProvider);

        server.runServer();
        return server;
    }
}
