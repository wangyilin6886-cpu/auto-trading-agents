package com.trading;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;

/**
 * 风控中枢 — 所有交易的最后一道门禁。
 * 5层防护：文件级KillSwitch、内存级KillSwitch、每日亏损限额、连亏冷却、单笔暴露上限。
 */
public class RiskManager {

    private static final String KILL_SWITCH_FILE = Config.KILL_SWITCH_FILE;

    private final double initialCapital;
    private volatile boolean memoryKilled = false;

    // 每日亏损追踪
    private volatile LocalDate currentDay = LocalDate.now();
    private volatile double dailyLoss = 0.0;
    private static final double MAX_DAILY_LOSS_PCT = 0.10; // 单日最多亏本金10%

    // 连亏冷却
    private volatile int consecutiveLosses = 0;
    private volatile long cooldownUntil = 0;
    private static final int MAX_CONSECUTIVE_LOSSES = 3;
    private static final long COOLDOWN_DURATION_MS = 30 * 60 * 1000; // 30分钟

    // 单笔暴露上限
    private static final double MAX_SINGLE_MARGIN_PCT = 0.05; // 单笔最多动用本金5%

    // 全局最大回撤
    private static final double MAX_DRAWDOWN_PCT = 0.20; // 亏20%永久停机

    public RiskManager(double initialCapital) {
        this.initialCapital = initialCapital;
    }

    /**
     * 开仓前的门禁检查。返回null表示通过，返回字符串表示拒绝理由。
     */
    public synchronized String canTrade(double currentBalance) {
        // 第5层：文件级 Kill Switch
        if (new File(KILL_SWITCH_FILE).exists()) {
            return "文件级KillSwitch已激活 (" + KILL_SWITCH_FILE + ")";
        }

        // 第4层：内存级永久熔断
        if (memoryKilled) {
            return "内存级熔断已触发，本次运行永久停止交易";
        }

        // 全局最大回撤检测
        if (currentBalance <= initialCapital * (1.0 - MAX_DRAWDOWN_PCT)) {
            triggerPermanentKill("全局回撤超过" + pct(MAX_DRAWDOWN_PCT));
            return "全局回撤超过" + pct(MAX_DRAWDOWN_PCT) + "，永久停机";
        }

        // 第3层：每日亏损限额
        resetDayIfNeeded();
        double maxDailyLossAmount = initialCapital * MAX_DAILY_LOSS_PCT;
        if (dailyLoss >= maxDailyLossAmount) {
            return "今日已亏损 " + fmt(dailyLoss) + " USDT，达到日限额 " + fmt(maxDailyLossAmount) + "，明天再战";
        }

        // 第2层：连亏冷却
        if (System.currentTimeMillis() < cooldownUntil) {
            long remainSec = (cooldownUntil - System.currentTimeMillis()) / 1000;
            return "连亏" + MAX_CONSECUTIVE_LOSSES + "笔，冷却中，剩余 " + remainSec + " 秒";
        }

        return null; // 通过
    }

    /**
     * 校验并裁剪保证金，确保不超过单笔上限。
     */
    public double clipMargin(double requestedMargin, double currentBalance) {
        double maxMargin = initialCapital * MAX_SINGLE_MARGIN_PCT;
        double clipped = Math.min(requestedMargin, maxMargin);
        clipped = Math.min(clipped, currentBalance * 0.95); // 留5%缓冲
        return Math.max(clipped, 0);
    }

    /**
     * 交易结束后上报盈亏。
     */
    public synchronized void reportTradeResult(double pnl) {
        resetDayIfNeeded();
        if (pnl < 0) {
            dailyLoss += Math.abs(pnl);
            consecutiveLosses++;
            if (consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) {
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_DURATION_MS;
                System.out.println("🧊 [风控冷却] 连亏" + consecutiveLosses + "笔，强制冷却30分钟");
                try {
                    AuditLogger.get().alert("CONSECUTIVE_LOSS", "连亏" + consecutiveLosses + "笔，冷却30分钟");
                } catch (Exception ignored) {}
                consecutiveLosses = 0; // 冷却后重新计数
            }
        } else {
            consecutiveLosses = 0; // 盈利重置连亏计数
        }
    }

    /**
     * 触发永久熔断：内存 + 写文件（重启也杀不死）。
     */
    public void triggerPermanentKill(String reason) {
        memoryKilled = true;
        try {
            new File(KILL_SWITCH_FILE).createNewFile();
        } catch (IOException e) {
            System.err.println("警告：无法创建KillSwitch文件: " + e.getMessage());
        }
        System.out.println("💀💀💀 [永久熔断] " + reason + " | KillSwitch文件已写入，重启也无法交易 💀💀💀");
        try {
            AuditLogger.get().alert("KILL_SWITCH", reason);
        } catch (Exception ignored) {}
    }

    /**
     * 检查当前余额是否触发全局熔断。
     */
    public boolean checkDrawdown(double currentBalance) {
        if (currentBalance <= initialCapital * (1.0 - MAX_DRAWDOWN_PCT)) {
            triggerPermanentKill("全局回撤超过" + pct(MAX_DRAWDOWN_PCT));
            return true;
        }
        return memoryKilled || new File(KILL_SWITCH_FILE).exists();
    }

    private void resetDayIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay)) {
            currentDay = today;
            dailyLoss = 0.0;
            System.out.println("📅 [风控日重置] 新的一天，日亏损计数器归零");
        }
    }

    public boolean isKilled() {
        return memoryKilled || new File(KILL_SWITCH_FILE).exists();
    }

    private String fmt(double v) { return String.format(Locale.US, "%.2f", v); }
    private String pct(double v) { return String.format(Locale.US, "%.0f%%", v * 100); }
}
