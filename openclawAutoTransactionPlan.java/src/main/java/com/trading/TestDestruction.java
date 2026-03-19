package com.trading;

public class TestDestruction {

    public static void main(String[] args) {
        System.out.println("=== Whale Harvester v5.0 - Stress Test ===\n");

        // 140 USDT 初始资金 (≈1000 RMB)
        FuturesVirtualAccount account = new FuturesVirtualAccount(140.0);
        double currentPrice = 130.0;

        // ==========================================
        // 场景1：B级信号做多 (10x, 20%子弹仓)
        // ==========================================
        System.out.println("\n[Scene 1] B-signal LONG with 10x leverage");
        double bulletPct = 0.20;
        double margin = account.getBulletBalance() * bulletPct; // 42 * 0.20 = 8.4U
        account.openPosition("LONG", currentPrice, margin, 10, "B-signal RSI=38 trend=NEUTRAL");

        // 涨2% → ROE ≈ +20%
        currentPrice = 132.6;
        System.out.println("\n>>> Price up 2% -> " + currentPrice);
        System.out.println("    ROE = " + String.format("%.1f%%", account.getROE(currentPrice)));
        account.printStatus(currentPrice);

        // TP1 触发：平1/3
        System.out.println("\n--- TP1: close 1/3 ---");
        account.closePartial(currentPrice, 0.333, "TP1 ROE=+20%");

        // 继续涨到 +40% ROE
        currentPrice = 135.2;
        System.out.println("\n>>> Price continues to " + currentPrice);
        System.out.println("    ROE = " + String.format("%.1f%%", account.getROE(currentPrice)));

        // TP2 触发：再平1/3 (剩余的50%)
        System.out.println("\n--- TP2: close half remaining ---");
        account.closePartial(currentPrice, 0.50, "TP2 ROE=+40%");

        // 最后1/3移动止盈平仓
        currentPrice = 134.0;
        System.out.println("\n--- Trailing stop: close remaining ---");
        account.closePosition(currentPrice, "Trailing stop");

        // ==========================================
        // 场景2：S级信号做空 (20x, 50%子弹仓)
        // ==========================================
        System.out.println("\n\n[Scene 2] S-signal SHORT with 20x leverage");
        currentPrice = 130.0;
        margin = account.getBulletBalance() * 0.50;
        account.openPosition("SHORT", currentPrice, margin, 20, "S-signal RSI=80 BEAR vol=3x breakdown");

        // 跌3% → ROE ≈ +60%
        currentPrice = 126.1;
        System.out.println("\n>>> Price drops 3% -> " + currentPrice);
        System.out.println("    ROE = " + String.format("%.1f%%", account.getROE(currentPrice)));
        account.closePosition(currentPrice, "Full take profit");

        // ==========================================
        // 场景3：止损测试 (价格反向)
        // ==========================================
        System.out.println("\n\n[Scene 3] Stop loss test");
        currentPrice = 130.0;
        margin = account.getBulletBalance() * 0.30;
        account.openPosition("LONG", currentPrice, margin, 15, "A-signal test");

        // 跌1% → 15x杠杆下 ROE ≈ -15%
        currentPrice = 129.0;
        System.out.println("\n>>> Price drops to " + currentPrice);
        System.out.println("    ROE = " + String.format("%.1f%%", account.getROE(currentPrice)));
        account.closePosition(currentPrice, "STOP LOSS ROE=-15%");

        // ==========================================
        // 最终状态
        // ==========================================
        System.out.println("\n\n=== FINAL STATUS ===");
        account.printStatus(130.0);
        System.out.println("Vault preserved: " + String.format("%.2f", account.getVaultBalance()) + " USDT");
        System.out.println("Bullet remaining: " + String.format("%.2f", account.getBulletBalance()) + " USDT");
    }
}
