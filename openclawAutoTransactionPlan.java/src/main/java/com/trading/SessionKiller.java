package com.trading;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * 时区猎杀模式 (Session Killer)
 *
 * 根据全球交易时区自动调整策略参数：
 *   ASIA    (08:00-20:00 北京) → 保守：杠杆×0.6，信号门槛提高
 *   US_GOLD (20:00-00:00 北京) → 激进：杠杆×1.3，信号放宽，趋势可靠
 *   US_LATE (00:00-04:00 北京) → 防守：锁利润，不新开仓
 *   DEAD    (04:00-08:00 北京) → 真空：最小仓位，流动性差易插针
 */
public class SessionKiller {

    public enum Session {
        ASIA,       // 08:00-20:00 CST
        US_GOLD,    // 20:00-00:00 CST (美盘黄金4小时)
        US_LATE,    // 00:00-04:00 CST
        DEAD        // 04:00-08:00 CST (流动性真空)
    }

    private static Session lastSession = null;

    /**
     * 获取当前交易时段（北京时间）
     */
    public static Session getCurrentSession() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        // 20-24 = US_GOLD, 0-4 = US_LATE, 4-8 = DEAD, 8-20 = ASIA
        if (hour >= 20)             return Session.US_GOLD;
        if (hour < 4)               return Session.US_LATE;
        if (hour < 8)               return Session.DEAD;
        return Session.ASIA; // 8-20
    }

    /**
     * 杠杆倍率调整
     */
    public static double getLeverageMultiplier() {
        switch (getCurrentSession()) {
            case ASIA:    return 0.6;   // 亚洲盘假突破多，降杠杆
            case US_GOLD: return 1.3;   // 美盘趋势可靠，加杠杆
            case US_LATE: return 0.8;   // 美盘后段V型反转多
            case DEAD:    return 0.4;   // 真空期流动性差
            default:      return 1.0;
        }
    }

    /**
     * 信号门槛倍率（越高越严格）
     * 亚洲盘要求更高的量能确认才开仓
     */
    public static double getSignalThresholdMultiplier() {
        switch (getCurrentSession()) {
            case ASIA:    return 1.5;   // 量能要求×1.5 防假突破
            case US_GOLD: return 0.8;   // 放宽门槛，趋势概率高
            case US_LATE: return 1.2;
            case DEAD:    return 2.0;   // 非常严格
            default:      return 1.0;
        }
    }

    /**
     * 是否允许新开仓
     */
    public static boolean canOpenNewPosition() {
        Session s = getCurrentSession();
        // US_LATE: 只在有强信号时开仓（由调用方判断）
        // DEAD: 不开新仓
        return s != Session.DEAD;
    }

    /**
     * 是否应该锁利润（US_LATE和DEAD时段）
     */
    public static boolean shouldLockProfit() {
        Session s = getCurrentSession();
        return s == Session.US_LATE || s == Session.DEAD;
    }

    /**
     * 子弹仓可用比例上限
     */
    public static double getMaxBulletUsage() {
        switch (getCurrentSession()) {
            case ASIA:    return 0.40;  // 最多用40%子弹
            case US_GOLD: return 0.60;  // 可用60%
            case US_LATE: return 0.25;  // 保守
            case DEAD:    return 0.15;  // 极小仓位
            default:      return 0.40;
        }
    }

    /**
     * 调整后的杠杆值（原始杠杆 × 时区倍率）
     */
    public static int adjustLeverage(int baseLeverage) {
        double adjusted = baseLeverage * getLeverageMultiplier();
        return Math.max(1, Math.min(20, (int) Math.round(adjusted)));
    }

    /**
     * 打印时段切换信息
     */
    public static void checkAndPrintSessionChange() {
        Session current = getCurrentSession();
        if (lastSession != null && current != lastSession) {
            System.out.println("\n====================================================");
            System.out.println("  [SESSION SWITCH] " + lastSession + " -> " + current);
            System.out.println("  Leverage multiplier: " + getLeverageMultiplier() + "x");
            System.out.println("  Signal threshold: " + getSignalThresholdMultiplier() + "x");
            System.out.println("  Can open new: " + canOpenNewPosition());
            System.out.println("  Max bullet usage: " + (getMaxBulletUsage() * 100) + "%");
            System.out.println("====================================================\n");
        }
        lastSession = current;
    }
}
