package com.trading;

import java.util.ArrayList;
import java.util.List;

public class IndicatorCalculator {
    private static final int MAX_DATA_SIZE = 50;
    private static final List<Double> prices = new ArrayList<>();
    private static final List<Double> volumes = new ArrayList<>();
    
    // 🌟 新增：15分钟宏观趋势
    private static String macroTrend = "UNKNOWN";

    public static void setMacroTrend(String trend) { macroTrend = trend; }
    public static String getMacroTrend() { return macroTrend; }

    public static void addData(double price, double volume) {
        if (prices.size() >= MAX_DATA_SIZE) {
            prices.remove(0);
            volumes.remove(0);
        }
        prices.add(price);
        volumes.add(volume);
    }

    public static boolean isReady() { return prices.size() >= 20; }
    public static int getCurrentDataCount() { return prices.size(); }

    public static double getSma(int period) {
        if (prices.size() < period) return 0.0;
        double sum = 0.0;
        for (int i = prices.size() - period; i < prices.size(); i++) sum += prices.get(i);
        return sum / period;
    }

    public static double getBollingerUpper(int period) {
        if (prices.size() < period) return 0.0;
        double sma = getSma(period);
        double sumOfSq = 0.0;
        for (int i = prices.size() - period; i < prices.size(); i++) sumOfSq += Math.pow(prices.get(i) - sma, 2);
        double stdDev = Math.sqrt(sumOfSq / period);
        return sma + (2 * stdDev);
    }

    public static double getRSI(int period) {
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

    public static double getVolumeSurgeMultiplier() {
        if (volumes.size() < 6) return 1.0;
        double currentVol = volumes.get(volumes.size() - 1);
        double sumVol = 0.0;
        for (int i = volumes.size() - 6; i < volumes.size() - 1; i++) sumVol += volumes.get(i);
        double avgVol5 = sumVol / 5.0;
        return avgVol5 == 0 ? 1.0 : (currentVol / avgVol5);
    }
}