package com.trading.apex;

import com.trading.TradingAccount;
import com.trading.BinanceRealAccount;

import java.util.Locale;

/**
 * ApexAccount - 统一账户管理器(支持模拟/实盘无缝切换)
 *
 * 特性:
 *   - 级联复利: 每笔交易后立即更新可用资金(不等日结算)
 *   - 利润分配: 80%复投 + 10%安全金库 + 10%可提现
 *   - 多仓位管理: 支持多个币种同时持仓(每个FusionEngine独立管理)
 *   - 模式切换: --real 实盘 / 默认模拟盘
 */
public class ApexAccount implements TradingAccount {

    private final boolean isRealMode;
    private final BinanceRealAccount realAccount; // 实盘时非null

    // === 模拟盘状态 ===
    private final double initialCapital;
    private double activeBalance;       // 可用于交易的资金(级联复利池)
    private double vaultBalance;        // 安全金库(永不交易)
    private double withdrawable;        // 可提现
    private double realizedProfit = 0;

    // === 仓位(模拟盘) ===
    private String positionSide = "NONE";
    private double positionSize = 0;
    private double entryPrice = 0;
    private int leverage = 1;
    private double isolatedMargin = 0;

    // === 利润分配比例 ===
    private static final double REINVEST_RATIO = 0.80;
    private static final double VAULT_RATIO = 0.10;
    private static final double WITHDRAW_RATIO = 0.10;

    // === 交易参数 ===
    private static final double TAKER_FEE = 0.0004;
    private static final double MAKER_FEE = 0.0002;
    private static final double MAINT_MARGIN_RATE = 0.01;

    // === 统计 ===
    private int totalTrades = 0;
    private int winTrades = 0;
    private boolean isGlobalKilled = false;

    /**
     * 模拟盘构造
     */
    public ApexAccount(double initialCapital) {
        this.isRealMode = false;
        this.realAccount = null;
        this.initialCapital = initialCapital;
        this.activeBalance = initialCapital * 0.90;  // 90%进交易池
        this.vaultBalance = initialCapital * 0.05;    // 5%初始金库
        this.withdrawable = initialCapital * 0.05;    // 5%初始可提现
        printInit();
    }

    /**
     * 实盘构造
     */
    public ApexAccount(String apiKey, String secretKey, double initialCapital) {
        this.isRealMode = true;
        this.realAccount = new BinanceRealAccount(apiKey, secretKey, initialCapital);
        this.initialCapital = initialCapital;
        this.activeBalance = initialCapital * 0.90;
        this.vaultBalance = initialCapital * 0.05;
        this.withdrawable = initialCapital * 0.05;
        printInit();
    }

    private void printInit() {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║  APEX PREDATOR Account Initialized       ║");
        System.out.println("║  Mode: " + (isRealMode ? ">>> REAL TRADING <<<" : "SIMULATION") + "            ║");
        System.out.println(String.format(Locale.US,
            "║  Capital:    %10.2f USDT              ║", initialCapital));
        System.out.println(String.format(Locale.US,
            "║  Active:     %10.2f USDT (90%%)         ║", activeBalance));
        System.out.println(String.format(Locale.US,
            "║  Vault:      %10.2f USDT (5%%)          ║", vaultBalance));
        System.out.println(String.format(Locale.US,
            "║  Withdrawable:%9.2f USDT (5%%)          ║", withdrawable));
        System.out.println("╚══════════════════════════════════════════╝");
    }

    // ==========================================
    // TradingAccount 接口实现
    // ==========================================

