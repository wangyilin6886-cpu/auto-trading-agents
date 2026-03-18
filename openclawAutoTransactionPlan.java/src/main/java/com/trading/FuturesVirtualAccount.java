package com.trading;

import java.util.Locale;

public class FuturesVirtualAccount implements TradingAccount {

    // ===== 资金中心 (本金与利润严格分离) =====
    private final double initialCapital; // 初始本金 (绝不动用超过风控比例)
    private double walletBalance;        // 钱包总余额 (本金 + 已实现利润)
    private double realizedProfit;       // 累计真金白银利润

    // ===== 仓位雷达 (逐仓模式 Isolated) =====
    private String positionSide = "NONE"; // LONG(做多), SHORT(做空), NONE(空仓)
    private double positionSize = 0.0;    // 持仓数量 (SOL)
    private double entryPrice = 0.0;      // 开仓均价
    private int leverage = 1;             // 当前杠杆倍数
    private double isolatedMargin = 0.0;  // 逐仓冻结保证金

    // ===== 交易所参��� =====
    private static final double TAKER_FEE = 0.0004; // 币安合约市价单手续费 0.04%
    private static final double MAINT_MARGIN_RATE = 0.01; // 维持保证金率 (低于此线爆仓)

    private boolean isGlobalKilled = false;

    public FuturesVirtualAccount(double initialCapital) {
        this.initialCapital = initialCapital;
        this.walletBalance = initialCapital;
        this.realizedProfit = 0.0;
    }

    public boolean checkGlobalKillSwitch() {
        if (isGlobalKilled) return true;
        if (walletBalance <= initialCapital * 0.80) {
            isGlobalKilled = true;
            System.out.println("💀💀💀 [最高灾难] 触发 20% 全局最大回撤！模拟盘已锁死！ 💀💀💀");
        }
        return isGlobalKilled;
    }

    // ==========================================
    // ⚔️ 终极开火指令 (支持做多/做空/加杠杆)
    // ==========================================
    public void openPosition(String side, double price, double marginAmount, int lev, String reason) {
        if (!positionSide.equals("NONE")) {
            System.out.println("⚠️ [指令驳回] 当前已有持仓，必须先平仓！");
            return;
        }
        if (marginAmount > walletBalance) {
            System.out.println("⚠️ [指令驳回] 余额不足！剩余可用: " + fmt(walletBalance));
            return;
        }

        this.positionSide = side.toUpperCase();
        this.leverage = lev;
        this.entryPrice = price;
        this.isolatedMargin = marginAmount;

        // 核心数学：开仓名义价值 = 保证金 * 杠杆
        double notionalValue = marginAmount * leverage;
        
        // 扣除开仓手续费 (按名义价值计算)
        double fee = notionalValue * TAKER_FEE;
        this.walletBalance -= fee; // 手续费直接从钱包扣除

        // 获得标的数量
        this.positionSize = notionalValue / price;

        System.out.println("\n🔥 [全军出击] 方向: " + (side.equals("LONG") ? "🟩 做多(LONG)" : "🟥 做空(SHORT)"));
        System.out.println("   ‣ 动用保证金: " + fmt(marginAmount) + " USDT | 杠杆: " + lev + "x");
        System.out.println("   ‣ 进场价格: " + fmt(price) + " | 获得筹码: " + fmt(positionSize) + " SOL");
        System.out.println("   ‣ 战术意图: " + reason);
        printStatus(price);
    }

    // ==========================================
    // 🛡️ 撤退/平仓结算中心
    // ==========================================
    public void closePosition(double price, String reason) {
        if (positionSide.equals("NONE")) return;

        double pnl = getUnrealizedPNL(price);
        double notionalValue = positionSize * price;
        double closeFee = notionalValue * TAKER_FEE;

        // 结算真金白银：退回保证金 + 利润 - 平仓手续费
        double netReturn = isolatedMargin + pnl - closeFee;
        
        this.walletBalance += (netReturn - isolatedMargin); // 更新钱包
        this.realizedProfit += (pnl - closeFee);           // 记录历史总利润

        System.out.println("\n🪂 [平仓撤退] 触发原因: " + reason);
        System.out.println("   ‣ 离场价格: " + fmt(price) + " | 净盈亏(含手续费): " + (pnl - closeFee > 0 ? "🟩 +" : "🟥 ") + fmt(pnl - closeFee) + " USDT");

        // 清空枪膛
        this.positionSide = "NONE";
        this.positionSize = 0;
        this.entryPrice = 0;
        this.isolatedMargin = 0;
        this.leverage = 1;

        printStatus(price);
    }

    // ==========================================
    // ☠️ 死亡审判：强制平仓(爆仓)检测
    // ==========================================
    public boolean checkLiquidation(double currentPrice) {
        if (positionSide.equals("NONE")) return false;

        double pnl = getUnrealizedPNL(currentPrice);
        // 如果亏损达到了保证金的 95%，触发交易所强制爆仓
        if (pnl <= -isolatedMargin * (1 - MAINT_MARGIN_RATE)) {
            System.out.println("\n💀 [毁灭打击] 触发强制平仓线！你的保证金被彻底清零！");
            // 爆仓时，保证金被没收，直接清空状态
            this.walletBalance -= isolatedMargin; 
            this.realizedProfit -= isolatedMargin;
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
    // 📊 雷达计算数学公式
    // ==========================================
    public double getUnrealizedPNL(double currentPrice) {
        if (positionSide.equals("LONG")) {
            return (currentPrice - entryPrice) * positionSize;
        } else if (positionSide.equals("SHORT")) {
            return (entryPrice - currentPrice) * positionSize;
        }
        return 0.0;
    }

    public double getROE(double currentPrice) {
        if (isolatedMargin == 0) return 0.0;
        return (getUnrealizedPNL(currentPrice) / isolatedMargin) * 100.0;
    }

    public double getLiquidationPrice() {
        if (positionSide.equals("NONE")) return 0.0;
        double bankruptcyDrop = isolatedMargin * (1 - MAINT_MARGIN_RATE) / positionSize;
        if (positionSide.equals("LONG")) return entryPrice - bankruptcyDrop;
        if (positionSide.equals("SHORT")) return entryPrice + bankruptcyDrop;
        return 0;
    }

    public double getRealizedProfit() { return realizedProfit; }
    public double getWalletBalance() { return walletBalance; }
    public String getPositionSide() { return positionSide; }

    public void printStatus(double price) {
        System.out.println("---------------------------------------------------------");
        System.out.println("🏦 [金库总值] 净余额: " + fmt(walletBalance) + " USDT | 纯利润: " + (realizedProfit>=0?"+":"") + fmt(realizedProfit) + " USDT");
        
        if (!positionSide.equals("NONE")) {
            double pnl = getUnrealizedPNL(price);
            System.out.println("📦 [当前战局] " + positionSide + " | 杠杆: " + leverage + "x | 保证金: " + fmt(isolatedMargin) + " USDT");
            System.out.println("   ‣ 价格: 开仓 " + fmt(entryPrice) + " -> 现价 " + fmt(price));
            System.out.println("   ‣ 浮动盈亏: " + (pnl>=0?"🟩 +":"🟥 ") + fmt(pnl) + " USDT (ROE: " + fmt(getROE(price)) + "%)");
            System.out.println("   ‣ 💀 死亡爆仓价: " + fmt(getLiquidationPrice()));
        } else {
            System.out.println("🛡️ [当前战局] 游击隐蔽中 (空仓)");
        }
        System.out.println("---------------------------------------------------------");
    }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}