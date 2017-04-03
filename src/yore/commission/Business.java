package yore.commission;

import yore.util.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;


/**
 * commission 业务逻辑类
 * 不是静态的，记得要实例化哟
 * 方法的使用和结果遍历可以参考test()方法
 */
public class Business {

    // 错误信息
    private static final String UNKNOWN_ERROR = "未知的错误";           // 这个不用解释了吧
    private static final String NO_SUCH_ACCOUNT = "用户名或密码错误";    // 登录错误
    private static final String UPPER_LIMIT = "数量超出可出售上限";      // 输入的数值太大或者数据库里的sum加上输入数值的和越界
    private static final String COMMIT_ERROR = "提交确认失败";          // 发送-1时未知的错误
    private static final String COMMIT_TWICE = "请不要重复确认";        // 发送过-1后又发了-1时候的错误
    private static final String COMMIT_NOT_REACH_REQUIRE = "未达到结束确认要求：每样商品至少售出1"; // 发送-1时每种商品没有至少售出1
    private static final String NUMBER_NO_SENSE = "数量不合法";        // 输入的数量小于0或者全为0
    private static final String NO_TOWN_NAME = "未填写城市名";          // 不用解释
    private static final String INVALID_DATE = "日期不合法";            // 不用解释
    private static final String DATABASE_EXEC_ERROR = "数据操作错误";   // 数据库连接错误，检查数据库吧
    private static final String COMMIT_ALREADY = "当月已经提交过终止消息，修改数据请联系管理员"; // 发送-1后又发送出售商品的消息
    private static final String AUTO_COMMIT_LAST_MONTH = "检测到上月份未自动提交出售信息确认，已自动提交"; // 登录提示
    private static final String NOT_REACH_LAST_MONTH = "上月业绩未达标，请联系管理人员进行处理";          // 登录提示

    // code
    private static final int commitSuccess = 100;           // -1录入成功
    private static final int commitError = 101;             // 录入失败
    private static final int commitTwice = 102;             // 重复录入
    private static final int commitNotReachRequire = 103;   // 未达到最低标准

    // 商品价格
    private static int lockCost;
    private static int stocksCost;
    private static int barrelsCost;

    // 商品出售上限
    private static int lockLimit;
    private static int stocksLimit;
    private static int barrelsLimit;

    // 商人身份
    private Long userId = null;
    private String userAccount = null;
    private String userPassword = null;
    private String userNickName = null;
    private Long userSignUpTime = null;     // 11位时间戳

    /**
     * 逻辑类构造方法
     *
     * @param account  用户名
     * @param password 密码
     */
    public Business(String account, String password) {
        userAccount = account;
        userPassword = DatabaseUtil.md5Encode(password);    // md5加密
        // 获取配置信息
        lockCost = Integer.parseInt(PropertiesUtil.getProperty("lock_cost"));
        stocksCost = Integer.parseInt(PropertiesUtil.getProperty("stocks_cost"));
        barrelsCost = Integer.parseInt(PropertiesUtil.getProperty("barrels_cost"));
        lockLimit = Integer.parseInt(PropertiesUtil.getProperty("lock_limit"));
        stocksLimit = Integer.parseInt(PropertiesUtil.getProperty("stocks_limit"));
        barrelsLimit = Integer.parseInt(PropertiesUtil.getProperty("barrels_limit"));
    }

