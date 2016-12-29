package com.linkedkeeper.apns.proxy;

import io.netty.handler.proxy.ProxyHandler;

/**
 * @author frank@linkedkeerp.com on 2016/12/28.
 */
public interface ProxyHandlerFactory {

    ProxyHandler createProxyHandler();

}
