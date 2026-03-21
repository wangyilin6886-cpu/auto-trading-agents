package com.trading.apex;

/**
 * SignalResult - 感知器输出的统一信号格式
 *
 * score: -100 (强看空) 到 +100 (强看多), 0=无信号
 * confidence: 0.0~1.0 信号置信度
 * reason: 可读的信号原因
 */
public class SignalResult {
    public final double score;       // [-100, +100]
    public final double confidence;  // [0, 1]
    public final String source;      // 信号来源
    public final String reason;      // 人类可读原因
    public final long timestamp;

    public SignalResult(double score, double confidence, String source, String reason) {
        this.score = Math.max(-100, Math.min(100, score));
        this.confidence = Math.max(0, Math.min(1, confidence));
        this.source = source;
        this.reason = reason;
        this.timestamp = System.currentTimeMillis();
    }

    public static SignalResult none(String source) {
        return new SignalResult(0, 0, source, "no signal");
    }

    public boolean hasSignal() {
        return Math.abs(score) > 10 && confidence > 0.2;
    }

    public boolean isLong() { return score > 0; }
    public boolean isShort() { return score < 0; }

    @Override
    public String toString() {
        return String.format("[%s] score=%+.0f conf=%.0f%% | %s",
            source, score, confidence * 100, reason);
    }
}
