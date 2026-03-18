package com.trading;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/**
 * 爆仓猎杀引擎 — 监测市场大规模爆仓事件并极速反向狙击。
 *
 * 原理：大规模爆仓 → 价格急速冲到爆仓价位 → 清算完成后反弹。
 * 策略：检测到爆仓潮 → 反向开25x极速单 → 0.3%止盈即跑 → 最长持仓30秒。
 *
 * 监听：wss://fstream.binance.com/ws/solusdt@forceOrder
 */
public class LiquidationHunter {

    // 爆仓事件记录
    private static class LiqEvent {
        final long timestamp;
        final String side; // "BUY"=空头爆仓, "SELL"=多头爆仓
        final double qty;
        final double price;

        LiqEvent(long ts, String side, double qty, double price) {
            this.timestamp = ts;
            this.side = side;
            this.qty = qty;
            this.price = price;
        }
    }

    private static final long WINDOW_MS = 5000;         // 5秒窗口
    private static final double SURGE_THRESHOLD = 5.0;   // 5倍均量触发
    private static final double TAKE_PROFIT_ROE = 0.3;   // 0.3% ROE止盈
    private static final double STOP_LOSS_ROE = -0.15;   // 0.15% ROE止损
    private static final long MAX_HOLD_MS = 30_000;      // 最长持仓30秒

    private final ConcurrentLinkedDeque<LiqEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private volatile double avgLiqQtyPerWindow = 0;
    private volatile int windowCount = 0;
    private volatile double totalLiqQty = 0;

    // 猎杀仓位状态
    private volatile boolean huntActive = false;
    private volatile String huntSide = "NONE";
    private volatile double huntEntryPrice = 0;
    private volatile long huntEntryTime = 0;
    private volatile int huntQty = 0;

    private final BinanceRealAccount account;
    private final RiskManager riskManager;

    private WebSocketClient wsClient;

    public LiquidationHunter(BinanceRealAccount account, RiskManager riskManager) {
        this.account = account;
        this.riskManager = riskManager;
    }

    /**
     * 启动爆仓流监听。
     */
    public void start() {
        try {
            URI uri = new URI("wss://fstream.binance.com/ws/solusdt@forceOrder");
            wsClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    System.out.println("🎯 [爆仓猎手] 爆仓流监听已上线");
                }

                @Override
                public void onMessage(String message) {
                    try {
                        processForceOrder(message);
                    } catch (Exception e) {
                        System.err.println("⚠️ [爆仓猎手] 解析异常: " + e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("⚠️ [爆仓猎手] 连接断开，5秒后重连...");
                    reconnectWithDelay();
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("⚠️ [爆仓猎手] WebSocket错误: " + ex.getMessage());
                }
            };
            wsClient.connect();
        } catch (Exception e) {
            System.err.println("❌ [爆仓猎手] 启动失败: " + e.getMessage());
        }
    }

