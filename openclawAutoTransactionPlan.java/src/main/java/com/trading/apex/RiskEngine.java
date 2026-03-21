package com.trading.apex;

import java.util.Locale;

/**
 * RiskEngine - 四层风控引擎
 *
 * Layer 0: 单笔级 - 最大亏损1.5%本金，杠杆上限20x
 * Layer 1: 策略级 - 连续亏损降仓，单策略日亏损8%停机
 * Layer 2: 组合级 - 总净敞口上限，同向敞口上限
 * Layer 3: 系统级 - 日回撤5%停机，周回撤12%停机，总资金<75%永停
 */
public class RiskEngine {

    // === Layer 0: 单笔限制 ===
    private static final int MAX_LEVERAGE = 20;
    private static final double MAX_SINGLE_LOSS_PCT = 0.015;   // 单笔最大亏1.5%本金

    // === Layer 2: 组合限制 ===
    private static final double MAX_NET_EXPOSURE_RATIO = 8.0;   // 净敞口≤本金×8
    private static final double MAX_DIRECTIONAL_RATIO = 5.0;    // 单方向≤本金×5
    private static final double CONFLUENCE_BOOST_RATIO = 10.0;  // 多策略共振时可到×10

    // === Layer 3: 系统限制 ===
    private static final double DAILY_MAX_DRAWDOWN = 0.05;      // 日最大回撤5%
    private static final double WEEKLY_MAX_DRAWDOWN = 0.12;     // 周最大回撤12%
    private static final double TOTAL_KILL_PCT = 0.75;          // 总资金<75%永停

    // === 连续亏损追踪 ===
    private int consecutiveLosses = 0;
    private static final int LOSS_REDUCE_THRESHOLD = 3;         // 连亏3次减仓
    private static final int LOSS_PAUSE_THRESHOLD = 5;          // 连亏5次暂停30分钟
    private long pauseUntil = 0;

    // === 日/周PnL追踪 ===
    private double dayStartBalance = 0;
    private double weekStartBalance = 0;
    private double peakBalance = 0;
    private long dayStartTime = 0;
    private long weekStartTime = 0;

    // === 敞口追踪 ===
    private double currentLongExposure = 0;   // 当前做多名义总额
    private double currentShortExposure = 0;  // 当前做空名义总额
    private int openPositionCount = 0;

    // === 状态 ===
    private boolean dailyKilled = false;
    private boolean weeklyKilled = false;
    private boolean permanentKilled = false;
    private final double initialCapital;
    private int totalTradesChecked = 0;
    private int tradesApproved = 0;
    private int tradesRejected = 0;

    public RiskEngine(double initialCapital) {
        this.initialCapital = initialCapital;
        this.dayStartBalance = initialCapital;
        this.weekStartBalance = initialCapital;
        this.peakBalance = initialCapital;
        this.dayStartTime = System.currentTimeMillis();
        this.weekStartTime = System.currentTimeMillis();
    }

    /**
     * 交易前检查：是否允许开仓
     * @return null = 允许, String = 拒绝原因
     */
    public synchronized String preTradeCheck(double currentBalance, double marginAmount,
                                              int leverage, String side, boolean isConfluence) {
        totalTradesChecked++;

        // Layer 3: 系统级
        if (permanentKilled) { tradesRejected++; return "PERMANENT_KILL: balance < 75% initial"; }
        if (dailyKilled) { tradesRejected++; return "DAILY_KILL: drawdown > 5%"; }
        if (weeklyKilled) { tradesRejected++; return "WEEKLY_KILL: drawdown > 12%"; }

        // 检查暂停状态
        if (System.currentTimeMillis() < pauseUntil) {
            tradesRejected++;
            return "PAUSED: " + consecutiveLosses + " consecutive losses, resume in " +
                   ((pauseUntil - System.currentTimeMillis()) / 1000) + "s";
        }

        // Layer 0: 单笔级
        if (leverage > MAX_LEVERAGE) {
            tradesRejected++; return "LEVERAGE_EXCEEDED: " + leverage + " > " + MAX_LEVERAGE;
        }
        double maxLoss = marginAmount; // 极端情况下亏完保证金
        if (maxLoss > currentBalance * MAX_SINGLE_LOSS_PCT) {
            tradesRejected++;
            return String.format(Locale.US, "SINGLE_LOSS_EXCEEDED: margin=%.2f > %.2f (1.5%% of %.2f)",
                marginAmount, currentBalance * MAX_SINGLE_LOSS_PCT, currentBalance);
        }

        // Layer 2: 组合级敞口检查
        double notional = marginAmount * leverage;
        double maxExposure = currentBalance * (isConfluence ? CONFLUENCE_BOOST_RATIO : MAX_NET_EXPOSURE_RATIO);
        double maxDirectional = currentBalance * (isConfluence ? CONFLUENCE_BOOST_RATIO : MAX_DIRECTIONAL_RATIO);

        double newLong = currentLongExposure + (side.equals("LONG") ? notional : 0);
        double newShort = currentShortExposure + (side.equals("SHORT") ? notional : 0);
        double newNet = Math.abs(newLong - newShort);

        if (newNet > maxExposure) {
            tradesRejected++;
            return String.format(Locale.US, "NET_EXPOSURE: %.0f > limit %.0f", newNet, maxExposure);
        }
        if (side.equals("LONG") && newLong > maxDirectional) {
            tradesRejected++;
            return String.format(Locale.US, "LONG_EXPOSURE: %.0f > limit %.0f", newLong, maxDirectional);
        }
        if (side.equals("SHORT") && newShort > maxDirectional) {
            tradesRejected++;
            return String.format(Locale.US, "SHORT_EXPOSURE: %.0f > limit %.0f", newShort, maxDirectional);
        }

        tradesApproved++;
        return null; // 通过
    }

