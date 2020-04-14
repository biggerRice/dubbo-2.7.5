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
package org.apache.dubbo.demo.consumer;

import com.sun.corba.se.spi.protocol.RequestDispatcherRegistry;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.demo.DemoService;

import java.io.IOException;

public class ApplicationConsumer {
    public static void main(String[] args) {
        ReferenceConfig<DemoService> reference = new ReferenceConfig<>();

        ApplicationConfig applicationConfig = new ApplicationConfig("dubbo-bigger-rice-api-consumer");
        applicationConfig.setQosEnable(true);
        applicationConfig.setQosPort(22222);
        reference.setApplication(applicationConfig);


        //服务提供者注册中心配置，可以多个
        RegistryConfig registryConfig = new RegistryConfig("zookeeper://127.0.0.1:2181");
        registryConfig.setServer("netty4");
        reference.setRegistry(registryConfig);

        reference.setInterface(DemoService.class);

        // 获取引用服务实例对象
        DemoService service = reference.get();
        String message = service.sayHello("l love you lilongsheng");

        System.out.println(message);
        try {
            System.in.read();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
