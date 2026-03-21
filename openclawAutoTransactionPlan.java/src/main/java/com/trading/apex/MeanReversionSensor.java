package com.trading.apex;

/**
 * 感知器D: 均值回归弹弓 (Mean Reversion Snapper)
 *
 * 原理: 价格短期偏离VWAP过多时大概率回归
 *       VWAP = 成交量加权均价 = "公允价值"
 *
 * 信号:
 *   priceVsVwap > +0.25% 且 rsi14s > 75 → 超买回归(做空)
 *   priceVsVwap < -0.25% 且 rsi14s < 25 → 超卖回归(做多)
 *
 * 过滤:
 *   爆仓瀑布中 → 不做(均值回归失效)
 *   极端波动率 → 不做(可能是趋势不是过度)
 */
public class MeanReversionSensor {

    private static final double VWAP_DEVIATION_MIN = 0.20;   // 最小偏离%
    private static final double VWAP_DEVIATION_STRONG = 0.40; // 强信号偏离%
    private static final double RSI_OVERBOUGHT = 72;
    private static final double RSI_OVERSOLD = 28;
    private static final double RSI_EXTREME_OB = 85;
    private static final double RSI_EXTREME_OS = 15;
    private static final double MAX_VOLATILITY = 0.8;        // 波动率过高不做

    public SignalResult evaluate(MarketMicrostructure m) {
        if (m.price == 0 || m.vwap1m == 0) return SignalResult.none("MREV");

        double deviation = m.priceVsVwap;    // 已经是百分比
        double rsi = m.rsi14s;
        double vol1m = m.volatility1m;

        // 过滤: 爆仓瀑布中不做均值回归
        if (m.liqCascadeActive) {
            return SignalResult.none("MREV");
        }

        // 过滤: 波动率过高(可能是趋势启动，不是过度偏离)
        if (vol1m > MAX_VOLATILITY) {
            return SignalResult.none("MREV");
        }

        // 做空回归: 价格远高于VWAP + RSI超买
        if (deviation > VWAP_DEVIATION_MIN && rsi > RSI_OVERBOUGHT) {
            return buildMeanRevSignal(false, deviation, rsi, m);
        }

        // 做多回归: 价格远低于VWAP + RSI超卖
        if (deviation < -VWAP_DEVIATION_MIN && rsi < RSI_OVERSOLD) {
            return buildMeanRevSignal(true, Math.abs(deviation), rsi, m);
        }

        return SignalResult.none("MREV");
    }

    private SignalResult buildMeanRevSignal(boolean bullish, double absDeviation,
                                             double rsi, MarketMicrostructure m) {
        double score = 0;
        double confidence = 0;
        StringBuilder reason = new StringBuilder();

        // 基础分: 偏离程度
        score = 25 + (absDeviation - VWAP_DEVIATION_MIN) / (1.0 - VWAP_DEVIATION_MIN) * 40;
        confidence = 0.40;
        reason.append(String.format("vwapDev=%+.2f%%", bullish ? -absDeviation : absDeviation));

        // 强偏离加分
        if (absDeviation > VWAP_DEVIATION_STRONG) {
            score += 15;
            confidence += 0.10;
            reason.append(" STRONG_DEV");
        }

        // RSI确认加分
        if (bullish) {
            if (rsi < RSI_EXTREME_OS) {
                score += 20;
                confidence += 0.15;
                reason.append(String.format(" RSI=%.0f EXTREME", rsi));
            } else {
                score += 10;
                confidence += 0.05;
                reason.append(String.format(" RSI=%.0f", rsi));
            }
        } else {
            if (rsi > RSI_EXTREME_OB) {
                score += 20;
                confidence += 0.15;
                reason.append(String.format(" RSI=%.0f EXTREME", rsi));
            } else {
                score += 10;
                confidence += 0.05;
                reason.append(String.format(" RSI=%.0f", rsi));
            }
        }

        // 订单簿支持回归方向 → 加分(阈值与DepthImbalanceSensor对齐)
        if (bullish ? (m.depthImbalance > 0.35) : (m.depthImbalance < -0.35)) {
            score += 10;
            confidence += 0.10;
            reason.append(" depthSupport");
        }

        // 动量已经减弱(不再加速远离) → 加分(回归更可能)
        double momAbs = Math.abs(m.momentum5s);
        if (momAbs < 0.05) {
            score += 8;
            confidence += 0.05;
            reason.append(" momFading");
        }

        score = Math.min(85, score); // 均值回归信号上限略低(不如趋势信号确定)
        if (!bullish) score = -score;

        return new SignalResult(score, Math.min(confidence, 0.85), "MREV", reason.toString());
    }
}
