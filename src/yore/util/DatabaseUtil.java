package yore.util;

import sun.misc.BASE64Encoder;

import java.security.MessageDigest;
import java.sql.*;

public class DatabaseUtil {
    private static String name = null;
    private static String url = null;
    private static String username = null;
    private static String password = null;
    private static Connection conn = null;

    /**
     * 获取一个数据库连接，由于业务比较简单，不使用连接池
     *
     * @return java.sql.Connection 的一个实例
     */
    public static Connection getConnection() {
        if (conn == null) {
            try {
                getConnectionInfo();
                // 新版本jdk不需要这句话，旧版本有报错打开注释，Warning请忽略
                // Class.forName(name);
                conn = DriverManager.getConnection(url, username, password);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return conn;
    }

    /**
     * 第一次连接数据库时获取数据库连接参数
     */
    private static void getConnectionInfo() {
        if (name == null) {
            name = PropertiesUtil.getProperty("db_name");
            url = PropertiesUtil.getProperty("db_url");
            username = PropertiesUtil.getProperty("db_username");
            password = PropertiesUtil.getProperty("db_password");
        }
    }

    public static ResultSet execSQL(String sql) {
        if (conn == null) getConnection();
        try {
            Statement st = conn.createStatement();
            st.execute(sql);
            ResultSet rs = st.getResultSet();
            return rs;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 长时间不用数据库时请调用此方法关闭连接
     */
    public static void closeConnection() {
        try {
            if (conn != null) conn.close();
        } catch (SQLException sqlError) {
            sqlError.printStackTrace();
        }
    }

    /**
     * 用于密码的加密
     *
     * @param str 密码明文
     * @return String 密码密文
     */
    public static String md5Encode(String str) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            BASE64Encoder base64Encoder = new BASE64Encoder();
            return base64Encoder.encode(md5.digest(str.getBytes("utf-8")));
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // 测试
    public static ResultSet addUser(String account, String password, String nickName, Long time) {
        String _password = md5Encode(password);
        String sql = "insert into cm_user" +
                " (user_account, user_password, user_nickname, user_sign_up_time)" +
                " values ('" + account + "', '" + _password + "', '" + nickName + "'," + time + ")";
        return execSQL(sql);
    }

    /**
     * 第一次使用工程的时候调用以创建数据库和测试用户
     * 其他时间不要调用
     */
    public static void createDatabase() {

    }
}
