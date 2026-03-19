package com.trading;

import java.net.URI;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

public class Main {
    private static long lastEvalTime = 0;
    private static long lastGridStatusTime = 0;

    public static void main(String[] args) {
        System.out.println("=====================================================");
        System.out.println("  Whale Harvester v6.0 - Pulse Engine + Grid Hybrid");
        System.out.println("=====================================================\n");

        // ==========================================
        // 模式切换：命令行参数 --real 开启实盘
        //   默认: 模拟盘
        //   java -jar bot.jar --real      → 实盘
        //   java -jar bot.jar             → 模拟盘
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
        // 资金分配：子弹仓 → 60%脉冲引擎 + 40%突破策略
        // ==========================================
        double bulletTotal = account.getBulletBalance(); // 42U (140 * 30%)
        double pulseAllocation = account.allocateFromBullet(bulletTotal * 0.60); // 25.2U给脉冲引擎
        // 剩余 16.8U 留给突破策略

        System.out.println("\n[FUND SPLIT]");
        System.out.println("  Pulse engine:    " + String.format("%.2f", pulseAllocation) + " USDT (60% of bullet)");
        System.out.println("  Breakout engine: " + String.format("%.2f", account.getBulletBalance()) + " USDT (40% of bullet)");
        System.out.println();

        // ==========================================
        // 脉冲引擎：自适应四模式切换
        // ==========================================
        PulseEngine pulseEngine = new PulseEngine(pulseAllocation, account);

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

                        // === 毫秒级：脉冲引擎（每条消息） ===
                        pulseEngine.onTick(currentPrice, currentVolume);

                        // === 毫秒级：突破陷阱检测（每条消息） ===
                        TradingDecisionEngine.checkFastTrap(currentPrice, account);

                        // === 15秒：突破策略决策 ===
                        long now = System.currentTimeMillis();
                        if (now - lastEvalTime > 15000) {
                            lastEvalTime = now;
                            final double priceForAI = currentPrice;
                            new Thread(() -> {
                                try {
                                    TradingDecisionEngine.evaluateAndAskAI(priceForAI, gateway, account);
                                } catch (Exception e) {
                                    System.out.println("[ENGINE ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                                    e.printStackTrace();
                                }
                            }).start();
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
            System.out.println("[SYSTEM] To switch: java -jar bot.jar --real  OR  java -jar bot.jar");

            while (true) { Thread.sleep(60000); }

        } catch (Exception e) {
            System.out.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
        }
    }
}
