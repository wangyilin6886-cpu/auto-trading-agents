package com.trading;

import java.util.Locale;

/**
 * 快通道交易员 — 基于 OBI（订单簿失衡）的毫秒级信号交易。
 *
 * 触发条件：|OBI| >= FAST_OBI_THRESHOLD (默认 0.30)
 * 仓位大小：基础仓位的 30%
 * 杠杆上限：15x
 * 止盈：1% ROE
 * 止损：0.5% ROE
 * 最大持仓：60 秒
 *
 * 特性：
 *   - 不经过 AI Agent（纯数学信号）
 *   - 不与慢通道仓位冲突（检查持仓状态）
 *   - 独立 TP/SL/超时管理
 *   - 审计日志记录
 */
public class FastLaneTrader {

    private final SignalEngine signalEngine;
    private final OrderManager orderManager;
    private final BinanceRealAccount account;
    private final RiskManager riskManager;

    // 快通道仓位状态
    private volatile boolean fastPositionActive = false;
    private volatile String fastSide = "NONE";
    private volatile double fastEntryPrice = 0;
    private volatile long fastEntryTime = 0;
    private volatile int fastQty = 0;

    // 冷却
    private volatile long lastFastTradeTime = 0;
    private static final long FAST_COOLDOWN_MS = 10_000; // 快通道冷却 10 秒

    public FastLaneTrader(SignalEngine signalEngine, OrderManager orderManager,
                          BinanceRealAccount account, RiskManager riskManager) {
        this.signalEngine = signalEngine;
        this.orderManager = orderManager;
        this.account = account;
        this.riskManager = riskManager;
    }

    /**
     * 每个 tick 调用：检查是否触发快通道信号。
     * 由 WebSocket onMessage 线程直接调用，不开新线程。
     */
    public void onTick(double currentPrice) {
        // 持仓管理
        if (fastPositionActive) {
            checkFastExit(currentPrice);
            return;
        }

        // 冷却检查
        if (System.currentTimeMillis() - lastFastTradeTime < FAST_COOLDOWN_MS) return;

        // 慢通道有仓位时不开快通道
        if (!account.getPositionSide().equals("NONE")) return;

        // 风控检查
        if (riskManager.isKilled()) return;

        // OBI 信号检查
        String signal = signalEngine.checkFastOBI();
        if (signal == null) return;

        // 触发快通道交易
        executeFastTrade(signal, currentPrice);
    }

    /**
     * 执行快通道交易。
     */
    private synchronized void executeFastTrade(String side, double price) {
        if (fastPositionActive) return;
        if (!account.getPositionSide().equals("NONE")) return;

        double obi = signalEngine.getLastOBI();
        double wallet = account.getWalletBalance();
        double margin = wallet * Config.KELLY_COLD_START_PCT * Config.FAST_POSITION_PCT; // 基础仓 * 30%
        margin = riskManager.clipMargin(margin, wallet);

        if (margin < 3.0) return;

        // 动态杠杆（快通道上限 15x）
        int leverage = calculateFastLeverage(obi);

        System.out.println(String.format(Locale.US,
                "\n[FastLane] OBI=%.3f → %s @ %.4f | Margin=%.2f | Lev=%dx",
                obi, side, price, margin, leverage));

        // 通过 OrderManager 开仓
        boolean success = orderManager.openPosition(side, price, leverage, "FastLane_OBI=" + fmt(obi));

        if (success) {
            fastPositionActive = true;
            fastSide = side;
            fastEntryPrice = price;
            fastEntryTime = System.currentTimeMillis();
            fastQty = account.getPositionSize();

            AuditLogger.get().logSignal(obi, signalEngine.getLastCVD(), signalEngine.getLastRSI(),
                    signalEngine.getLastLCI(), signalEngine.getLastFR(), signalEngine.getLastComposite());
        }
    }

    /**
     * 检查快通道仓位是否该平仓。
     */
    private synchronized void checkFastExit(double currentPrice) {
        if (!fastPositionActive) return;

        double roe = account.getROE(currentPrice);
        long elapsed = System.currentTimeMillis() - fastEntryTime;

        // 止盈
        if (roe >= Config.FAST_TP_ROE) {
            System.out.println(String.format(Locale.US,
                    "[FastLane] TP hit: ROE=%.2f%% (target %.2f%%)", roe, Config.FAST_TP_ROE));
            orderManager.closePosition(currentPrice, "FastLane TP: ROE=" + fmt(roe) + "%");
            resetFast();
            return;
        }

        // 止损
        if (roe <= Config.FAST_SL_ROE) {
            System.out.println(String.format(Locale.US,
                    "[FastLane] SL hit: ROE=%.2f%% (limit %.2f%%)", roe, Config.FAST_SL_ROE));
            orderManager.closePosition(currentPrice, "FastLane SL: ROE=" + fmt(roe) + "%");
            resetFast();
            return;
        }

        // 超时强平
        if (elapsed > Config.FAST_MAX_HOLD_MS) {
            System.out.println(String.format(Locale.US,
                    "[FastLane] Timeout: %ds > %ds, ROE=%.2f%%",
                    elapsed / 1000, Config.FAST_MAX_HOLD_MS / 1000, roe));
            orderManager.closePosition(currentPrice, "FastLane timeout " + (elapsed / 1000) + "s");
            resetFast();
        }
    }

    /**
     * 快通道杠杆计算。
     * |OBI| 0.30 → 5x, |OBI| 0.60 → 10x, |OBI| 0.80+ → 15x
     */
    private int calculateFastLeverage(double obi) {
        double absObi = Math.abs(obi);
        int lev = (int) (5 + (absObi - Config.FAST_OBI_THRESHOLD) / (1.0 - Config.FAST_OBI_THRESHOLD) * 10);
        return Math.max(5, Math.min(Config.FAST_MAX_LEVERAGE, lev));
    }

    private void resetFast() {
        fastPositionActive = false;
        fastSide = "NONE";
        fastEntryPrice = 0;
        fastEntryTime = 0;
        fastQty = 0;
        lastFastTradeTime = System.currentTimeMillis();
    }

    public boolean isFastPositionActive() { return fastPositionActive; }
    public String getFastSide() { return fastSide; }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}