    @Override
    public synchronized void openPosition(String side, double price, double marginAmount, int lev, String reason) {
        if (isRealMode) {
            realAccount.openPosition(side, price, marginAmount, lev, reason);
            return;
        }

        if (checkGlobalKillSwitch()) return;
        if (!positionSide.equals("NONE")) {
            System.out.println("[APEX REJECT] Already have position");
            return;
        }

        double notional = marginAmount * lev;
        double fee = notional * TAKER_FEE;
        double totalCost = marginAmount + fee;

        if (totalCost > activeBalance) {
            System.out.println("[APEX REJECT] Insufficient balance: " + fmt(activeBalance) + " < " + fmt(totalCost));
            return;
        }

        this.positionSide = side.toUpperCase();
        this.leverage = lev;
        this.entryPrice = price;
        this.isolatedMargin = marginAmount;
        this.activeBalance -= totalCost;
        this.positionSize = notional / price;

        System.out.println(String.format(Locale.US,
            ">> [OPEN %s] %.4f SOL @ %.4f | margin=%.2f lev=%dx | fee=%.4f | %s",
            side, positionSize, price, marginAmount, lev, fee, reason));
    }

    @Override
    public synchronized void closePosition(double price, String reason) {
        closePartial(price, 1.0, reason);
    }

    @Override
    public synchronized void closePartial(double price, double fraction, String reason) {
        if (isRealMode) {
            realAccount.closePartial(price, fraction, reason);
            return;
        }

        if (positionSide.equals("NONE")) return;
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        double closeSize = positionSize * fraction;
        double closeMargin = isolatedMargin * fraction;
        double closeNotional = closeSize * price;

        double pnl;
        if (positionSide.equals("LONG")) pnl = (price - entryPrice) * closeSize;
        else pnl = (entryPrice - price) * closeSize;

        double fee = closeNotional * TAKER_FEE;
        double netPnl = pnl - fee;

        // 级联复利: 利润立即分配
        activeBalance += closeMargin; // 返还保证金
        distributePnl(netPnl);

        realizedProfit += netPnl;
        totalTrades++;
        if (netPnl > 0) winTrades++;

        System.out.println(String.format(Locale.US,
            "<< [CLOSE %.0f%%] pnl=%+.4f fee=%.4f | %s | active=%.2f vault=%.2f",
            fraction * 100, netPnl, fee, reason, activeBalance, vaultBalance));

        // 更新仓位
        positionSize -= closeSize;
        isolatedMargin -= closeMargin;
        if (positionSize < 0.001 || fraction >= 0.99) {
            positionSide = "NONE";
            positionSize = 0;
            entryPrice = 0;
            isolatedMargin = 0;
            leverage = 1;
        }

        checkGlobalKillSwitch();
    }

    /**
     * 级联复利核心: 每笔利润立即按比例分配
     */
    private void distributePnl(double pnl) {
        if (pnl > 0) {
            activeBalance += pnl * REINVEST_RATIO;   // 80%立刻可用于下一笔交易
            vaultBalance += pnl * VAULT_RATIO;        // 10%锁入金库
            withdrawable += pnl * WITHDRAW_RATIO;     // 10%可提现
        } else {
            // 亏损全部由activeBalance承担
            activeBalance += pnl;
            // 如果activeBalance耗尽，从金库紧急补充(最多10%)
            if (activeBalance < 0) {
                double deficit = -activeBalance;
                double canTake = Math.min(deficit, vaultBalance * 0.1);
                vaultBalance -= canTake;
                activeBalance += canTake;
            }
        }
    }

    @Override
    public synchronized boolean checkLiquidation(double currentPrice) {
        if (isRealMode) return realAccount.checkLiquidation(currentPrice);
        if (positionSide.equals("NONE")) return false;
        double pnl = getUnrealizedPNL(currentPrice);
        if (pnl <= -isolatedMargin * (1 - MAINT_MARGIN_RATE)) {
            System.out.println("[LIQUIDATION] Lost margin: " + fmt(isolatedMargin));
            realizedProfit -= isolatedMargin;
            totalTrades++;
            positionSide = "NONE";
            positionSize = 0;
            entryPrice = 0;
            isolatedMargin = 0;
            checkGlobalKillSwitch();
            return true;
        }
        return false;
    }

