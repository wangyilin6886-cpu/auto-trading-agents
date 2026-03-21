package com.trading.apex;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DataEngine - 多流WebSocket数据引擎
 *
 * 同时连接6条币安数据流:
 *   1. aggTrade     - 逐笔成交(核心，毫秒级)
 *   2. depth@100ms  - 订单簿20档
 *   3. forceOrder   - 全市场爆仓单
 *   4. kline_1s     - 1秒K线
 *   5. miniTicker   - 24h滚动统计
 *   6. markPrice@1s - 标记价格+资金费率
 *
 * 所有数据汇入 MarketMicrostructure 统一状态对象
 */
public class DataEngine {

    private final MarketMicrostructure state;
    private final String symbol;
    private WebSocketClient wsClient;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile long lastMessageTime = 0;
    private Runnable onTickCallback;

    // 多币种支持: 可创建多个DataEngine实例
    public DataEngine(String symbol, MarketMicrostructure state) {
        this.symbol = symbol.toLowerCase();
        this.state = state;
        state.symbol = symbol.toUpperCase();
    }

    public void setOnTickCallback(Runnable callback) {
        this.onTickCallback = callback;
    }

    /**
     * 启动数据引擎: 连接WebSocket + 启动定时任务
     */
    public void start() {
        connectWebSocket();

        // 每100ms重算逐笔成交衍生指标
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long now = System.currentTimeMillis();
                state.recalcTradeFlow(now);
                if (onTickCallback != null && state.price > 0) {
                    onTickCallback.run();
                }
            } catch (Exception e) {
                System.out.println("[DATA] recalc error: " + e.getMessage());
            }
        }, 500, 100, TimeUnit.MILLISECONDS);

        // 每30秒健康检查
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long silence = System.currentTimeMillis() - lastMessageTime;
                if (silence > 10000 && lastMessageTime > 0) {
                    System.out.println("[DATA] No data for " + (silence / 1000) + "s, reconnecting...");
                    reconnect();
                }
            } catch (Exception e) {
                System.out.println("[DATA] health check error: " + e.getMessage());
            }
        }, 15, 30, TimeUnit.SECONDS);

        System.out.println("[DATA ENGINE] Started for " + symbol.toUpperCase() + " | 6 streams active");
    }

    private void connectWebSocket() {
        try {
            // 使用combined stream连接多条数据流
            String streams = String.join("/",
                symbol + "@aggTrade",
                symbol + "@depth20@100ms",
                symbol + "@forceOrder",
                symbol + "@kline_1s",
                symbol + "@miniTicker",
                symbol + "@markPrice@1s"
            );
            URI uri = new URI("wss://fstream.binance.com/stream?streams=" + streams);

            wsClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected.set(true);
                    lastMessageTime = System.currentTimeMillis();
                    System.out.println("[WS] Connected to Binance futures stream | " + symbol.toUpperCase());
                }

                @Override
                public void onMessage(String message) {
                    try {
                        lastMessageTime = System.currentTimeMillis();
                        JSONObject wrapper = new JSONObject(message);
                        String stream = wrapper.getString("stream");
                        JSONObject data = wrapper.getJSONObject("data");
                        dispatchMessage(stream, data);
                    } catch (Exception e) {
                        // 静默处理解析错误，不影响其他消息
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected.set(false);
                    System.out.println("[WS] Disconnected (" + code + ": " + reason + ") reconnecting in 3s...");
                    scheduler.schedule(() -> reconnect(), 3, TimeUnit.SECONDS);
                }

                @Override
                public void onError(Exception ex) {
                    System.out.println("[WS ERROR] " + ex.getMessage());
                }
            };

            wsClient.setConnectionLostTimeout(15);
            wsClient.connect();
        } catch (Exception e) {
            System.out.println("[DATA] Connection failed: " + e.getMessage());
            scheduler.schedule(() -> reconnect(), 5, TimeUnit.SECONDS);
        }
    }

    private void reconnect() {
        try {
            if (wsClient != null) {
                try { wsClient.closeBlocking(); } catch (Exception e) {}
            }
            connectWebSocket();
        } catch (Exception e) {
            System.out.println("[DATA] Reconnect failed: " + e.getMessage());
        }
    }

    /**
     * 根据stream名称分发到对应处理器
     */
    private void dispatchMessage(String stream, JSONObject data) {
        if (stream.endsWith("@aggTrade")) {
            handleAggTrade(data);
        } else if (stream.contains("@depth")) {
            handleDepth(data);
        } else if (stream.endsWith("@forceOrder")) {
            handleForceOrder(data);
        } else if (stream.contains("@kline_1s")) {
            handleKline1s(data);
        } else if (stream.endsWith("@miniTicker")) {
            handleTicker(data);
        } else if (stream.contains("@markPrice")) {
            handleMarkPrice(data);
        }
    }

    /**
     * 逐笔成交: 最核心的数据流
     */
    private void handleAggTrade(JSONObject data) {
        double price = data.getDouble("p");
        double qty = data.getDouble("q");
        boolean isBuyerMaker = data.getBoolean("m");
        long time = data.getLong("T");
        state.recordTrade(price, qty, isBuyerMaker, time);
    }

    /**
     * 订单簿深度: 每100ms推送
     */
    private void handleDepth(JSONObject data) {
        JSONArray bidsArr = data.getJSONArray("b");
        JSONArray asksArr = data.getJSONArray("a");

        double[][] bids = new double[bidsArr.length()][2];
        double[][] asks = new double[asksArr.length()][2];

        for (int i = 0; i < bidsArr.length(); i++) {
            JSONArray level = bidsArr.getJSONArray(i);
            bids[i][0] = Double.parseDouble(level.getString(0));
            bids[i][1] = Double.parseDouble(level.getString(1));
        }
        for (int i = 0; i < asksArr.length(); i++) {
            JSONArray level = asksArr.getJSONArray(i);
            asks[i][0] = Double.parseDouble(level.getString(0));
            asks[i][1] = Double.parseDouble(level.getString(1));
        }

        state.updateDepth(bids, asks);
    }

    /**
     * 爆仓数据流
     */
    private void handleForceOrder(JSONObject data) {
        JSONObject order = data.getJSONObject("o");
        String side = order.getString("S");   // BUY/SELL
        double qty = Double.parseDouble(order.getString("q"));
        double price = Double.parseDouble(order.getString("p"));
        long time = order.getLong("T");
        state.recordLiquidation(side, qty, price, time);
    }

    /**
     * 1秒K线
     */
    private void handleKline1s(JSONObject data) {
        JSONObject k = data.getJSONObject("k");
        double close = Double.parseDouble(k.getString("c"));
        double volume = Double.parseDouble(k.getString("v"));
        long time = k.getLong("T");
        state.recordKline1s(close, volume, time);
    }

    /**
     * 24h滚动统计
     */
    private void handleTicker(JSONObject data) {
        double vol = Double.parseDouble(data.getString("v"));
        double changePct = Double.parseDouble(data.getString("P"));
        double high = Double.parseDouble(data.getString("h"));
        double low = Double.parseDouble(data.getString("l"));
        state.updateTicker(vol, changePct, high, low);
    }

    /**
     * 标记价格+资金费率
     */
    private void handleMarkPrice(JSONObject data) {
        double mark = Double.parseDouble(data.getString("p"));
        double funding = Double.parseDouble(data.getString("r"));
        long nextFunding = data.getLong("T");
        state.updateMarkPrice(mark, funding, nextFunding);
    }

    public boolean isConnected() { return connected.get(); }
    public MarketMicrostructure getState() { return state; }

    public void shutdown() {
        scheduler.shutdownNow();
        if (wsClient != null) {
            try { wsClient.closeBlocking(); } catch (Exception e) {}
        }
        System.out.println("[DATA ENGINE] Shutdown complete for " + symbol.toUpperCase());
    }
}
