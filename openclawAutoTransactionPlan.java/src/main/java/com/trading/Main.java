package com.trading;

import java.net.URI;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/**
 * 主入口 — 修复版。
 *
 * 修复：
 * - 所有 catch(Exception e){} 改为有意义的错误处理
 * - WebSocket 断线重连加指数退避
 * - 启动 LiquidationHunter 独立 WebSocket
 * - JVM shutdown hook 安全退出
 */
public class Main {
    private static long lastEvalTime = 0;
    private static final Object evalLock = new Object();

    public static void main(String[] args) {
        // 打印配置摘要
        Config.printSummary();

        // 初始化审计日志
        AuditLogger audit = AuditLogger.init(Config.LOG_DIR);
        audit.logSystem("System starting in " + Config.TRADING_MODE + " mode");

        // 初始化组件
        RiskManager riskManager = new RiskManager(Config.INITIAL_CAPITAL);
        OpenClawGatewayClient gateway = new OpenClawGatewayClient("openclaw");

        // 初始化 Telegram 通知
        TelegramNotifier telegramNotifier = new TelegramNotifier(gateway);
        audit.setTelegramNotifier(telegramNotifier);

        BinanceRealAccount account = new BinanceRealAccount(Config.BINANCE_API_KEY, Config.BINANCE_SECRET_KEY, Config.INITIAL_CAPITAL);
        account.setRiskManager(riskManager);

        // 初始化决策引擎
        TradingDecisionEngine.init(riskManager);

        // 启动爆仓猎杀引擎
        LiquidationHunter liquidationHunter = new LiquidationHunter(account, riskManager);
        liquidationHunter.start();

        // 启动前检查 Kill Switch
        if (riskManager.isKilled()) {
            System.out.println("💀 [启动终止] 检测到KillSwitch文件，系统拒绝启动。删除 " + Config.KILL_SWITCH_FILE + " 后重试。");
            return;
        }

        // JVM Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 [安全关机] 收到关闭信号...");
            if (!account.getPositionSide().equals("NONE")) {
                System.out.println("⚠️ [关机警告] 当前有持仓！交易所上的物理止损单会继续保护你的仓位。");
            }
        }));

        // 主行情 WebSocket
        try {
            URI uri = new URI("wss://stream.binance.com:9443/ws/solusdt@kline_1m");

            WebSocketClient client = new WebSocketClient(uri) {
                private int reconnectAttempts = 0;

                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    reconnectAttempts = 0;
                    System.out.println("✅ [行情雷达上线] 5层防爆 + 极限激进引擎已全部激活！");
                }

                @Override
                public void onMessage(String message) {
                    try {
                        JSONObject json = new JSONObject(message);

                        long eventTime = json.getLong("E");
                        long localTime = System.currentTimeMillis();
                        if (localTime - eventTime > 5000) return; // 过期数据

                        JSONObject kline = json.getJSONObject("k");
                        double currentPrice = kline.getDouble("c");
                        double currentVolume = kline.getDouble("v");

                        IndicatorCalculator.addData(currentPrice, currentVolume);

                        // 毫秒级陷阱检查（主线程直接执行，不开新线程）
                        TradingDecisionEngine.checkFastTrap(currentPrice, account);

                        // 爆仓猎杀出场检查
                        liquidationHunter.checkHuntExit(currentPrice);

                        // 每15秒AI思考（单独线程，避免阻塞行情）
                        long now = System.currentTimeMillis();
                        synchronized (evalLock) {
                            if (now - lastEvalTime > 15000) {
                                lastEvalTime = now;
                                final double priceForAI = currentPrice;
                                new Thread(() -> {
                                    try {
                                        TradingDecisionEngine.evaluateAndAskAI(priceForAI, gateway, account);
                                    } catch (Exception e) {
                                        System.err.println("⚠️ [AI线程异常] " + e.getMessage());
                                    }
                                }).start();
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("⚠️ [行情处理异常] " + e.getMessage());
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    reconnectAttempts++;
                    long delay = Math.min(5000L * (1L << Math.min(reconnectAttempts, 5)), 160000); // 指数退避，最长160秒
                    System.out.println("⚠️ [行情断开] 第" + reconnectAttempts + "次断线，" + (delay / 1000) + "秒后重连...");
                    new Thread(() -> {
                        try {
                            Thread.sleep(delay);
                            this.reconnect();
                        } catch (Exception e) {
                            System.err.println("❌ [重连失败] " + e.getMessage());
                        }
                    }).start();
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("⚠️ [WebSocket错误] " + ex.getMessage());
                }
            };

            client.connect();

            // 主线程保活
            while (true) {
                Thread.sleep(60000);
                // 每分钟检查一次系统状态
                if (riskManager.isKilled()) {
                    System.out.println("💀 [主循环] 检测到熔断，系统将在当前仓位处理完后停止新交易");
                }
            }

        } catch (Exception e) {
            System.err.println("❌ [致命错误] 系统启动失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
