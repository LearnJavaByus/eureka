/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.Date;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.CloudInstanceConfig;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.MyDataCenterInstanceConfig;
import com.netflix.appinfo.providers.EurekaConfigBasedInstanceInfoProvider;
import com.netflix.config.ConfigurationManager;
import com.netflix.config.DeploymentContext;
import com.netflix.discovery.DefaultEurekaClientConfig;
import com.netflix.discovery.DiscoveryClient;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.JsonXStream;
import com.netflix.discovery.converters.XmlXStream;
import com.netflix.eureka.aws.AwsBinder;
import com.netflix.eureka.aws.AwsBinderDelegate;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.AwsInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.EurekaMonitors;
import com.thoughtworks.xstream.XStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class that kick starts the eureka server.
 *
 * <p>
 * The eureka server is configured by using the configuration
 * {@link EurekaServerConfig} specified by <em>eureka.server.props</em> in the
 * classpath.  The eureka client component is also initialized by using the
 * configuration {@link EurekaInstanceConfig} specified by
 * <em>eureka.client.props</em>. If the server runs in the AWS cloud, the eureka
 * server binds it to the elastic ip as specified.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim, David Liu
 *
 * eureka server对应的配置类为：EurekaServerConfig
 * eureka client对应的配置类为：EurekaInstanceConfig
 *
 * 负责EurekaServer初始化的类,Eureka-Server 启动入口
 *
 */
public class EurekaBootStrap implements ServletContextListener {
    private static final Logger logger = LoggerFactory.getLogger(EurekaBootStrap.class);

    /**
     * 部署环境 - 测服
     */
    private static final String TEST = "test";

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";

    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    /**
     * 部署数据中心 - CLOUD
     */
    private static final String CLOUD = "cloud";
    /**
     * 部署数据中心 - 默认
     */
    private static final String DEFAULT = "default";

    private static final String ARCHAIUS_DEPLOYMENT_DATACENTER = "archaius.deployment.datacenter";

    private static final String EUREKA_DATACENTER = "eureka.datacenter";

    protected volatile EurekaServerContext serverContext;
    protected volatile AwsBinder awsBinder;
    
    private EurekaClient eurekaClient;

    /**
     * Construct a default instance of Eureka boostrap
     */
    public EurekaBootStrap() {
        this(null);
    }
    
    /**
     * Construct an instance of eureka bootstrap with the supplied eureka client
     * 
     * @param eurekaClient the eureka client to bootstrap
     */
    public EurekaBootStrap(EurekaClient eurekaClient) {
        this.eurekaClient = eurekaClient;
    }

