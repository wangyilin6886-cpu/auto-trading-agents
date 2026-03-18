package com.trading;

import java.util.Locale;

/**
 * 动态杠杆引擎 — 根据 7 维信号强度自动调整 3x~25x 杠杆。
 *
 * 输入：
 *   - RSI 极端度
 *   - 成交量异常
 *   - 宏观顺势度
 *   - SignalEngine 综合评分
 *   - RRO 风控标记
 *
 * 规则：
 *   信号越强 → 杠杆越高
 *   HIGH_MANIPULATION → 杠杆硬顶 5x
 *   综合评分与方向不一致 → 杠杆减半
 */
public class DynamicLeverageEngine {

    private static final int MIN_LEVERAGE = 3;
    private static final int MAX_LEVERAGE = 25;
    private static final int MANIPULATION_CAP = 5;

    /**
     * 基础动态杠杆计算（兼容旧接口）。
     */
    public static int calculate(String side, double rsi, double volSurge, String macroTrend, String riskFlag) {
        return calculate(side, rsi, volSurge, macroTrend, riskFlag, 0.0);
    }

    /**
     * 增强动态杠杆计算 — 增加 compositeScore 输入。
     *
     * @param side            交易方向 "LONG" / "SHORT"
     * @param rsi             当前RSI值
     * @param volSurge        成交量倍数
     * @param macroTrend      宏观趋势
     * @param riskFlag        风控标记
     * @param compositeScore  SignalEngine 综合评分 [-1, +1]
     * @return                3~25之间的杠杆倍数
     */
    public static int calculate(String side, double rsi, double volSurge, String macroTrend, String riskFlag, double compositeScore) {
        int leverage = MIN_LEVERAGE;

        // RSI 极端度加成
        if (rsi < 20 || rsi > 80) {
            leverage += 8;
        } else if (rsi < 30 || rsi > 70) {
            leverage += 4;
        }

        // 成交量加成
        if (volSurge > 3.0) {
            leverage += 6;
        } else if (volSurge > 2.0) {
            leverage += 3;
        }

        // 宏观顺势加成
        if (side.equals("LONG") && macroTrend.contains("BULL_TREND")) {
            leverage += 3;
        } else if (side.equals("SHORT") && macroTrend.contains("BEAR_TREND")) {
            leverage += 3;
        }

        // 综合评分加成/减仓
        if (compositeScore != 0) {
            boolean signalAligned = (side.equals("LONG") && compositeScore > 0)
                    || (side.equals("SHORT") && compositeScore < 0);

            if (signalAligned) {
                // 信号方向一致：根据信号强度加杠杆
                leverage += (int) (Math.abs(compositeScore) * 4); // 最多 +4
            } else {
                // 信号方向矛盾：杠杆减半
                leverage = leverage / 2;
            }
        }

        // 风控限制
        if (riskFlag != null && riskFlag.contains("HIGH_MANIPULATION")) {
            leverage = Math.min(leverage, MANIPULATION_CAP);
        }

        // 硬性边界
        leverage = Math.max(MIN_LEVERAGE, Math.min(leverage, MAX_LEVERAGE));

        System.out.println("[DynamicLeverage] " + leverage + "x | RSI=" + fmt(rsi)
                + " Vol=" + fmt(volSurge) + "x Macro=" + macroTrend
                + " Composite=" + fmt(compositeScore));

        return leverage;
    }

    /**
     * 爆仓猎杀专用：固定25x极速杠杆。
     */
    public static int getLiquidationHuntLeverage() {
        return MAX_LEVERAGE;
    }

    private static String fmt(double v) { return String.format(Locale.US, "%.2f", v); }
}
