/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.registry.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.support.FailbackRegistry;
import com.alibaba.dubbo.remoting.zookeeper.ChildListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperClient;
import com.alibaba.dubbo.remoting.zookeeper.StateListener;
import com.alibaba.dubbo.remoting.zookeeper.ZookeeperTransporter;
import com.alibaba.dubbo.rpc.RpcException;

/**
 * ZookeeperRegistry
 * 
 * @author william.liangf
 */
public class ZookeeperRegistry extends FailbackRegistry {

    private final static Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    private final static int DEFAULT_ZOOKEEPER_PORT = 2181;
    
    private final static String DEFAULT_ROOT = "dubbo";

    private final String        root;
    
    private final Set<String> anyServices = new ConcurrentHashSet<String>();

    private final ConcurrentMap<URL, ConcurrentMap<NotifyListener, ChildListener>> zkListeners = new ConcurrentHashMap<URL, ConcurrentMap<NotifyListener, ChildListener>>();
    
    private final ZookeeperClient zkClient;
    
    public ZookeeperRegistry(URL url, ZookeeperTransporter zookeeperTransporter) {
        super(url);
        if (url.isAnyHost()) {
    		throw new IllegalStateException("registry address == null");
    	}
        String group = url.getParameter(Constants.GROUP_KEY, DEFAULT_ROOT);
        if (! group.startsWith(Constants.PATH_SEPARATOR)) {
            group = Constants.PATH_SEPARATOR + group;
        }
        this.root = group;
        zkClient = zookeeperTransporter.connect(url);
        zkClient.addStateListener(new StateListener() {
            public void stateChanged(int state) {
            	if (state == RECONNECTED) {
	            	try {
						recover();
					} catch (Exception e) {
						logger.error(e.getMessage(), e);
					}
            	}
            }
        });
    }

    public boolean isAvailable() {
        return zkClient.isConnected();
    }

