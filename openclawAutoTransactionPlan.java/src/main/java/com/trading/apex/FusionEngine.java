package com.trading.apex;

import com.trading.SessionKiller;
import com.trading.TradingAccount;

import java.util.Locale;

/**
 * FusionEngine - 信号融合 + 确信度缩放 + 统一仓位管理
 *
 * 核心逻辑:
 *   1. 4个感知器各自打分 [-100, +100]
 *   2. 加权融合成 FusionScore
 *   3. 根据置信度等级决定仓位大小和杠杆
 *   4. 持仓中动态加减仓(确信度缩放)
 *   5. 统一止损/止盈管理
 *
 * 置信度→仓位映射:
 *   |score| 0-20:   不交易
 *   |score| 20-40:  3%保证金, 10x杠杆
 *   |score| 40-60:  8%保证金, 15x杠杆
 *   |score| 60-80:  15%保证金, 18x杠杆
 *   |score| 80-100: 25%保证金, 20x杠杆
 */
public class FusionEngine {

    // === 感知器 ===
    private final DepthImbalanceSensor depthSensor = new DepthImbalanceSensor();
    private final LiquidationCascadeSensor liqSensor = new LiquidationCascadeSensor();
    private final TapeReadingSensor tapeSensor = new TapeReadingSensor();
    private final MeanReversionSensor mrevSensor = new MeanReversionSensor();

    // === 权重 ===
    private static final double W_DEPTH = 0.25;
    private static final double W_LIQ = 0.20;
    private static final double W_TAPE = 0.35;
    private static final double W_MREV = 0.20;

    // === 仓位状态 ===
    private String positionSide = "NONE";
    private double entryPrice = 0;
    private double positionMargin = 0;      // 当前总保证金
    private double positionNotional = 0;    // 当前名义价值
    private int positionLeverage = 0;
    private double positionSize = 0;        // 持仓数量
    private double peakROE = 0;
    private long entryTime = 0;
    private double lastFusionScore = 0;

    // === 加减仓控制 ===
    private long lastScaleTime = System.currentTimeMillis();
    private static final long SCALE_COOLDOWN_MS = 2000;        // 加减仓间隔2秒
    private static final double MAX_MARGIN_PCT = 0.30;         // 最大保证金占总资金30%
    private static final double SCALE_IN_ROE_MIN = 0.5;        // 浮盈>0.5%才加仓

    // === 止损/止盈 ===
    private static final double HARD_STOP_ROE = -4.0;          // 硬止损 -4% ROE
    private static final double TRAILING_ACTIVATE_ROE = 5.0;   // 移动止盈激活: 峰值ROE>5%
    private static final double TRAILING_DRAWDOWN_PCT = 0.35;  // 从峰值回撤35%平仓
    private static final long MAX_HOLD_TIME_MS = 180000;       // 最长持仓3分钟

    // === 统计 ===
    private double totalPnl = 0;
    private int totalTrades = 0;
    private int winTrades = 0;
    private long lastLogTime = 0;

    // === 关联组件 ===
    private final RiskEngine riskEngine;
    private final TradingAccount account;
    private final String symbol;

    public FusionEngine(RiskEngine riskEngine, TradingAccount account, String symbol) {
        this.riskEngine = riskEngine;
        this.account = account;
        this.symbol = symbol;
    }

