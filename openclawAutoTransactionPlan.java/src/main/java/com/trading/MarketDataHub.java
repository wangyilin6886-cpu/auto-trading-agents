package com.trading;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 市场数据中心 — 5 流 WebSocket 合流，线程安全数据存储。
 *
 * 数据流：
 *   solusdt@kline_1m      → K线数据（价格/量）
 *   solusdt@depth@100ms    → 订单簿 Top 10（用于 OBI）
 *   solusdt@aggTrade        → 逐笔成交（用于 CVD）
 *   solusdt@forceOrder      → 全网爆仓（用于 LCI + 爆仓猎杀）
 *   solusdt@markPrice@1s    → 标记价/资金费率
 *
 * 特性：
 *   - 心跳监控（30s 无数据 → 主动断开重连）
 *   - 指数退避重连（2s → 4s → 8s → 16s → 32s）
 *   - 线程安全数据存储
 */
public class MarketDataHub {

    // ==================== 数据存储（线程安全） ====================

    // K线数据
    private static final int MAX_KLINE_SIZE = 200;
    private final ConcurrentLinkedDeque<double[]> klineData = new ConcurrentLinkedDeque<>();
    // double[]: {close, high, low, volume, timestamp}

    // 订单簿快照（Top 10 bids/asks）
    private final AtomicReference<OrderBookSnapshot> orderBook = new AtomicReference<>(new OrderBookSnapshot());

    // 逐笔成交（滚动窗口）
    private static final int MAX_TRADES = 5000;
    private final ConcurrentLinkedDeque<AggTrade> recentTrades = new ConcurrentLinkedDeque<>();

    // 爆仓事件（滚动窗口）
    private static final int MAX_LIQUIDATIONS = 1000;
    private final ConcurrentLinkedDeque<LiquidationEvent> recentLiquidations = new ConcurrentLinkedDeque<>();

    // 标记价 + 资金费率
    private final AtomicReference<Double> markPrice = new AtomicReference<>(0.0);
    private final AtomicReference<Double> fundingRate = new AtomicReference<>(0.0);

    // 最新价格
    private final AtomicReference<Double> lastPrice = new AtomicReference<>(0.0);

    // 心跳
    private final AtomicLong lastDataTime = new AtomicLong(System.currentTimeMillis());

    // 回调
    private final List<ForceOrderCallback> forceOrderCallbacks = new CopyOnWriteArrayList<>();

    // WebSocket
    private WebSocketClient wsClient;
    private int reconnectAttempts = 0;
    private volatile boolean running = false;

    // ==================== 数据结构 ====================

    public static class OrderBookSnapshot {
        public final double[] bidPrices;
        public final double[] bidQtys;
        public final double[] askPrices;
        public final double[] askQtys;
        public final long timestamp;

        public OrderBookSnapshot() {
            this.bidPrices = new double[10];
            this.bidQtys = new double[10];
            this.askPrices = new double[10];
            this.askQtys = new double[10];
            this.timestamp = 0;
        }

