package yore.util;

import java.io.FileInputStream;
import java.util.Properties;


/**
 * 用于读取配置文件
 */
public class PropertiesUtil {
    // 文件名
    public static final String propertiesFileName = "config.properties";

    // 初始化配置读取
    private static Properties pps = new Properties();
    // 静态模块程序初始化时缺少文件会报错
    static {
        try {
            pps.load(new FileInputStream(propertiesFileName));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取配置值
     * @param propertyKey
     * @return 对应键值的配置值，缺省为""
     */
    public static String getProperty(String propertyKey) {
        return pps.getProperty(propertyKey, "");
    }

}
