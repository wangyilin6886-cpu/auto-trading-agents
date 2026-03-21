package com.trading.apex;

/**
 * 感知器C: 大单动量追踪 (Tape Reading Momentum)
 *
 * 原理: 逐笔成交中大单方向 = 机构/鲸鱼方向
 *       连续大单同向出现 → 价格将沿该方向运动
 *
 * 信号:
 *   10秒内大单买 ≥ 5笔 且 takerDelta30s 偏高 → 看多
 *   10秒内大单卖 ≥ 5笔 且 takerDelta30s 偏低 → 看空
 */
public class TapeReadingSensor {

    private static final int LARGE_ORDER_MIN_COUNT = 4;     // 大单最少笔数
    private static final int STRONG_LARGE_COUNT = 7;        // 强信号笔数
    private static final double DELTA_PERCENTILE_THRESHOLD = 0.6; // delta相对强度

    // 历史delta分布追踪(简化版：滚动均值+标准差)
    private double deltaEma = 0;
    private double deltaVar = 0;
    private int samples = 0;
    private static final double EMA_ALPHA = 0.02; // 慢速EMA跟踪

    public SignalResult evaluate(MarketMicrostructure m) {
        if (m.price == 0) return SignalResult.none("TAPE");

        int largeBuy = m.largeBuyCount10s;
        int largeSell = m.largeSellCount10s;
        double delta30 = m.takerDelta30s;

        // 更新delta分布
        if (samples > 0) {
            deltaEma = deltaEma * (1 - EMA_ALPHA) + delta30 * EMA_ALPHA;
            double diff = delta30 - deltaEma;
            deltaVar = deltaVar * (1 - EMA_ALPHA) + diff * diff * EMA_ALPHA;
        } else {
            deltaEma = delta30;
            deltaVar = delta30 * delta30 * 0.01;
        }
        samples++;

        double deltaStd = Math.sqrt(Math.max(deltaVar, 1));

        // 大单买方主导
        if (largeBuy >= LARGE_ORDER_MIN_COUNT && largeBuy > largeSell * 2) {
            return buildSignal(true, largeBuy, largeSell, delta30, deltaStd, m);
        }

        // 大单卖方主导
        if (largeSell >= LARGE_ORDER_MIN_COUNT && largeSell > largeBuy * 2) {
            return buildSignal(false, largeBuy, largeSell, delta30, deltaStd, m);
        }

        // 没有明确的大单方向，但delta30极端
        double zScore = deltaStd > 0 ? (delta30 - deltaEma) / deltaStd : 0;
        if (Math.abs(zScore) > 2.5) {
            boolean bullish = zScore > 0;
            double score = zScore * 15; // ±37.5 at zScore=2.5
            score = Math.max(-60, Math.min(60, score));
            return new SignalResult(score, 0.35, "TAPE",
                "extreme delta zScore=" + String.format("%.1f", zScore) + " delta30=" + fmt(delta30));
        }

        return SignalResult.none("TAPE");
    }

    private SignalResult buildSignal(boolean bullish, int largeBuy, int largeSell,
                                     double delta30, double deltaStd, MarketMicrostructure m) {
        double score = 0;
        double confidence = 0;
        StringBuilder reason = new StringBuilder();

        int dominantCount = bullish ? largeBuy : largeSell;

        // 基础分
        score = 35 + (dominantCount - LARGE_ORDER_MIN_COUNT) * 8;
        confidence = 0.45;
        reason.append(String.format("largeBuy=%d sell=%d", largeBuy, largeSell));

        // 强信号加分
        if (dominantCount >= STRONG_LARGE_COUNT) {
            score += 20;
            confidence += 0.15;
            reason.append(" STRONG");
        }

        // delta30方向确认
        boolean deltaConfirm = bullish ? (delta30 > 0) : (delta30 < 0);
        if (deltaConfirm) {
            double zScore = deltaStd > 0 ? Math.abs(delta30 - deltaEma) / deltaStd : 0;
            score += Math.min(zScore * 5, 15);
            confidence += 0.10;
            reason.append(String.format(" delta30=%s(z=%.1f)", fmt(delta30), zScore));
        } else {
            // 大单方向和delta不一致 → 降分
            score *= 0.5;
            confidence *= 0.6;
            reason.append(" deltaConflict");
        }

        // 深度方向确认
        if (bullish ? (m.depthImbalance > 0.15) : (m.depthImbalance < -0.15)) {
            score += 8;
            confidence += 0.05;
            reason.append(" depthOK");
        }

        // 成交频率加分(每秒成交笔数多=活跃)
        if (m.tradeCount1s > 20) {
            score += 5;
            reason.append(" active");
        }

        score = Math.min(90, score);
        if (!bullish) score = -score;

        return new SignalResult(score, Math.min(confidence, 0.90), "TAPE", reason.toString());
    }

    private String fmt(double v) { return String.format("%.0f", v); }
}
