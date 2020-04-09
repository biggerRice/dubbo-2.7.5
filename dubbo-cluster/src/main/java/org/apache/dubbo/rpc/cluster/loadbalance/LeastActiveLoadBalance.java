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
import org.apache.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LeastActiveLoadBalance
 * <p>
 * Filter the number of invokers with the least number of active calls and count the weights and quantities of these invokers.
 * If there is only one invoker, use the invoker directly;
 * if there are multiple invokers and the weights are not the same, then random according to the total weight;
 * if there are multiple invokers and the same weight, then randomly called.
 *
 * 即服务提供者目前正在处理的请求数（一个请求对应一条连接）最少，表明该服务提供者效率高，
 * 单位时间内可处理更多的请求。此时应优先将请求分配给该服务提供者。
 * 在具体实现中，每个服务提供者对应一个活跃数 active。初始情况下，
 * 所有服务提供者活跃数均为0。每收到一个请求，活跃数加1，完成请求后则将活跃数减1。
 * 在服务运行一段时间后，性能好的服务提供者处理请求的速度更快，
 * 因此活跃数下降的也越快
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    /**
     * 内容：
     * 1.遍历 invokers 列表，寻找活跃数最小的 Invoker
     * 2.如果有多个 Invoker 具有相同的最小活跃数，此时记录下这些 Invoker 在 invokers 集合中的下标，以及累加它们的权重，比较它们之间的权重值是否相等
     * 3.如果只有一个 Invoker 具有最小的活跃数，此时直接返回该 Invoker 即可
     * 4.如果有多个 Invoker 具有最小活跃数，且它们的权重不相等，此时处理方式和 RandomLoadBalance 一致
     * 5.如果有多个 Invoker 具有最小活跃数，但它们的权重相等，此时随机返回一个即可
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        // Number of invokers
        // 提供者数量
        int length = invokers.size();
        // The least active value of all invokers
        // 最小活跃数
        int leastActive = -1;
        // The number of invokers having the same least active value (leastActive)
        // 具有相同最小活跃数的提供者数量
        int leastCount = 0;
        // The index of invokers having the same least active value (leastActive)
        // 存储具有相同最小活跃数的提供者下标索引
        int[] leastIndexes = new int[length];
        // the weight of every invokers
        // 每个提供者的权重
        int[] weights = new int[length];
        // The sum of the warmup weights of all the least active invokers
        // 具有相同最小活跃数的提供者权重之和
        int totalWeight = 0;
        // The weight of the first least active invoker
        // 第一个最小活跃数的提供者权重
        int firstWeight = 0;
        // Every least active invoker has the same weight value?
        // 是否剧痛相同的权重
        boolean sameWeight = true;


        // 遍历 invokers 列表
        // Filter out all the least active invokers
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            // Get the active number of the invoker
            // 获取 Invoker 对应的活跃数
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive();
            // Get the weight of the invoker's configuration. The default value is 100.
            // afterWarmup 等价于上面的 weight 变量，这样命名是为了强调该变量经过 warmup 降权处理了
            int afterWarmup = getWeight(invoker, invocation);
            // save for later use
            weights[i] = afterWarmup;
            // If it is the first invoker or the active number of the invoker is less than the current least active number
            // 发现更小的活跃数，重新开始
            if (leastActive == -1 || active < leastActive) {
                // Reset the active number of the current invoker to the least active number
                // 使用当前活跃数 active 更新最小活跃数 leastActive
                leastActive = active;
                // Reset the number of least active invokers
                leastCount = 1;
                // Put the first least active invoker first in leastIndexes
                // 记录当前下标值到 leastIndexs 中
                leastIndexes[0] = i;
                // Reset totalWeight
                totalWeight = afterWarmup;
                // Record the weight the first least active invoker
                firstWeight = afterWarmup;
                // Each invoke has the same weight (only one invoker here)
                sameWeight = true;
                // If current invoker's active value equals with leaseActive, then accumulating.
                // 当前 Invoker 的活跃数 active 与最小活跃数 leastActive 相同
            } else if (active == leastActive) {
                // Record the index of the least active invoker in leastIndexes order
                // 在 leastIndexs 中记录下当前 Invoker 在 invokers 集合中的下标
                leastIndexes[leastCount++] = i;
                // Accumulate the total weight of the least active invoker
                // 累加权重
                totalWeight += afterWarmup;
                // If every invoker has the same weight?
                // 检测当前 Invoker 的权重与 firstWeight 是否相等，
                // 不相等则将 sameWeight 置为 false
                if (sameWeight && i > 0
                        && afterWarmup != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // Choose an invoker from all the least active invokers
        // 当只有一个 Invoker 具有最小活跃数，此时直接返回该 Invoker 即可
        if (leastCount == 1) {
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexes[0]);
        }

        // 有多个 Invoker 具有相同的最小活跃数，但他们的权重不同
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on 
            // 随机获取一个 [0, totalWeight) 之间的数字
            // totalWeight.
            int offsetWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            // 循环让随机数减去具有最小活跃数的 Invoker 的权重值，
            // 当 offset 小于等于0时，返回相应的 Invoker
            // Return a invoker based on the random value.
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexes[i];
                offsetWeight -= weights[leastIndex];
                if (offsetWeight < 0) {
                    return invokers.get(leastIndex);
                }
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果权重相同或权重为0时，随机返回一个 Invoker
        return invokers.get(leastIndexes[ThreadLocalRandom.current().nextInt(leastCount)]);
    }
}
