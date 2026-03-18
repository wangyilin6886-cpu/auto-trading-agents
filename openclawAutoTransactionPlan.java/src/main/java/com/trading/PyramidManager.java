package com.trading;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 浮盈加仓管理器 — 只用利润追加仓位，本金绝不追加暴露。
 *
 * 规则：
 * 1. 仅当浮盈 > 0 时才能加仓
 * 2. 加仓金额 = 当前浮盈的50%
 * 3. 最多加仓3层（基础仓 + 3次追加 = 共4层）
 * 4. 每次加仓后，全局止损线上移到上一层入场价（保底不亏本金）
 * 5. 触发条件：ROE ≥ 30% 且趋势方向一致
 */
public class PyramidManager {

    public static class PyramidLayer {
        public final double entryPrice;
        public final int qty;
        public final double margin;
        public final long timestamp;

        public PyramidLayer(double entryPrice, int qty, double margin) {
            this.entryPrice = entryPrice;
            this.qty = qty;
            this.margin = margin;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private static final int MAX_PYRAMID_LAYERS = 3; // 最多追加3层
    private static final double MIN_ROE_TO_PYRAMID = 30.0; // ROE≥30%才加仓
    private static final double PROFIT_USAGE_PCT = 0.50; // 用浮盈的50%加仓

    private final List<PyramidLayer> layers = new ArrayList<>();
    private String currentSide = "NONE";

    /**
     * 重置（新一轮交易开始时调用）
     */
    public synchronized void reset() {
        layers.clear();
        currentSide = "NONE";
    }

    /**
     * 记录基础仓位。
     */
    public synchronized void recordBaseLayer(String side, double entryPrice, int qty, double margin) {
        reset();
        currentSide = side;
        layers.add(new PyramidLayer(entryPrice, qty, margin));
    }

    /**
     * 检查是否可以加仓。
     *
     * @param currentPrice 当前价格
     * @param currentROE   当前ROE%
     * @param macroTrend   宏观趋势
     * @return 应追加的保证金金额，0表示不加仓
     */
    public synchronized double checkPyramid(double currentPrice, double currentROE, String macroTrend) {
        if (currentSide.equals("NONE")) return 0;
        if (layers.size() > MAX_PYRAMID_LAYERS) return 0; // 已满
        if (currentROE < MIN_ROE_TO_PYRAMID) return 0;    // 盈利不够

        // 趋势必须顺势
        boolean trendAligned = (currentSide.equals("LONG") && macroTrend.contains("BULL"))
                            || (currentSide.equals("SHORT") && macroTrend.contains("BEAR"));
        if (!trendAligned) return 0;

        // 计算浮盈
        double unrealizedProfit = calculateTotalUnrealizedPNL(currentPrice);
        if (unrealizedProfit <= 0) return 0;

        // 加仓金额 = 浮盈的50%
        double pyramidMargin = unrealizedProfit * PROFIT_USAGE_PCT;
        if (pyramidMargin < 5.0) return 0; // 太少不值得

        System.out.println("📈 [浮盈加仓] 当前浮盈 " + fmt(unrealizedProfit)
                + " USDT, 追加保证金 " + fmt(pyramidMargin) + " USDT (第" + (layers.size()) + "层加仓)");

        return pyramidMargin;
    }

    /**
     * 记录加仓层。
     */
    public synchronized void recordPyramidLayer(double entryPrice, int qty, double margin) {
        layers.add(new PyramidLayer(entryPrice, qty, margin));
    }

    /**
     * 获取安全止损价 — 加仓后止损线上移到上一层入场价。
     * 保证即使止损也不会亏本金。
     */
    public synchronized double getSafeStopPrice() {
        if (layers.size() <= 1) return 0; // 基础仓使用原始止损

        // 止损线 = 上一层的入场价（保底不亏本金）
        PyramidLayer previousLayer = layers.get(layers.size() - 2);
        return previousLayer.entryPrice;
    }

    /**
     * 获取总持仓数量。
     */
    public synchronized int getTotalQty() {
        return layers.stream().mapToInt(l -> l.qty).sum();
    }

    /**
     * 获取总保证金。
     */
    public synchronized double getTotalMargin() {
        return layers.stream().mapToDouble(l -> l.margin).sum();
    }

    /**
     * 计算所有层的总浮盈。
     */
    public synchronized double calculateTotalUnrealizedPNL(double currentPrice) {
        double totalPNL = 0;
        for (PyramidLayer layer : layers) {
            if (currentSide.equals("LONG")) {
                totalPNL += (currentPrice - layer.entryPrice) * layer.qty;
            } else if (currentSide.equals("SHORT")) {
                totalPNL += (layer.entryPrice - currentPrice) * layer.qty;
            }
        }
        return totalPNL;
    }

    /**
     * 获取加仓层数（不含基础仓）。
     */
    public synchronized int getPyramidCount() {
        return Math.max(0, layers.size() - 1);
    }

    public synchronized String getCurrentSide() { return currentSide; }
    public synchronized List<PyramidLayer> getLayers() { return new ArrayList<>(layers); }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}
