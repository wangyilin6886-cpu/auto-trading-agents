package com.trading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 线程安全的技术指标计算器
 * 支持：RSI、EMA、布林带、成交量涌浪、突破检测
 */
public class IndicatorCalculator {
    private static final int MAX_DATA_SIZE = 200;
    private static final List<Double> prices = Collections.synchronizedList(new ArrayList<>());
    private static final List<Double> volumes = Collections.synchronizedList(new ArrayList<>());

    public static synchronized void addData(double price, double volume) {
        if (prices.size() >= MAX_DATA_SIZE) {
            prices.remove(0);
            volumes.remove(0);
        }
        prices.add(price);
        volumes.add(volume);
    }

    public static synchronized boolean isReady() { return prices.size() >= 30; }
    public static synchronized int getCurrentDataCount() { return prices.size(); }

    // ==========================================
    // RSI (相对强弱指标)
    // ==========================================
    public static synchronized double getRSI(int period) {
        if (prices.size() <= period) return 50.0;
        double gains = 0.0, losses = 0.0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) gains += change;
            else losses -= change;
        }
        double avgGain = gains / period;
        double avgLoss = losses / period;
        if (avgLoss == 0) return 100.0;
        return 100.0 - (100.0 / (1 + (avgGain / avgLoss)));
    }

    // ==========================================
    // EMA (指数移动平均线)
    // ==========================================
    public static synchronized double getEMA(int period) {
        if (prices.size() < period) return prices.isEmpty() ? 0 : prices.get(prices.size() - 1);
        double multiplier = 2.0 / (period + 1);
        double ema = 0;
        // 用前 period 个价格的 SMA 作为 EMA 的种子
        for (int i = 0; i < period; i++) ema += prices.get(i);
        ema /= period;
        for (int i = period; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }

    // ==========================================
    // 趋势判断：价格在 EMA 之上=多头，之下=空头
    // ==========================================
    public static synchronized String getTrend() {
        if (prices.size() < 30) return "NEUTRAL";
        double ema20 = getEMA(20);
        double ema7 = getEMA(7);
        double current = prices.get(prices.size() - 1);

        // EMA7 > EMA20 且价格在 EMA7 上方 = 强多头
        if (ema7 > ema20 && current > ema7) return "BULL";
        // EMA7 < EMA20 且价格在 EMA7 下方 = 强空头
        if (ema7 < ema20 && current < ema7) return "BEAR";
        return "NEUTRAL";
    }

    // ==========================================
    // 突破检测：价格是否突破近期高/低点
    // ==========================================
    public static synchronized String checkBreakout(int lookback) {
        if (prices.size() < lookback + 1) return "NONE";

        double current = prices.get(prices.size() - 1);
        double recentHigh = Double.MIN_VALUE;
        double recentLow = Double.MAX_VALUE;

        // 计算前 lookback 根K线的高低点（不含当前）
        for (int i = prices.size() - lookback - 1; i < prices.size() - 1; i++) {
            if (i < 0) continue;
            recentHigh = Math.max(recentHigh, prices.get(i));
            recentLow = Math.min(recentLow, prices.get(i));
        }

        if (current > recentHigh) return "BREAK_UP";
        if (current < recentLow) return "BREAK_DOWN";
        return "NONE";
    }

    // ==========================================
    // 近期高低点价格（用于陷阱触发价）
    // ==========================================
    public static synchronized double getRecentHigh(int lookback) {
        if (prices.size() < 2) return prices.isEmpty() ? 0 : prices.get(prices.size() - 1);
        double high = Double.MIN_VALUE;
        int start = Math.max(0, prices.size() - lookback);
        for (int i = start; i < prices.size(); i++) high = Math.max(high, prices.get(i));
        return high;
    }

    public static synchronized double getRecentLow(int lookback) {
        if (prices.size() < 2) return prices.isEmpty() ? 0 : prices.get(prices.size() - 1);
        double low = Double.MAX_VALUE;
        int start = Math.max(0, prices.size() - lookback);
        for (int i = start; i < prices.size(); i++) low = Math.min(low, prices.get(i));
        return low;
    }

    // ==========================================
    // SMA
    // ==========================================
    public static synchronized double getSma(int period) {
        if (prices.size() < period) return 0.0;
        double sum = 0.0;
        for (int i = prices.size() - period; i < prices.size(); i++) sum += prices.get(i);
        return sum / period;
    }

    // ==========================================
    // 布林带
    // ==========================================
    public static synchronized double getBollingerUpper(int period) {
        if (prices.size() < period) return 0.0;
        double sma = getSma(period);
        double stdDev = getBollingerStdDev(period, sma);
        return sma + (2 * stdDev);
    }

    public static synchronized double getBollingerLower(int period) {
        if (prices.size() < period) return 0.0;
        double sma = getSma(period);
        double stdDev = getBollingerStdDev(period, sma);
        return sma - (2 * stdDev);
    }

    public static synchronized double getBollingerMiddle(int period) {
        return getSma(period);
    }

    /**
     * 布林带宽度百分比 = (上轨 - 下轨) / 中轨 * 100
     * 用于检测波动率压缩（Squeeze）
     */
    public static synchronized double getBollingerBandwidth(int period) {
        if (prices.size() < period) return 999.0; // 数据不够返回大值，不触发squeeze
        double sma = getSma(period);
        if (sma == 0) return 999.0;
        double stdDev = getBollingerStdDev(period, sma);
        double upper = sma + 2 * stdDev;
        double lower = sma - 2 * stdDev;
        return (upper - lower) / sma * 100.0;
    }

    private static double getBollingerStdDev(int period, double sma) {
        double sumOfSq = 0.0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            sumOfSq += Math.pow(prices.get(i) - sma, 2);
        }
        return Math.sqrt(sumOfSq / period);
    }

    /**
     * 获取价格列表的副本（用于高频引擎的独立分析）
     */
    public static synchronized List<Double> getPriceSnapshot() {
        return new ArrayList<>(prices);
    }

    public static synchronized double[] getRecentPrices(int count) {
        int start = Math.max(0, prices.size() - count);
        double[] result = new double[prices.size() - start];
        for (int i = 0; i < result.length; i++) {
            result[i] = prices.get(start + i);
        }
        return result;
    }

    // ==========================================
    // 成交量涌浪倍数
    // ==========================================
    public static synchronized double getVolumeSurgeMultiplier() {
        if (volumes.size() < 6) return 1.0;
        double currentVol = volumes.get(volumes.size() - 1);
        double sumVol = 0.0;
        for (int i = volumes.size() - 6; i < volumes.size() - 1; i++) sumVol += volumes.get(i);
        double avgVol5 = sumVol / 5.0;
        return avgVol5 == 0 ? 1.0 : (currentVol / avgVol5);
    }

    // ==========================================
    // 当前价格
    // ==========================================
    public static synchronized double getLastPrice() {
        return prices.isEmpty() ? 0 : prices.get(prices.size() - 1);
    }
}
