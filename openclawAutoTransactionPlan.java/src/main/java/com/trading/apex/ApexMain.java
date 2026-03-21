package com.trading.apex;

import com.trading.SessionKiller;

import java.util.*;
import java.util.concurrent.*;

/**
 * ApexMain - Apex Predator 超高频交易系统入口
 *
 * 架构:
 *   DataEngine(多流WS) → MarketMicrostructure(统一状态)
 *       ↓ 每100ms回调
 *   FusionEngine(4感知器融合) → RiskEngine(4层风控) → Account(开平仓)
 *
 * 启动方式:
 *   模拟盘(默认):  java -cp ... com.trading.apex.ApexMain
 *   实盘:          java -cp ... com.trading.apex.ApexMain --real
 *   自定义本金:     java -cp ... com.trading.apex.ApexMain --capital=1000
 *   指定币种:       java -cp ... com.trading.apex.ApexMain --symbol=SOLUSDT
 *   多币种:         java -cp ... com.trading.apex.ApexMain --multi=3
 *   单币种(默认SOL): java -cp ... com.trading.apex.ApexMain
 */
public class ApexMain {

    // 所有活跃的交易引擎实例(每个币种一个)
    private static final Map<String, EngineInstance> engines = new ConcurrentHashMap<>();
    private static ApexAccount account;
    private static RiskEngine riskEngine;
    private static CoinSelector coinSelector;
    private static ScheduledExecutorService scheduler;

    static class EngineInstance {
        final String symbol;
        final DataEngine dataEngine;
        final MarketMicrostructure state;
        final FusionEngine fusionEngine;

        EngineInstance(String symbol, DataEngine de, MarketMicrostructure ms, FusionEngine fe) {
            this.symbol = symbol;
            this.dataEngine = de;
            this.state = ms;
            this.fusionEngine = fe;
        }
    }

