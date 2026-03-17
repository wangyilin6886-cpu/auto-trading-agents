package com.trading;

import java.net.URI;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

public class Main {
    private static long lastEvalTime = 0;

    public static void main(String[] args) {
        System.out.println("=====================================================");
        System.out.println("🚀 [实盘点火] 巨鲸收割者 5.0 (HFT 陷阱触发引擎版)");
        System.out.println("=====================================================\n");

        String API_KEY = "gKPgDkGiFRPHYaT7qwH4uYlw404oVC9KVdsdyrpCtzx37zj8y73fMTcQo01Ah5sL"; 
        String SECRET_KEY = "i8zlTP9YuCwuzksMRsPxa5hE8PbCczS0owMRrwgq8ddceHmrqZ1qHIOKve7eoVjr";

        OpenClawGatewayClient gateway = new OpenClawGatewayClient("openclaw");
        BinanceRealAccount account = new BinanceRealAccount(API_KEY, SECRET_KEY, 50.0);

        try {
            URI uri = new URI("wss://stream.binance.com:9443/ws/solusdt@kline_1m");
            
            WebSocketClient client = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("✅ [实盘雷达���线] HFT 陷阱感应器已全部激活！");
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

                        // ⚡⚡⚡ 核心改造：毫秒级陷阱感应器！
                        // 不管 AI 是不是在思考，主线每一毫秒收到价格都会走这里检查陷阱！
                        TradingDecisionEngine.checkFastTrap(currentPrice, account);

                        long now = System.currentTimeMillis();
                        if (now - lastEvalTime > 15000) { 
                            lastEvalTime = now;
                            final double priceForAI = currentPrice;
                            new Thread(() -> {
                                TradingDecisionEngine.evaluateAndAskAI(priceForAI, gateway, account);
                            }).start();
                        }
                    } catch (Exception e) {}
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    new Thread(() -> {
                        try { Thread.sleep(5000); this.reconnect(); } catch (Exception e) {}
                    }).start();
                }

                @Override
                public void onError(Exception ex) {}
            };
            
            client.connect();
            while (true) { Thread.sleep(60000); }

        } catch (Exception e) {}
    }
}