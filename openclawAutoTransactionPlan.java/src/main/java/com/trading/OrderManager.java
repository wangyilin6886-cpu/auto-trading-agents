package com.trading;

import java.time.LocalDate;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 原子订单管理器 — 所有开仓/平仓/加仓的唯一入口。
 *
 * 职责：
 *   1. 频率限制：最小间隔 30s、每小时 20 笔、每天 100 笔
 *   2. 模式分发：LIVE → BinanceRealAccount，SIMULATION → SimulationAccount
 *   3. Kelly 仓位管理：冷启动固定 3% → 半 Kelly 自动计算
 *   4. 审计日志：所有订单记录到 AuditLogger
 *   5. Telegram 通知：开仓/平仓推送
 *   6. 滑点监控：记录预期价 vs 成交价
 */
public class OrderManager {

    private final BinanceRealAccount liveAccount;
    private final RiskManager riskManager;
    private final TelegramNotifier telegramNotifier;

    // 频率限制
    private final AtomicLong lastTradeTime = new AtomicLong(0);
    private final AtomicInteger tradesThisHour = new AtomicInteger(0);
    private final AtomicInteger tradesToday = new AtomicInteger(0);
    private long currentHourStart = System.currentTimeMillis();
    private volatile LocalDate currentDay = LocalDate.now();

    // Kelly 公式统计
    private int totalTrades = 0;
    private int wins = 0;
    private double avgWin = 0;
    private double avgLoss = 0;
    private double kellyPct = Config.KELLY_COLD_START_PCT;

    public OrderManager(BinanceRealAccount liveAccount, RiskManager riskManager, TelegramNotifier telegramNotifier) {
        this.liveAccount = liveAccount;
        this.riskManager = riskManager;
        this.telegramNotifier = telegramNotifier;
    }

    // ==================== 开仓入口 ====================

    /**
     * 原子开仓操作。
     * @return true 如果开仓成功
     */
    public synchronized boolean openPosition(String side, double price, int leverage, String source) {
        // 频率限制
        String rateLimitReason = checkRateLimit();
        if (rateLimitReason != null) {
            System.out.println("[OrderManager] Rate limit: " + rateLimitReason);
            return false;
        }

        // 风控门禁
        if (riskManager.isKilled()) {
            System.out.println("[OrderManager] Blocked: system killed");
            return false;
        }

        double wallet = liveAccount.getWalletBalance();
        String riskCheck = riskManager.canTrade(wallet);
        if (riskCheck != null) {
            System.out.println("[OrderManager] Risk blocked: " + riskCheck);
            AuditLogger.get().logRiskCheck(false, 0, wallet, riskCheck);
            return false;
        }

        // 计算仓位（Kelly or 固定）
        double marginPct = getPositionSize();
        double margin = wallet * marginPct;
        margin = riskManager.clipMargin(margin, wallet);

        if (margin < 5.0) {
            System.out.println("[OrderManager] Margin too small: " + fmt(margin));
            return false;
        }

        // 审计日志
        int estQty = (int) Math.max(1, (margin * leverage) / price);
        AuditLogger.get().logOrder(side, estQty, price, price, leverage, source);

        if (Config.IS_LIVE) {
            // 实盘
            liveAccount.openPosition(side, price, margin, leverage, source);

            if (!liveAccount.getPositionSide().equals("NONE")) {
                recordTradeTime();

                // 计算止盈止损价格（用于通知）
                double tpPrice = side.equals("LONG") ? price * 1.008 : price * 0.992;
                double slPrice = side.equals("LONG") ? price * (1 - 0.6 / leverage) : price * (1 + 0.6 / leverage);

                if (telegramNotifier != null) {
                    telegramNotifier.sendTradeNotification(side, liveAccount.getPositionSize(),
                            price, leverage, tpPrice, slPrice, source);
                }

                return true;
            }
        } else {
            // 模拟模式
            AuditLogger.get().logSimulatedOrder(side, estQty, price, leverage, source);
            System.out.println("[SIMULATION] " + side + " " + estQty + " SOL @ " + fmt(price) + " " + leverage + "x");
            recordTradeTime();
            return true;
        }

        return false;
    }

    // ==================== 平仓入口 ====================