    /**
     * 主循环: 每100ms由DataEngine回调
     */
    public synchronized void onTick(MarketMicrostructure m) {
        if (!riskEngine.canTrade()) {
            if (!positionSide.equals("NONE")) {
                emergencyClose(m.price, "risk engine killed");
            }
            return;
        }

        double currentBalance = account.getWalletBalance();
        riskEngine.periodicCheck(currentBalance);

        if (riskEngine.shouldEmergencyClose() && !positionSide.equals("NONE")) {
            emergencyClose(m.price, "emergency close: risk limit");
            return;
        }

        // === 1. 获取4个感知器的信号 ===
        SignalResult depthSignal = depthSensor.evaluate(m);
        SignalResult liqSignal = liqSensor.evaluate(m);
        SignalResult tapeSignal = tapeSensor.evaluate(m);
        SignalResult mrevSignal = mrevSensor.evaluate(m);

        // === 2. 加权融合 ===
        double fusionScore = depthSignal.score * W_DEPTH
                           + liqSignal.score * W_LIQ
                           + tapeSignal.score * W_TAPE
                           + mrevSignal.score * W_MREV;

        // 计算加权置信度
        double fusionConfidence = depthSignal.confidence * W_DEPTH
                                + liqSignal.confidence * W_LIQ
                                + tapeSignal.confidence * W_TAPE
                                + mrevSignal.confidence * W_MREV;

        // 一致性加成: 多个感知器同方向 → 提高置信度
        int bullishCount = 0, bearishCount = 0;
        if (depthSignal.score > 15) bullishCount++; else if (depthSignal.score < -15) bearishCount++;
        if (liqSignal.score > 15) bullishCount++; else if (liqSignal.score < -15) bearishCount++;
        if (tapeSignal.score > 15) bullishCount++; else if (tapeSignal.score < -15) bearishCount++;
        if (mrevSignal.score > 15) bullishCount++; else if (mrevSignal.score < -15) bearishCount++;

        int maxAgreement = Math.max(bullishCount, bearishCount);
        boolean isConfluence = maxAgreement >= 3;
        if (isConfluence) {
            fusionScore *= 1.25; // 三个以上同向，分数加成25%
            fusionConfidence = Math.min(fusionConfidence * 1.3, 0.95);
        }

        // 时区调整
        double sessionMult = SessionKiller.getLeverageMultiplier();
        fusionScore *= sessionMult;

        lastFusionScore = fusionScore;

        // === 3. 执行逻辑 ===
        if (positionSide.equals("NONE")) {
            // 无仓位 → 考虑开仓
            tryOpenPosition(fusionScore, fusionConfidence, isConfluence, m, currentBalance);
        } else {
            // 有仓位 → 管理仓位(止损/止盈/加减仓)
            managePosition(fusionScore, fusionConfidence, isConfluence, m, currentBalance);
        }

        // === 4. 日志(每5秒打一次) ===
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 5000) {
            lastLogTime = now;
            printStatus(m, fusionScore, depthSignal, liqSignal, tapeSignal, mrevSignal);
        }
    }

    private void tryOpenPosition(double fusionScore, double confidence, boolean isConfluence,
                                  MarketMicrostructure m, double currentBalance) {
        double absScore = Math.abs(fusionScore);
        if (absScore < 20) return; // 噪音区，不交易

        // 时区检查
        if (!SessionKiller.canOpenNewPosition()) return;

        // 确定方向
        String side = fusionScore > 0 ? "LONG" : "SHORT";

        // 置信度→仓位映射
        double marginPct;
        int leverage;
        if (absScore >= 80) {
            marginPct = 0.25; leverage = 20;
        } else if (absScore >= 60) {
            marginPct = 0.15; leverage = 18;
        } else if (absScore >= 40) {
            marginPct = 0.08; leverage = 15;
        } else {
            marginPct = 0.03; leverage = 10;
        }

        // 时区杠杆调整
        leverage = SessionKiller.adjustLeverage(leverage);

        double margin = currentBalance * marginPct;
        margin = riskEngine.adjustPositionSize(margin); // 连续亏损后缩仓
        margin = Math.min(margin, currentBalance * MAX_MARGIN_PCT); // 上限保护

        // 风控审批
        String rejection = riskEngine.preTradeCheck(currentBalance, margin, leverage, side, isConfluence);
        if (rejection != null) {
            return; // 被风控拒绝
        }

        // 执行开仓
        double notional = margin * leverage;
        double size = notional / m.price;

        account.openPosition(side, m.price, margin, leverage,
            String.format(Locale.US, "[APEX] %s score=%+.0f conf=%.0f%% margin=%.2f lev=%dx %s",
                symbol, fusionScore, confidence * 100, margin, leverage, isConfluence ? "CONFLUENCE" : ""));

        positionSide = side;
        entryPrice = m.price;
        positionMargin = margin;
        positionNotional = notional;
        positionLeverage = leverage;
        positionSize = size;
        peakROE = 0;
        entryTime = System.currentTimeMillis();

        riskEngine.addExposure(side, notional);
    }

    private void managePosition(double fusionScore, double confidence, boolean isConfluence,
                                 MarketMicrostructure m, double currentBalance) {
        double roe = calcROE(m.price);
        if (roe > peakROE) peakROE = roe;
        long holdTime = System.currentTimeMillis() - entryTime;

        // === 检查止损(最高优先级) ===

        // 1. 硬止损
        if (roe <= HARD_STOP_ROE) {
            closePosition(m.price, 1.0, String.format("HARD_STOP roe=%.2f%%", roe));
            return;
        }

        // 2. 信号反转止损: fusion分数大幅反转
        boolean signalReversed = (positionSide.equals("LONG") && fusionScore < -40)
                              || (positionSide.equals("SHORT") && fusionScore > 40);
        if (signalReversed && roe < 1.0) {
            closePosition(m.price, 1.0, String.format("SIGNAL_REVERSE score=%+.0f roe=%.2f%%", fusionScore, roe));
            return;
        }

        // 3. 最长持仓时间
        if (holdTime > MAX_HOLD_TIME_MS && roe < 2.0) {
            closePosition(m.price, 1.0, String.format("MAX_HOLD_TIME %ds roe=%.2f%%", holdTime / 1000, roe));
            return;
        }

        // 4. 移动止盈
        if (peakROE >= TRAILING_ACTIVATE_ROE && roe <= peakROE * (1 - TRAILING_DRAWDOWN_PCT)) {
            closePosition(m.price, 1.0,
                String.format("TRAILING_STOP peak=%.2f%% roe=%.2f%%", peakROE, roe));
            return;
        }

        // 5. 时区锁利
        if (SessionKiller.shouldLockProfit() && roe > 2.0) {
            closePosition(m.price, 1.0, String.format("SESSION_LOCK roe=%.2f%%", roe));
            return;
        }

        // === 确信度缩放(加减仓) ===
        long now = System.currentTimeMillis();
        if (now - lastScaleTime < SCALE_COOLDOWN_MS) return;

        double absScore = Math.abs(fusionScore);
        boolean scoreAligned = (positionSide.equals("LONG") && fusionScore > 0)
                            || (positionSide.equals("SHORT") && fusionScore < 0);

        // 加仓条件: 信号同向增强 + 浮盈 + 仓位未满
        if (scoreAligned && absScore > 60 && roe > SCALE_IN_ROE_MIN
            && positionMargin < currentBalance * MAX_MARGIN_PCT * 0.8) {

            double addMargin = currentBalance * 0.05; // 每次加仓5%
            addMargin = Math.min(addMargin, currentBalance * MAX_MARGIN_PCT - positionMargin);
            if (addMargin > 1.0) {
                String rejection = riskEngine.preTradeCheck(currentBalance, positionMargin + addMargin,
                    positionLeverage, positionSide, isConfluence);
                if (rejection == null) {
                    double addNotional = addMargin * positionLeverage;
                    double addSize = addNotional / m.price;

                    // 更新均价
                    double totalNotional = positionNotional + addNotional;
                    entryPrice = (entryPrice * positionNotional + m.price * addNotional) / totalNotional;
                    positionMargin += addMargin;
                    positionNotional = totalNotional;
                    positionSize += addSize;
                    peakROE = Math.max(peakROE, calcROE(m.price)); // 加仓后重算，不可低于历史峰值

                    riskEngine.addExposure(positionSide, addNotional);
                    lastScaleTime = now;

                    System.out.println(String.format(Locale.US,
                        "[APEX SCALE-IN] +%.2fU margin | total=%.2fU | roe=%.2f%%",
                        addMargin, positionMargin, roe));
                }
            }
        }

        // 减仓条件: 信号减弱/反向 + 浮盈中
        if (!scoreAligned && roe > 1.0 && positionMargin > 5.0) {
            double closeFraction = 0.3; // 减仓30%
            if (absScore > 30) closeFraction = 0.5; // 信号强反转减50%

            closePosition(m.price, closeFraction,
                String.format("SCALE-OUT score=%+.0f roe=%.2f%%", fusionScore, roe));
            lastScaleTime = now;
        }
    }

    private void closePosition(double price, double fraction, String reason) {
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        double closeMargin = positionMargin * fraction;
        double closeNotional = positionNotional * fraction;
        double closeSize = positionSize * fraction;

        double pnl;
        if (positionSide.equals("LONG")) pnl = (price - entryPrice) * closeSize;
        else pnl = (entryPrice - price) * closeSize;

        double fee = closeNotional * 0.0004; // taker fee
        double netPnl = pnl - fee;

        // 通过account执行
        if (fraction >= 0.99) {
            account.closePosition(price, "[APEX] " + reason);
        } else {
            account.closePartial(price, fraction, "[APEX] " + reason);
        }

        // 更新本地状态
        totalPnl += netPnl;
        totalTrades++;
        if (netPnl > 0) winTrades++;

        riskEngine.removeExposure(positionSide, closeNotional);
        riskEngine.recordTrade(netPnl, closeNotional, positionSide);

        if (fraction >= 0.99) {
            positionSide = "NONE";
            entryPrice = 0;
            positionMargin = 0;
            positionNotional = 0;
            positionSize = 0;
            peakROE = 0;
            positionLeverage = 0;
        } else {
            positionMargin -= closeMargin;
            positionNotional -= closeNotional;
            positionSize -= closeSize;
        }

        System.out.println(String.format(Locale.US,
            "[APEX CLOSE] %s | pnl=%+.4f | reason=%s | totalPnl=%+.4f | W/L=%d/%d",
            symbol, netPnl, reason, totalPnl, winTrades, totalTrades - winTrades));
    }

    private void emergencyClose(double price, String reason) {
        if (!positionSide.equals("NONE")) {
            closePosition(price, 1.0, "EMERGENCY: " + reason);
        }
    }

    private double calcROE(double currentPrice) {
        if (positionMargin == 0) return 0;
        double pnl;
        if (positionSide.equals("LONG")) pnl = (currentPrice - entryPrice) * positionSize;
        else pnl = (entryPrice - currentPrice) * positionSize;
        return (pnl / positionMargin) * 100.0;
    }

    private void printStatus(MarketMicrostructure m, double fusionScore,
                              SignalResult depth, SignalResult liq, SignalResult tape, SignalResult mrev) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "\n[APEX %s] FusionScore=%+.1f | ", symbol, fusionScore));
        if (!positionSide.equals("NONE")) {
            double roe = calcROE(m.price);
            sb.append(String.format(Locale.US, "%s margin=%.2f roe=%+.2f%% peak=%+.2f%%",
                positionSide, positionMargin, roe, peakROE));
        } else {
            sb.append("FLAT");
        }
        sb.append(String.format(Locale.US, " | pnl=%+.4f W/L=%d/%d", totalPnl, winTrades, totalTrades - winTrades));
        sb.append("\n  ").append(depth);
        sb.append("\n  ").append(liq);
        sb.append("\n  ").append(tape);
        sb.append("\n  ").append(mrev);
        System.out.println(sb);
    }

    // === Getters ===
    public double getTotalPnl() { return totalPnl; }
    public int getTotalTrades() { return totalTrades; }
    public int getWinTrades() { return winTrades; }
    public String getPositionSide() { return positionSide; }
    public double getLastFusionScore() { return lastFusionScore; }
}
