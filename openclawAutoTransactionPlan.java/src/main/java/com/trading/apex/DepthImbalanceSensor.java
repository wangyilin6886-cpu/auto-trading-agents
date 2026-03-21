package com.trading.apex;

/**
 * 感知器A: 深度失衡剥头皮 (Depth Imbalance Scalper)
 *
 * 原理: 当买方挂单 >> 卖方挂单时，价格短期上涨概率 > 65%
 *
 * 信号生成:
 *   depthImbalance > +0.35  且  takerDelta_5s > 0 → 看多
 *   depthImbalance < -0.35  且  takerDelta_5s < 0 → 看空
 *
 * 增强:
 *   有挂单墙支撑 → 置信度+20%
 *   takerDelta方向一致 → 置信度+15%
 */
public class DepthImbalanceSensor {

    private static final double IMBALANCE_THRESHOLD = 0.35;
    private static final double STRONG_IMBALANCE = 0.55;

    public SignalResult evaluate(MarketMicrostructure m) {
        if (m.price == 0 || m.bidTotal5 == 0 || m.askTotal5 == 0) {
            return SignalResult.none("DEPTH");
        }

        double imb = m.depthImbalance;
        double delta5 = m.takerDelta5s;
        double delta1 = m.takerDelta1s;

        // 基本信号: 深度失衡超过阈值
        if (Math.abs(imb) < IMBALANCE_THRESHOLD) {
            return SignalResult.none("DEPTH");
        }

        boolean bullish = imb > 0;
        double absImb = Math.abs(imb);

        // 方向确认: takerDelta必须同向
        boolean takerConfirm = bullish ? (delta5 > 0) : (delta5 < 0);
        if (!takerConfirm) {
            // 深度看多但实际成交在卖 → 可能是假墙(诱多)
            // 给一个微弱的反向信号
            double fakeWallScore = bullish ? -15 : +15;
            return new SignalResult(fakeWallScore, 0.25, "DEPTH",
                "fake wall detected: depth " + (bullish ? "bid" : "ask") + " heavy but taker opposite");
        }

        // 评分
        double score = 0;
        double confidence = 0;
        StringBuilder reason = new StringBuilder();

        // 基础分: 按失衡程度线性映射
        score = (absImb - IMBALANCE_THRESHOLD) / (1.0 - IMBALANCE_THRESHOLD) * 60 + 30;
        confidence = 0.45;
        reason.append(String.format("imbalance=%.1f%%", imb * 100));

        // 强失衡加分
        if (absImb > STRONG_IMBALANCE) {
            score += 15;
            confidence += 0.10;
            reason.append(" STRONG");
        }

        // taker即时确认(1秒delta同向)加分
        if (bullish ? (delta1 > 0) : (delta1 < 0)) {
            score += 10;
            confidence += 0.10;
            reason.append(" taker1sOK");
        }

        // 买单墙/卖单墙加分
        if (bullish && m.bidWallPrice > 0 && m.bidWallPrice < m.price) {
            score += 10;
            confidence += 0.10;
            reason.append(String.format(" bidWall@%.2f(%.0fU)", m.bidWallPrice, m.bidWallSize));
        }
        if (!bullish && m.askWallPrice > 0 && m.askWallPrice > m.price) {
            score += 10;
            confidence += 0.10;
            reason.append(String.format(" askWall@%.2f(%.0fU)", m.askWallPrice, m.askWallSize));
        }

        // 价差太大时降分(流动性差)
        if (m.spreadBps > 5) {
            score *= 0.7;
            confidence *= 0.7;
            reason.append(" wideSpread");
        }

        if (!bullish) score = -score;

        return new SignalResult(score, Math.min(confidence, 0.95), "DEPTH", reason.toString());
    }
}
