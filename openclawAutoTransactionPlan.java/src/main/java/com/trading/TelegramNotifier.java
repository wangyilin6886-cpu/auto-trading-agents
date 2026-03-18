package com.trading;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram 通知 — 通过 OpenClaw v3 agent 推送交易反馈到 Telegram。
 *
 * 消息类型：
 *   1. 告警（Kill Switch、连亏、高滑点、日亏损）
 *   2. 交易执行摘要
 *   3. 每小时汇总报告
 *
 * 限流：同类消息 5 分钟去重，异步发送不阻塞主流程。
 */
public class TelegramNotifier {

    private final OpenClawGatewayClient gateway;
    private final Map<String, Long> lastSentTime = new ConcurrentHashMap<>();

    public TelegramNotifier(OpenClawGatewayClient gateway) {
        this.gateway = gateway;
    }

    /**
     * 发送告警消息到 Telegram。
     */
    public void sendAlert(String alertType, String message) {
        if (isDuplicate("ALERT_" + alertType)) return;

        String emoji;
        switch (alertType) {
            case "KILL_SWITCH": emoji = "\uD83D\uDD34"; break;       // 🔴
            case "CONSECUTIVE_LOSS": emoji = "\u26A0\uFE0F"; break;  // ⚠️
            case "HIGH_SLIPPAGE": emoji = "\uD83D\uDCA8"; break;     // 💨
            case "DAILY_LOSS": emoji = "\uD83D\uDCC9"; break;        // 📉
            default: emoji = "\u2757"; break;                         // ❗
        }

        String text = emoji + " " + alertType + "\n" + message
                + "\nTime: " + java.time.Instant.now().toString();

        sendAsync(text);
    }

    /**
     * 发送交易执行通知到 Telegram。
     */
    public void sendTradeNotification(String side, int qty, double entryPrice, int leverage,
                                      double tpPrice, double slPrice, String source) {
        if (isDuplicate("TRADE")) return;

        String arrow = side.contains("LONG") ? "\uD83D\uDFE9" : "\uD83D\uDFE5"; // 🟩 / 🟥
        String text = String.format(Locale.US,
                "%s Trade Executed\n" +
                "Direction: %s | Leverage: %dx\n" +
                "Entry: $%.4f | Qty: %d SOL\n" +
                "TP: $%.4f | SL: $%.4f\n" +
                "Source: %s",
                arrow, side, leverage, entryPrice, qty, tpPrice, slPrice, source);

        sendAsync(text);
    }

    /**
     * 发送每小时汇总报告到 Telegram。
     */
    public void sendHourlySummary(double balance, double dailyPnl, double dailyPnlPct,
                                  int wins, int totalTrades, String positionInfo) {
        // 每小时报告不去重
        String pnlEmoji = dailyPnl >= 0 ? "\uD83D\uDCC8" : "\uD83D\uDCC9"; // 📈 / 📉
        String winRate = totalTrades > 0 ? String.format(Locale.US, "%d/%d (%.0f%%)", wins, totalTrades, (100.0 * wins / totalTrades)) : "0/0";

        String text = String.format(Locale.US,
                "%s Hourly Report\n" +
                "Balance: %.2f USDT\n" +
                "Daily PnL: %+.2f (%.1f%%)\n" +
                "Win Rate: %s\n" +
                "Position: %s",
                pnlEmoji, balance, dailyPnl, dailyPnlPct, winRate, positionInfo);

        sendAsync(text);
    }

    /**
     * 发送平仓通知。
     */
    public void sendCloseNotification(String side, double entryPrice, double closePrice,
                                      double pnl, double roe, String reason) {
        String emoji = pnl >= 0 ? "\u2705" : "\u274C"; // ✅ / ❌
        String text = String.format(Locale.US,
                "%s Position Closed\n" +
                "Direction: %s\n" +
                "Entry: $%.4f -> Exit: $%.4f\n" +
                "PnL: %+.4f USDT (ROE: %.2f%%)\n" +
                "Reason: %s",
                emoji, side, entryPrice, closePrice, pnl, roe, reason);

        sendAsync(text);
    }

    // ==================== 内部方法 ====================

    private boolean isDuplicate(String key) {
        long now = System.currentTimeMillis();
        Long last = lastSentTime.get(key);
        if (last != null && (now - last) < Config.ALERT_DEDUP_MS) {
            return true;
        }
        lastSentTime.put(key, now);
        return false;
    }

    private void sendAsync(String message) {
        CompletableFuture.runAsync(() -> {
            try {
                // 通过 OpenClaw v3 agent（已绑定 Telegram）发送消息
                String prompt = "Please forward this message to the user exactly as is:\n\n" + message;
                gateway.askAgentAsync(Config.AGENT_TELEGRAM, Config.SESSION_TELEGRAM, prompt, 15000);
            } catch (Exception e) {
                System.err.println("[TelegramNotifier] Failed to send: " + e.getMessage());
                try {
                    AuditLogger.get().logError("TelegramNotifier", "Send failed: " + e.getMessage(), null);
                } catch (Exception ignored) {}
            }
        });
    }
}
