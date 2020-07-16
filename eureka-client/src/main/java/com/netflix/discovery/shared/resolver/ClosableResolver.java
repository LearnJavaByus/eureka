package com.netflix.discovery.shared.resolver;

/**
 * @author David Liu
 *
 * 可关闭的解析器接口，继承自 ClusterResolver 接口
 */
public interface ClosableResolver<T extends EurekaEndpoint> extends ClusterResolver<T> {
    /**
     * 关闭
     */
    void shutdown();
}
