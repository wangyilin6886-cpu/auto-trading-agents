package com.trading;

import java.util.Locale;

/**
 * 合约模拟账户 v2.0
 * - 金库(70%) / 子弹仓(30%) 资金分离
 * - 逐仓隔离模式
 * - 分批平仓支持
 * - 利润回流机制
 * - 全线程安全
 */
public class FuturesVirtualAccount implements TradingAccount {

    // ===== 资金分离架构 =====
    private final double initialCapital;
    private double vaultBalance;        // 安全金库 (70%) - 保命钱
    private double bulletBalance;       // 子弹仓 (30%) - 作战资金
    private double realizedProfit;

    // ===== 仓位信息 =====
    private String positionSide = "NONE";
    private double positionSize = 0.0;      // 持仓数量 (SOL)
    private double entryPrice = 0.0;
    private int leverage = 1;
    private double isolatedMargin = 0.0;    // 逐仓冻结保证金

    // ===== 交易所参数 =====
    private static final double TAKER_FEE = 0.0004;
    private static final double MAINT_MARGIN_RATE = 0.01;

    // ===== 资金分配比例 =====
    private static final double VAULT_RATIO = 0.70;
    private static final double BULLET_RATIO = 0.30;
    private static final double PROFIT_TO_VAULT = 0.50;  // 利润50%回金库
    private static final double PROFIT_TO_BULLET = 0.50;  // 利润50%加子弹

    private boolean isGlobalKilled = false;
    private int totalTrades = 0;
    private int winTrades = 0;

    public FuturesVirtualAccount(double initialCapital) {
        this.initialCapital = initialCapital;
        this.vaultBalance = initialCapital * VAULT_RATIO;
        this.bulletBalance = initialCapital * BULLET_RATIO;
        this.realizedProfit = 0.0;
        System.out.println("======= 资金分配 =======");
        System.out.println("  安全金库: " + fmt(vaultBalance) + " USDT (70%)");
        System.out.println("  子弹仓:   " + fmt(bulletBalance) + " USDT (30%)");
        System.out.println("========================");
    }

    // ==========================================
    // 全局熔断：总资金跌破初始的 60% 锁死
    // ==========================================
    public synchronized boolean checkGlobalKillSwitch() {
        if (isGlobalKilled) return true;
        double total = getWalletBalance();
        if (total <= initialCapital * 0.60) {
            isGlobalKilled = true;
            System.out.println("====================================================");
            System.out.println("  [KILL SWITCH] 总资金跌破 60%！系统永久锁死！");
            System.out.println("  剩余: " + fmt(total) + " / 初始: " + fmt(initialCapital));
            System.out.println("====================================================");
        }
        return isGlobalKilled;
    }

    // ==========================================
    // 开仓（保证金从子弹仓扣除）
    // ==========================================
    public synchronized void openPosition(String side, double price, double marginAmount, int lev, String reason) {
        if (!positionSide.equals("NONE")) {
            System.out.println("[REJECT] 当前已有持仓，必须先平仓");
            return;
        }
        if (marginAmount > bulletBalance) {
            System.out.println("[REJECT] 子弹仓余额不足！可用: " + fmt(bulletBalance) + " / 需要: " + fmt(marginAmount));
            return;
        }

        this.positionSide = side.toUpperCase();
        this.leverage = lev;
        this.entryPrice = price;
        this.isolatedMargin = marginAmount;

        double notionalValue = marginAmount * leverage;
        double fee = notionalValue * TAKER_FEE;
        this.bulletBalance -= fee; // 手续费从子弹仓扣

        this.positionSize = notionalValue / price;

        System.out.println("\n>> [OPEN] " + (side.equals("LONG") ? "LONG" : "SHORT"));
        System.out.println("   margin=" + fmt(marginAmount) + "U | lev=" + lev + "x | size=" + fmt(positionSize) + " SOL");
        System.out.println("   entry=" + fmt(price) + " | fee=" + fmt(fee) + "U");
        System.out.println("   reason: " + reason);
        printStatus(price);
    }

    // ==========================================
    // 全部平仓
    // ==========================================
    public synchronized void closePosition(double price, String reason) {
        closePartial(price, 1.0, reason);
    }

    // ==========================================
    // 分批平仓：fraction = 0.0~1.0
    // ==========================================
    public synchronized void closePartial(double price, double fraction, String reason) {
        if (positionSide.equals("NONE")) return;
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        double closeSize = positionSize * fraction;
        double closeMargin = isolatedMargin * fraction;

        // 计算这部分仓位的盈亏
        double pnl;
        if (positionSide.equals("LONG")) {
            pnl = (price - entryPrice) * closeSize;
        } else {
            pnl = (entryPrice - price) * closeSize;
        }

        double closeFee = closeSize * price * TAKER_FEE;
        double netPnl = pnl - closeFee;

        // 退回保证金到子弹仓
        this.bulletBalance += closeMargin;
        // 利润回流
        recycleProfit(netPnl);

        this.realizedProfit += netPnl;
        this.totalTrades++;
        if (netPnl > 0) this.winTrades++;

        boolean isFullClose = fraction >= 0.999;
        String pctLabel = isFullClose ? "100%" : String.format("%.0f%%", fraction * 100);

        System.out.println("\n>> [CLOSE " + pctLabel + "] " + reason);
        System.out.println("   exit=" + fmt(price) + " | pnl=" + (netPnl >= 0 ? "+" : "") + fmt(netPnl) + "U | fee=" + fmt(closeFee) + "U");

        // 更新仓位
        this.positionSize -= closeSize;
        this.isolatedMargin -= closeMargin;

        if (this.positionSize < 1e-10 || isFullClose) {
            this.positionSide = "NONE";
            this.positionSize = 0;
            this.entryPrice = 0;
            this.isolatedMargin = 0;
            this.leverage = 1;
        }

        printStatus(price);
    }