    private void reconnectWithDelay() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                if (wsClient != null) wsClient.reconnect();
            } catch (Exception e) {
                System.err.println("⚠️ [爆仓猎手] 重连失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 处理实时爆仓事件。
     */
    private void processForceOrder(String message) {
        JSONObject json = new JSONObject(message);
        JSONObject order = json.getJSONObject("o");

        String side = order.getString("S");     // BUY=空头被爆, SELL=多头被爆
        double qty = order.getDouble("q");
        double price = order.getDouble("p");
        long now = System.currentTimeMillis();

        // 记录事件
        recentEvents.add(new LiqEvent(now, side, qty, price));

        // 清理过期事件
        while (!recentEvents.isEmpty() && recentEvents.peekFirst().timestamp < now - WINDOW_MS) {
            recentEvents.pollFirst();
        }

        // 计算窗口内爆仓量
        double windowQty = 0;
        String dominantSide = null;
        double buyQty = 0, sellQty = 0;
        for (LiqEvent e : recentEvents) {
            windowQty += e.qty;
            if (e.side.equals("BUY")) buyQty += e.qty;
            else sellQty += e.qty;
        }
        dominantSide = buyQty > sellQty ? "BUY" : "SELL";

        // 更新均量基线
        windowCount++;
        totalLiqQty += windowQty;
        if (windowCount > 1) {
            avgLiqQtyPerWindow = totalLiqQty / windowCount;
        }

        // 触发条件：窗口爆仓量 > 均量的5倍
        if (avgLiqQtyPerWindow > 0 && windowQty > avgLiqQtyPerWindow * SURGE_THRESHOLD) {
            if (!huntActive && account.getPositionSide().equals("NONE")) {
                triggerHunt(dominantSide, price, windowQty);
            }
        }
    }

    /**
     * 触发猎杀交易。
     * 空头爆仓(BUY) → 做空（价格被推高后会回落）
     * 多头爆仓(SELL) → 做多（价格被砸低后会反弹）
     */
    private synchronized void triggerHunt(String liqDominantSide, double currentPrice, double windowQty) {
        if (riskManager.isKilled()) return;

        String rejectReason = riskManager.canTrade(account.getWalletBalance());
        if (rejectReason != null) {
            System.out.println("🎯 [爆仓猎手] 检测到爆仓潮但风控拦截: " + rejectReason);
            return;
        }

        // 反向操作：空头被爆(价格被推高) → 做空; 多头被爆(价格被砸低) → 做多
        String huntDirection = liqDominantSide.equals("BUY") ? "SHORT" : "LONG";

        double wallet = account.getWalletBalance();
        double margin = riskManager.clipMargin(wallet * 0.03, wallet); // 猎杀单用3%本金
        int leverage = DynamicLeverageEngine.getLiquidationHuntLeverage();

        System.out.println("\n🎯🎯🎯 [爆仓猎杀触发] 5秒内爆仓量=" + fmt(windowQty)
                + " (均量=" + fmt(avgLiqQtyPerWindow) + ") | 方向: " + huntDirection + " @ " + fmt(currentPrice));

        account.openPosition(huntDirection, currentPrice, margin, leverage, "爆仓猎杀: 检测到" + (liqDominantSide.equals("BUY") ? "空头" : "多头") + "大规模爆仓");

        if (!account.getPositionSide().equals("NONE")) {
            huntActive = true;
            huntSide = huntDirection;
            huntEntryPrice = currentPrice;
            huntEntryTime = System.currentTimeMillis();
        }
    }

    /**
     * 每个tick检查猎杀仓位是否该平仓。由主循环调用。
     */
    public synchronized void checkHuntExit(double currentPrice) {
        if (!huntActive) return;

        double roe = account.getROE(currentPrice);
        long elapsed = System.currentTimeMillis() - huntEntryTime;

        // 止盈
        if (roe >= TAKE_PROFIT_ROE) {
            System.out.println("🎯 [猎杀止盈] ROE=" + fmt(roe) + "% 达到目标，极速撤退！");
            account.closePosition(currentPrice, "爆仓猎杀止盈 ROE=" + fmt(roe) + "%");
            resetHunt();
            return;
        }

        // 止损
        if (roe <= STOP_LOSS_ROE) {
            System.out.println("🎯 [猎杀止损] ROE=" + fmt(roe) + "% 触发止损");
            account.closePosition(currentPrice, "爆仓猎杀止损 ROE=" + fmt(roe) + "%");
            resetHunt();
            return;
        }

        // 超时强平
        if (elapsed > MAX_HOLD_MS) {
            System.out.println("🎯 [猎杀超时] 持仓" + (elapsed / 1000) + "秒超过限制，强制撤退");
            account.closePosition(currentPrice, "爆仓猎杀超时强平");
            resetHunt();
        }
    }

    private void resetHunt() {
        huntActive = false;
        huntSide = "NONE";
        huntEntryPrice = 0;
        huntEntryTime = 0;
    }

    public boolean isHuntActive() { return huntActive; }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}
