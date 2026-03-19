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
        System.out.println("  Whale Harvester v5.1 - Breakout + Grid Hybrid");
        System.out.println("=====================================================\n");

        OpenClawGatewayClient gateway = new OpenClawGatewayClient("openclaw");

        // === 模拟盘 (默认) ===
        // 1000 RMB ≈ 140 USDT
        FuturesVirtualAccount account = new FuturesVirtualAccount(140.0);

        // === 实盘 (注释掉) ===
        // String API_KEY = System.getenv("BINANCE_API_KEY");
        // String SECRET_KEY = System.getenv("BINANCE_SECRET_KEY");
        // BinanceRealAccount account = new BinanceRealAccount(API_KEY, SECRET_KEY, 140.0);

        // ==========================================
        // 资金分配：子弹仓 → 60%网格 + 40%突破
        // ==========================================
        double bulletTotal = account.getBulletBalance(); // 42U (140 * 30%)
        double gridAllocation = account.allocateFromBullet(bulletTotal * 0.60); // 25.2U给网格
        // 剩余 16.8U 留给突破策略

        System.out.println("\n[FUND SPLIT]");
        System.out.println("  Grid engine:    " + String.format("%.2f", gridAllocation) + " USDT (60% of bullet)");
        System.out.println("  Breakout engine: " + String.format("%.2f", account.getBulletBalance()) + " USDT (40% of bullet)");
        System.out.println();

        // ==========================================
        // 网格引擎：8格 × 0.3%格距 × 5倍杠杆
        // ==========================================
        GridTradingEngine gridEngine = new GridTradingEngine(
                gridAllocation,
                8,          // 单边8格（上下各8，共16格可开仓）
                0.003,      // 0.3% 格距（SOL ~130U → 每格 ~0.39U）
                5,          // 5x 杠杆（网格用低杠杆，安全优先）
                account     // 利润回流到主账户金库
        );

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

                        // === 毫秒级：网格引擎（每条消息） ===
                        gridEngine.onPriceUpdate(currentPrice);

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

                        // === 5分钟：打印网格状态 ===
                        if (now - lastGridStatusTime > 300000) {
                            lastGridStatusTime = now;
                            gridEngine.printStatus(currentPrice);
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

            while (true) { Thread.sleep(60000); }

        } catch (Exception e) {
            System.out.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
        }
    }
}