    // ==========================================
    // 爆仓检测
    // ==========================================
    public synchronized boolean checkLiquidation(double currentPrice) {
        if (positionSide.equals("NONE")) return false;

        double pnl = getUnrealizedPNL(currentPrice);
        if (pnl <= -isolatedMargin * (1 - MAINT_MARGIN_RATE)) {
            System.out.println("\n>> [LIQUIDATION] 保证金清零！亏损: " + fmt(isolatedMargin) + "U");
            // 爆仓：保证金被没收，不退回
            this.realizedProfit -= isolatedMargin;
            this.totalTrades++;

            this.positionSide = "NONE";
            this.positionSize = 0;
            this.entryPrice = 0;
            this.isolatedMargin = 0;
            printStatus(currentPrice);
            return true;
        }
        return false;
    }

    // ==========================================
    // 利润回流机制
    // ==========================================
    public synchronized void recycleProfit(double pnl) {
        if (pnl > 0) {
            // 赚钱：50%进金库保命，50%加子弹
            double toVault = pnl * PROFIT_TO_VAULT;
            double toBullet = pnl * PROFIT_TO_BULLET;
            this.vaultBalance += toVault;
            this.bulletBalance += toBullet;
        } else {
            // 亏钱：从子弹仓扣
            this.bulletBalance += pnl; // pnl是负数
            // 子弹仓打空了，从金库补充到 30%
            if (this.bulletBalance < 0) {
                double deficit = -this.bulletBalance;
                this.bulletBalance = 0;
                double canTake = Math.min(deficit, this.vaultBalance * 0.1); // 每次最多取金库10%
                this.vaultBalance -= canTake;
                this.bulletBalance += canTake;
            }
        }
    }

    // ==========================================
    // 盈亏计算
    // ==========================================
    public synchronized double getUnrealizedPNL(double currentPrice) {
        if (positionSide.equals("LONG")) return (currentPrice - entryPrice) * positionSize;
        if (positionSide.equals("SHORT")) return (entryPrice - currentPrice) * positionSize;
        return 0.0;
    }

    public synchronized double getROE(double currentPrice) {
        if (isolatedMargin == 0) return 0.0;
        return (getUnrealizedPNL(currentPrice) / isolatedMargin) * 100.0;
    }

    public synchronized double getLiquidationPrice() {
        if (positionSide.equals("NONE")) return 0.0;
        double bankruptcyDrop = isolatedMargin * (1 - MAINT_MARGIN_RATE) / positionSize;
        if (positionSide.equals("LONG")) return entryPrice - bankruptcyDrop;
        if (positionSide.equals("SHORT")) return entryPrice + bankruptcyDrop;
        return 0;
    }

    // ==========================================
    // Getters
    // ==========================================
    public synchronized double getRealizedProfit() { return realizedProfit; }
    public synchronized double getWalletBalance() { return vaultBalance + bulletBalance; }
    public synchronized double getVaultBalance() { return vaultBalance; }
    public synchronized double getBulletBalance() { return bulletBalance; }
    public synchronized String getPositionSide() { return positionSide; }
    public synchronized double getPositionSize() { return positionSize; }
    public synchronized double getEntryPrice() { return entryPrice; }

    /**
     * 从子弹仓划拨资金给网格引擎（仅启动时调用一次）
     */
    public synchronized double allocateFromBullet(double amount) {
        double actual = Math.min(amount, bulletBalance);
        bulletBalance -= actual;
        return actual;
    }

    public synchronized void printStatus(double price) {
        double total = getWalletBalance();
        double totalPnlPct = (total - initialCapital) / initialCapital * 100.0;
        double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0;

        System.out.println("---------------------------------------------------------");
        System.out.println("[ACCOUNT] total=" + fmt(total) + "U (" + (totalPnlPct >= 0 ? "+" : "") + fmt(totalPnlPct) + "%)");
        System.out.println("  vault=" + fmt(vaultBalance) + "U | bullet=" + fmt(bulletBalance) + "U | profit=" + (realizedProfit >= 0 ? "+" : "") + fmt(realizedProfit) + "U");
        System.out.println("  trades=" + totalTrades + " | winRate=" + fmt(winRate) + "%");

        if (!positionSide.equals("NONE")) {
            double pnl = getUnrealizedPNL(price);
            double roe = getROE(price);
            System.out.println("[POSITION] " + positionSide + " " + fmt(positionSize) + " SOL | lev=" + leverage + "x | margin=" + fmt(isolatedMargin) + "U");
            System.out.println("  entry=" + fmt(entryPrice) + " -> now=" + fmt(price) + " | pnl=" + (pnl >= 0 ? "+" : "") + fmt(pnl) + "U (ROE=" + (roe >= 0 ? "+" : "") + fmt(roe) + "%)");
            System.out.println("  liq=" + fmt(getLiquidationPrice()));
        } else {
            System.out.println("[POSITION] FLAT - waiting for signal");
        }
        System.out.println("---------------------------------------------------------");
    }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}