    public static void main(String[] args) {
        printBanner();

        // === 解析命令行参数 ===
        boolean isRealMode = false;
        double initialCapital = 1000.0;
        String singleSymbol = null;
        int multiCount = 0; // 0=单币种模式

        for (String arg : args) {
            if (arg.equals("--real")) isRealMode = true;
            if (arg.startsWith("--capital=")) {
                try { initialCapital = Double.parseDouble(arg.substring("--capital=".length())); }
                catch (NumberFormatException e) { System.out.println("[WARN] Invalid capital: " + arg); }
            }
            if (arg.startsWith("--symbol=")) {
                singleSymbol = arg.substring("--symbol=".length()).toUpperCase();
            }
            if (arg.startsWith("--multi=")) {
                try { multiCount = Integer.parseInt(arg.substring("--multi=".length())); }
                catch (NumberFormatException e) { multiCount = 3; }
            }
        }

        // === 初始化账户 ===
        if (isRealMode) {
            String apiKey = System.getenv("BINANCE_API_KEY");
            String secretKey = System.getenv("BINANCE_SECRET_KEY");
            if (apiKey == null || secretKey == null) {
                System.out.println("[FATAL] Real mode requires environment variables:");
                System.out.println("  export BINANCE_API_KEY=your_key");
                System.out.println("  export BINANCE_SECRET_KEY=your_secret");
                return;
            }
            account = new ApexAccount(apiKey, secretKey, initialCapital);
        } else {
            account = new ApexAccount(initialCapital);
        }

        // === 初始化风控引擎 ===
        riskEngine = new RiskEngine(initialCapital);

        // === 初始化调度器 ===
        scheduler = Executors.newScheduledThreadPool(4);

        // === 选择交易币种 ===
        List<String> symbols = new ArrayList<>();
        if (singleSymbol != null) {
            symbols.add(singleSymbol);
        } else if (multiCount > 0) {
            coinSelector = new CoinSelector();
            coinSelector.refresh();
            List<CoinSelector.CoinScore> top = coinSelector.getTopCoins(multiCount);
            for (CoinSelector.CoinScore cs : top) {
                symbols.add(cs.symbol);
            }
            if (symbols.isEmpty()) {
                symbols.add("SOLUSDT"); // 兜底
            }
        } else {
            symbols.add("SOLUSDT"); // 默认
        }

        System.out.println("\n[APEX] Trading symbols: " + symbols);
        System.out.println("[APEX] Session: " + SessionKiller.getCurrentSession());
        System.out.println("[APEX] Mode: " + (isRealMode ? ">>> REAL <<<" : "SIMULATION"));
        System.out.println();

        // === 为每个币种启动引擎 ===
        for (String sym : symbols) {
            startEngineForSymbol(sym);
        }

        // === 定时任务 ===

        // 1. 每60秒打印账户状态
        scheduler.scheduleAtFixedRate(() -> {
            try {
                double px = 0;
                for (EngineInstance ei : engines.values()) {
                    if (ei.state.price > 0) { px = ei.state.price; break; }
                }
                if (px > 0) account.printStatus(px);
                System.out.println(riskEngine.statusLine());
            } catch (Exception e) {
                System.out.println("[STATUS ERROR] " + e.getMessage());
            }
        }, 30, 60, TimeUnit.SECONDS);

        // 2. 每5秒检查时区切换
        scheduler.scheduleAtFixedRate(() -> {
            try {
                SessionKiller.checkAndPrintSessionChange();
            } catch (Exception e) {}
        }, 5, 5, TimeUnit.SECONDS);

        // 3. 多币种模式: 每小时重新选币
        if (multiCount > 0 && coinSelector != null) {
            final int mc = multiCount;
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    rebalanceSymbols(mc);
                } catch (Exception e) {
                    System.out.println("[REBALANCE ERROR] " + e.getMessage());
                }
            }, 3600, 3600, TimeUnit.SECONDS);
        }

        // === 优雅关闭 ===
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[APEX] Shutting down...");
            for (EngineInstance ei : engines.values()) {
                ei.dataEngine.shutdown();
            }
            scheduler.shutdownNow();
            double px = 0;
            for (EngineInstance ei : engines.values()) {
                if (ei.state.price > 0) { px = ei.state.price; break; }
            }
            if (px > 0) account.printStatus(px);
            System.out.println("[APEX] Shutdown complete.");
        }));

        System.out.println("[APEX] System running. Press Ctrl+C to stop.\n");

        // 主线程保持存活
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.out.println("[APEX] Main thread interrupted.");
        }
    }

    /**
     * 为指定币种启动完整的交易引擎链
     */
    private static void startEngineForSymbol(String symbol) {
        if (engines.containsKey(symbol)) {
            System.out.println("[APEX] Engine already running for " + symbol);
            return;
        }

        MarketMicrostructure state = new MarketMicrostructure();
        FusionEngine fusion = new FusionEngine(riskEngine, account, symbol);
        DataEngine data = new DataEngine(symbol, state);

        // DataEngine每100ms回调 → FusionEngine处理
        data.setOnTickCallback(() -> {
            try {
                fusion.onTick(state);
            } catch (Exception e) {
                System.out.println("[" + symbol + " FUSION ERROR] " + e.getMessage());
            }
        });

        data.start();

        engines.put(symbol, new EngineInstance(symbol, data, state, fusion));
        System.out.println("[APEX] Engine started for " + symbol);
    }

    /**
     * 停止指定币种的引擎
     */
    private static void stopEngineForSymbol(String symbol) {
        EngineInstance ei = engines.remove(symbol);
        if (ei != null) {
            ei.dataEngine.shutdown();
            System.out.println("[APEX] Engine stopped for " + symbol);
        }
    }

    /**
     * 多币种重平衡: 每小时重新选币
     */
    private static void rebalanceSymbols(int targetCount) {
        if (coinSelector == null) return;

        coinSelector.refresh();
        List<CoinSelector.CoinScore> top = coinSelector.getTopCoins(targetCount);
        Set<String> newSymbols = new HashSet<>();
        for (CoinSelector.CoinScore cs : top) {
            newSymbols.add(cs.symbol);
        }

        // 停掉不再需要的
        Set<String> toRemove = new HashSet<>(engines.keySet());
        toRemove.removeAll(newSymbols);
        for (String sym : toRemove) {
            // 只有在没有持仓时才停
            EngineInstance ei = engines.get(sym);
            if (ei != null && ei.fusionEngine.getPositionSide().equals("NONE")) {
                stopEngineForSymbol(sym);
            }
        }

        // 启动新的
        for (String sym : newSymbols) {
            if (!engines.containsKey(sym)) {
                startEngineForSymbol(sym);
            }
        }

        System.out.println("[REBALANCE] Active symbols: " + engines.keySet());
    }

    private static void printBanner() {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║                                                      ║");
        System.out.println("║     APEX PREDATOR v1.0                               ║");
        System.out.println("║     Ultra-HFT Signal Fusion Trading System           ║");
        System.out.println("║                                                      ║");
        System.out.println("║     4 Sensors | Fusion Engine | 4-Layer Risk         ║");
        System.out.println("║     Multi-Symbol | Cascade Compounding               ║");
        System.out.println("║                                                      ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
    }
}
