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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.URLBuilder;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.serialize.support.SerializableClassRegistry;
import org.apache.dubbo.common.serialize.support.SerializationOptimizer;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.ConcurrentHashSet;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporter;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchangers;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerAdapter;
import org.apache.dubbo.rpc.Exporter;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Protocol;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.protocol.AbstractProtocol;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.INTERFACE_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_CONNECT_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.ON_DISCONNECT_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_HEARTBEAT;
import static org.apache.dubbo.remoting.Constants.HEARTBEAT_KEY;
import static org.apache.dubbo.remoting.Constants.CHANNEL_READONLYEVENT_SENT_KEY;
import static org.apache.dubbo.remoting.Constants.CLIENT_KEY;
import static org.apache.dubbo.remoting.Constants.CODEC_KEY;
import static org.apache.dubbo.remoting.Constants.DEFAULT_REMOTING_CLIENT;
import static org.apache.dubbo.remoting.Constants.SERVER_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_REMOTING_SERVER;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.CALLBACK_SERVICE_KEY;
import static org.apache.dubbo.remoting.Constants.CONNECTIONS_KEY;
import static org.apache.dubbo.rpc.Constants.DEFAULT_STUB_EVENT;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.IS_CALLBACK_SERVICE;
import static org.apache.dubbo.rpc.Constants.IS_SERVER_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.OPTIMIZER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_METHODS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.SHARE_CONNECTIONS_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.Constants.DEFAULT_SHARE_CONNECTIONS;


/**
 * dubbo protocol support.
 */
public class DubboProtocol extends AbstractProtocol {

    public static final String NAME = "dubbo";

    public static final int DEFAULT_PORT = 20880;
    private static final String IS_CALLBACK_SERVICE_INVOKE = "_isCallBackServiceInvoke";
    private static DubboProtocol INSTANCE;

    /**
     * <host:port,Exchanger>
     *   通信服务器集合
     *   key: 服务器地址。格式为：host:port
     *   例如 key:172.16.10.18:12345
     *
     */
    private final Map<String, ExchangeServer> serverMap = new ConcurrentHashMap<>();
    /**
     * <host:port,Exchanger>
     *
     *  通信客户端集合
     *
     *  key: 服务器地址。格式为：host:port
     *
     */
    private final Map<String, List<ReferenceCountExchangeClient>> referenceClientMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Set<String> optimizers = new ConcurrentHashSet<>();
    /**
     * consumer side export a stub service for dispatching event
     * servicekey-stubmethods
     */
    private final ConcurrentMap<String, String> stubServiceMethodsMap = new ConcurrentHashMap<>();