        public OrderBookSnapshot(double[] bp, double[] bq, double[] ap, double[] aq) {
            this.bidPrices = bp;
            this.bidQtys = bq;
            this.askPrices = ap;
            this.askQtys = aq;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class AggTrade {
        public final double price;
        public final double qty;
        public final boolean isBuyerMaker; // true=主动卖, false=主动买
        public final long timestamp;

        public AggTrade(double price, double qty, boolean isBuyerMaker, long timestamp) {
            this.price = price;
            this.qty = qty;
            this.isBuyerMaker = isBuyerMaker;
            this.timestamp = timestamp;
        }
    }

    public static class LiquidationEvent {
        public final String side; // "BUY"=空头被爆, "SELL"=多头被爆
        public final double qty;
        public final double price;
        public final long timestamp;

        public LiquidationEvent(String side, double qty, double price, long timestamp) {
            this.side = side;
            this.qty = qty;
            this.price = price;
            this.timestamp = timestamp;
        }
    }

    @FunctionalInterface
    public interface ForceOrderCallback {
        void onForceOrder(LiquidationEvent event);
    }

    // ==================== 启动/停止 ====================

    public void start() {
        running = true;
        connect();
        startHeartbeatMonitor();
        AuditLogger.get().logSystem("MarketDataHub started: 5-stream WebSocket");
    }

    public void stop() {
        running = false;
        if (wsClient != null) {
            try { wsClient.close(); } catch (Exception ignored) {}
        }
    }

    public void addForceOrderCallback(ForceOrderCallback callback) {
        forceOrderCallbacks.add(callback);
    }

    private void connect() {
        try {
            String streams = Config.SYMBOL.toLowerCase() + "@kline_1m/"
                    + Config.SYMBOL.toLowerCase() + "@depth@100ms/"
                    + Config.SYMBOL.toLowerCase() + "@aggTrade/"
                    + Config.SYMBOL.toLowerCase() + "@forceOrder/"
                    + Config.SYMBOL.toLowerCase() + "@markPrice@1s";

            URI uri = new URI(Config.WS_FUTURES_BASE + "/stream?streams=" + streams);

            wsClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    reconnectAttempts = 0;
                    lastDataTime.set(System.currentTimeMillis());
                    System.out.println("[MarketDataHub] 5-stream WebSocket connected");
                }

                @Override
                public void onMessage(String message) {
                    lastDataTime.set(System.currentTimeMillis());
                    try {
                        JSONObject json = new JSONObject(message);
                        String stream = json.optString("stream", "");
                        JSONObject data = json.optJSONObject("data");
                        if (data == null) return;

                        if (stream.contains("kline")) {
                            processKline(data);
                        } else if (stream.contains("depth")) {
                            processDepth(data);
                        } else if (stream.contains("aggTrade")) {
                            processAggTrade(data);
                        } else if (stream.contains("forceOrder")) {
                            processForceOrder(data);
                        } else if (stream.contains("markPrice")) {
                            processMarkPrice(data);
                        }
                    } catch (Exception e) {
                        System.err.println("[MarketDataHub] Parse error: " + e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    if (!running) return;
                    reconnectAttempts++;
                    long delay = Math.min(2000L * (1L << Math.min(reconnectAttempts - 1, 4)),
                            Config.WS_MAX_RECONNECT_DELAY_MS);
                    System.out.println("[MarketDataHub] Disconnected, reconnecting in " + delay + "ms (attempt " + reconnectAttempts + ")");
                    scheduleReconnect(delay);
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("[MarketDataHub] WebSocket error: " + ex.getMessage());
                }
            };

            wsClient.connect();
        } catch (Exception e) {
            System.err.println("[MarketDataHub] Failed to connect: " + e.getMessage());
            AuditLogger.get().logError("MarketDataHub", "Connection failed: " + e.getMessage(), null);
        }
    }

    private void scheduleReconnect(long delayMs) {
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
                if (running) connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    private void startHeartbeatMonitor() {
        Thread heartbeat = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(10_000); // 每 10 秒检查一次
                    long elapsed = System.currentTimeMillis() - lastDataTime.get();
                    if (elapsed > Config.WS_HEARTBEAT_TIMEOUT_MS && running) {
                        System.out.println("[MarketDataHub] No data for " + (elapsed / 1000) + "s, forcing reconnect");
                        if (wsClient != null) {
                            try { wsClient.close(); } catch (Exception ignored) {}
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        heartbeat.setDaemon(true);
        heartbeat.setName("MarketDataHub-Heartbeat");
        heartbeat.start();
    }

    // ==================== 数据处理 ====================

    private void processKline(JSONObject data) {
        JSONObject k = data.optJSONObject("k");
        if (k == null) return;

        double close = k.getDouble("c");
        double high = k.getDouble("h");
        double low = k.getDouble("l");
        double volume = k.getDouble("v");
        long timestamp = k.getLong("t");

        lastPrice.set(close);

        klineData.addLast(new double[]{close, high, low, volume, timestamp});
        while (klineData.size() > MAX_KLINE_SIZE) {
            klineData.pollFirst();
        }
    }

    private void processDepth(JSONObject data) {
        try {
            JSONArray bids = data.getJSONArray("b");
            JSONArray asks = data.getJSONArray("a");

            int bidCount = Math.min(bids.length(), 10);
            int askCount = Math.min(asks.length(), 10);

            double[] bp = new double[10], bq = new double[10];
            double[] ap = new double[10], aq = new double[10];

            for (int i = 0; i < bidCount; i++) {
                JSONArray level = bids.getJSONArray(i);
                bp[i] = level.getDouble(0);
                bq[i] = level.getDouble(1);
            }
            for (int i = 0; i < askCount; i++) {
                JSONArray level = asks.getJSONArray(i);
                ap[i] = level.getDouble(0);
                aq[i] = level.getDouble(1);
            }

            orderBook.set(new OrderBookSnapshot(bp, bq, ap, aq));
        } catch (Exception e) {
            System.err.println("[MarketDataHub] Depth parse error: " + e.getMessage());
        }
    }

    private void processAggTrade(JSONObject data) {
        double price = data.getDouble("p");
        double qty = data.getDouble("q");
        boolean isBuyerMaker = data.getBoolean("m");
        long timestamp = data.getLong("T");

        lastPrice.set(price);

        AggTrade trade = new AggTrade(price, qty, isBuyerMaker, timestamp);
        recentTrades.addLast(trade);
        while (recentTrades.size() > MAX_TRADES) {
            recentTrades.pollFirst();
        }
    }

    private void processForceOrder(JSONObject data) {
        JSONObject order = data.optJSONObject("o");
        if (order == null) return;

        String side = order.getString("S");
        double qty = order.getDouble("q");
        double price = order.getDouble("p");
        long now = System.currentTimeMillis();

        LiquidationEvent event = new LiquidationEvent(side, qty, price, now);
        recentLiquidations.addLast(event);
        while (recentLiquidations.size() > MAX_LIQUIDATIONS) {
            recentLiquidations.pollFirst();
        }

        // 通知回调（LiquidationHunter 等）
        for (ForceOrderCallback cb : forceOrderCallbacks) {
            try {
                cb.onForceOrder(event);
            } catch (Exception e) {
                System.err.println("[MarketDataHub] ForceOrder callback error: " + e.getMessage());
            }
        }
    }

    private void processMarkPrice(JSONObject data) {
        markPrice.set(data.optDouble("p", 0.0));
        double fr = data.optDouble("r", 0.0);
        if (fr != 0.0) {
            fundingRate.set(fr);
        }
    }

    // ==================== 数据查询 API ====================

    public double getLastPrice() {
        return lastPrice.get();
    }

    public double getMarkPrice() {
        return markPrice.get();
    }

    public double getFundingRate() {
        return fundingRate.get();
    }

    public OrderBookSnapshot getOrderBook() {
        return orderBook.get();
    }

    /** 获取最近 N 根 K 线数据 */
    public double[][] getRecentKlines(int count) {
        Object[] arr = klineData.toArray();
        int start = Math.max(0, arr.length - count);
        double[][] result = new double[arr.length - start][];
        for (int i = start; i < arr.length; i++) {
            result[i - start] = (double[]) arr[i];
        }
        return result;
    }

    /** 获取指定时间窗口内的逐笔成交 */
    public AggTrade[] getRecentTrades(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        return recentTrades.stream()
                .filter(t -> t.timestamp >= cutoff)
                .toArray(AggTrade[]::new);
    }

    /** 获取指定时间窗口内的爆仓事件 */
    public LiquidationEvent[] getRecentLiquidations(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        return recentLiquidations.stream()
                .filter(e -> e.timestamp >= cutoff)
                .toArray(LiquidationEvent[]::new);
    }

    /** K 线数据数量 */
    public int getKlineCount() {
        return klineData.size();
    }

    /** 数据是否就绪（至少有足够的 K 线数据） */
    public boolean isReady() {
        return klineData.size() >= 15;
    }
}
