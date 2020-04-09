/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.registry.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.EXPORT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.REFER_KEY;

/**
 * AbstractRegistryFactory. (SPI, Singleton, ThreadSafe)
 * 实现 RegistryFactory 接口，RegistryFactory 抽象类，实现了 Registry 的容器管理
 * @see org.apache.dubbo.registry.RegistryFactory
 */
public abstract class AbstractRegistryFactory implements RegistryFactory {

    // Log output
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractRegistryFactory.class);

    // The lock for the acquisition process of the registry
    // 获取和销毁注册对象时 ，防止竞争
    private static final ReentrantLock LOCK = new ReentrantLock();

    // Registry Collection Map<RegistryAddress, Registry>
    // key:dubbo://172.16.10.18:2233/org.apache.dubbo.registry.RegistryService
    // value:Registry对象
    private static final Map<String, Registry> REGISTRIES = new HashMap<>();

    /**
     * Get all registries
     *
     * @return all registries
     */
    public static Collection<Registry> getRegistries() {
        return Collections.unmodifiableCollection(REGISTRIES.values());
    }

    /**
     * Close all created registries
     */
    // TODO: 2017/8/30 to move somewhere else better
    public static void destroyAll() {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Close all registries " + getRegistries());
        }
        // Lock up the registry shutdown process
        LOCK.lock();
        try {
            for (Registry registry : getRegistries()) {
                try {
                    registry.destroy();
                } catch (Throwable e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
            REGISTRIES.clear();
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    /**
     * 获取注册中心这个写在父类主要是为了操作缓存，创建、查询等去走子类
     * 和自己写的查询risk-open-api类似
     * @param url Registry address, is not allowed to be empty
     * @return
     */
    @Override
    public Registry getRegistry(URL url) {
        url = URLBuilder.from(url)
                .setPath(RegistryService.class.getName())
                .addParameter(INTERFACE_KEY, RegistryService.class.getName())
                .removeParameters(EXPORT_KEY, REFER_KEY)
                .build();
        String key = url.toServiceStringWithoutResolving();
        // Lock the registry access process to ensure a single instance of the registry
        LOCK.lock();
        try {
            // 读取缓存
            Registry registry = REGISTRIES.get(key);
            if (registry != null) {
                return registry;
            }
            //create registry by spi/ioc
            // 未命中则创建
            registry = createRegistry(url);
            if (registry == null) {
                throw new IllegalStateException("Can not create registry " + url);
            }
            // 写缓存
            REGISTRIES.put(key, registry);
            return registry;
        } finally {
            // Release the lock
            LOCK.unlock();
        }
    }

    /**
     * 创建具体的注册中心对象，如
     * ZookeeperRegistryFactory 的该方法，创建 ZookeeperRegistry 对象。
     * @param url
     * @return
     */
    protected abstract Registry createRegistry(URL url);

}
