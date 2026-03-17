package com.trading;

import java.util.Locale;

public class VirtualAccount {

    // ===== 账户状态 =====
    private final double initialUsdt;
    private double usdtBalance;     // 可用USDT
    private double solPosition;     // 持仓SOL数量
    private double avgEntryPrice;   // 持仓均价（有仓时有效）

    // ===== 统计 =====
    private int buyCount = 0;
    private int sellCount = 0;
    private int totalOrders = 0;

    // ===== 风控参数 =====
    private static final double FEE_RATE = 0.001;          // 0.1% 手续费（近似）
    private static final double PHYSICAL_STOP_LOSS = -0.015; // -1.5% 物理止损

    public VirtualAccount(double initialUsdt) {
        this.initialUsdt = initialUsdt;
        this.usdtBalance = initialUsdt;
        this.solPosition = 0.0;
        this.avgEntryPrice = 0.0;
    }

    // ==========================
    // 兼容旧接口：固定金额买入
    // ==========================
    public synchronized void buy(double currentPrice, String reason) {
        // 兼容旧逻辑：每次500 USDT
        buyByAmount(currentPrice, 500.0, reason);
    }

    // ==========================
    // 新接口：按账户比例买入
    // ratio 例如 0.08 = 8%
    // ==========================
    public synchronized void buyByRatio(double currentPrice, double ratio, String reason) {
        if (ratio <= 0) return;
        double amount = getTotalValue(currentPrice) * ratio;  // 用总权益计算更稳
        buyByAmount(currentPrice, amount, reason);
    }

    // ==========================
    // 买入核心实现
    // ==========================
    public synchronized void buyByAmount(double currentPrice, double usdtAmount, String reason) {
        if (currentPrice <= 0) {
            System.out.println("⚠️ [买入失败] 价格异常");
            return;
        }
        if (usdtAmount <= 0) {
            System.out.println("⚠️ [买入失败] 下单金额非法");
            return;
        }

        double spend = Math.min(usdtAmount, usdtBalance);
        if (spend < 10) { // 最小下单门槛，防止碎单
            System.out.println("⚠️ [买入跳过] 可用资金不足或下单过小: " + fmt(spend) + " USDT");
            return;
        }

        double fee = spend * FEE_RATE;
        double netSpend = spend - fee;
        double buyQty = netSpend / currentPrice;

        // 更新均价（加权）
        if (solPosition <= 0) {
            avgEntryPrice = currentPrice;
            solPosition = buyQty;
        } else {
            double totalCostBefore = solPosition * avgEntryPrice;
            double totalCostAfter = totalCostBefore + netSpend;
            solPosition += buyQty;
            avgEntryPrice = totalCostAfter / solPosition;
        }

        usdtBalance -= spend;

        buyCount++;
        totalOrders++;

        System.out.println("🟩 [加特林买入] 射出 1 发子弹 (" + fmt(spend) + " USDT) | 成交价: " + fmtPrice(currentPrice)
                + " | 理由: " + reason);
    }

    // ==========================
    // 全部平仓卖出
    // ==========================
    public synchronized void sell(double currentPrice, String reason) {
        if (!hasPosition()) {
            System.out.println("🛡️ [卖出跳过] 当前无持仓");
            return;
        }
        if (currentPrice <= 0) {
            System.out.println("⚠️ [卖出失败] 价格异常");
            return;
        }

        double gross = solPosition * currentPrice;
        double fee = gross * FEE_RATE;
        double net = gross - fee;

        usdtBalance += net;

        // 清仓
        solPosition = 0.0;
        avgEntryPrice = 0.0;

        sellCount++;
        totalOrders++;

        System.out.println("🟥 [加特林平仓] 获利了结/止损！回笼资金: " + fmt(net) + " USDT | 成交价: "
                + fmtPrice(currentPrice) + " | 理由: " + reason);
    }

    // ==========================
    // 风控：物理止损（有仓时）
    // ==========================
    public synchronized boolean checkPhysicalStopLoss(double currentPrice) {
        if (!hasPosition()) return false;
        double pnlPct = getPositionPnlPercent(currentPrice);
        return pnlPct <= PHYSICAL_STOP_LOSS;
    }

    // ==========================
    // 账户/仓位查询
    // ==========================
    public synchronized boolean hasPosition() {
        return solPosition > 1e-10;
    }

    public synchronized double getPositionPnlPercent(double currentPrice) {
        if (!hasPosition() || avgEntryPrice <= 0) return 0.0;
        return (currentPrice - avgEntryPrice) / avgEntryPrice;
    }

    public synchronized double getExposureRatio(double currentPrice) {
        double equity = getTotalValue(currentPrice);
        if (equity <= 0) return 0.0;
        double positionValue = solPosition * currentPrice;
        return positionValue / equity;
    }

    public synchronized double getTotalValue(double currentPrice) {
        return usdtBalance + solPosition * currentPrice;
    }

    // 兼容旧代码常见命名（若你其他类有调用可直接工作）
    public synchronized double getCashBalance() {
        return usdtBalance;
    }

    public synchronized double getUsdtBalance() {
        return usdtBalance;
    }

    public synchronized double getSolPosition() {
        return solPosition;
    }

    public synchronized double getAvgEntryPrice() {
        return avgEntryPrice;
    }

    public synchronized int getTradeCount() {
        return totalOrders;
    }

    public synchronized int getBuyCount() {
        return buyCount;
    }

    public synchronized int getSellCount() {
        return sellCount;
    }

    // ==========================
    // 状态打印
    // ==========================
    public synchronized void printStatus(double currentPrice) {
        double total = getTotalValue(currentPrice);
        double totalPnlPct = (total - initialUsdt) / initialUsdt * 100.0;

        System.out.println("---------------------------------------------------");
        System.out.println("🏦 [金库总值] " + fmt(total) + " USDT (总盈亏: "
                + String.format(Locale.US, "%+.2f%%", totalPnlPct)
                + ") | 开火次数: " + totalOrders
                + " (买:" + buyCount + " 卖:" + sellCount + ")");
        if (hasPosition()) {
            double posPnlPct = getPositionPnlPercent(currentPrice) * 100.0;
            System.out.println("📦 [当前持仓] 浮动盈亏: "
                    + String.format(Locale.US, "%+.2f%%", posPnlPct)
                    + " | 均价: " + fmtPrice(avgEntryPrice)
                    + " | 持仓: " + fmtQty(solPosition) + " SOL"
                    + " | 仓位占比: " + String.format(Locale.US, "%.1f%%", getExposureRatio(currentPrice) * 100));
        } else {
            System.out.println("🛡️ [当前持仓] 空仓游走，填装弹药中 (100% USDT)");
        }
        System.out.println("---------------------------------------------------");
    }

    // ==========================
    // 工具函数
    // ==========================
    private String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private String fmtPrice(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private String fmtQty(double v) {
        return String.format(Locale.US, "%.4f", v);
    }
}