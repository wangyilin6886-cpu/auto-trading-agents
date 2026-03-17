package com.trading;

public class TestDestruction {

    public static void main(String[] args) {
        System.out.println("=== 💀 巨鲸收割者：破坏性演习开始 ===");
        
        // 你手里只有 500 U
        FuturesVirtualAccount account = new FuturesVirtualAccount(500.0);
        double currentPrice = 100.0;

        // ==========================================
        // 场景 1：精准做空 (价格跌了，你赚钱)
        // ==========================================
        System.out.println("\n[场景1] R1察觉到砸盘，指示开 10倍 做空！");
        // 拿 50 U 的本金，开 10 倍做空
        account.openPosition("SHORT", currentPrice, 50.0, 10, "R1预判瀑布");

        // 价格暴跌到 90
        currentPrice = 90.0;
        System.out.println("\n>>> 市场暴跌至 90 USDT，散户在哀嚎，我们在数钱...");
        account.printStatus(currentPrice); // 此时ROE应该高达 +100%

        // 止盈平仓
        account.closePosition(currentPrice, "吃饱喝足，落袋为安");


        // ==========================================
        // 场景 2：滚雪球暴击 (反马丁格尔)
        // ==========================================
        System.out.println("\n[场景2] V3加特林检测到超级信号，启动利润滚雪球！");
        currentPrice = 100.0;
        
        // 提取刚才赚到的纯利润 (大概 49 U)
        double profitToGamble = account.getRealizedProfit(); 
        
        // 拿赚来的纯利润，直接开 20 倍做多！输了本金一分不少！
        account.openPosition("LONG", currentPrice, profitToGamble, 20, "拿利润去狂赌！");

        // 价格稍微涨了 2块钱
        currentPrice = 102.0;
        System.out.println("\n>>> 市场拉升至 102 USDT...");
        account.printStatus(currentPrice); // 感受一下 20 倍杠杆下，涨 2% 利润有多恐怖


        // ==========================================
        // 场景 3：死亡插针 (体验爆仓的绝望)
        // ==========================================
        System.out.println("\n[场景3] 突发黑天鹅，庄家恶意砸盘插针！");
        currentPrice = 94.0; // 跌破了多单的爆仓价 (大概是 95 左右)
        System.out.println(">>> 价格瞬间砸到 94 USDT...");
        
        // 每次价格变动，底层都会调用 checkLiquidation
        boolean isDead = account.checkLiquidation(currentPrice);
        if (isDead) {
            System.out.println("系统报告：单子被交易所强制平仓。但你看一眼总金库，你的 500 U 初始本金还在不在？");
        }
    }
}