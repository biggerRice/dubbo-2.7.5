/*
 * Decompiled with CFR.
 *
 * Could not load the following classes:
 *  org.apache.dubbo.common.bytecode.ClassGenerator$DC
 */
package org.apache.dubbo.rpc.proxy;

import java.lang.reflect.InvocationHandler;
import org.apache.dubbo.common.bytecode.ClassGenerator;
import org.apache.dubbo.common.bytecode.Proxy;
import org.apache.dubbo.rpc.proxy.javassist.proxy0;

public class Proxy0
extends Proxy
implements ClassGenerator.DC {
    @Override
    public Object newInstance(InvocationHandler invocationHandler) {
        return new proxy0(invocationHandler);
    }
}
