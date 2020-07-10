/*
 * Copyright 2015 Netflix, Inc.
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

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.ServerCodecs;

/**
 * @author David Liu
 *
 * 提供Eureka-Server 内部各组件对象的初始化、关闭、获取等方法。
 */
public interface EurekaServerContext {

    void initialize() throws Exception;

    void shutdown() throws Exception;

    EurekaServerConfig getServerConfig();

    PeerEurekaNodes getPeerEurekaNodes();

    ServerCodecs getServerCodecs();

    PeerAwareInstanceRegistry getRegistry();

    ApplicationInfoManager getApplicationInfoManager();

}