    /**
     * Initializes Eureka, including syncing up with other Eureka peers and publishing the registry.
     *
     * @see
     * javax.servlet.ServletContextListener#contextInitialized(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextInitialized(ServletContextEvent event) {
        try {
            // 初始化 Eureka-Server 配置环境
            initEurekaEnvironment();
            // 初始化 Eureka-Server 上下文
            initEurekaServerContext();

            ServletContext sc = event.getServletContext();
            sc.setAttribute(EurekaServerContext.class.getName(), serverContext);
        } catch (Throwable e) {
            logger.error("Cannot bootstrap eureka server :", e);
            throw new RuntimeException("Cannot bootstrap eureka server :", e);
        }
    }

    /**
     * Users can override to initialize the environment themselves.
     */
    protected void initEurekaEnvironment() throws Exception {
        logger.info("Setting the eureka configuration..");

        /**
         * 获取dataCenter数据中心 这里重点看ConfigurationManager
         * ConfigurationManager:配置管理器，管理eureka自己所有的配置，
         * 重点：getConfigInstance里面使用的是volatile+synchronized+double check模式的单例模式
         *
         * ConfigurationManager 创建过程：（继续往后跟读代码）
         * 1、创建一个ConcurrentCompositeConfiguration实例，这个类代表了所谓的配置，包括eureka需要的所有配置。
         * 2、往ConcurrentCompositeConfiguration加入一堆config，然后返回ConfigurationManager实例
         * 3、初始化数据中心的配置，如果没有配置的话就是default data center
         * 4、初始化eureka 运行的环境，如果没有配置的话，默认就是test环境
         */
        // 设置配置文件的数据中心
        String dataCenter = ConfigurationManager.getConfigInstance().getString(EUREKA_DATACENTER);
        if (dataCenter == null) {
            //如果没有配置的话就是default data center
            logger.info("Eureka data center value eureka.datacenter is not set, defaulting to default");
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, DEFAULT);
        } else {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_DATACENTER, dataCenter);
        }
        // 后面读取配置文件会根据运行环境读取，比如eureka-server-test.properties // 设置配置文件的环境
        String environment = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT);
        //如果没有配置的话，默认就是test环境
        if (environment == null) {
            ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, TEST);
            logger.info("Eureka environment value eureka.environment is not set, defaulting to test");
        }
    }

    /**
     * init hook for server context. Override for custom logic.
     *
     * 加载eureka-server.properties的过程：
     *
     * （1）创建了一个DefaultEurekaServerConfig对象
     * （2）创建DefaultEurekaServerConfig对象的时候，在里面会有一个init方法
     * （3）先是将eureka-server.properties中的配置加载到了一个Properties对象中，然后将Properties对象中的配置放到ConfigurationManager中去，此时ConfigurationManager中去就有了所有的配置了
     * （4）然后DefaultEurekaServerConfig提供的获取配置项的各个方法，都是通过硬编码的配置项名称，从DynamicPropertyFactory中获取配置项的值，DynamicPropertyFactory是从ConfigurationManager那儿来的，所以也包含了所有配置项的值
     * （5）在获取配置项的时候，如果没有配置，那么就会有默认的值，全部属性都是有默认值的
     */
    protected void initEurekaServerContext() throws Exception {
        // 1、加载eureka-server properties文件中和配置
        EurekaServerConfig eurekaServerConfig = new DefaultEurekaServerConfig();

        // For backward compatibility 请求和响应的数据兼容
        JsonXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);
        XmlXStream.getInstance().registerConverter(new V1AwareInstanceInfoConverter(), XStream.PRIORITY_VERY_HIGH);

        logger.info("Initializing the eureka client...");
        logger.info(eurekaServerConfig.getJsonCodecName());
        // 创建 Eureka-Server 请求和响应编解码器
        ServerCodecs serverCodecs = new DefaultServerCodecs(eurekaServerConfig);

        // 2、初始化一个ApplicationInfoManager，和第3步创建eureka client相关，后续会讲解
        ApplicationInfoManager applicationInfoManager = null;

        /**
         * 3、初始化eureka-server内部的一个eureka-client（用来跟其他的eureka-server节点做注册和通信）
         * 类的开头已经说明了：EurekaInstanceConfig其实就是eureka client相关的配置类
         */
        if (eurekaClient == null) {
            EurekaInstanceConfig instanceConfig = isCloud(ConfigurationManager.getDeploymentContext())
                    ? new CloudInstanceConfig()
                    : new MyDataCenterInstanceConfig();
            
            applicationInfoManager = new ApplicationInfoManager(
                    instanceConfig, new EurekaConfigBasedInstanceInfoProvider(instanceConfig).get());

            // DefaultEurekaClientConfig类似于上面的DefaultEurekaServerConfig类实现
            EurekaClientConfig eurekaClientConfig = new DefaultEurekaClientConfig();
            eurekaClient = new DiscoveryClient(applicationInfoManager, eurekaClientConfig);
        } else {
            applicationInfoManager = eurekaClient.getApplicationInfoManager();
        }

        // 3、处理注册相关的事情
        // PeerAware:可以识别eureka server集群的。多个同样的东西组成一个集群，peers：集群，peer就是集群中的一个实例
        // InstanceRegistry:实例注册，服务实例注册。注册表，这个里面放了所有的主车道这个eureka server上的服务实例，就是一个服务实例的注册表。
        // PeerAwareInstanceRegistry：可以感知eureka server集群的服务实例注册表，eureka client(作为服务实例)过来注册的注册表，而且这个注册表是可以感知到eureka
        // server集群的，假如有一个eureka server集群的话，这里包含了其他eureka server中的服务实例注册表信息的。
        PeerAwareInstanceRegistry registry;
        if (isAws(applicationInfoManager.getInfo())) {
            registry = new AwsInstanceRegistry(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
            awsBinder = new AwsBinderDelegate(eurekaServerConfig, eurekaClient.getEurekaClientConfig(), registry, applicationInfoManager);
            awsBinder.start();
        } else {
            // 创建PeerAwareInstanceRegistry。最近取消的实例，最近注册的实例都会保存到registry中
            registry = new PeerAwareInstanceRegistryImpl(
                    eurekaServerConfig,
                    eurekaClient.getEurekaClientConfig(),
                    serverCodecs,
                    eurekaClient
            );
        }

        // 4、处理peer节点相关的事情   创建 Eureka-Server 集群节点集合
        // peerEurekaNodes 代表了eureka server集群，peers大概来说是多个相同的实例组成的一个集群，peer就是peers中的一个实例
        // PeerEurekaNodes，代表的是eureka server集群
        PeerEurekaNodes peerEurekaNodes = getPeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClient.getEurekaClientConfig(),
                serverCodecs,
                applicationInfoManager
        );

        // 5、完成eureka-server上下文(context)的构建及初始化
        serverContext = new DefaultEurekaServerContext(
                eurekaServerConfig,
                serverCodecs,
                registry,
                peerEurekaNodes,
                applicationInfoManager
        );

        // 将上下文信息放到holder中
        EurekaServerContextHolder.initialize(serverContext);

        // 将eureka server集群启动起来。里面会更新eureka server集群的信息，让当前的eureka server感知到所有的其他eureka server
        // 然后搞一个定时调度任务，就是一个后台线程，每隔一段时间，更新eureka server集群的信息
        // registry.init: 基于eureka server集群的信息，来初始化注册表，将eureka server集群中所有的eureka
        // server的注册表信息抓取过来，放到自己本地的注册表中。都是跟eureka server集群中之间的注册表信息互换有关联的
        serverContext.initialize();
        logger.info("Initialized server context");

        // Copy registry from neighboring eureka node
        // 6、处理一些善后的事情，从相邻的eureka节点拷贝注册信息,如果拷贝失败，就找下一个
        int registryCount = registry.syncUp();
        //服务实例检测的调度任务,计算预期的1分钟所有服务实例心跳次数，如宕机
        registry.openForTraffic(applicationInfoManager, registryCount);

        // Register all monitoring statistics.
        // 7、注册所有的监控统计项
        EurekaMonitors.registerAllStats();
    }
    
    protected PeerEurekaNodes getPeerEurekaNodes(PeerAwareInstanceRegistry registry, EurekaServerConfig eurekaServerConfig, EurekaClientConfig eurekaClientConfig, ServerCodecs serverCodecs, ApplicationInfoManager applicationInfoManager) {
        PeerEurekaNodes peerEurekaNodes = new PeerEurekaNodes(
                registry,
                eurekaServerConfig,
                eurekaClientConfig,
                serverCodecs,
                applicationInfoManager
        );
        
        return peerEurekaNodes;
    }

    /**
     * Handles Eureka cleanup, including shutting down all monitors and yielding all EIPs.
     *
     * @see javax.servlet.ServletContextListener#contextDestroyed(javax.servlet.ServletContextEvent)
     */
    @Override
    public void contextDestroyed(ServletContextEvent event) {
        try {
            logger.info("{} Shutting down Eureka Server..", new Date().toString());
            ServletContext sc = event.getServletContext();
            sc.removeAttribute(EurekaServerContext.class.getName());

            destroyEurekaServerContext();
            destroyEurekaEnvironment();

        } catch (Throwable e) {
            logger.error("Error shutting down eureka", e);
        }
        logger.info("{} Eureka Service is now shutdown...", new Date().toString());
    }

    /**
     * Server context shutdown hook. Override for custom logic
     */
    protected void destroyEurekaServerContext() throws Exception {
        EurekaMonitors.shutdown();
        if (awsBinder != null) {
            awsBinder.shutdown();
        }
        if (serverContext != null) {
            serverContext.shutdown();
        }
    }

    /**
     * Users can override to clean up the environment themselves.
     */
    protected void destroyEurekaEnvironment() throws Exception {

    }

    protected boolean isAws(InstanceInfo selfInstanceInfo) {
        boolean result = DataCenterInfo.Name.Amazon == selfInstanceInfo.getDataCenterInfo().getName();
        logger.info("isAws returned {}", result);
        return result;
    }

    protected boolean isCloud(DeploymentContext deploymentContext) {
        logger.info("Deployment datacenter is {}", deploymentContext.getDeploymentDatacenter());
        return CLOUD.equals(deploymentContext.getDeploymentDatacenter());
    }
}
