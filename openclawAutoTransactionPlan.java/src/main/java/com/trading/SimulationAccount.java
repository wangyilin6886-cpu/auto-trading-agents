package com.trading;

import java.util.Locale;

/**
 * 模拟账户 — 完全在内存中模拟交易，不发任何 API 请求。
 * 用于 TRADING_MODE=SIMULATION 时替代 BinanceRealAccount 的交易部分。
 *
 * 特性：
 *   - 模拟滑点（0.01%）
 *   - 模拟手续费（0.04%）
 *   - 追踪所有交易统计
 *   - 审计日志记录
 */
public class SimulationAccount {

    private final double initialCapital;
    private double walletBalance;
    private double realizedProfit = 0;

    private String positionSide = "NONE";
    private int positionSize = 0;
    private double entryPrice = 0;
    private int leverage = 1;
    private double isolatedMargin = 0;

    // 统计
    private int totalTrades = 0;
    private int wins = 0;

    private static final double SIMULATED_SLIPPAGE = 0.0001; // 0.01%
    private static final double FEE_RATE = 0.0004;           // 0.04%

    public SimulationAccount(double initialCapital) {
        this.initialCapital = initialCapital;
        this.walletBalance = initialCapital;
        System.out.println("[SIMULATION] Account initialized with " + fmt(initialCapital) + " USDT");
    }

    public synchronized void openPosition(String side, double price, double marginAmount, int lev, String reason) {
        if (!positionSide.equals("NONE")) {
            System.out.println("[SIM] Already have position, ignoring open");
            return;
        }

        // 模拟滑点
        double fillPrice = side.equals("LONG")
                ? price * (1 + SIMULATED_SLIPPAGE)
                : price * (1 - SIMULATED_SLIPPAGE);

        int qty = (int) Math.max(1, (marginAmount * lev) / fillPrice);
        double actualMargin = (qty * fillPrice) / lev;

        if (walletBalance < actualMargin) {
            System.out.println("[SIM] Insufficient balance");
            return;
        }

        double fee = qty * fillPrice * FEE_RATE;
        walletBalance -= fee;

        positionSide = side.toUpperCase();
        positionSize = qty;
        entryPrice = fillPrice;
        leverage = lev;
        isolatedMargin = actualMargin;

        AuditLogger.get().logSimulatedOrder(side, qty, fillPrice, lev, reason);

        System.out.println(String.format(Locale.US,
                "[SIM] OPEN %s %d SOL @ %.4f | Lev=%dx | Margin=%.2f | Fee=%.4f | %s",
                side, qty, fillPrice, lev, actualMargin, fee, reason));
    }

    public synchronized void closePosition(double price, String reason) {
        if (positionSide.equals("NONE")) return;

        double fillPrice = positionSide.equals("LONG")
                ? price * (1 - SIMULATED_SLIPPAGE)
                : price * (1 + SIMULATED_SLIPPAGE);

        double pnl = getUnrealizedPNL(fillPrice);
        double fee = positionSize * fillPrice * FEE_RATE;
        double netPnl = pnl - fee;

        walletBalance += netPnl;
        realizedProfit += netPnl;
        totalTrades++;
        if (netPnl > 0) wins++;

        double roe = isolatedMargin > 0 ? (netPnl / isolatedMargin) * 100 : 0;

        AuditLogger.get().logTradeResult(netPnl, walletBalance,
                totalTrades > 0 ? (double) wins / totalTrades : 0, totalTrades);

        System.out.println(String.format(Locale.US,
                "[SIM] CLOSE %s %d SOL @ %.4f | PnL=%+.4f | ROE=%.2f%% | Balance=%.2f | %s",
                positionSide, positionSize, fillPrice, netPnl, roe, walletBalance, reason));

        resetPosition();
    }

    public synchronized boolean addToPosition(double price, double marginAmount, int lev, String reason) {
        if (positionSide.equals("NONE")) return false;

        double fillPrice = positionSide.equals("LONG")
                ? price * (1 + SIMULATED_SLIPPAGE)
                : price * (1 - SIMULATED_SLIPPAGE);

        int qty = (int) Math.max(1, (marginAmount * lev) / fillPrice);
        double fee = qty * fillPrice * FEE_RATE;
        walletBalance -= fee;

        double totalValue = (entryPrice * positionSize) + (fillPrice * qty);
        positionSize += qty;
        entryPrice = totalValue / positionSize;
        isolatedMargin += (qty * fillPrice) / lev;

        AuditLogger.get().logSimulatedOrder("ADD_" + positionSide, qty, fillPrice, lev, reason);

        System.out.println(String.format(Locale.US,
                "[SIM] ADD %d SOL @ %.4f | NewAvg=%.4f | TotalQty=%d | %s",
                qty, fillPrice, entryPrice, positionSize, reason));

        return true;
    }

    public synchronized boolean checkLiquidation(double currentPrice) {
        if (positionSide.equals("NONE")) return false;

        double pnl = getUnrealizedPNL(currentPrice);
        if (pnl <= -isolatedMargin * 0.99) {
            System.out.println("[SIM] LIQUIDATED @ " + fmt(currentPrice));
            walletBalance -= isolatedMargin;
            realizedProfit -= isolatedMargin;
            totalTrades++;
            resetPosition();
            return true;
        }
        return false;
    }

    private void resetPosition() {
        positionSide = "NONE";
        positionSize = 0;
        entryPrice = 0;
        leverage = 1;
        isolatedMargin = 0;
    }

    // ==================== 查询接口 (与 BinanceRealAccount 兼容) ====================

    public synchronized double getUnrealizedPNL(double currentPrice) {
        if (positionSide.equals("LONG")) return (currentPrice - entryPrice) * positionSize;
        if (positionSide.equals("SHORT")) return (entryPrice - currentPrice) * positionSize;
        return 0;
    }

    public synchronized double getROE(double currentPrice) {
        if (isolatedMargin == 0) return 0;
        return (getUnrealizedPNL(currentPrice) / isolatedMargin) * 100;
    }

    public synchronized String getPositionSide() { return positionSide; }
    public synchronized int getPositionSize() { return positionSize; }
    public synchronized double getEntryPrice() { return entryPrice; }
    public synchronized int getLeverage() { return leverage; }
    public synchronized double getIsolatedMargin() { return isolatedMargin; }
    public synchronized double getWalletBalance() { return walletBalance; }
    public synchronized double getRealizedProfit() { return realizedProfit; }
    public int getTotalTrades() { return totalTrades; }
    public int getWins() { return wins; }
    public double getWinRate() { return totalTrades > 0 ? (double) wins / totalTrades : 0; }

    public synchronized void printStatus(double price) {
        System.out.println("---------------------------------------------------------");
        System.out.println("[SIM] Balance: " + fmt(walletBalance) + " USDT | Profit: " + (realizedProfit >= 0 ? "+" : "") + fmt(realizedProfit));
        System.out.println("[SIM] Trades: " + totalTrades + " | Wins: " + wins
                + " | WinRate: " + (totalTrades > 0 ? String.format(Locale.US, "%.0f%%", getWinRate() * 100) : "N/A"));
        if (!positionSide.equals("NONE")) {
            System.out.println("[SIM] Position: " + positionSide + " " + positionSize + " SOL @ " + fmt(entryPrice)
                    + " | Lev=" + leverage + "x | PnL=" + fmt(getUnrealizedPNL(price)));
        }
        System.out.println("---------------------------------------------------------");
    }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}
