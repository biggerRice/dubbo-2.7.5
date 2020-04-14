/*
 * Decompiled with CFR.
 *
 * Could not load the following classes:
 *  com.alibaba.dubbo.rpc.service.EchoService
 *  org.apache.dubbo.common.bytecode.ClassGenerator
 *  org.apache.dubbo.common.bytecode.ClassGenerator$DC
 *  org.apache.dubbo.demo.DemoService
 */
package org.apache.dubbo.demo.consumer;

import com.alibaba.dubbo.rpc.service.EchoService;
import org.apache.dubbo.common.bytecode.ClassGenerator;
import org.apache.dubbo.demo.DemoService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class proxy0
        implements ClassGenerator.DC,
        EchoService,
        DemoService {
    public static Method[] methods;
    private InvocationHandler handler;

    @Override
    public String sayHello(String string) {
        // 将参数存储到 Object 数组中
        Object[] arrobject = new Object[]{string};
        Object object = null;
        try {
            // 调用 InvocationHandler 实现类的 invoke 方法得到调用结果
            object = this.handler.invoke(this, methods[0], arrobject);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        // 返回调用结果
        return (String) object;
    }

    public proxy0(InvocationHandler invocationHandler) {
        this.handler = invocationHandler;
    }

    public proxy0() {
    }

    /**
     * 回声测试方法
     *
     * @param object
     * @return
     */
    @Override
    public Object $echo(Object object) {
        Object[] arrobject = new Object[]{object};
        Object object2 = null;
        try {
            object2 = this.handler.invoke(this, methods[1], arrobject);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
        return object2;
    }
}