    /**
     * 初始化Business类之后调用此方法来登录并获取商人的信息
     *
     * @return new String[] {"1", id, account, password, nickname, signUpTime, tip} || {"0", errMsg}
     */
    public String[] login() {
        DatabaseUtil.getConnection();
        String[] info;
        String sql = "select * from cm_user where user_account=\'" + userAccount + "\'";
        ResultSet rs = DatabaseUtil.execSQL(sql);
        try {
            if (rs != null && rs.next()) {
                String password = rs.getString("user_password");
                if (!password.equals(userPassword)) return new String[]{"0", NO_SUCH_ACCOUNT};
                userId = rs.getLong("user_id");
                userAccount = rs.getString("user_account");
                userNickName = rs.getString("user_nickname");
                userSignUpTime = rs.getLong("user_sign_up_time");
                printUser();
            } else {
                return new String[]{"0", NO_SUCH_ACCOUNT};
            }
            String tip = "";
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new Date());
            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH) + 1;
            if (userSignUpTime < (Timestamp.valueOf(year + "-" +
                    (((month + "").length() == 1) ? "0" : "") + month
                    + "-01 00:00:00")).getTime() / 1000) {
                if (month == 0) {
                    month = 12;
                    year--;
                }
                if (!commitAlready(year, month)) {
                    int code = saleCommit(year, month, true);
                    if (code == commitSuccess) {
                        tip = AUTO_COMMIT_LAST_MONTH;
                    } else if (code == commitNotReachRequire) {
                        tip = NOT_REACH_LAST_MONTH;
                    } else {
                        tip = UNKNOWN_ERROR;
                    }
                }
            }
            info = new String[]{
                    "1",
                    userId.toString(),
                    userAccount,
                    userPassword,
                    userNickName,
                    userSignUpTime.toString(),
                    tip  // 提示信息
            };
        } catch (Exception e) {
            e.printStackTrace();
            return new String[]{"0", UNKNOWN_ERROR};
        }
        DatabaseUtil.closeConnection();
        return info;
    }

    /**
     * 上报商品出售情况
     *
     * @param date     上报日期
     * @param lNum     lock 数量
     * @param sNum     stocks 数量
     * @param bNum     barrel s数量
     * @param townName 城市名称
     * @param debug    测试时打开以避免一些检查
     * @return 上报结果返回String[] {"1"} || {"0", "errorMsg"}
     */
    public String[] saleNumUpdate(Date date, int lNum, int sNum, int bNum, String townName, boolean debug) {
        // 数量判断
        if (lNum < -1 || sNum < 0 || bNum < 0 || (lNum == 0 && sNum == 0 && bNum == 0))
            return new String[]{"0", NUMBER_NO_SENSE};
        if (lNum > lockLimit || sNum > stocksLimit || bNum > barrelsLimit)
            return new String[]{"0", UPPER_LIMIT +
                    "(lock:" + lockLimit + ", stocks:" + stocksLimit + ", barrels:" + barrelsLimit + ")"};

        // 日期判断
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1;

        if (!debug) {
            // 当测试时可以把debug设成true来随意添加数据
            calendar.setTime(new Date());
            // 超过当前时间当然不行了
            if (calendar.getTimeInMillis() > System.currentTimeMillis()) return new String[]{"0", INVALID_DATE};
            // 日期必须是本月的
            if (year != calendar.get(Calendar.YEAR) || month != calendar.get(Calendar.MONTH) + 1) {
                return new String[]{"0", INVALID_DATE};
            }

            calendar.setTime(date);
            // 日期必须在用户注册日期之后
            if (calendar.getTimeInMillis() / 1000 <= userSignUpTime) {
                return new String[]{"0", INVALID_DATE};
            }
        }

        // 终结处理
        if (lNum == -1) {
            int code = saleCommit(year, month, false);
            if (code == commitSuccess) return new String[]{"1"};
            else if (code == commitTwice) return new String[]{"0", COMMIT_TWICE};
            else if (code == commitNotReachRequire) return new String[]{"0", COMMIT_NOT_REACH_REQUIRE};
            else return new String[]{"0", COMMIT_ERROR};
        }

        // 城市名判断
        if (townName == null || townName.equals("")) return new String[]{"0", NO_TOWN_NAME};

        String sql;

        // 上一月终结检查, 为了简单就不写递归检查上月commit了
        // 写在login里的这里的代码应该用不到了
        int lastMonth = month - 1;
        int lastYear = year;
        if (lastMonth == 0) {
            lastMonth = 12;
            lastYear--;
        }
        if (commitAlready(lastYear, lastMonth)) {
            saleCommit(lastYear, lastMonth, true);
        }

        // 如果本月已经发送过终结标识，就不能再插入数据
        calendar.setTime(date);
        if (commitAlready(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)) {
            return new String[]{"0", COMMIT_ALREADY};
        }

        DatabaseUtil.getConnection();
        // 上限判断
        sql = "select sum(record_lock) as 'lock', sum(record_stocks) as 'stocks'," +
                " sum(record_barrels) as 'barrels' from" +
                " cm_sale_record where record_user_id = " + userId + "" +
                " and year(record_time)='" + year + "'" +
                " and month(record_time)='" + month + "'";
        try {
            ResultSet records = DatabaseUtil.execSQL(sql);
            if (records != null && records.next()) {
                int lockAll = records.getInt("lock");
                int stocksAll = records.getInt("stocks");
                int barrelsAll = records.getInt("barrels");
                if (lockAll + lNum > lockLimit) return new String[]{"0", "lock" + UPPER_LIMIT};
                else if (stocksAll + sNum > lockLimit) return new String[]{"0", "stocks" + UPPER_LIMIT};
                else if (barrelsAll + bNum > lockLimit) return new String[]{"0", "barrels" + UPPER_LIMIT};
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR: saleNumberUpdate -> 上限判断");
            return new String[]{"0", DATABASE_EXEC_ERROR};
        }

        // 符合条件插入数据
        calendar.setTime(new Date());
        int yearNow = calendar.get(Calendar.YEAR);
        int monthNow = calendar.get(Calendar.MONTH) + 1;
        int dayNow = calendar.get(Calendar.DATE);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        int second = calendar.get(Calendar.SECOND);
        String dateTime = yearNow + "-" + monthNow + "-" + dayNow + " " + hour + ":" + minute + ":" + second;
        sql = "insert into cm_sale_record (record_time, record_lock, record_stocks, record_barrels," +
                " record_town_name, record_user_id) values ('" + dateTime + "', " + lNum + ", " + sNum + "," +
                " " + bNum + ", \'" + townName + "\', " + userId + ")";
        try {
            DatabaseUtil.execSQL(sql);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR: saleNumberUpdate -> 数据插入");
            return new String[]{"0", DATABASE_EXEC_ERROR};
        }
        DatabaseUtil.closeConnection();

        return new String[]{"1"};
    }

    /**
     * 商人发送 -1 locks时，每月第一次发送出售情况时，每月第一次发送月报请求时
     * 对一个月的出售情况作出终结和统计
     *
     * @return code 成功，失败，不可重复提交，未达到提交标准
     */
    private int saleCommit(int year, int month, boolean checked) {
        // 没有检查过该月是否提交过-1时进行检查
        if (!checked) {
            if (commitAlready(year, month)) return commitTwice;
        }
        // 进行统计
        String sql;
        sql = "select sum(record_lock) as 'lock', sum(record_stocks) as 'stocks'," +
                " sum(record_barrels) as 'barrels' from" +
                " cm_sale_record where record_user_id = " + userId + "" +
                " and year(record_time)='" + year + "'" +
                " and month(record_time)='" + month + "'";
        try {
            ResultSet rs = DatabaseUtil.execSQL(sql);
            if (rs != null && rs.next()) {
                int lockAll = rs.getInt("lock");
                int stocksAll = rs.getInt("stocks");
                int barrelsAll = rs.getInt("barrels");
                // 最低数量检查
                if (lockAll < 1 || stocksAll < 1 || barrelsAll < 1) return commitNotReachRequire;
                int commission = figureOutCommission(lockAll, stocksAll, barrelsAll);
                Long time = Calendar.getInstance().getTimeInMillis() / 1000;
                // 终结确认
                sql = "insert into cm_end (end_time, end_user_id, end_commission, end_lock, " +
                        " end_stocks, end_barrels, end_update_time) values ('" + year + "-" + month +
                        "-01', " + userId + ", " + commission + ", " + lockAll + ", " +
                        stocksAll + ", " + barrelsAll + ", " + time + ")";
                try {
                    DatabaseUtil.execSQL(sql);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.println("ERROR: saleCommit -> cm_end插入错误");
                    return commitError;
                }
                return commitSuccess;
            } else {
                return commitNotReachRequire;
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR: saleCommit -> 统计错误(" + year + "-" + month + ")");
        }
        return commitError;

    }

    /**
     * 检查某月份是否终结确认过，即发送过-1
     *
     * @param year  年
     * @param month 月
     * @return true: 已经确认过，不能commit, false: 该月没有确认过，可以commit
     */
    private boolean commitAlready(int year, int month) {
        String sql = "select * from cm_end where end_user_id=" + userId +
                " and year(end_time)='" + year + "'" +
                " and month(end_time)='" + month + "'";
        try {
            ResultSet lastEnd = DatabaseUtil.execSQL(sql);
            if (lastEnd == null || (!lastEnd.next())) {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * 获取某月的月报
     *
     * @param year  年
     * @param month 月
     * @return 月报信息 ArrayList[2]{ {佣金，lock总数，stocks总数，barrels总数，确认时间}
     * 如果要查看的月份没有发送过-1进行确认，那么数组里的第一项的size为0
     * {{时间，lock，stock，barrels，城市名}，{}，{}，...} }
     */
    public ArrayList[] getMonthlyReport(int year, int month) {
        ArrayList[] report = new ArrayList[2];
        ArrayList<String> sum = new ArrayList<>();
        ArrayList<ArrayList<String>> list = new ArrayList<>();

        DatabaseUtil.getConnection();
        // 获取统计信息
        String sql = "select * from cm_end where end_user_id=" + userId +
                " and year(end_time)='" + year + "' and month(end_time)='" + month + "'";
        try {
            ResultSet sumRs = DatabaseUtil.execSQL(sql);
            if (sumRs != null && sumRs.next()) {
                sum.add(sumRs.getInt("end_commission") + "");
                sum.add(sumRs.getInt("end_lock") + "");
                sum.add(sumRs.getInt("end_stocks") + "");
                sum.add(sumRs.getInt("end_barrels") + "");
                long time = sumRs.getLong("end_update_time");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                sum.add(sdf.format(new Date(time * 1000)));
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR: getMonthlyReport -> 获取统计信息");
        }

        // 获取销售记录
        sql = "select * from cm_sale_record where record_user_id=" + userId +
                " and year(record_time)='" + year + "' and month(record_time)='" + month + "' ";
        try {
            ResultSet records = DatabaseUtil.execSQL(sql);
            while (records != null && records.next()) {
                ArrayList<String> record = new ArrayList<>();
                record.add(records.getString("record_time"));
                record.add(records.getInt("record_lock") + "");
                record.add(records.getInt("record_stocks") + "");
                record.add(records.getInt("record_barrels") + "");
                record.add(records.getString("record_town_name"));
                list.add(record);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR: getMonthlyReport -> 获取销售记录");
        }
        report[0] = sum;
        report[1] = list;
        DatabaseUtil.closeConnection();
        return report;
    }

    /**
     * 计算佣金
     *
     * @param lockNumbers    lock总数
     * @param stocksNumbers  stocks总数
     * @param barrelsNumbers barrels总数
     * @return int型佣金数
     */
    private int figureOutCommission(int lockNumbers, int stocksNumbers, int barrelsNumbers) {
        int sum = lockNumbers * lockCost + stocksNumbers * stocksCost + barrelsNumbers * barrelsCost;
        int commission = 0;
        if (sum <= 1000) {
            commission += sum * 0.1;
        } else if (sum <= 1800) {
            commission += 100;
            commission += (sum - 1000) * 0.15;
        } else {
            commission += 220;
            commission += (sum - 1800) * 0.2;
        }
        return commission;
    }

    // 测试用 打印商人信息 可以在login方法中注释掉
    private void printUser() {
        System.out.println("user_id: " + userId);
        System.out.println("user_account: " + userAccount);
        System.out.println("user_password: " + userPassword);
        System.out.println("user_nickname: " + userNickName);
        System.out.println("user_sign_up_time: " + userSignUpTime);
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(
                new Date(userSignUpTime * 1000)
        );
        System.out.println("user_sign_up_date: " + date);
    }

    /**
     * 测试方法，同时也是对各方法的使用进行说明以及对返回结果如何遍历的说明
     */
    public static void test() {
        // 初始化时间，会用到两种时间，一个是'yyyy-MM-dd HH:mm:ss'格式的时间（包含里面具体的年月日），一个是11位的时间戳
        // 不一定要用同样的方法实现，只要结果相同即可
        Timestamp ts = Timestamp.valueOf("2017-04-01 10:00:00");    // Timestamp是Date的子类
        long t = ts.getTime() / 1000;   // 11位时间戳
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(ts);

        // 新增用户测试，记得用不同的用户名
        // DatabaseUtil.addUser("sale002", "12345", "saleman002", t);   // 用户名，密码，昵称，11位时间戳

        // 业务逻辑类实例化
        Business bs = new Business("sale002", "12345");
        // 登录，并输出用户信息
        String[] info = bs.login();     // 返回信息的格式参考login的注释
        // 输出登录提示，没错误则不提示
        if (!info[info.length - 1].equals("")) System.out.println("INFO: login-tip -> " + info[info.length - 1]);

        // 插入数据，为了测试方便写了一个循环，正常使用时没有必要
        // 正常使用时debug=false，只能插入时间为本月的信息
        for (int i = 0; i < 5; i++) {
            info = bs.saleNumUpdate(ts, i + 1, i + 1, i + 1, "peking", true);
            System.out.print("INFO: test -> saleNumUpdate ~ ");
            for (String s : info) System.out.print(s + ", ");
            System.out.println();
        }

        // 终止标识 插入之后这个月就不能再测试咯，慎用
        /*
        info = bs.saleNumUpdate(ts, -1, 0, 0, "", true);
        System.out.print("INFO: test -> saleNumUpdate[-1] ~ ");
        for (String s : info) System.out.print(s + ", ");
        System.out.println();
        */

        // 越界插入，测试用的
        info = bs.saleNumUpdate(ts, 70, 70, 70, "peking", true);
        System.out.print("INFO: test -> saleNumUpdate ~ ");
        for (String s : info) System.out.print(s + ", ");
        System.out.println();

        // 月报
        // list[0] = ArrayList{commission, lock_all, stocks_all, barrels_all, commit_time} 没提交过-1时这里面是空的
        // list[1] = ArrayList<ArrayList>;
        //           由于没有确定ArrayList里面具体是什么类型，所以遍历时要用Object
        //      底层的ArrayList={record_time, lock_number, stocks_number, barrels_number, town_name}
        ArrayList[] list = bs.getMonthlyReport(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1);
        if (list[0].size() != 0) {
            System.out.print("INFO: test -> getMonthlyReport[sum] ~ ");
            for (Object o : list[0]) System.out.print(o + ", ");
            System.out.println();
        }
        if (list[1].size() != 0) {
            System.out.println("INFO: test -> getMonthlyReport[list] # ");
            for (Object o : list[1]) {
                for (Object on : (ArrayList) o) {
                    System.out.print(on + ", ");
                }
                System.out.println();
            }
        }
    }

    public static void main(String[] args) {
        test();
    }

}
