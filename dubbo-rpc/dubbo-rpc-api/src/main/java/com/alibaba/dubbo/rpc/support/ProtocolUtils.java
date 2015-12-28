package com.alibaba.dubbo.rpc.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;

/**
 * @author <a href="mailto:gang.lvg@alibaba-inc.com">kimi</a>
 */
public class ProtocolUtils {

    private ProtocolUtils() {
    }
    
    //FIXME 获取url中的serviceKey 例如:GroupA/com.geek.dubbo.test.api.UserService:1.0.0:20880  add by jileng
    public static String serviceKey(URL url) {
        return serviceKey(url.getPort(), url.getPath(), url.getParameter(Constants.VERSION_KEY),
                          url.getParameter(Constants.GROUP_KEY));
    }

    /**
     * add by jileng
     * 
     * @param port  service暴露的端口 
     * @param serviceName  service的全限定名
     * @param serviceVersion  service定义的版本
     * @param serviceGroup  service定义的分组
     * @return  serviceKey  例如：GroupA/com.geek.dubbo.test.api.UserService:1.0.0:20880
     */
    public static String serviceKey(int port, String serviceName, String serviceVersion, String serviceGroup) {
        StringBuilder buf = new StringBuilder();
        if (serviceGroup != null && serviceGroup.length() > 0) {
            buf.append(serviceGroup);
            buf.append("/");
        }
        buf.append(serviceName);
        if (serviceVersion != null && serviceVersion.length() > 0 && !"0.0.0".equals(serviceVersion)) {
            buf.append(":");
            buf.append(serviceVersion);
        }
        buf.append(":");
        buf.append(port);
        return buf.toString();
    }

    public static boolean isGeneric(String generic) {
        return generic != null
            && !"".equals(generic)
            && (Constants.GENERIC_SERIALIZATION_DEFAULT.equalsIgnoreCase(generic)  /* 正常的泛化调用 */
            || Constants.GENERIC_SERIALIZATION_NATIVE_JAVA.equalsIgnoreCase(generic) /* 支持java序列化的流式泛化调用 */
            || Constants.GENERIC_SERIALIZATION_BEAN.equalsIgnoreCase(generic));
    }

    public static boolean isDefaultGenericSerialization(String generic) {
        return isGeneric(generic)
            && Constants.GENERIC_SERIALIZATION_DEFAULT.equalsIgnoreCase(generic);
    }

    public static boolean isJavaGenericSerialization(String generic) {
        return isGeneric(generic)
            && Constants.GENERIC_SERIALIZATION_NATIVE_JAVA.equalsIgnoreCase(generic);
    }

    public static boolean isBeanGenericSerialization(String generic) {
        return isGeneric(generic) && Constants.GENERIC_SERIALIZATION_BEAN.equals(generic);
    }
}