    /**
     * 这个处理器，负责将请求，转发到对应的 Invoker 对象，执行逻辑，返回结果
     */
    private ExchangeHandler requestHandler = new ExchangeHandlerAdapter() {

        @Override
        public CompletableFuture<Object> reply(ExchangeChannel channel, Object message) throws RemotingException {

            if (!(message instanceof Invocation)) {
                throw new RemotingException(channel, "Unsupported request: "
                        + (message == null ? null : (message.getClass().getName() + ": " + message))
                        + ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress());
            }

            Invocation inv = (Invocation) message;
            Invoker<?> invoker = getInvoker(channel, inv);
            // need to consider backward-compatibility if it's a callback
            if (Boolean.TRUE.toString().equals(inv.getAttachments().get(IS_CALLBACK_SERVICE_INVOKE))) {
                String methodsStr = invoker.getUrl().getParameters().get("methods");
                boolean hasMethod = false;
                if (methodsStr == null || !methodsStr.contains(",")) {
                    hasMethod = inv.getMethodName().equals(methodsStr);
                } else {
                    String[] methods = methodsStr.split(",");
                    for (String method : methods) {
                        if (inv.getMethodName().equals(method)) {
                            hasMethod = true;
                            break;
                        }
                    }
                }
                if (!hasMethod) {
                    logger.warn(new IllegalStateException("The methodName " + inv.getMethodName()
                            + " not found in callback service interface ,invoke will be ignored."
                            + " please update the api interface. url is:"
                            + invoker.getUrl()) + " ,invocation is :" + inv);
                    return null;
                }
            }
            RpcContext.getContext().setRemoteAddress(channel.getRemoteAddress());
            Result result = invoker.invoke(inv);
            return result.completionFuture().thenApply(Function.identity());
        }

        @Override
        public void received(Channel channel, Object message) throws RemotingException {
            if (message instanceof Invocation) {
                reply((ExchangeChannel) channel, message);

            } else {
                super.received(channel, message);
            }
        }

        @Override
        public void connected(Channel channel) throws RemotingException {
            invoke(channel, ON_CONNECT_KEY);
        }

        @Override
        public void disconnected(Channel channel) throws RemotingException {
            if (logger.isDebugEnabled()) {
                logger.debug("disconnected from " + channel.getRemoteAddress() + ",url:" + channel.getUrl());
            }
            invoke(channel, ON_DISCONNECT_KEY);
        }

        private void invoke(Channel channel, String methodKey) {
            Invocation invocation = createInvocation(channel, channel.getUrl(), methodKey);
            if (invocation != null) {
                try {
                    received(channel, invocation);
                } catch (Throwable t) {
                    logger.warn("Failed to invoke event method " + invocation.getMethodName() + "(), cause: " + t.getMessage(), t);
                }
            }
        }

        private Invocation createInvocation(Channel channel, URL url, String methodKey) {
            String method = url.getParameter(methodKey);
            if (method == null || method.length() == 0) {
                return null;
            }

            RpcInvocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
            invocation.setAttachment(PATH_KEY, url.getPath());
            invocation.setAttachment(GROUP_KEY, url.getParameter(GROUP_KEY));
            invocation.setAttachment(INTERFACE_KEY, url.getParameter(INTERFACE_KEY));
            invocation.setAttachment(VERSION_KEY, url.getParameter(VERSION_KEY));
            if (url.getParameter(STUB_EVENT_KEY, false)) {
                invocation.setAttachment(STUB_EVENT_KEY, Boolean.TRUE.toString());
            }

            return invocation;
        }
    };

    public DubboProtocol() {
        INSTANCE = this;
    }

    public static DubboProtocol getDubboProtocol() {
        if (INSTANCE == null) {
            // load
            ExtensionLoader.getExtensionLoader(Protocol.class).getExtension(DubboProtocol.NAME);
        }

        return INSTANCE;
    }

    public Collection<ExchangeServer> getServers() {
        return Collections.unmodifiableCollection(serverMap.values());
    }

    public Collection<Exporter<?>> getExporters() {
        return Collections.unmodifiableCollection(exporterMap.values());
    }

    Map<String, Exporter<?>> getExporterMap() {
        return exporterMap;
    }

    private boolean isClientSide(Channel channel) {
        InetSocketAddress address = channel.getRemoteAddress();
        URL url = channel.getUrl();
        return url.getPort() == address.getPort() &&
                NetUtils.filterLocalHost(channel.getUrl().getIp())
                        .equals(NetUtils.filterLocalHost(address.getAddress().getHostAddress()));
    }

    Invoker<?> getInvoker(Channel channel, Invocation inv) throws RemotingException {
        boolean isCallBackServiceInvoke = false;
        boolean isStubServiceInvoke = false;
        int port = channel.getLocalAddress().getPort();
        String path = inv.getAttachments().get(PATH_KEY);

        // if it's callback service on client side
        isStubServiceInvoke = Boolean.TRUE.toString().equals(inv.getAttachments().get(STUB_EVENT_KEY));
        if (isStubServiceInvoke) {
            port = channel.getRemoteAddress().getPort();
        }

        //callback
        isCallBackServiceInvoke = isClientSide(channel) && !isStubServiceInvoke;
        if (isCallBackServiceInvoke) {
            path += "." + inv.getAttachments().get(CALLBACK_SERVICE_KEY);
            inv.getAttachments().put(IS_CALLBACK_SERVICE_INVOKE, Boolean.TRUE.toString());
        }

        String serviceKey = serviceKey(port, path, inv.getAttachments().get(VERSION_KEY), inv.getAttachments().get(GROUP_KEY));
        DubboExporter<?> exporter = (DubboExporter<?>) exporterMap.get(serviceKey);

        if (exporter == null) {
            throw new RemotingException(channel, "Not found exported service: " + serviceKey + " in " + exporterMap.keySet() + ", may be version or group mismatch " +
                    ", channel: consumer: " + channel.getRemoteAddress() + " --> provider: " + channel.getLocalAddress() + ", message:" + inv);
        }

        return exporter.getInvoker();
    }

