package com.trading;

import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标计算器 — 线程安全版。
 *
 * 修复：所有 prices/volumes 操作用 synchronized 保护。
 */
public class IndicatorCalculator {
    private static final int MAX_DATA_SIZE = 50;
    private static final List<Double> prices = new ArrayList<>();
    private static final List<Double> volumes = new ArrayList<>();
    private static final List<Double> highs = new ArrayList<>();
    private static final List<Double> lows = new ArrayList<>();

    private static String macroTrend = "UNKNOWN";

    public static void setMacroTrend(String trend) { macroTrend = trend; }
    public static String getMacroTrend() { return macroTrend; }

    public static synchronized void addData(double price, double volume) {
        if (prices.size() >= MAX_DATA_SIZE) {
            prices.remove(0);
            volumes.remove(0);
            highs.remove(0);
            lows.remove(0);
        }
        prices.add(price);
        volumes.add(volume);
        // 1分钟K线里 high/low 近似用价格本身（真实值需从K线数据中取）
        highs.add(price);
        lows.add(price);
    }

    /**
     * 添加完整K线数据（含最高最低价）。
     */
    public static synchronized void addFullData(double close, double high, double low, double volume) {
        if (prices.size() >= MAX_DATA_SIZE) {
            prices.remove(0);
            volumes.remove(0);
            highs.remove(0);
            lows.remove(0);
        }
        prices.add(close);
        volumes.add(volume);
        highs.add(high);
        lows.add(low);
    }

    public static synchronized boolean isReady() { return prices.size() >= 20; }
    public static synchronized int getCurrentDataCount() { return prices.size(); }

    public static synchronized double getSma(int period) {
        if (prices.size() < period) return 0.0;
        double sum = 0.0;
        for (int i = prices.size() - period; i < prices.size(); i++) sum += prices.get(i);
        return sum / period;
    }

    public static synchronized double getBollingerUpper(int period) {
        if (prices.size() < period) return 0.0;
        double sma = getSma(period);
        double sumOfSq = 0.0;
        for (int i = prices.size() - period; i < prices.size(); i++) sumOfSq += Math.pow(prices.get(i) - sma, 2);
        double stdDev = Math.sqrt(sumOfSq / period);
        return sma + (2 * stdDev);
    }

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

    public static synchronized double getVolumeSurgeMultiplier() {
        if (volumes.size() < 6) return 1.0;
        double currentVol = volumes.get(volumes.size() - 1);
        double sumVol = 0.0;
        for (int i = volumes.size() - 6; i < volumes.size() - 1; i++) sumVol += volumes.get(i);
        double avgVol5 = sumVol / 5.0;
        return avgVol5 == 0 ? 1.0 : (currentVol / avgVol5);
    }

    /**
     * ATR (Average True Range) — 衡量波动性，供动态杠杆参考。
     */
    public static synchronized double getATR(int period) {
        if (prices.size() < period + 1 || highs.size() < period + 1) return 0.0;

        double sumTR = 0.0;
        for (int i = prices.size() - period; i < prices.size(); i++) {
            double high = highs.get(i);
            double low = lows.get(i);
            double prevClose = prices.get(i - 1);

            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sumTR += tr;
        }
        return sumTR / period;
    }
}
