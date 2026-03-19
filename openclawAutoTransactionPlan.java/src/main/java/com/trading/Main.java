package com.trading;

import java.net.URI;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

public class Main {
    private static long lastEvalTime = 0;
    private static long lastGridStatusTime = 0;
    private static long lastSubEngineStatusTime = 0;

    public static void main(String[] args) {
        System.out.println("=====================================================");
        System.out.println("  Whale Harvester v7.0 - Pulse v2.0 + 6 Strategies");
        System.out.println("=====================================================\n");

        // ==========================================
        // 模式切换：命令行参数 --real 开启实盘
        // ==========================================
        boolean isRealMode = false;
        double initialCapital = 140.0; // 1000 RMB ≈ 140 USDT

        for (String arg : args) {
            if (arg.equals("--real")) isRealMode = true;
            if (arg.startsWith("--capital=")) {
                try { initialCapital = Double.parseDouble(arg.substring("--capital=".length())); }
                catch (NumberFormatException e) { System.out.println("[WARN] Invalid capital: " + arg); }
            }
        }

        TradingAccount account;
        if (isRealMode) {
            String API_KEY = System.getenv("BINANCE_API_KEY");
            String SECRET_KEY = System.getenv("BINANCE_SECRET_KEY");
            if (API_KEY == null || SECRET_KEY == null) {
                System.out.println("[FATAL] BINANCE_API_KEY and BINANCE_SECRET_KEY env vars required for real mode!");
                System.out.println("  export BINANCE_API_KEY=your_key");
                System.out.println("  export BINANCE_SECRET_KEY=your_secret");
                return;
            }
            account = new BinanceRealAccount(API_KEY, SECRET_KEY, initialCapital);
            System.out.println("[MODE] >>> REAL TRADING <<< capital=" + initialCapital + " USDT");
        } else {
            account = new FuturesVirtualAccount(initialCapital);
            System.out.println("[MODE] Simulation mode | capital=" + initialCapital + " USDT");
        }

        OpenClawGatewayClient gateway = new OpenClawGatewayClient("openclaw");

        // ==========================================
        // 资金分配 v7.0：
        //   子弹仓 → 50% PulseEngine + 20% 突破策略 + 30% 新策略子引擎
        //   新策略：15% WickHarvester + 8% SqueezeDetonator + 7% LiquidationHunter
        // ==========================================
        double bulletTotal = account.getBulletBalance();
        double pulseAllocation = account.allocateFromBullet(bulletTotal * 0.50);
        double wickAllocation = account.allocateFromBullet(account.getBulletBalance() * 0.30); // 30% of remaining = ~15% total
        double squeezeAllocation = account.allocateFromBullet(account.getBulletBalance() * 0.38); // ~8% total
        double huntAllocation = account.allocateFromBullet(account.getBulletBalance() * 0.50); // ~7% total
        // 剩余 ~20% 留给突破策略 (TradingDecisionEngine)

        System.out.println("\n[FUND SPLIT v7.0]");
        System.out.println("  Pulse engine:      " + String.format("%.2f", pulseAllocation) + " USDT (50%)");
        System.out.println("  Wick harvester:    " + String.format("%.2f", wickAllocation) + " USDT (15%)");
        System.out.println("  Squeeze detonator: " + String.format("%.2f", squeezeAllocation) + " USDT (8%)");
        System.out.println("  Liquidation hunter:" + String.format("%.2f", huntAllocation) + " USDT (7%)");
        System.out.println("  Breakout engine:   " + String.format("%.2f", account.getBulletBalance()) + " USDT (20%)");
        System.out.println("  Session:           " + SessionKiller.getCurrentSession());
        System.out.println();

        // ==========================================
        // 初始化所有引擎
        // ==========================================
        PulseEngine pulseEngine = new PulseEngine(pulseAllocation, account);
        WickHarvester wickHarvester = new WickHarvester(wickAllocation, account);
        SqueezeDetonator squeezeDetonator = new SqueezeDetonator(squeezeAllocation, account);
        LiquidationHunter liquidationHunter = new LiquidationHunter(huntAllocation, account);

        try {
            URI uri = new URI("wss://stream.binance.com:9443/ws/solusdt@kline_1m");

            WebSocketClient client = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("[WS] Connected to Binance SOL/USDT stream\n");
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JSONObject json = new JSONObject(message);

                        long eventTime = json.getLong("E");
                        long localTime = System.currentTimeMillis();
                        if (localTime - eventTime > 5000) return;

                        JSONObject kline = json.getJSONObject("k");
                        double currentPrice = kline.getDouble("c");
                        double currentVolume = kline.getDouble("v");

                        IndicatorCalculator.addData(currentPrice, currentVolume);

                        // === 毫秒级引擎（每条消息都触发） ===

                        // 1. PulseEngine (网格/趋势/冲浪/呼吸)
                        pulseEngine.onTick(currentPrice, currentVolume);

                        // 2. WickHarvester (插针回收 - 需要毫秒级响应)
                        wickHarvester.onTick(currentPrice, localTime);

                        // 3. SqueezeDetonator (波动率压缩检测)
                        squeezeDetonator.onTick(currentPrice);

                        // 4. 突破陷阱检测
                        TradingDecisionEngine.checkFastTrap(currentPrice, account);

                        // === 15秒级引擎 ===
                        long now = System.currentTimeMillis();
                        if (now - lastEvalTime > 15000) {
                            lastEvalTime = now;
                            final double priceForAI = currentPrice;

                            new Thread(() -> {
                                try {
                                    // 5. LiquidationHunter (每15秒检查 funding rate)
                                    liquidationHunter.onTick(priceForAI);

                                    // 6. 突破策略 + AI决策
                                    TradingDecisionEngine.evaluateAndAskAI(priceForAI, gateway, account);
                                } catch (Exception e) {
                                    System.out.println("[ENGINE ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                }
                            }).start();
                        }

                        // === 5分钟级：子引擎状态打印 ===
                        if (now - lastSubEngineStatusTime > 300000) {
                            lastSubEngineStatusTime = now;
                            System.out.println("\n=================== [SUB-ENGINE STATUS] ===================");
                            wickHarvester.printStatus();
                            squeezeDetonator.printStatus();
                            liquidationHunter.printStatus();
                            System.out.println("=============================================================\n");
                        }

                    } catch (Exception e) {
                        System.out.println("[WS MSG ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("[WS] Disconnected (code=" + code + " reason=" + reason + "). Reconnecting in 5s...");
                    new Thread(() -> {
                        try { Thread.sleep(5000); this.reconnect(); }
                        catch (Exception e) { System.out.println("[WS] Reconnect failed: " + e.getMessage()); }
                    }).start();
                }

                @Override
                public void onError(Exception ex) {
                    System.out.println("[WS ERROR] " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            };

            client.connect();
            System.out.println("[SYSTEM] Waiting for WebSocket connection...");
            System.out.println("[SYSTEM] Mode: " + (isRealMode ? "REAL" : "SIMULATION"));
            System.out.println("[SYSTEM] Engines: PulseV2 + WickHarvester + SqueezeDetonator + LiquidationHunter + Breakout");
            System.out.println("[SYSTEM] To switch: java -jar bot.jar --real  OR  java -jar bot.jar");

            while (true) { Thread.sleep(60000); }

        } catch (Exception e) {
            System.out.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
        }
    }
}