    public void destroy() {
        super.destroy();
        try {
            zkClient.close();
        } catch (Exception e) {
            logger.warn("Failed to close zookeeper client " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doRegister(URL url) {
        //dubbo://10.0.45.75:20880/com.geek.dubbo.test.api.UserService?anyhost=true&application=dubbo-test-provider1
        //&dubbo=2.0.0&generic=false&group=GroupA&interface=com.geek.dubbo.test.api.UserService&methods=createUser,getUser
        //&pid=865&revision=1.0.0&side=provider&timeout=1000000&timestamp=1446434437850&version=1.0.0
        try {
            // toUrlPath(url)的结果如下
            // /dubbo/com.geek.dubbo.test.api.UserService/providers/dubbo%3A%2F%2F10.0.45.75%3A20880%2Fcom.geek.dubbo.test.api.UserService%3Fanyhost%3Dtrue%26application%3Ddubbo-test-provider1%26dubbo%3D2.0.0%26generic%3Dfalse%26group%3DGroupA%26interface%3Dcom.geek.dubbo.test.api.UserService%26methods%3DcreateUser%2CgetUser%26pid%3D897%26revision%3D1.0.0%26side%3Dprovider%26timeout%3D1000000%26timestamp%3D1446435538643%26version%3D1.0.0
        	zkClient.create(toUrlPath(url), url.getParameter(Constants.DYNAMIC_KEY, true));
        } catch (Throwable e) {
            throw new RpcException("Failed to register " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doUnregister(URL url) {
        try {
            zkClient.delete(toUrlPath(url));
        } catch (Throwable e) {
            throw new RpcException("Failed to unregister " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doSubscribe(final URL url, final NotifyListener listener) {
        try {
            if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
                String root = toRootPath();
                ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                if (listeners == null) {
                    zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                    listeners = zkListeners.get(url);
                }
                ChildListener zkListener = listeners.get(listener);
                if (zkListener == null) {
                    listeners.putIfAbsent(listener, new ChildListener() {
                        public void childChanged(String parentPath, List<String> currentChilds) {
                            for (String child : currentChilds) {
								child = URL.decode(child);
                                if (! anyServices.contains(child)) {
                                    anyServices.add(child);
                                    subscribe(url.setPath(child).addParameters(Constants.INTERFACE_KEY, child, 
                                            Constants.CHECK_KEY, String.valueOf(false)), listener);
                                }
                            }
                        }
                    });
                    zkListener = listeners.get(listener);
                }
                zkClient.create(root, false);
                List<String> services = zkClient.addChildListener(root, zkListener);
                if (services != null && services.size() > 0) {
                    for (String service : services) {
						service = URL.decode(service);
						anyServices.add(service);
                        subscribe(url.setPath(service).addParameters(Constants.INTERFACE_KEY, service, 
                                Constants.CHECK_KEY, String.valueOf(false)), listener);
                    }
                }
            } else {//存在指定的服务名
                List<URL> urls = new ArrayList<URL>();
                for (String path : toCategoriesPath(url)) {
                    ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
                    if (listeners == null) {
                        zkListeners.putIfAbsent(url, new ConcurrentHashMap<NotifyListener, ChildListener>());
                        listeners = zkListeners.get(url);
                    }
                    ChildListener zkListener = listeners.get(listener);
                    if (zkListener == null) {
                        listeners.putIfAbsent(listener, new ChildListener() {
                            public void childChanged(String parentPath, List<String> currentChilds) {
                            	ZookeeperRegistry.this.notify(url, listener, toUrlsWithEmpty(url, parentPath, currentChilds));
                            }
                        });
                        zkListener = listeners.get(listener);
                    }
                    //  服务端订阅override数据 /dubbo/com.geek.dubbo.test.api.UserService/configurators   add by jileng
                    //  客户端会订阅           /dubbo/com.geek.dubbo.test.api.UserService/configurators 
                    //                       /dubbo/com.geek.dubbo.test.api.UserService/providers 
                    //                       /dubbo/com.geek.dubbo.test.api.UserService/routers
                    zkClient.create(path, false);
                    //  添加监听
                    List<String> children = zkClient.addChildListener(path, zkListener);
                    if (children != null) {
                    	urls.addAll(toUrlsWithEmpty(url, path, children));
                    }
                }
                notify(url, listener, urls);
            }
        } catch (Throwable e) {
            throw new RpcException("Failed to subscribe " + url + " to zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }

    protected void doUnsubscribe(URL url, NotifyListener listener) {
        ConcurrentMap<NotifyListener, ChildListener> listeners = zkListeners.get(url);
        if (listeners != null) {
            ChildListener zkListener = listeners.get(listener);
            if (zkListener != null) {
                zkClient.removeChildListener(toUrlPath(url), zkListener);
            }
        }
    }

    public List<URL> lookup(URL url) {
        if (url == null) {
            throw new IllegalArgumentException("lookup url == null");
        }
        try {
            List<String> providers = new ArrayList<String>();
            for (String path : toCategoriesPath(url)) {
                    List<String> children = zkClient.getChildren(path);
                    if (children != null) {
                        providers.addAll(children);
                    }
            }
            return toUrlsWithoutEmpty(url, providers);
        } catch (Throwable e) {
            throw new RpcException("Failed to lookup " + url + " from zookeeper " + getUrl() + ", cause: " + e.getMessage(), e);
        }
    }
    
    private String toRootDir() {
        if (root.equals(Constants.PATH_SEPARATOR)) {
            return root;
        }
        return root + Constants.PATH_SEPARATOR;
    }
    
    private String toRootPath() {
        return root;
    }
    
    private String toServicePath(URL url) {
        String name = url.getServiceInterface();
        if (Constants.ANY_VALUE.equals(name)) {
            return toRootPath();
        }
        //   /dubbo/服务的全限定名
        return toRootDir() + URL.encode(name);
    }

    private String[] toCategoriesPath(URL url) {
        String[] categroies;
        if (Constants.ANY_VALUE.equals(url.getParameter(Constants.CATEGORY_KEY))) {
            categroies = new String[] {Constants.PROVIDERS_CATEGORY, Constants.CONSUMERS_CATEGORY, 
                    Constants.ROUTERS_CATEGORY, Constants.CONFIGURATORS_CATEGORY};
        } else {
            categroies = url.getParameter(Constants.CATEGORY_KEY, new String[] {Constants.DEFAULT_CATEGORY});
        }
        String[] paths = new String[categroies.length];
        for (int i = 0; i < categroies.length; i ++) {
            paths[i] = toServicePath(url) + Constants.PATH_SEPARATOR + categroies[i];
        }
        return paths;
    }

    private String toCategoryPath(URL url) {
        //category默认为providers   add by jileng
        // /dubbo/服务的全限定名/providers
        return toServicePath(url) + Constants.PATH_SEPARATOR + url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);
    }

    private String toUrlPath(URL url) {
        ///dubbo/服务的全限定名/providers/url.toFullString()    add by jileng
        return toCategoryPath(url) + Constants.PATH_SEPARATOR + URL.encode(url.toFullString());
    }
    
    
    
    private List<URL> toUrlsWithoutEmpty(URL consumer, List<String> providers) {
    	List<URL> urls = new ArrayList<URL>();
        if (providers != null && providers.size() > 0) {
            for (String provider : providers) {
                // dubbo://10.0.45.75:20880/com.geek.dubbo.test.api.UserService?anyhost=true&application=dubbo-test-provider1
                // &dubbo=2.0.0&generic=false&group=GroupA&interface=com.geek.dubbo.test.api.UserService&methods=getUser,createUser
                // &pid=1526&revision=1.0.0&side=provider&timeout=1000000&timestamp=1446621673431&version=1.0.0
                provider = URL.decode(provider);
                if (provider.contains("://")) {
                    URL url = URL.valueOf(provider);
                    if (UrlUtils.isMatch(consumer, url)) {
                        urls.add(url);
                    }
                }
            }
        }
        return urls;
    }

    private List<URL> toUrlsWithEmpty(URL consumer, String path, List<String> providers) {
        List<URL> urls = toUrlsWithoutEmpty(consumer, providers);
        if (urls == null || urls.isEmpty()) {
        	int i = path.lastIndexOf('/');
        	String category = i < 0 ? path : path.substring(i + 1);
        	URL empty = consumer.setProtocol(Constants.EMPTY_PROTOCOL).addParameter(Constants.CATEGORY_KEY, category);
            urls.add(empty);
        }
        return urls;
    }

    static String appendDefaultPort(String address) {
        if (address != null && address.length() > 0) {
            int i = address.indexOf(':');
            if (i < 0) {
                return address + ":" + DEFAULT_ZOOKEEPER_PORT;
            } else if (Integer.parseInt(address.substring(i + 1)) == 0) {
                return address.substring(0, i + 1) + DEFAULT_ZOOKEEPER_PORT;
            }
        }
        return address;
    }

}