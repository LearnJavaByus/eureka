package com.netflix.discovery.shared.transport;

import com.netflix.discovery.shared.resolver.EurekaEndpoint;

/**
 * A low level client factory interface. Not advised to be used by top level consumers.
 *
 * @author David Liu
 *
 * 创建 EurekaHttpClient 的工厂接口
 */
public interface TransportClientFactory {

    /**
     * 创建 EurekaHttpClient
     *
     * @param serviceUrl Eureka-Server 地址
     * @return EurekaHttpClient
     */
    EurekaHttpClient newClient(EurekaEndpoint serviceUrl);

    /**
     * 关闭工厂
     */
    void shutdown();

}
