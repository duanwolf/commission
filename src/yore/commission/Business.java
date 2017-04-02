package yore.commission;

import yore.util.*;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

public class Business {

    // 错误信息
    private static final String UNKNOWN_ERROR = "未知的错误";
    private static final String NO_SUCH_ACCOUNT = "用户名或密码错误";
    private static final String UPPER_LIMIT = "数量超出可出售上限";
    private static final String COMMIT_ERROR = "提交确认失败";
    private static final String COMMIT_TWICE = "请不要重复确认";
    private static final String COMMIT_NOT_REACH_REQUIRE = "未达到结束确认要求：每样商品至少售出1";
    private static final String NUMBER_NO_SENSE = "数量不合法";
    private static final String NO_TOWN_NAME = "未填写城市名";
    private static final String INVALID_DATE = "日期不合法";
    private static final String DATABASE_EXEC_ERROR = "数据操作错误";
    private static final String AUTO_COMMIT_LAST_MONTH = "检测到上月份未自动提交出售信息确认，已自动提交";

    // code
    private static final int commitSuccess = 100;
    private static final int commitError = 101;
    private static final int commitTwice = 102;
    private static final int commitNotReachRequire = 103;

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
    private Long userSignUpTime = null;

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
     * @return new String[] {"1", id, account, password, nickname, signUpTime, tip}
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
            Calendar calendar = Calendar.getInstance();
            calendar.setTime(new Date());
            int year = calendar.get(Calendar.YEAR) - 1900;
            int month = calendar.get(Calendar.MONTH) - 1;
            if (month == 0) {
                month = 12;
                year--;
            }
            String tip = "";
            if (!commitAlready(year, month)) {
                saleCommit(year, month, true);
                tip = AUTO_COMMIT_LAST_MONTH;
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
        // 城市名判断
        if (townName == null || townName.equals("")) return new String[]{"0", NO_TOWN_NAME};

        // 日期判断
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        int year = calendar.get(Calendar.YEAR) - 1900;
        int month = calendar.get(Calendar.MONTH);

        if (!debug) {
            // 当测试时可以把debug设成true来随意添加数据
            calendar.setTime(new Date());
            // 日期必须是本月的
            if (year != calendar.get(Calendar.YEAR) - 1900 || month != calendar.get(Calendar.MONTH)) {
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

        // 上限判断
        sql = "select sum(record_lock) as lock, sum(record_stocks) as stocks," +
                " sum(record_barrels) as barrels from" +
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
        int yearNow = calendar.get(Calendar.YEAR) - 1900;
        int monthNow = calendar.get(Calendar.MONTH) + 1;
        int dayNow = calendar.get(Calendar.DATE);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        int second = calendar.get(Calendar.SECOND);
        String dateTime = yearNow + "-" + monthNow + "-" + dayNow + " " + hour + ":" + minute + ":" + second;
        sql = "insert into cm_sale_record (record_time, record_lock, record_stocks, record_barrels," +
                " record_town_name, record_user_id) values ('" + dateTime + "', " + lNum + ", " + sNum + "," +
                " " + bNum + ", " + townName + ", " + userId + ")";
        try {
            DatabaseUtil.execSQL(sql);
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR: saleNumberUpdate -> 数据插入");
            return new String[]{"0", DATABASE_EXEC_ERROR};
        }

        return new String[]{"1"};
    }

    /**
     * 商人发送 -1 locks时，每月第一次发送出售情况时，每月第一次发送月报请求时
     * 对一个月的出售情况作出终结和统计
     *
     * @return code 成功，失败，不可重复提交，未达到提交标准
     */
    private int saleCommit(int year, int month, boolean checked) {
        if (!checked) {
            if (commitAlready(year, month)) return commitTwice;
        }
        // 进行统计
        String sql;
        sql = "select sum(record_lock) as lock, sum(record_stocks) as stocks," +
                " sum(record_barrels) as barrels from" +
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
     * 检查某月份是否终结确认过
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
     *                               {{时间，lock，stock，barrels，城市名}，{}，{}，...} }
     */
    public ArrayList[] getMonthlyReport(int year, int month) {
        ArrayList[] report = new ArrayList[2];
        ArrayList<String> sum = new ArrayList<>();
        ArrayList<ArrayList<String>> list = new ArrayList<>();

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
            while (records!=null && records.next()) {
                ArrayList<String> record = new ArrayList<>();
                record.add(records.getString("record_time"));
                record.add(records.getInt("record_lock")+"");
                record.add(records.getInt("record_stocks")+"");
                record.add(records.getInt("record_barrels")+"");
                record.add(records.getString("record_town_name"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("ERROR: getMonthlyReport -> 获取销售记录");
        }
        report[0] = sum;
        report[1] = list;
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

    // 测试用
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

    // 测试用
    public static void main(String[] args) {
        Date date = Timestamp.valueOf("2017-03-01 10:00:00");
        System.out.println(date);
        long ts = date.getTime();
        System.out.println(ts);
        System.out.println(new Date(ts));
        //DatabaseUtil.addUser("sale001", "12345", "saleman001", ts);
        Business bs = new Business("sale001", "12345");
        bs.login();

    }

}
