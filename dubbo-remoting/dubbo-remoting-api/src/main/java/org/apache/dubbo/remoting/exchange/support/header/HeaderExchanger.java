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
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Client;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.Transporters;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchanger;
import org.apache.dubbo.remoting.transport.DecodeHandler;

/**
 * DefaultMessenger
 * 实现 Exchanger 接口，基于消息头部( Header )的信息交换者实现类
 *
 */
public class HeaderExchanger implements Exchanger {

    public static final String NAME = "header";

    /**
     * 通过 Transporters#connect(url, handler) 方法，创建通信 Client ，内嵌到 HeaderExchangeClient 中。
     * 传入的 handler 处理器，内嵌到 HeaderExchangeHandler ，
     * 再进一步内嵌到 DecodeHandler 中。
     * 所以，处理器的顺序是：DecodeHandler => HeaderExchangeHandler => ExchangeHandler( handler ) 。
     * @param url server url 服务器地址
     * @param handler 数据交换处理器
     * @return
     * @throws RemotingException
     */
    @Override
    public ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
        // 这里包含了多个装饰器调用，分别如下：
        // 1. 创建 HeaderExchangeHandler 对象
        HeaderExchangeHandler headerExchangeHandler = new HeaderExchangeHandler(handler);
        // 2. 创建 DecodeHandler 对象
        DecodeHandler decodeHandler = new DecodeHandler(headerExchangeHandler);
        // 3. 通过 Transporters 构建 Client 实例
        Client client = Transporters.connect(url, decodeHandler);
        // 4. 创建 HeaderExchangeClient 对象
        return new HeaderExchangeClient(client, true);
    }

    @Override
    public ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        // 创建 HeaderExchangeServer 实例，该方法包含了多步操作，本别如下：
        //   1. new HeaderExchangeHandler(handler)
        //	 2. new DecodeHandler(new HeaderExchangeHandler(handler))
        //   3. Transporters.bind(url, new DecodeHandler(new HeaderExchangeHandler(handler)))
        return new HeaderExchangeServer(Transporters.bind(url, new DecodeHandler(new HeaderExchangeHandler(handler))));
    }

}
