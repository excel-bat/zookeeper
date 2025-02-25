/*
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

package org.apache.zookeeper.server;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.management.JMException;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.audit.ZKAuditProvider;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.metrics.MetricsProvider;
import org.apache.zookeeper.metrics.MetricsProviderLifeCycleException;
import org.apache.zookeeper.metrics.impl.MetricsProviderBootstrap;
import org.apache.zookeeper.server.admin.AdminServer;
import org.apache.zookeeper.server.admin.AdminServer.AdminServerException;
import org.apache.zookeeper.server.admin.AdminServerFactory;
import org.apache.zookeeper.server.auth.ProviderRegistry;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog.DatadirException;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;
import org.apache.zookeeper.server.util.JvmPauseMonitor;
import org.apache.zookeeper.util.ServiceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动 ZooKeeperServer 单机运行
 */
@InterfaceAudience.Public
public class ZooKeeperServerMain {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperServerMain.class);

    private static final String USAGE = "Usage: ZooKeeperServerMain configfile | port datadir [ticktime] [maxcnxns]";

    // ZooKeeper server supports two kinds of connection: unencrypted and encrypted.
    private ServerCnxnFactory cnxnFactory;
    private ServerCnxnFactory secureCnxnFactory;
    private ContainerManager containerManager;
    private MetricsProvider metricsProvider;
    private AdminServer adminServer;

    /**
     *  启动参数为 配置文件 或 端口号 data目录 [ticktime]
     *  这里启动参数使用 conf/zoo_sample.cfg 可启动起来
     */
    public static void main(String[] args) {
        ZooKeeperServerMain main = new ZooKeeperServerMain();
        try {
            // 初始化单机服务
            main.initializeAndRun(args);
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid arguments, exiting abnormally", e);
            LOG.info(USAGE);
            System.err.println(USAGE);
            ZKAuditProvider.addServerStartFailureAuditLog();
            ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
        } catch (ConfigException e) {
            LOG.error("Invalid config, exiting abnormally", e);
            System.err.println("Invalid config, exiting abnormally");
            ZKAuditProvider.addServerStartFailureAuditLog();
            ServiceUtils.requestSystemExit(ExitCode.INVALID_INVOCATION.getValue());
        } catch (DatadirException e) {
            LOG.error("Unable to access datadir, exiting abnormally", e);
            System.err.println("Unable to access datadir, exiting abnormally");
            ZKAuditProvider.addServerStartFailureAuditLog();
            ServiceUtils.requestSystemExit(ExitCode.UNABLE_TO_ACCESS_DATADIR.getValue());
        } catch (AdminServerException e) {
            LOG.error("Unable to start AdminServer, exiting abnormally", e);
            System.err.println("Unable to start AdminServer, exiting abnormally");
            ZKAuditProvider.addServerStartFailureAuditLog();
            ServiceUtils.requestSystemExit(ExitCode.ERROR_STARTING_ADMIN_SERVER.getValue());
        } catch (Exception e) {
            LOG.error("Unexpected exception, exiting abnormally", e);
            ZKAuditProvider.addServerStartFailureAuditLog();
            ServiceUtils.requestSystemExit(ExitCode.UNEXPECTED_ERROR.getValue());
        }
        LOG.info("Exiting normally");
        ServiceUtils.requestSystemExit(ExitCode.EXECUTION_FINISHED.getValue());
    }

    protected void initializeAndRun(String[] args) throws ConfigException, IOException, AdminServerException {
        try {
            // 为 Log4j 注册 jmx
            ManagedUtil.registerLog4jMBeans();
        } catch (JMException e) {
            LOG.warn("Unable to register log4j JMX control", e);
        }
        
        ServerConfig config = new ServerConfig();
        if (args.length == 1) {
            // 此时指定的是配置文件
            config.parse(args[0]);
        } else {
            // 此时指定的是 port datadir [ticktime]
            config.parse(args);
        }
        // 准备启动服务
        runFromConfig(config);
    }

    /**
     * 解析好配置后，开始读取配置启动服务了，记住，这里是单机服务
     * @param config ServerConfig to use.
     * @throws IOException
     * @throws AdminServerException
     */
    public void runFromConfig(ServerConfig config) throws IOException, AdminServerException {
        LOG.info("Starting server");
        // 日志和快照操作类
        FileTxnSnapLog txnLog = null;
        try {
            try {
                metricsProvider = MetricsProviderBootstrap.startMetricsProvider(
                    config.getMetricsProviderClassName(),
                    config.getMetricsProviderConfiguration());
            } catch (MetricsProviderLifeCycleException error) {
                throw new IOException("Cannot boot MetricsProvider " + config.getMetricsProviderClassName(), error);
            }
            ServerMetrics.metricsProviderInitialized(metricsProvider);
            ProviderRegistry.initialize();
            // 实例化单机服务的 zk 服务器
            // 该构造器将设置一个用于统计数据的 serverStats 对象
            // 和一个用于监听 zk 服务是否异常的监听器
            txnLog = new FileTxnSnapLog(config.dataLogDir, config.dataDir);
            JvmPauseMonitor jvmPauseMonitor = null;
            if (config.jvmPauseMonitorToRun) {
                jvmPauseMonitor = new JvmPauseMonitor(config);
            }
            final ZooKeeperServer zkServer = new ZooKeeperServer(jvmPauseMonitor, txnLog, config.tickTime, config.minSessionTimeout, config.maxSessionTimeout, config.listenBacklog, null, config.initialConfig);
            txnLog.setServerStats(zkServer.serverStats());
    
            // shutdownLatch 是一个计数器，当发生错误或者关闭事件，则其值减一，当 shutdownLatch 为零时，服务将关闭
            final CountDownLatch shutdownLatch = new CountDownLatch(1);
            // 注册 zk 的异常关闭处理器
            zkServer.registerServerShutdownHandler(new ZooKeeperServerShutdownHandler(shutdownLatch));

            // Start Admin server
            adminServer = AdminServerFactory.createAdminServer();
            adminServer.setZooKeeperServer(zkServer);
            adminServer.start();

            boolean needStartZKServer = true;
            if (config.getClientPortAddress() != null) {
                // serverCnxnFactory 用于创建和客户端的连接
                // createFactory() 是为了检查是否有指定其他实现类
                // ServerCnxnFactory 默认采用的实现类是 NIOServerCnxnFactory
                cnxnFactory = ServerCnxnFactory.createFactory();
                // 传入该服务器的地址和最大允许客户端的连接数，
                // 默认使用的
                cnxnFactory.configure(config.getClientPortAddress(), config.getMaxClientCnxns(), config.getClientPortListenBacklog(), false);
                // 启动守护进程 serverCnxnFactory
                cnxnFactory.startup(zkServer);
                // zkServer has been started. So we don't need to start it again in secureCnxnFactory.
                needStartZKServer = false;
            }
            if (config.getSecureClientPortAddress() != null) {
                secureCnxnFactory = ServerCnxnFactory.createFactory();
                secureCnxnFactory.configure(config.getSecureClientPortAddress(), config.getMaxClientCnxns(), config.getClientPortListenBacklog(), true);
                secureCnxnFactory.startup(zkServer, needStartZKServer);
            }

            containerManager = new ContainerManager(
                zkServer.getZKDatabase(),
                zkServer.firstProcessor,
                Integer.getInteger("znode.container.checkIntervalMs", (int) TimeUnit.MINUTES.toMillis(1)),
                Integer.getInteger("znode.container.maxPerMinute", 10000),
                Long.getLong("znode.container.maxNeverUsedIntervalMs", 0)
            );
            containerManager.start();
            ZKAuditProvider.addZKStartStopAuditLog();

        
            // 监听zookeeper server，当shutdownLatch为零时执行后面的代码（实现优雅停服）
            shutdownLatch.await();
            // 关闭服务
            shutdown();

            if (cnxnFactory != null) {
                // 等待 cnxnFactory 执行完毕，其处理关闭和客户端的连接
                cnxnFactory.join();
            }
            if (secureCnxnFactory != null) {
                secureCnxnFactory.join();
            }
            if (zkServer.canShutdown()) {
                // 如果服务器状态处于 RUNNING 或者 ERROR 时即允许关闭；这是因为服务器可能已经处于关闭状态就不必再关闭了
                zkServer.shutdown(true);
            }
        } catch (InterruptedException e) {
            // 意外的线程中断，但一般情况下这是允许发生的
            LOG.warn("Server interrupted", e);
        } finally {
            // 快照和日志做关闭处理
            if (txnLog != null) {
                txnLog.close();
            }
            if (metricsProvider != null) {
                try {
                    metricsProvider.stop();
                } catch (Throwable error) {
                    LOG.warn("Error while stopping metrics", error);
                }
            }
        }
    }

    /**
     * Shutdown the serving instance
     */
    protected void shutdown() {
        if (containerManager != null) {
            containerManager.stop();
        }
        if (cnxnFactory != null) {
            cnxnFactory.shutdown();
        }
        if (secureCnxnFactory != null) {
            secureCnxnFactory.shutdown();
        }
        try {
            if (adminServer != null) {
                adminServer.shutdown();
            }
        } catch (AdminServerException e) {
            LOG.warn("Problem stopping AdminServer", e);
        }
    }

    // VisibleForTesting
    ServerCnxnFactory getCnxnFactory() {
        return cnxnFactory;
    }

    // VisibleForTesting
    ServerCnxnFactory getSecureCnxnFactory() {
        return secureCnxnFactory;
    }

    /**
     * Shutdowns properly the service, this method is not a public API.
     */
    public void close() {
        ServerCnxnFactory primaryCnxnFactory = this.cnxnFactory;
        if (primaryCnxnFactory == null) {
            // in case of pure TLS we can hook into secureCnxnFactory
            primaryCnxnFactory = secureCnxnFactory;
        }
        if (primaryCnxnFactory == null || primaryCnxnFactory.getZooKeeperServer() == null) {
            return;
        }
        ZooKeeperServerShutdownHandler zkShutdownHandler = primaryCnxnFactory.getZooKeeperServer().getZkShutdownHandler();
        zkShutdownHandler.handle(ZooKeeperServer.State.SHUTDOWN);
        try {
            // ServerCnxnFactory will call the shutdown
            primaryCnxnFactory.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

}