    public Collection<Invoker<?>> getInvokers() {
        return Collections.unmodifiableCollection(invokers);
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public <T> Exporter<T> export(Invoker<T> invoker) throws RpcException {
        URL url = invoker.getUrl();

        // export service.
        // 获取服务标识，理解成服务坐标也行。由服务组名，服务名，服务版本号以及端口组成。比如：
        // demoGroup/com.alibaba.dubbo.demo.DemoService:1.0.1:20880
        String key = serviceKey(url);
        // 创建 DubboExporter
        DubboExporter<T> exporter = new DubboExporter<T>(invoker, key, exporterMap);
        // 将 <key, exporter> 键值对放入缓存中
        exporterMap.put(key, exporter);

        //export an stub service for dispatching event
        Boolean isStubSupportEvent = url.getParameter(STUB_EVENT_KEY, DEFAULT_STUB_EVENT);
        Boolean isCallbackservice = url.getParameter(IS_CALLBACK_SERVICE, false);
        if (isStubSupportEvent && !isCallbackservice) {
            String stubServiceMethods = url.getParameter(STUB_EVENT_METHODS_KEY);
            if (stubServiceMethods == null || stubServiceMethods.length() == 0) {
                if (logger.isWarnEnabled()) {
                    logger.warn(new IllegalStateException("consumer [" + url.getParameter(INTERFACE_KEY) +
                            "], has set stubproxy support event ,but no stub methods founded."));
                }

            } else {
                stubServiceMethodsMap.put(url.getServiceKey(), stubServiceMethods);
            }
        }

        // 启动服务器
        openServer(url);
        // 初始化序列化优化器
        optimizeSerialization(url);

        return exporter;
    }

    /**
     *那么服务器为什么会存在？因为很多服务是用同一个服务配配置？
     * @param url
     */
    private void openServer(URL url) {
        // find server.
        // 获取 host:port，并将其作为服务器实例的 key，用于标识当前的服务器实例
        String key = url.getAddress();
        //client can export a service which's only for server to invoke
        boolean isServer = url.getParameter(IS_SERVER_KEY, true);
        if (isServer) {
            // 访问缓存
            ExchangeServer server = serverMap.get(key);
            if (server == null) {
                synchronized (this) {
                    server = serverMap.get(key);
                    if (server == null) {
                        // 创建服务器实例
                        serverMap.put(key, createServer(url));
                    }
                }
            } else {
                // server supports reset, use together with override
                // 如果服务器已经创建，则更新服务器配置
                server.reset(url);
            }
        }
    }

    /**
     * createServer 包含三个核心的操作
     * 第一是检测是否存在 server 参数所代表的 Transporter 拓展，不存在则抛出异常
     * 第二是创建服务器实例。
     * 第三是检测是否支持 client 参数所表示的 Transporter 拓展，不存在也是抛出异常
     * @param url
     * @return
     */
    private ExchangeServer createServer(URL url) {
        url = URLBuilder.from(url)
                // send readonly event when server closes, it's enabled by default
                .addParameterIfAbsent(CHANNEL_READONLYEVENT_SENT_KEY, Boolean.TRUE.toString())
                // enable heartbeat by default
                // 添加心跳检测配置到 url 中，默认开启
                .addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT))
                // 添加编码解码器参数 dubbo协议
                .addParameter(CODEC_KEY, DubboCodec.NAME)
                .build();
        // 获取 server 参数，默认为 netty
        String str = url.getParameter(SERVER_KEY, DEFAULT_REMOTING_SERVER);

