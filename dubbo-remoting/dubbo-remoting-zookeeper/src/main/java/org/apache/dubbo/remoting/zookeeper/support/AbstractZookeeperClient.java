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
package org.apache.dubbo.remoting.zookeeper.support;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.remoting.zookeeper.ChildListener;
import org.apache.dubbo.remoting.zookeeper.DataListener;
import org.apache.dubbo.remoting.zookeeper.StateListener;
import org.apache.dubbo.remoting.zookeeper.ZookeeperClient;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;

/**
 * 实现 ZookeeperClient 接口，Zookeeper 客户端抽象类，实现通用的逻辑
 * @param <TargetDataListener>
 * @param <TargetChildListener>
 */
public abstract class AbstractZookeeperClient<TargetDataListener, TargetChildListener> implements ZookeeperClient {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractZookeeperClient.class);

    protected int DEFAULT_CONNECTION_TIMEOUT_MS = 5 * 1000;
    protected int DEFAULT_SESSION_TIMEOUT_MS = 60 * 1000;

    /**
     * 注册中心url
     */
    private final URL url;

    /**
     * stateListener 集合
     */
    private final Set<StateListener> stateListeners = new CopyOnWriteArraySet<StateListener>();

    /**
     * ChildListener 集合
     *
     * key1：节点路径
     * key2：ChildListener 对象
     * value ：监听器具体对象。不同 Zookeeper 客户端，实现会不同。
     */
    private final ConcurrentMap<String, ConcurrentMap<ChildListener, TargetChildListener>> childListeners = new ConcurrentHashMap<String, ConcurrentMap<ChildListener, TargetChildListener>>();

    private final ConcurrentMap<String, ConcurrentMap<DataListener, TargetDataListener>> listeners = new ConcurrentHashMap<String, ConcurrentMap<DataListener, TargetDataListener>>();
    /**
     * 是否关闭
     */
    private volatile boolean closed = false;
    /**
     * 持久化节点路径集合
     */
    private final Set<String>  persistentExistNodePath = new ConcurrentHashSet<>();

    public AbstractZookeeperClient(URL url) {
        this.url = url;
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public void delete(String path){
        //never mind if ephemeral
        persistentExistNodePath.remove(path);
        deletePath(path);
    }


    @Override
    public void create(String path, boolean ephemeral) {
        if (!ephemeral) {
            if(persistentExistNodePath.contains(path)){
                return;
            }
            // 如果要创建的节点类型非临时路径，那么这里要检测节点是否存在
            if (checkExists(path)) {
                persistentExistNodePath.add(path);
                return;
            }
        }
        //循环创建父路径
        int i = path.lastIndexOf('/');
        if (i > 0) {
            // 递归创建上一级路径
            create(path.substring(0, i), false);
        }
        // 根据 ephemeral 的值创建临时或持久节点
        if (ephemeral) {
            createEphemeral(path);
        } else {
            createPersistent(path);
            persistentExistNodePath.add(path);
        }
    }

    @Override
    public void addStateListener(StateListener listener) {
        stateListeners.add(listener);
    }

    @Override
    public void removeStateListener(StateListener listener) {
        stateListeners.remove(listener);
    }

    public Set<StateListener> getSessionListeners() {
        return stateListeners;
    }

    /**
     * 添加监听器数据 向注册中心监听且本地缓存
     * @param path 节点路径
     * @param listener 监听器
     * @return
     */
    @Override
    public List<String> addChildListener(String path, final ChildListener listener) {
        // 获得路径下的监听器数组
        // key :我们自己定义的监听器对象，value: curator框架的监听器对象
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners == null) {
            childListeners.putIfAbsent(path, new ConcurrentHashMap<ChildListener, TargetChildListener>());
            listeners = childListeners.get(path);
        }
        // 获得是否已经有该监听器（curator监听器对象）
        TargetChildListener targetListener = listeners.get(listener);
        if (targetListener == null) {
            listeners.putIfAbsent(listener, createTargetChildListener(path, listener));
            targetListener = listeners.get(listener);
        }
        // 向 Zookeeper ，真正发起订阅
        return addTargetChildListener(path, targetListener);
    }

    @Override
    public void addDataListener(String path, DataListener listener) {
        this.addDataListener(path, listener, null);
    }

    @Override
    public void addDataListener(String path, DataListener listener, Executor executor) {
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        if (dataListenerMap == null) {
            listeners.putIfAbsent(path, new ConcurrentHashMap<DataListener, TargetDataListener>());
            dataListenerMap = listeners.get(path);
        }
        TargetDataListener targetListener = dataListenerMap.get(listener);
        if (targetListener == null) {
            dataListenerMap.putIfAbsent(listener, createTargetDataListener(path, listener));
            targetListener = dataListenerMap.get(listener);
        }
        addTargetDataListener(path, targetListener, executor);
    }

    @Override
    public void removeDataListener(String path, DataListener listener ){
        ConcurrentMap<DataListener, TargetDataListener> dataListenerMap = listeners.get(path);
        if (dataListenerMap != null) {
            TargetDataListener targetListener = dataListenerMap.remove(listener);
            if(targetListener != null){
                removeTargetDataListener(path, targetListener);
            }
        }
    }

    /**
     * 移除指定路径的监听器 先移除本地缓存，再移除注册中心
     * @param path
     * @param listener
     */
    @Override
    public void removeChildListener(String path, ChildListener listener) {
        ConcurrentMap<ChildListener, TargetChildListener> listeners = childListeners.get(path);
        if (listeners != null) {
            TargetChildListener targetListener = listeners.remove(listener);
            if (targetListener != null) {
                removeTargetChildListener(path, targetListener);
            }
        }
    }

    /**
     * StateListener 数组，回调
     *
     * @param state 状态
     */
    protected void stateChanged(int state) {
        for (StateListener sessionListener : getSessionListeners()) {
            sessionListener.stateChanged(state);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            doClose();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public void create(String path, String content, boolean ephemeral) {
        if (checkExists(path)) {
            delete(path);
        }
        int i = path.lastIndexOf('/');
        if (i > 0) {
            create(path.substring(0, i), false);
        }
        if (ephemeral) {
            createEphemeral(path, content);
        } else {
            createPersistent(path, content);
        }
    }

    @Override
    public String getContent(String path) {
        if (!checkExists(path)) {
            return null;
        }
        return doGetContent(path);
    }

    protected abstract void doClose();

    protected abstract void createPersistent(String path);

    protected abstract void createEphemeral(String path);

    protected abstract void createPersistent(String path, String data);

    protected abstract void createEphemeral(String path, String data);

    /**
     * 检查节点是否存在
     * @param path 节点路径
     * @return true 存在
     */
    protected abstract boolean checkExists(String path);

    /**
     * 创建真正的 ChildListener 对象
     * 因为，每个 Zookeeper 的库，实现不同,抽象出来了不同实现
     * @param path
     * @param listener
     * @return
     */
    protected abstract TargetChildListener createTargetChildListener(String path, ChildListener listener);

    /**
     * 向 Zookeeper ，真正发起订阅
     * @param path
     * @param listener
     * @return
     */
    protected abstract List<String> addTargetChildListener(String path, TargetChildListener listener);

    protected abstract TargetDataListener createTargetDataListener(String path, DataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener);

    protected abstract void addTargetDataListener(String path, TargetDataListener listener, Executor executor);

    protected abstract void removeTargetDataListener(String path, TargetDataListener listener);

    protected abstract void removeTargetChildListener(String path, TargetChildListener listener);

    protected abstract String doGetContent(String path);

    /**
     * we invoke the zookeeper client to delete the node
     * @param path the node path
     */
    protected abstract void deletePath(String path);

}
