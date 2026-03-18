package com.trading;

import java.util.Locale;

/**
 * 7 维信号引擎 — 从 MarketDataHub 的原始数据计算综合评分。
 *
 * 维度：
 *   1. OBI  (Order Book Imbalance)  — 订单簿买卖失衡
 *   2. CVD  (Cumulative Volume Delta) — 累积量差
 *   3. RSI  (Relative Strength Index) — 相对强弱
 *   4. LCI  (Liquidation Cascade Index) — 爆仓级联指标
 *   5. BB   (Bollinger Band Position)  — 布林带位置
 *   6. FR   (Funding Rate Sentiment)   — 资金费率情绪
 *   7. VOL  (Volume Surge)             — 成交量异常
 *
 * 输出：compositeScore ∈ [-1, +1]
 *   > +0.6 → 强多信号
 *   < -0.6 → 强空信号
 *   [-0.3, +0.3] → 中性区
 */
public class SignalEngine {

    private final MarketDataHub hub;

    // 缓存最近一次计算结果
    private volatile double lastOBI = 0;
    private volatile double lastCVD = 0;
    private volatile double lastRSI = 50;
    private volatile double lastLCI = 0;
    private volatile double lastBB = 0;
    private volatile double lastFR = 0;
    private volatile double lastVOL = 0;
    private volatile double lastComposite = 0;
    private volatile long lastCalcTime = 0;

    // CVD 累积
    private double cvdAccumulator = 0;
    private long cvdLastReset = System.currentTimeMillis();
    private static final long CVD_WINDOW_MS = 5 * 60 * 1000; // 5 分钟窗口

    public SignalEngine(MarketDataHub hub) {
        this.hub = hub;
    }

    /**
     * 计算所有 7 维信号并返回综合评分。
     * 可频繁调用（内部无重计算保护，由调用方控制频率）。
     */
    public double calculate() {
        lastOBI = calcOBI();
        lastCVD = calcCVD();
        lastRSI = calcRSI(14);
        lastLCI = calcLCI();
        lastBB = calcBBPosition();
        lastFR = calcFundingRateSentiment();
        lastVOL = calcVolumeSurge();

        lastComposite = Config.WEIGHT_OBI * lastOBI
                + Config.WEIGHT_CVD * lastCVD
                + Config.WEIGHT_RSI * rsiToSignal(lastRSI)
                + Config.WEIGHT_LCI * lastLCI
                + Config.WEIGHT_BB * lastBB
                + Config.WEIGHT_FUNDING * lastFR
                + Config.WEIGHT_VOL * lastVOL;

        // clamp to [-1, 1]
        lastComposite = Math.max(-1.0, Math.min(1.0, lastComposite));
        lastCalcTime = System.currentTimeMillis();

        return lastComposite;
    }

    // ==================== 维度 1: OBI ====================

    /**
     * 订单簿失衡：(bidVolume - askVolume) / (bidVolume + askVolume)
     * 输出 ∈ [-1, +1]。正值=买压大，负值=卖压大。
     */
    private double calcOBI() {
        MarketDataHub.OrderBookSnapshot ob = hub.getOrderBook();
        if (ob.timestamp == 0) return 0;

        double bidVol = 0, askVol = 0;
        for (int i = 0; i < 10; i++) {
            bidVol += ob.bidQtys[i];
            askVol += ob.askQtys[i];
        }

        double total = bidVol + askVol;
        if (total < 1e-9) return 0;

        return (bidVol - askVol) / total;
    }

    // ==================== 维度 2: CVD ====================

    /**
     * 累积量差（5 分钟窗口）：
     * 主动买（taker buy）减去主动卖（taker sell）的累积量。
     * 归一化到 [-1, +1]。
     */
    private double calcCVD() {
        long now = System.currentTimeMillis();

        // 定期重置
        if (now - cvdLastReset > CVD_WINDOW_MS) {
            cvdAccumulator = 0;
            cvdLastReset = now;
        }

        MarketDataHub.AggTrade[] trades = hub.getRecentTrades(CVD_WINDOW_MS);
        if (trades.length == 0) return 0;

        double buyVol = 0, sellVol = 0;
        for (MarketDataHub.AggTrade t : trades) {
            if (t.isBuyerMaker) {
                sellVol += t.qty; // buyer is maker → taker is seller
            } else {
                buyVol += t.qty;  // seller is maker → taker is buyer
            }
        }

        double total = buyVol + sellVol;
        if (total < 1e-9) return 0;

        // 归一化
        return Math.max(-1.0, Math.min(1.0, (buyVol - sellVol) / total * 2));
    }

    // ==================== 维度 3: RSI ====================