        // 通过 SPI 检测是否存在 server 参数所代表的 Transporter 拓展，不存在则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported server type: " + str + ", url: " + url);
        }

        ExchangeServer server;
        try {
            // 创建 ExchangeServer
            server = Exchangers.bind(url, requestHandler);
        } catch (RemotingException e) {
            throw new RpcException("Fail to start server(url: " + url + ") " + e.getMessage(), e);
        }

        // 获取 client 参数，可指定 netty，mina
        str = url.getParameter(CLIENT_KEY);
        if (str != null && str.length() > 0) {
            // 获取所有的 Transporter 实现类名称集合，比如 supportedTypes = [netty, mina]
            Set<String> supportedTypes = ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions();
            // 检测当前 Dubbo 所支持的 Transporter 实现类名称列表中，
            // 是否包含 client 所表示的 Transporter，若不包含，则抛出异常
            if (!supportedTypes.contains(str)) {
                throw new RpcException("Unsupported client type: " + str);
            }
        }

        return server;
    }

    private void optimizeSerialization(URL url) throws RpcException {
        String className = url.getParameter(OPTIMIZER_KEY, "");
        if (StringUtils.isEmpty(className) || optimizers.contains(className)) {
            return;
        }

        logger.info("Optimizing the serialization process for Kryo, FST, etc...");

        try {
            Class clazz = Thread.currentThread().getContextClassLoader().loadClass(className);
            if (!SerializationOptimizer.class.isAssignableFrom(clazz)) {
                throw new RpcException("The serialization optimizer " + className + " isn't an instance of " + SerializationOptimizer.class.getName());
            }

            SerializationOptimizer optimizer = (SerializationOptimizer) clazz.newInstance();

            if (optimizer.getSerializableClasses() == null) {
                return;
            }

            for (Class c : optimizer.getSerializableClasses()) {
                SerializableClassRegistry.registerClass(c);
            }

            optimizers.add(className);

        } catch (ClassNotFoundException e) {
            throw new RpcException("Cannot find the serialization optimizer class: " + className, e);

        } catch (InstantiationException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);

        } catch (IllegalAccessException e) {
            throw new RpcException("Cannot instantiate the serialization optimizer class: " + className, e);
        }
    }

    @Override
    public <T> Invoker<T> protocolBindingRefer(Class<T> serviceType, URL url) throws RpcException {
        //初始化序列优化器
        optimizeSerialization(url);

        // 创建 DubboInvoker
        // create rpc invoker.
        // getClients。这个方法用于获取客户端实例，实例类型为 ExchangeClient。
        // ExchangeClient 实际上并不具备通信能力，因此它需要更底层的客户端实例进行通信。
        // 比如 NettyClient、MinaClient 等，默认情况下，Dubbo 使用 NettyClient 进行通信
        DubboInvoker<T> invoker = new DubboInvoker<T>(serviceType, url, getClients(url), invokers);

        //添加到invoker集合
        invokers.add(invoker);

        return invoker;
    }

    /**
     * 获得连接服务提供者的远程通信客户端数组
     * @param url 服务提供者 URL
     * @return 远程通信客户端
     */
    private ExchangeClient[] getClients(URL url) {
        // whether to share connection
        // // 是否共享连接
        boolean useShareConnect = false;

        // 获取连接数，默认为0，表示未配置
        int connections = url.getParameter(CONNECTIONS_KEY, 0);
        List<ReferenceCountExchangeClient> shareClients = null;
        // if not configured, connection is shared, otherwise, one connection for one service
        //对每个提供者的最大连接数，rmi、http、hessian等短连接协议表示限制连接数，dubbo等长连接协表示建立的长连接个数
        // 如果未配置 connections，则共享连接
        if (connections == 0) {
            useShareConnect = true;

            /**
             * The xml configuration should have a higher priority than properties.
             */
            String shareConnectionsStr = url.getParameter(SHARE_CONNECTIONS_KEY, (String) null);
            connections = Integer.parseInt(StringUtils.isBlank(shareConnectionsStr) ? ConfigUtils.getProperty(SHARE_CONNECTIONS_KEY,
                    DEFAULT_SHARE_CONNECTIONS) : shareConnectionsStr);
            // 获取共享客户端
            shareClients = getSharedClient(url, connections);
        }

        ExchangeClient[] clients = new ExchangeClient[connections];
        for (int i = 0; i < clients.length; i++) {
            if (useShareConnect) {
                // 获取共享客户端
                clients[i] = shareClients.get(i);

            } else {
                // 新建客户端
                clients[i] = initClient(url);
            }
        }

        return clients;
    }

    /**
     * Get shared connection
     *
     * 内容：
     * 先访问缓存，若缓存未命中，则通过 initClient 方法创建新的 ExchangeClient 实例，
     * 并将该实例传给 ReferenceCountExchangeClient 构造方法创建一个带有引用计数功能的 ExchangeClient 实例
     * @param url
     * @param connectNum connectNum must be greater than or equal to 1
     */
    private List<ReferenceCountExchangeClient> getSharedClient(URL url, int connectNum) {
        String key = url.getAddress();
        // 获取带有“引用计数”功能的 ExchangeClient
        List<ReferenceCountExchangeClient> clients = referenceClientMap.get(key);

        if (checkClientCanUse(clients)) {
            batchClientRefIncr(clients);
            return clients;
        }

        locks.putIfAbsent(key, new Object());
        synchronized (locks.get(key)) {
            clients = referenceClientMap.get(key);
            // dubbo check
            if (checkClientCanUse(clients)) {
                batchClientRefIncr(clients);
                return clients;
            }

            // connectNum must be greater than or equal to 1
            connectNum = Math.max(connectNum, 1);

            // If the clients is empty, then the first initialization is
            // 如果为空构建 具有引用计数的客户端
            if (CollectionUtils.isEmpty(clients)) {
                clients = buildReferenceCountExchangeClientList(url, connectNum);
                referenceClientMap.put(key, clients);

            } else {
                for (int i = 0; i < clients.size(); i++) {
                    ReferenceCountExchangeClient referenceCountExchangeClient = clients.get(i);
                    // If there is a client in the list that is no longer available, create a new one to replace him.
                    if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                        clients.set(i, buildReferenceCountExchangeClient(url));
                        continue;
                    }

                    // 增加引用计数
                    referenceCountExchangeClient.incrementAndGetCount();
                }
            }

            /**
             * I understand that the purpose of the remove operation here is to avoid the expired url key
             * always occupying this memory space.
             */
            locks.remove(key);

            return clients;
        }
    }

    /**
     * Check if the client list is all available
     *
     * @param referenceCountExchangeClients
     * @return true-available，false-unavailable
     */
    private boolean checkClientCanUse(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return false;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            // As long as one client is not available, you need to replace the unavailable client with the available one.
            if (referenceCountExchangeClient == null || referenceCountExchangeClient.isClosed()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Increase the reference Count if we create new invoker shares same connection, the connection will be closed without any reference.
     *
     * @param referenceCountExchangeClients
     */
    private void batchClientRefIncr(List<ReferenceCountExchangeClient> referenceCountExchangeClients) {
        if (CollectionUtils.isEmpty(referenceCountExchangeClients)) {
            return;
        }

        for (ReferenceCountExchangeClient referenceCountExchangeClient : referenceCountExchangeClients) {
            if (referenceCountExchangeClient != null) {
                referenceCountExchangeClient.incrementAndGetCount();
            }
        }
    }

    /**
     * Bulk build client
     *
     * @param url
     * @param connectNum
     * @return
     */
    private List<ReferenceCountExchangeClient> buildReferenceCountExchangeClientList(URL url, int connectNum) {
        List<ReferenceCountExchangeClient> clients = new ArrayList<>();

        for (int i = 0; i < connectNum; i++) {
            clients.add(buildReferenceCountExchangeClient(url));
        }

        return clients;
    }

    /**
     * Build a single client
     *
     * @param url
     * @return
     */
    private ReferenceCountExchangeClient buildReferenceCountExchangeClient(URL url) {
        ExchangeClient exchangeClient = initClient(url);

        // 将 ExchangeClient 实例传给 ReferenceCountExchangeClient，这里明显用了装饰模式
        return new ReferenceCountExchangeClient(exchangeClient);
    }

    /**
     * Create new connection
     *initClient 方法首先获取用户配置的客户端类型，默认为 netty。
     * 然后检测用户配置的客户端类型是否存在，不存在则抛出异常
     * @param url
     */
    private ExchangeClient initClient(URL url) {

        // client type setting.
        // 获取客户端类型，默认为 netty
        String str = url.getParameter(CLIENT_KEY, url.getParameter(SERVER_KEY, DEFAULT_REMOTING_CLIENT));

        // 添加编解码和心跳包参数到 url 中
        url = url.addParameter(CODEC_KEY, DubboCodec.NAME);
        // enable heartbeat by default
        url = url.addParameterIfAbsent(HEARTBEAT_KEY, String.valueOf(DEFAULT_HEARTBEAT));

        // BIO is not allowed since it has severe performance issue.
        // 检测客户端类型是否存在，不存在则抛出异常
        if (str != null && str.length() > 0 && !ExtensionLoader.getExtensionLoader(Transporter.class).hasExtension(str)) {
            throw new RpcException("Unsupported client type: " + str + "," +
                    " supported client type is " + StringUtils.join(ExtensionLoader.getExtensionLoader(Transporter.class).getSupportedExtensions(), " "));
        }

        ExchangeClient client;
        try {
            // connection should be lazy
            // 获取 lazy 配置，并根据配置值决定创建的客户端类型
            if (url.getParameter(LAZY_CONNECT_KEY, false)) {
                // 懒汉式加载
                client = new LazyConnectExchangeClient(url, requestHandler);

            } else {
                // 普通加载
                client = Exchangers.connect(url, requestHandler);
            }

        } catch (RemotingException e) {
            throw new RpcException("Fail to create remoting client for service(" + url + "): " + e.getMessage(), e);
        }

        return client;
    }

    @Override
    public void destroy() {
        for (String key : new ArrayList<>(serverMap.keySet())) {
            ExchangeServer server = serverMap.remove(key);

            if (server == null) {
                continue;
            }

            try {
                if (logger.isInfoEnabled()) {
                    logger.info("Close dubbo server: " + server.getLocalAddress());
                }

                server.close(ConfigurationUtils.getServerShutdownTimeout());

            } catch (Throwable t) {
                logger.warn(t.getMessage(), t);
            }
        }

        for (String key : new ArrayList<>(referenceClientMap.keySet())) {
            List<ReferenceCountExchangeClient> clients = referenceClientMap.remove(key);

            if (CollectionUtils.isEmpty(clients)) {
                continue;
            }

            for (ReferenceCountExchangeClient client : clients) {
                closeReferenceCountExchangeClient(client);
            }
        }

        stubServiceMethodsMap.clear();
        super.destroy();
    }

    /**
     * close ReferenceCountExchangeClient
     *
     * @param client
     */
    private void closeReferenceCountExchangeClient(ReferenceCountExchangeClient client) {
        if (client == null) {
            return;
        }

        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close dubbo connect: " + client.getLocalAddress() + "-->" + client.getRemoteAddress());
            }

            client.close(ConfigurationUtils.getServerShutdownTimeout());

            // TODO
            /**
             * At this time, ReferenceCountExchangeClient#client has been replaced with LazyConnectExchangeClient.
             * Do you need to call client.close again to ensure that LazyConnectExchangeClient is also closed?
             */

        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }
}
