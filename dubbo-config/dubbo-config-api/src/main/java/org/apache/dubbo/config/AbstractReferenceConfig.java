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
package org.apache.dubbo.config;

import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.config.support.Parameter;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.rpc.InvokerListener;
import org.apache.dubbo.rpc.support.ProtocolUtils;

import static org.apache.dubbo.common.constants.CommonConstants.GROUP_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.Constants.INVOKER_LISTENER_KEY;
import static org.apache.dubbo.rpc.Constants.LAZY_CONNECT_KEY;
import static org.apache.dubbo.rpc.Constants.REFERENCE_FILTER_KEY;
import static org.apache.dubbo.rpc.Constants.STUB_EVENT_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CLUSTER_STICKY_KEY;

/**
 * AbstractConsumerConfig
 *
 * @export
 * @see org.apache.dubbo.config.ReferenceConfig
 */
public abstract class AbstractReferenceConfig extends AbstractInterfaceConfig {

    private static final long serialVersionUID = -2786526984373031126L;

    // ======== Reference config default values, will take effect if reference's attribute is not set  ========

    /**
     * Check if service provider exists, if not exists, it will be fast fail
     * 服务治理
     * 启动时检查提供者是否存在，true报错，false忽略
     */
    protected Boolean check;

    /**
     * Whether to eagle-init
     * 性能调优
     * 是否在afterPropertiesSet()时饥饿初始化引用，否则等到有人注入或引用该实例时再初始化。
     */
    protected Boolean init;

    /**
     * Whether to use generic interface
     * 服务治理
     * 是否缺省泛化接口，如果为泛化接口，将返回GenericService
     */
    protected String generic;

    /**
     * Whether to find reference's instance from the current JVM
     */
    protected Boolean injvm;

    /**
     * Lazy create connection
     */
    protected Boolean lazy = false;

    protected String reconnect;

    protected Boolean sticky = false;

    /**
     * Whether to support event in stub.
     */
    //TODO solve merge problem
    protected Boolean stubevent;//= Constants.DEFAULT_STUB_EVENT;

    /**
     * The remote service version the customer side will reference
     * 服务发现
     * 服务版本，与服务提供者的版本一致
     */
    protected String version;

    /**
     * The remote service group the customer side will reference
     * 服务发现
     * 服务分组，当一个接口有多个实现，可以用分组区分，必需和服务提供方一致
     */
    protected String group;

    public Boolean isCheck() {
        return check;
    }

    public void setCheck(Boolean check) {
        this.check = check;
    }

    public Boolean isInit() {
        return init;
    }

    public void setInit(Boolean init) {
        this.init = init;
    }

    @Deprecated
    @Parameter(excluded = true)
    public Boolean isGeneric() {
        return this.generic != null ? ProtocolUtils.isGeneric(generic) : null;
    }

    @Deprecated
    public void setGeneric(Boolean generic) {
        if (generic != null) {
            this.generic = generic.toString();
        }
    }

    public String getGeneric() {
        return generic;
    }

    public void setGeneric(String generic) {
        if (StringUtils.isEmpty(generic)) {
            return;
        }
        if (ProtocolUtils.isValidGenericValue(generic)) {
            this.generic = generic;
        } else {
            throw new IllegalArgumentException("Unsupported generic type " + generic);
        }
    }

    /**
     * @return
     * @deprecated instead, use the parameter <b>scope</> to judge if it's in jvm, scope=local
     */
    @Deprecated
    public Boolean isInjvm() {
        return injvm;
    }

    /**
     * @param injvm
     * @deprecated instead, use the parameter <b>scope</b> to judge if it's in jvm, scope=local
     */
    @Deprecated
    public void setInjvm(Boolean injvm) {
        this.injvm = injvm;
    }

    @Override
    @Parameter(key = REFERENCE_FILTER_KEY, append = true)
    public String getFilter() {
        return super.getFilter();
    }

    @Override
    @Parameter(key = INVOKER_LISTENER_KEY, append = true)
    public String getListener() {
        return super.getListener();
    }

    @Override
    public void setListener(String listener) {
        checkMultiExtension(InvokerListener.class, "listener", listener);
        super.setListener(listener);
    }

    @Parameter(key = LAZY_CONNECT_KEY)
    public Boolean getLazy() {
        return lazy;
    }

    public void setLazy(Boolean lazy) {
        this.lazy = lazy;
    }

    @Override
    public void setOnconnect(String onconnect) {
        if (onconnect != null && onconnect.length() > 0) {
            this.stubevent = true;
        }
        super.setOnconnect(onconnect);
    }

    @Override
    public void setOndisconnect(String ondisconnect) {
        if (ondisconnect != null && ondisconnect.length() > 0) {
            this.stubevent = true;
        }
        super.setOndisconnect(ondisconnect);
    }

    @Parameter(key = STUB_EVENT_KEY)
    public Boolean getStubevent() {
        return stubevent;
    }

    @Parameter(key = Constants.RECONNECT_KEY)
    public String getReconnect() {
        return reconnect;
    }

    public void setReconnect(String reconnect) {
        this.reconnect = reconnect;
    }

    @Parameter(key = CLUSTER_STICKY_KEY)
    public Boolean getSticky() {
        return sticky;
    }

    public void setSticky(Boolean sticky) {
        this.sticky = sticky;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        checkKey(VERSION_KEY, version);
        this.version = version;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        checkKey(GROUP_KEY, group);
        this.group = group;
    }
}
