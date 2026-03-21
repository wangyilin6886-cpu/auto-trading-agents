package com.trading.apex;

/**
 * 感知器B: 爆仓瀑布引擎 (Liquidation Cascade Engine)
 *
 * 原理: forceOrder流实时推送强平订单
 *       爆仓集中出现 → 价格加速朝爆仓方向移动 → 雪崩
 *
 * 信号:
 *   多头爆仓>200,000U/60s + 速率加速 → 做空(跟着瀑布走)
 *   空头爆仓>200,000U/60s + 速率加速 → 做多(跟着瀑布走)
 *
 * 反手信号:
 *   爆仓速率降至峰值30%以下 → 瀑布结束，反向回弹
 */
public class LiquidationCascadeSensor {

    private static final double CASCADE_THRESHOLD = 200000;    // 60s累计爆仓>20万U
    private static final double SMALL_CASCADE_THRESHOLD = 80000; // 较小瀑布
    private static final double ACCEL_RATIO = 1.3;             // 加速判定: 当前>前周期×1.3

    // 追踪瀑布峰值速率(用于判断结束)
    private double peakLiqRate = 0;
    private boolean cascadeRiding = false;
    private String cascadeDirection = "NONE"; // 瀑布方向(LONG多头被爆/SHORT空头被爆)

    public SignalResult evaluate(MarketMicrostructure m) {
        double totalLiq = m.liqBuyVol60s + m.liqSellVol60s;
        double rate15 = m.liqRate15s;
        double ratePrev = m.liqRatePrev15s;

        // 更新峰值
        if (rate15 > peakLiqRate) peakLiqRate = rate15;

        // 状态1: 没有爆仓 → 重置
        if (totalLiq < 30000) {
            if (cascadeRiding) {
                // 瀑布彻底结束，发反手信号
                cascadeRiding = false;
                double reboundScore = cascadeDirection.equals("LONG") ? +55 : -55;
                String reason = "cascade ended, rebound signal | peakRate=" + fmt(peakLiqRate);
                peakLiqRate = 0;
                cascadeDirection = "NONE";
                return new SignalResult(reboundScore, 0.55, "LIQ", reason);
            }
            peakLiqRate = 0;
            cascadeDirection = "NONE";
            return SignalResult.none("LIQ");
        }

        // 状态2: 正在骑瀑布
        if (cascadeRiding) {
            // 检测减速 → 瀑布可能要结束
            if (peakLiqRate > 0 && rate15 < peakLiqRate * 0.30) {
                cascadeRiding = false;
                // 减速信号: 减仓
                double exitScore = cascadeDirection.equals("LONG") ? +30 : -30;
                String reason = "cascade decelerating | rate=" + fmt(rate15) + " peak=" + fmt(peakLiqRate);
                return new SignalResult(exitScore, 0.45, "LIQ", reason);
            }
            // 还在加速 → 继续持有，发持仓信号
            double holdScore = cascadeDirection.equals("LONG") ? -70 : +70;
            return new SignalResult(holdScore, 0.70, "LIQ",
                "cascade active | rate=" + fmt(rate15) + " total=" + fmt(totalLiq));
        }

        // 状态3: 检测新瀑布启动
        boolean accelerating = ratePrev > 0 && rate15 > ratePrev * ACCEL_RATIO;

        if (totalLiq >= CASCADE_THRESHOLD && accelerating) {
            // 大瀑布确认
            cascadeRiding = true;
            peakLiqRate = rate15;
            boolean longLiq = m.liqBuyVol60s > m.liqSellVol60s;
            cascadeDirection = longLiq ? "LONG" : "SHORT";

            // 多头被爆 → 做空(跟瀑布); 空头被爆 → 做多
            double score = longLiq ? -85 : +85;
            return new SignalResult(score, 0.80, "LIQ",
                "CASCADE START! " + cascadeDirection + " liq | total=" + fmt(totalLiq) +
                " rate=" + fmt(rate15) + " accel=" + String.format("%.1fx", rate15/ratePrev));

        } else if (totalLiq >= SMALL_CASCADE_THRESHOLD && accelerating) {
            // 小瀑布
            boolean longLiq = m.liqBuyVol60s > m.liqSellVol60s;
            double score = longLiq ? -45 : +45;
            return new SignalResult(score, 0.50, "LIQ",
                "small cascade | " + (longLiq ? "LONG" : "SHORT") + " liq=" + fmt(totalLiq));

        } else if (totalLiq >= SMALL_CASCADE_THRESHOLD) {
            // 有爆仓但没加速 → 弱信号
            boolean longLiq = m.liqBuyVol60s > m.liqSellVol60s;
            double score = longLiq ? -25 : +25;
            return new SignalResult(score, 0.30, "LIQ",
                "liq elevated | " + (longLiq ? "LONG" : "SHORT") + " liq=" + fmt(totalLiq));
        }

        return SignalResult.none("LIQ");
    }

    public boolean isCascadeActive() { return cascadeRiding; }
    public String getCascadeDirection() { return cascadeDirection; }

    public void reset() {
        cascadeRiding = false;
        cascadeDirection = "NONE";
        peakLiqRate = 0;
    }

    private String fmt(double v) { return String.format("%.0f", v); }
}