    /**
     * RSI(14) 基于最近 K 线收盘价。
     */
    private double calcRSI(int period) {
        double[][] klines = hub.getRecentKlines(period + 1);
        if (klines.length < period + 1) return 50; // 数据不足返回中性

        double gainSum = 0, lossSum = 0;
        for (int i = 1; i < klines.length; i++) {
            double change = klines[i][0] - klines[i - 1][0]; // close - prev close
            if (change > 0) gainSum += change;
            else lossSum += Math.abs(change);
        }

        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;

        if (avgLoss < 1e-12) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /**
     * RSI → 信号：
     *   RSI < 30 → +1 (超卖=做多信号)
     *   RSI > 70 → -1 (超买=做空信号)
     *   RSI 50   →  0 (中性)
     */
    private double rsiToSignal(double rsi) {
        if (rsi <= 30) return 1.0;
        if (rsi >= 70) return -1.0;
        if (rsi < 50) return (50 - rsi) / 20.0;  // 30-50 → 1.0-0.0
        return -(rsi - 50) / 20.0;                // 50-70 → 0.0-(-1.0)
    }

    // ==================== 维度 4: LCI ====================

    /**
     * 爆仓级联指标（60 秒窗口）：
     * 空头被爆量 - 多头被爆量，归一化。
     * 正值=空头被爆（做多信号），负值=多头被爆（做空信号）。
     */
    private double calcLCI() {
        MarketDataHub.LiquidationEvent[] events = hub.getRecentLiquidations(60_000);
        if (events.length == 0) return 0;

        double shortLiqVol = 0, longLiqVol = 0;
        for (MarketDataHub.LiquidationEvent e : events) {
            if ("BUY".equals(e.side)) {
                shortLiqVol += e.qty * e.price; // 空头被爆 → 被迫买入
            } else {
                longLiqVol += e.qty * e.price;  // 多头被爆 → 被迫卖出
            }
        }

        double total = shortLiqVol + longLiqVol;
        if (total < 1e-9) return 0;

        return Math.max(-1.0, Math.min(1.0, (shortLiqVol - longLiqVol) / total));
    }

    // ==================== 维度 5: Bollinger Band Position ====================

    /**
     * 当前价格在布林带中的位置：
     *   +1 = 触及上轨（做空信号）
     *   -1 = 触及下轨（做多信号）
     *    0 = 中轨
     * 注意：信号取反，触及上轨是做空信号。
     */
    private double calcBBPosition() {
        double[][] klines = hub.getRecentKlines(20);
        if (klines.length < 20) return 0;

        // 20 周期均值和标准差
        double sum = 0;
        for (double[] k : klines) sum += k[0];
        double ma = sum / klines.length;

        double variance = 0;
        for (double[] k : klines) variance += (k[0] - ma) * (k[0] - ma);
        double std = Math.sqrt(variance / klines.length);

        if (std < 1e-9) return 0;

        double price = hub.getLastPrice();
        double upper = ma + 2 * std;
        double lower = ma - 2 * std;

        // 归一化到 [-1, +1]，然后取反（上轨=空，下轨=多）
        double position = (price - ma) / (2 * std);
        return -Math.max(-1.0, Math.min(1.0, position)); // 取反
    }

    // ==================== 维度 6: Funding Rate ====================

    /**
     * 资金费率情绪：
     *   正费率 → 多头付费给空头 → 市场过热 → 做空信号 (负值)
     *   负费率 → 空头付费给多头 → 市场恐慌 → 做多信号 (正值)
     * 归一化：费率通常在 -0.03% ~ +0.03%。
     */
    private double calcFundingRateSentiment() {
        double fr = hub.getFundingRate();
        if (Math.abs(fr) < 1e-9) return 0;

        // 费率 0.03% → 信号 ±1.0
        double signal = -fr / 0.0003;
        return Math.max(-1.0, Math.min(1.0, signal));
    }

    // ==================== 维度 7: Volume Surge ====================

    /**
     * 成交量异常：最近 1 分钟成交量 vs 最近 20 分钟均量。
     * > 2x → 异常高（做趋势方向信号）
     * < 0.5x → 异常低（中性）
     */
    private double calcVolumeSurge() {
        double[][] klines = hub.getRecentKlines(20);
        if (klines.length < 5) return 0;

        // 最近一根 K 线的量
        double recentVol = klines[klines.length - 1][3];

        // 前面 K 线均量（排除最后一根）
        double sumVol = 0;
        for (int i = 0; i < klines.length - 1; i++) {
            sumVol += klines[i][3];
        }
        double avgVol = sumVol / (klines.length - 1);

        if (avgVol < 1e-9) return 0;

        double surge = recentVol / avgVol;

        // surge > 2 → 信号 +1（需结合方向判断，这里只返回量级）
        // 量的方向由 CVD 决定
        if (surge < 1.0) return 0;
        return Math.min(1.0, (surge - 1.0) / 2.0); // 1x→0, 3x→1
    }

    // ==================== 快通道专用：检查 OBI 快信号 ====================

    /**
     * 检查 OBI 是否触发快通道。
     * @return "LONG" / "SHORT" / null
     */
    public String checkFastOBI() {
        double obi = calcOBI();
        if (obi >= Config.FAST_OBI_THRESHOLD) return "LONG";
        if (obi <= -Config.FAST_OBI_THRESHOLD) return "SHORT";
        return null;
    }

    // ==================== 获取缓存值 ====================

    public double getLastOBI() { return lastOBI; }
    public double getLastCVD() { return lastCVD; }
    public double getLastRSI() { return lastRSI; }
    public double getLastLCI() { return lastLCI; }
    public double getLastBB() { return lastBB; }
    public double getLastFR() { return lastFR; }
    public double getLastVOL() { return lastVOL; }
    public double getLastComposite() { return lastComposite; }

    /** 信号摘要 */
    public String summary() {
        return String.format(Locale.US,
                "OBI=%.2f CVD=%.2f RSI=%.1f LCI=%.2f BB=%.2f FR=%.2f VOL=%.2f → Composite=%.3f",
                lastOBI, lastCVD, lastRSI, lastLCI, lastBB, lastFR, lastVOL, lastComposite);
    }
}
