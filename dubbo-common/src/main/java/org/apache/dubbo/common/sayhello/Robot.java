package org.apache.dubbo.common.sayhello;

import org.apache.dubbo.common.extension.SPI;

@SPI
public interface Robot {
    void sayHello();
}