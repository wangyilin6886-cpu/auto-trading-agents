package com.trading;

/**
 * 主入口 — 多智能体交易系统。
 *
 * 架构：
 *   MarketDataHub (5 流 WebSocket)
 *       ↓
 *   SignalEngine (7 维信号计算)
 *       ↓
 *   ┌─ FastLaneTrader (OBI 快通道，毫秒级)
 *   └─ MultiAgentOrchestrator (MIO/RRO/TEO，秒级)
 *       ↓
 *   OrderManager (原子订单 + Kelly 仓位)
 *       ↓
 *   BinanceRealAccount / SimulationAccount
 *
 * 风控：RiskManager (5 层) + DynamicLeverageEngine + PyramidManager
 * 日志：AuditLogger (JSON Lines) + TelegramNotifier
 */
public class Main {
    private static long lastEvalTime = 0;
    private static final Object evalLock = new Object();
    private static long lastHourlySummary = 0;

    public static void main(String[] args) {
        // ===== 1. 配置 & 日志 =====
        Config.printSummary();
        AuditLogger audit = AuditLogger.init(Config.LOG_DIR);
        audit.logSystem("System starting in " + Config.TRADING_MODE + " mode");

        // ===== 2. 风控 =====
        RiskManager riskManager = new RiskManager(Config.INITIAL_CAPITAL);

        // Kill Switch 检查
        if (riskManager.isKilled()) {
            System.out.println("[ABORT] Kill switch active. Delete " + Config.KILL_SWITCH_FILE + " to restart.");
            return;
        }

        // ===== 3. 网关 & 通知 =====
        OpenClawGatewayClient gateway = new OpenClawGatewayClient("openclaw");
        TelegramNotifier telegramNotifier = new TelegramNotifier(gateway);
        audit.setTelegramNotifier(telegramNotifier);

        // ===== 4. 交易账户 =====
        BinanceRealAccount account = new BinanceRealAccount(
                Config.BINANCE_API_KEY, Config.BINANCE_SECRET_KEY, Config.INITIAL_CAPITAL);
        account.setRiskManager(riskManager);

        SimulationAccount simAccount = new SimulationAccount(Config.INITIAL_CAPITAL);

        // ===== 5. 数据 & 信号 =====
        MarketDataHub dataHub = new MarketDataHub();
        SignalEngine signalEngine = new SignalEngine(dataHub);

        // ===== 6. 决策层 =====
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(gateway, signalEngine);
        OrderManager orderManager = new OrderManager(account, riskManager, telegramNotifier);
        FastLaneTrader fastLane = new FastLaneTrader(signalEngine, orderManager, account, riskManager);

        // ===== 7. 爆仓猎杀 =====
        LiquidationHunter liquidationHunter = new LiquidationHunter(account, riskManager);
        PyramidManager pyramidManager = new PyramidManager();

        // 旧引擎兼容初始化
        TradingDecisionEngine.init(riskManager);

        // ===== 8. JVM Shutdown Hook =====
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SHUTDOWN] Received shutdown signal...");
            dataHub.stop();
            audit.logSystem("System shutting down");
            if (!account.getPositionSide().equals("NONE")) {
                System.out.println("[SHUTDOWN] WARNING: Open position exists! Physical stop-loss on exchange is still active.");
            }
        }));

        // ===== 9. 启动数据流 =====
        dataHub.start();

        // 注册爆仓事件回调（让 LiquidationHunter 也能从 MarketDataHub 获取数据）
        dataHub.addForceOrderCallback(event -> {
            // LiquidationHunter 有自己的 WebSocket，这里不重复处理
        });

        // 启动爆仓猎杀引擎（独立 WebSocket）
        liquidationHunter.start();

        System.out.println("[READY] All systems online. Mode=" + Config.TRADING_MODE);
        audit.logSystem("All systems online");

        // ===== 10. 主循环 =====
        try {
            while (true) {
                Thread.sleep(1000); // 1 秒心跳

                // 数据就绪检查
                if (!dataHub.isReady()) continue;

                double currentPrice = dataHub.getLastPrice();
                if (currentPrice <= 0) continue;

                // 风控熔断检查
                if (riskManager.isKilled()) {
                    System.out.println("[MAIN] System killed, waiting for position cleanup...");
                    Thread.sleep(60000);
                    continue;
                }

                // ===== 快通道（每秒） =====
                fastLane.onTick(currentPrice);

                // ===== 爆仓猎杀出场检查 =====
                liquidationHunter.checkHuntExit(currentPrice);

                // ===== 信号计算（每秒） =====
                signalEngine.calculate();

                // ===== 持仓管理 =====
                if (!account.getPositionSide().equals("NONE")) {
                    manageLivePosition(currentPrice, account, orderManager, pyramidManager,
                            orchestrator, signalEngine);
                }

                // ===== 慢通道 AI 决策（每 15 秒） =====
                long now = System.currentTimeMillis();
                synchronized (evalLock) {
                    if (now - lastEvalTime > 15000) {
                        lastEvalTime = now;
                        final double priceForAI = currentPrice;

                        new Thread(() -> {
                            try {
                                slowChannelDecision(priceForAI, orchestrator, orderManager,
                                        account, signalEngine, pyramidManager);
                            } catch (Exception e) {
                                System.err.println("[MAIN] AI thread error: " + e.getMessage());
                                audit.logError("Main", "AI thread: " + e.getMessage(), null);
                            }
                        }).start();
                    }
                }

                // ===== 每小时汇总 =====
                if (now - lastHourlySummary > 3600_000) {
                    lastHourlySummary = now;
                    double balance = account.getWalletBalance();
                    double dailyPnl = account.getRealizedProfit();
                    double dailyPnlPct = Config.INITIAL_CAPITAL > 0
                            ? (dailyPnl / Config.INITIAL_CAPITAL) * 100 : 0;
                    String posInfo = account.getPositionSide().equals("NONE")
                            ? "No position"
                            : account.getPositionSide() + " " + account.getPositionSize() + " SOL";

                    audit.notifyHourlySummary(balance, dailyPnl, dailyPnlPct,
                            orderManager.getWins(), orderManager.getTotalTrades(), posInfo);
                }
            }
        } catch (Exception e) {
            System.err.println("[FATAL] System error: " + e.getMessage());
            e.printStackTrace();
            audit.logError("Main", "Fatal: " + e.getMessage(), null);
        }
    }

    // ==================== 持仓管理 ====================

    private static void manageLivePosition(double price, BinanceRealAccount account,
                                           OrderManager orderManager, PyramidManager pyramidManager,
                                           MultiAgentOrchestrator orchestrator, SignalEngine signalEngine) {
        double roe = account.getROE(price);

        // 爆仓检测
        if (account.checkLiquidation(price)) {
            pyramidManager.reset();
            return;
        }

        // 固定止损 ROE < -50%
        if (roe <= -50.0) {
            orderManager.closePosition(price, "StopLoss ROE=" + String.format("%.1f%%", roe));
            pyramidManager.reset();
            return;
        }

        // 固定止盈 ROE > 80%
        if (roe >= 80.0) {
            orderManager.closePosition(price, "TakeProfit ROE=" + String.format("%.1f%%", roe));
            pyramidManager.reset();
            return;
        }

        // 追踪止盈（ROE > 30% 激活）
        // 由 TradingDecisionEngine 管理（保留旧逻辑兼容）

        // 浮盈加仓检查
        String macro = orchestrator.getMioStrategy();
        double pyramidMargin = pyramidManager.checkPyramid(price, roe, macro);
        if (pyramidMargin > 0) {
            int lev = account.getLeverage();
            boolean added = orderManager.addToPosition(price, pyramidMargin, lev,
                    "Pyramid L" + (pyramidManager.getPyramidCount() + 1));
            if (added) {
                int addedQty = (int) Math.max(1, (pyramidMargin * lev) / price);
                pyramidManager.recordPyramidLayer(price, addedQty, pyramidMargin);

                double safeStop = pyramidManager.getSafeStopPrice();
                if (safeStop > 0) {
                    account.updateStopLoss(safeStop);
                }
            }
        }
    }

    // ==================== 慢通道 AI 决策 ====================

    private static void slowChannelDecision(double price, MultiAgentOrchestrator orchestrator,
                                            OrderManager orderManager, BinanceRealAccount account,
                                            SignalEngine signalEngine, PyramidManager pyramidManager) {
        double rsi = signalEngine.getLastRSI();
        double composite = signalEngine.getLastComposite();

        // 刷新 MIO / RRO
        orchestrator.refreshMIO(price, rsi);
        orchestrator.refreshRRO(price, rsi, signalEngine.getLastVOL());

        boolean hasPos = !account.getPositionSide().equals("NONE");

        System.out.println("\n[AI] Price=" + String.format("%.4f", price)
                + " | " + signalEngine.summary()
                + " | Macro=" + orchestrator.getMioStrategy()
                + " | Risk=" + orchestrator.getRroRisk());

        // TEO 决策
        MultiAgentOrchestrator.Decision decision = orchestrator.askTEO(
                price, rsi, hasPos, account.getPositionSide());

        System.out.println("[AI] Decision: " + decision.action + " | " + decision.reason
                + (decision.validated ? " [VALIDATED]" : " [REJECTED]"));

        if ("CLOSE".equals(decision.action) && hasPos) {
            orderManager.closePosition(price, "AI: " + decision.reason);
            pyramidManager.reset();
        } else if (decision.action.startsWith("TRAP_") && !hasPos && decision.validated) {
            String side = decision.action.replace("TRAP_", "");

            // 动态杠杆
            String macro = orchestrator.getMioStrategy();
            String risk = orchestrator.getRroRisk();
            int leverage = DynamicLeverageEngine.calculate(side, rsi,
                    signalEngine.getLastVOL(), macro, risk, composite);

            // 部署陷阱（使用旧的 TacticalTrap 机制）
            double triggerPrice = Math.round(decision.triggerPrice * 1000.0) / 1000.0;
            System.out.println("[AI] Trap deployed: " + side + " @ " + triggerPrice);

            // 直接开仓（不等陷阱触发，因为 TEO 已经考虑了时机）
            if (Math.abs(price - triggerPrice) / price < 0.005) {
                // 价格距离触发价 < 0.5%，直接开仓
                orderManager.openPosition(side, price, leverage, "AI_" + decision.action);
                if (!account.getPositionSide().equals("NONE")) {
                    pyramidManager.recordBaseLayer(side, price, account.getPositionSize(),
                            account.getIsolatedMargin());
                }
            }
            // 否则等待 TradingDecisionEngine 的陷阱机制处理
        } else {
            if (!hasPos) {
                account.printStatus(price);
            }
        }
    }
}
