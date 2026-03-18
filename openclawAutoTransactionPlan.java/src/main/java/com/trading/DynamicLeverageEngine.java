package com.trading;

import java.util.Locale;

/**
 * 动态杠杆引擎 — 根据信号强度自动调整3x~25x杠杆。
 * 信号越强杠杆越高，风险越高杠杆越低。
 */
public class DynamicLeverageEngine {

    private static final int MIN_LEVERAGE = 3;
    private static final int MAX_LEVERAGE = 25;
    private static final int MANIPULATION_CAP = 5; // 检测到操纵时强制限制

    /**
     * 计算动态杠杆。
     *
     * @param side         交易方向 "LONG" / "SHORT"
     * @param rsi          当前RSI值
     * @param volSurge     成交量倍数（相对5周期均量）
     * @param macroTrend   宏观趋势 "BULL_TREND" / "BEAR_TREND" / "RANGING"
     * @param riskFlag     风控标记 含"HIGH_MANIPULATION"则限制杠杆
     * @return             3~25之间的杠杆倍数
     */
    public static int calculate(String side, double rsi, double volSurge, String macroTrend, String riskFlag) {
        int leverage = MIN_LEVERAGE;

        // RSI极端度加成
        if (rsi < 20 || rsi > 80) {
            leverage += 8;  // 极端超买超卖，反转概率最高
        } else if (rsi < 30 || rsi > 70) {
            leverage += 4;  // 较强信号
        }

        // 成交量加成
        if (volSurge > 3.0) {
            leverage += 6;  // 巨鲸进场
        } else if (volSurge > 2.0) {
            leverage += 3;  // 明显放量
        }

        // 宏观顺势加成（方向一致时额外加杠杆）
        if (side.equals("LONG") && macroTrend.contains("BULL_TREND")) {
            leverage += 3;
        } else if (side.equals("SHORT") && macroTrend.contains("BEAR_TREND")) {
            leverage += 3;
        }

        // 风控限制：检测到操纵时强制压低
        if (riskFlag != null && riskFlag.contains("HIGH_MANIPULATION")) {
            leverage = Math.min(leverage, MANIPULATION_CAP);
        }

        // 硬性边界
        leverage = Math.max(MIN_LEVERAGE, Math.min(leverage, MAX_LEVERAGE));

        System.out.println("🎚️ [动态杠杆] " + leverage + "x | RSI=" + fmt(rsi)
                + " VolSurge=" + fmt(volSurge) + "x Macro=" + macroTrend);

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