    @Override
    public synchronized boolean checkGlobalKillSwitch() {
        if (isRealMode) return realAccount.checkGlobalKillSwitch();
        if (isGlobalKilled) return true;
        double total = getWalletBalance();
        if (total <= initialCapital * 0.75) {
            isGlobalKilled = true;
            System.out.println("╔══════════════════════════════════════════╗");
            System.out.println("║  [KILL] Balance < 75%! System locked!    ║");
            System.out.println("║  Remaining: " + fmt(total) + " / " + fmt(initialCapital) + "       ║");
            System.out.println("╚══════════════════════════════════════════╝");
        }
        return isGlobalKilled;
    }

    @Override
    public synchronized double getUnrealizedPNL(double currentPrice) {
        if (isRealMode) return realAccount.getUnrealizedPNL(currentPrice);
        if (positionSide.equals("LONG")) return (currentPrice - entryPrice) * positionSize;
        if (positionSide.equals("SHORT")) return (entryPrice - currentPrice) * positionSize;
        return 0;
    }

    @Override
    public synchronized double getROE(double currentPrice) {
        if (isRealMode) return realAccount.getROE(currentPrice);
        if (isolatedMargin == 0) return 0;
        return (getUnrealizedPNL(currentPrice) / isolatedMargin) * 100.0;
    }

    @Override
    public synchronized double getRealizedProfit() {
        return isRealMode ? realAccount.getRealizedProfit() : realizedProfit;
    }

    @Override
    public synchronized double getWalletBalance() {
        if (isRealMode) return realAccount.getWalletBalance();
        return activeBalance + vaultBalance + withdrawable + isolatedMargin;
    }

    @Override
    public synchronized double getVaultBalance() {
        return isRealMode ? realAccount.getVaultBalance() : vaultBalance;
    }

    @Override
    public synchronized double getBulletBalance() {
        return isRealMode ? realAccount.getBulletBalance() : activeBalance;
    }

    @Override
    public synchronized double allocateFromBullet(double amount) {
        if (isRealMode) return realAccount.allocateFromBullet(amount);
        double actual = Math.min(amount, activeBalance);
        activeBalance -= actual;
        return actual;
    }

    @Override
    public synchronized void recycleProfit(double pnl) {
        if (isRealMode) { realAccount.recycleProfit(pnl); return; }
        distributePnl(pnl);
    }

    @Override
    public synchronized String getPositionSide() {
        return isRealMode ? realAccount.getPositionSide() : positionSide;
    }

    @Override
    public synchronized double getPositionSize() {
        return isRealMode ? realAccount.getPositionSize() : positionSize;
    }

    @Override
    public synchronized double getEntryPrice() {
        return isRealMode ? realAccount.getEntryPrice() : entryPrice;
    }

    @Override
    public synchronized void printStatus(double price) {
        if (isRealMode) { realAccount.printStatus(price); return; }

        double total = getWalletBalance();
        double pnlPct = (total - initialCapital) / initialCapital * 100;
        System.out.println("══════════════════════════════════════════");
        System.out.println(String.format(Locale.US,
            "[APEX ACCOUNT] total=%.2f (%+.2f%%) | active=%.2f vault=%.2f withdraw=%.2f",
            total, pnlPct, activeBalance, vaultBalance, withdrawable));
        System.out.println(String.format(Locale.US,
            "  profit=%+.4f | trades=%d (win=%d loss=%d wr=%.1f%%)",
            realizedProfit, totalTrades, winTrades, totalTrades - winTrades,
            totalTrades > 0 ? (double) winTrades / totalTrades * 100 : 0));
        if (!positionSide.equals("NONE")) {
            double roe = getROE(price);
            System.out.println(String.format(Locale.US,
                "  [POS] %s %.4f @ %.4f | margin=%.2f lev=%dx | roe=%+.2f%%",
                positionSide, positionSize, entryPrice, isolatedMargin, leverage, roe));
        }
        System.out.println("══════════════════════════════════════════");
    }

    // === Apex specific ===
    public double getActiveBalance() { return activeBalance; }
    public double getWithdrawable() { return withdrawable; }
    public double getInitialCapital() { return initialCapital; }
    public boolean isRealMode() { return isRealMode; }
    public int getTotalTrades() { return totalTrades; }
    public int getWinTrades() { return winTrades; }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}
