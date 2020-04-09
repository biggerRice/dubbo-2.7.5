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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Round robin load balance.
 */
public class RoundRobinLoadBalance extends AbstractLoadBalance {
    public static final String NAME = "roundrobin";
    
    private static final int RECYCLE_PERIOD = 60000;

    /**
     * 平滑加权轮询
     * 原理
     * 每个服务器都有两个权重变量：
     *
     * weight，配置文件中指定的该服务器的权重，这个值是固定不变的；
     * current，服务器目前的权重。一开始为0，之后会动态调整
     *
     * 　1.每次当请求到来，选取服务器时，会遍历数组中所有服务器。对于每个服务器，让它的current增加它的weight；
     *      同时累加所有服务器的weight，并保存为total。
     *
     * 　2.遍历完所有服务器之后，如果该服务器的current是最大的，就选择这个服务器处理本次请求。
     *      最后把该服务器的current_weight减去total。
     *
     *   说明实例 ：https://blog.csdn.net/larry_zeng1/article/details/80407745
     * */
    protected static class WeightedRoundRobin {
        // 配置文件中指定的该服务器的权重，这个值是固定不变的；
        private int weight;
        // 当前服务器的动态权重
        private AtomicLong current = new AtomicLong(0);
        // 最后更新时间
        private long lastUpdate;

        public int getWeight() {
            return weight;
        }
        public void setWeight(int weight) {
            this.weight = weight;
            current.set(0);
        }
        public long increaseCurrent() {
            return current.addAndGet(weight);
        }
        public void sel(int total) {
            current.addAndGet(-1 * total);
        }
        public long getLastUpdate() {
            return lastUpdate;
        }
        public void setLastUpdate(long lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    /**
    * 为每个方法缓存对应的一组带权重的轮询服务器
     **/
    private ConcurrentMap<String, ConcurrentMap<String, WeightedRoundRobin>> methodWeightMap = new ConcurrentHashMap<String, ConcurrentMap<String, WeightedRoundRobin>>();
    private AtomicBoolean updateLock = new AtomicBoolean();
    
    /**
     * get invoker addr list cached for specified invocation
     * <p>
     * <b>for unit test only</b>
     * 
     * @param invokers
     * @param invocation
     * @return
     */
    protected <T> Collection<String> getInvokerAddrList(List<Invoker<T>> invokers, Invocation invocation) {
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        Map<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        if (map != null) {
            return map.keySet();
        }
        return null;
    }
    
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // key = 全限定类名 + "." + 方法名，比如 com.xxx.DemoService.sayHello
        String key = invokers.get(0).getUrl().getServiceKey() + "." + invocation.getMethodName();
        // 获取当前方法对应的带权重服务器列表
        ConcurrentMap<String, WeightedRoundRobin> map = methodWeightMap.get(key);
        // 如果为空位当前方法创建
        if (map == null) {
            methodWeightMap.putIfAbsent(key, new ConcurrentHashMap<String, WeightedRoundRobin>());
            map = methodWeightMap.get(key);
        }
        // 权重总和
        int totalWeight = 0;
        // 最大权重（每一轮循环都是找到最大权重）
        long maxCurrent = Long.MIN_VALUE;
        long now = System.currentTimeMillis();
        // 选中的服务提供者
        Invoker<T> selectedInvoker = null;
        WeightedRoundRobin selectedWRR = null;
        // 求总权重
        for (Invoker<T> invoker : invokers) {
            String identifyString = invoker.getUrl().toIdentityString();
            WeightedRoundRobin weightedRoundRobin = map.get(identifyString);
            int weight = getWeight(invoker, invocation);

            if (weightedRoundRobin == null) {
                weightedRoundRobin = new WeightedRoundRobin();
                weightedRoundRobin.setWeight(weight);
                map.putIfAbsent(identifyString, weightedRoundRobin);
            }
            if (weight != weightedRoundRobin.getWeight()) {
                //weight changed
                weightedRoundRobin.setWeight(weight);
            }
            long cur = weightedRoundRobin.increaseCurrent();
            weightedRoundRobin.setLastUpdate(now);
            if (cur > maxCurrent) {
                maxCurrent = cur;
                selectedInvoker = invoker;
                selectedWRR = weightedRoundRobin;
            }
            totalWeight += weight;
        }
        if (!updateLock.get() && invokers.size() != map.size()) {
            if (updateLock.compareAndSet(false, true)) {
                try {
                    // copy -> modify -> update reference
                    ConcurrentMap<String, WeightedRoundRobin> newMap = new ConcurrentHashMap<>(map);
                    newMap.entrySet().removeIf(item -> now - item.getValue().getLastUpdate() > RECYCLE_PERIOD);
                    methodWeightMap.put(key, newMap);
                } finally {
                    updateLock.set(false);
                }
            }
        }
        if (selectedInvoker != null) {
            selectedWRR.sel(totalWeight);
            return selectedInvoker;
        }
        // should not happen here
        return invokers.get(0);
    }

}