    /**
     * 调整仓位大小（根据连续亏损）
     */
    public double adjustPositionSize(double baseMargin) {
        if (consecutiveLosses >= LOSS_REDUCE_THRESHOLD) {
            double factor = Math.max(0.25, 1.0 - (consecutiveLosses - LOSS_REDUCE_THRESHOLD + 1) * 0.2);
            return baseMargin * factor;
        }
        return baseMargin;
    }

    /**
     * 交易后报告: 更新所有追踪数据
     */
    public synchronized void recordTrade(double pnl, double notional, String side) {
        if (pnl >= 0) {
            consecutiveLosses = 0;
        } else {
            consecutiveLosses++;
            if (consecutiveLosses >= LOSS_PAUSE_THRESHOLD) {
                pauseUntil = System.currentTimeMillis() + 30 * 60 * 1000; // 暂停30分钟
                System.out.println("[RISK] " + consecutiveLosses + " consecutive losses! Pausing 30 minutes.");
            }
        }
    }

    /**
     * 更新敞口（开仓时调用）
     */
    public synchronized void addExposure(String side, double notional) {
        if (side.equals("LONG")) currentLongExposure += notional;
        else currentShortExposure += notional;
        openPositionCount++;
    }

    /**
     * 移除敞口（平仓时调用）
     */
    public synchronized void removeExposure(String side, double notional) {
        if (side.equals("LONG")) currentLongExposure = Math.max(0, currentLongExposure - notional);
        else currentShortExposure = Math.max(0, currentShortExposure - notional);
        openPositionCount = Math.max(0, openPositionCount - 1);
    }

    /**
     * 定期检查(每秒调用): 日/周回撤检查
     */
    public synchronized void periodicCheck(double currentBalance) {
        long now = System.currentTimeMillis();

        // 更新峰值
        if (currentBalance > peakBalance) peakBalance = currentBalance;

        // 日重置(24h)
        if (now - dayStartTime > 86400000) {
            dayStartBalance = currentBalance;
            dayStartTime = now;
            dailyKilled = false;
        }

        // 周重置(7天)
        if (now - weekStartTime > 604800000) {
            weekStartBalance = currentBalance;
            weekStartTime = now;
            weeklyKilled = false;
        }

        // Layer 3检查
        double dailyDrawdown = (dayStartBalance - currentBalance) / dayStartBalance;
        double weeklyDrawdown = (weekStartBalance - currentBalance) / weekStartBalance;

        if (dailyDrawdown >= DAILY_MAX_DRAWDOWN && !dailyKilled) {
            dailyKilled = true;
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║  [RISK] DAILY KILL SWITCH TRIGGERED!     ║");
            System.out.println("║  Drawdown: " + String.format("%.2f%%", dailyDrawdown * 100) + " >= 5%                 ║");
            System.out.println("║  All trading halted until tomorrow.      ║");
            System.out.println("╚══════════════════════════════════════════╝");
        }

        if (weeklyDrawdown >= WEEKLY_MAX_DRAWDOWN && !weeklyKilled) {
            weeklyKilled = true;
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║  [RISK] WEEKLY KILL SWITCH TRIGGERED!    ║");
            System.out.println("║  Drawdown: " + String.format("%.2f%%", weeklyDrawdown * 100) + " >= 12%               ║");
            System.out.println("║  MANUAL RESTART REQUIRED.                ║");
            System.out.println("╚══════════════════════════════════════════╝");
        }

        if (currentBalance < initialCapital * TOTAL_KILL_PCT && !permanentKilled) {
            permanentKilled = true;
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║  [RISK] PERMANENT KILL SWITCH!           ║");
            System.out.println("║  Balance " + String.format("%.2f", currentBalance) + " < 75% of " + String.format("%.2f", initialCapital) + "    ║");
            System.out.println("║  SYSTEM PERMANENTLY LOCKED.              ║");
            System.out.println("╚══════════════════════════════════════════╝");
        }
    }

    /**
     * 是否应该紧急平仓所有持仓
     */
    public boolean shouldEmergencyClose() {
        return permanentKilled || dailyKilled || weeklyKilled;
    }

    /**
     * 能否交易
     */
    public boolean canTrade() {
        if (permanentKilled || dailyKilled || weeklyKilled) return false;
        if (System.currentTimeMillis() < pauseUntil) return false;
        return true;
    }

    public String statusLine() {
        return String.format(Locale.US,
            "[RISK] longExp=%.0f shortExp=%.0f net=%.0f | " +
            "consLoss=%d | trades=%d(ok=%d rej=%d) | " +
            "daily%s weekly%s perm%s",
            currentLongExposure, currentShortExposure,
            Math.abs(currentLongExposure - currentShortExposure),
            consecutiveLosses,
            totalTradesChecked, tradesApproved, tradesRejected,
            dailyKilled ? "=KILLED" : "=OK",
            weeklyKilled ? "=KILLED" : "=OK",
            permanentKilled ? "=KILLED" : "=OK");
    }

    // Getters
    public double getCurrentLongExposure() { return currentLongExposure; }
    public double getCurrentShortExposure() { return currentShortExposure; }
    public int getConsecutiveLosses() { return consecutiveLosses; }
    public boolean isPermanentKilled() { return permanentKilled; }
}
