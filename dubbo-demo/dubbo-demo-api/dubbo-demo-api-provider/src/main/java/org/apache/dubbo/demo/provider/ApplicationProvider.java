/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.dubbo.demo.provider;

import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.demo.DemoService;

public class ApplicationProvider {
    public static void main(String[] args) throws Exception {
//        System.setProperty("sun.misc.ProxyGenerator.saveGeneratedFiles", "true");

        ServiceConfig<DemoServiceImpl> service = new ServiceConfig<>();

        //应用配置
        ApplicationConfig applicationConfig = new ApplicationConfig("dubbo-bigger-rice-api-provider");
        applicationConfig.setQosEnable(true);
        applicationConfig.setQosPort(22222);

        applicationConfig.setCompiler("javassist");
        service.setApplication(applicationConfig);



        //服务提供者注册中心配置，可以多个
        service.setRegistry(new RegistryConfig("zookeeper://127.0.0.1:2181"));

        // 服务提供者协议配置，可以多个
        ProtocolConfig protocol = new ProtocolConfig();
        protocol.setName("dubbo");
        protocol.setPort(20880);
        protocol.setThreads(5);
        protocol.setServer("netty4");

        service.setProtocol(protocol);
        service.setInterface(DemoService.class);
        service.setRef(new DemoServiceImpl());
        service.export();
        System.in.read();
    }
}
