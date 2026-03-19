package com.trading;

import java.net.URI;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

public class Main {
    private static long lastEvalTime = 0;

    public static void main(String[] args) {
        System.out.println("=====================================================");
        System.out.println("  Whale Harvester v5.0 - Breakout + Dynamic Leverage");
        System.out.println("=====================================================\n");

        OpenClawGatewayClient gateway = new OpenClawGatewayClient("openclaw");

        // === 模拟盘 (默认) ===
        // 1000 RMB ≈ 140 USDT
        FuturesVirtualAccount account = new FuturesVirtualAccount(140.0);

        // === 实盘 (注释掉) ===
        // String API_KEY = System.getenv("BINANCE_API_KEY");
        // String SECRET_KEY = System.getenv("BINANCE_SECRET_KEY");
        // BinanceRealAccount account = new BinanceRealAccount(API_KEY, SECRET_KEY, 140.0);

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
                        if (localTime - eventTime > 5000) return; // 丢弃过期数据

                        JSONObject kline = json.getJSONObject("k");
                        double currentPrice = kline.getDouble("c");
                        double currentVolume = kline.getDouble("v");

                        IndicatorCalculator.addData(currentPrice, currentVolume);

                        // 毫秒级陷阱检测 - 每条消息都检查
                        TradingDecisionEngine.checkFastTrap(currentPrice, account);

                        // 15秒决策周期
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

            // 主线程保活
            while (true) { Thread.sleep(60000); }

        } catch (Exception e) {
            System.out.println("[FATAL] " + e.getMessage());
            e.printStackTrace();
        }
    }
}