    /**
     * 原子平仓操作。
     */
    public synchronized void closePosition(double price, String reason) {
        if (liveAccount.getPositionSide().equals("NONE")) return;

        String side = liveAccount.getPositionSide();
        double entryPrice = liveAccount.getEntryPrice();
        double pnlBefore = liveAccount.getUnrealizedPNL(price);
        double roeBefore = liveAccount.getROE(price);

        if (Config.IS_LIVE) {
            liveAccount.closePosition(price, reason);
        }

        // 记录交易结果
        recordTradeResult(pnlBefore);

        // 审计
        AuditLogger.get().logTradeResult(pnlBefore, liveAccount.getWalletBalance(),
                totalTrades > 0 ? (double) wins / totalTrades : 0, totalTrades);

        // Telegram 通知
        if (telegramNotifier != null) {
            telegramNotifier.sendCloseNotification(side, entryPrice, price, pnlBefore, roeBefore, reason);
        }
    }

    // ==================== 加仓入口 ====================

    /**
     * 浮盈加仓。
     */
    public synchronized boolean addToPosition(double price, double margin, int leverage, String reason) {
        if (liveAccount.getPositionSide().equals("NONE")) return false;

        margin = riskManager.clipMargin(margin, liveAccount.getWalletBalance());

        if (Config.IS_LIVE) {
            return liveAccount.addToPosition(price, margin, leverage, reason);
        } else {
            int qty = (int) Math.max(1, (margin * leverage) / price);
            AuditLogger.get().logSimulatedOrder("ADD_" + liveAccount.getPositionSide(), qty, price, leverage, reason);
            System.out.println("[SIMULATION] ADD " + qty + " SOL @ " + fmt(price) + " " + reason);
            return true;
        }
    }

    // ==================== Kelly 仓位计算 ====================

    /**
     * 获取当前推荐仓位百分比。
     * 前 50 笔用固定 3%，之后用半 Kelly 公式。
     */
    private double getPositionSize() {
        if (totalTrades < Config.KELLY_COLD_START_TRADES) {
            return Config.KELLY_COLD_START_PCT;
        }
        return kellyPct;
    }

    private void recalcKelly() {
        if (totalTrades < Config.KELLY_COLD_START_TRADES) return;
        if (totalTrades % Config.KELLY_RECALC_INTERVAL != 0) return;

        double winRate = (double) wins / totalTrades;
        if (winRate <= 0 || avgLoss <= 0) {
            kellyPct = Config.KELLY_COLD_START_PCT;
            return;
        }

        double lossRate = 1.0 - winRate;
        double payoffRatio = avgWin / avgLoss;

        // Kelly % = W - (L / R)
        double kelly = winRate - (lossRate / payoffRatio);

        // 半 Kelly，上限 25%
        kellyPct = Math.max(0.01, Math.min(Config.KELLY_MAX_PCT, kelly / 2.0));

        System.out.println(String.format(Locale.US,
                "[Kelly] WinRate=%.1f%% AvgWin=%.2f AvgLoss=%.2f Payoff=%.2f Kelly=%.1f%% → HalfKelly=%.1f%%",
                winRate * 100, avgWin, avgLoss, payoffRatio, kelly * 100, kellyPct * 100));
    }

    private void recordTradeResult(double pnl) {
        totalTrades++;
        if (pnl > 0) {
            wins++;
            // 移动平均
            avgWin = avgWin == 0 ? pnl : avgWin * 0.9 + pnl * 0.1;
        } else if (pnl < 0) {
            avgLoss = avgLoss == 0 ? Math.abs(pnl) : avgLoss * 0.9 + Math.abs(pnl) * 0.1;
        }
        recalcKelly();
    }

    // ==================== 频率限制 ====================

    private String checkRateLimit() {
        long now = System.currentTimeMillis();

        // 最小间隔
        if (now - lastTradeTime.get() < Config.MIN_TRADE_INTERVAL_MS) {
            return "Too fast: min interval " + (Config.MIN_TRADE_INTERVAL_MS / 1000) + "s";
        }

        // 每小时限制
        if (now - currentHourStart > 3600_000) {
            currentHourStart = now;
            tradesThisHour.set(0);
        }
        if (tradesThisHour.get() >= Config.MAX_TRADES_PER_HOUR) {
            return "Hourly limit reached: " + Config.MAX_TRADES_PER_HOUR;
        }

        // 每日限制
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay)) {
            currentDay = today;
            tradesToday.set(0);
        }
        if (tradesToday.get() >= Config.MAX_TRADES_PER_DAY) {
            return "Daily limit reached: " + Config.MAX_TRADES_PER_DAY;
        }

        return null;
    }

    private void recordTradeTime() {
        lastTradeTime.set(System.currentTimeMillis());
        tradesThisHour.incrementAndGet();
        tradesToday.incrementAndGet();
    }

    // ==================== 查询 ====================

    public int getTotalTrades() { return totalTrades; }
    public int getWins() { return wins; }
    public double getWinRate() { return totalTrades > 0 ? (double) wins / totalTrades : 0; }
    public double getKellyPct() { return kellyPct; }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}
