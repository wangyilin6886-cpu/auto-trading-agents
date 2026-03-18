package com.trading;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;

/**
 * 审计日志 — JSON Lines 格式，按天滚动。
 * 线程安全：所有写操作 synchronized。
 *
 * 记录类型：AI_DECISION, ORDER, SIMULATED_ORDER, RISK_CHECK, SIGNAL,
 *           KILL_SWITCH, ERROR, ALERT, TRADE_RESULT, SYSTEM
 */
public class AuditLogger {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private static AuditLogger instance;

    private final String logDir;
    private PrintWriter writer;
    private PrintWriter alertWriter;
    private String currentDate;

    // 告警去重
    private final Map<String, Long> alertLastSent = new ConcurrentHashMap<>();

    private TelegramNotifier telegramNotifier;

    private AuditLogger(String logDir) {
        this.logDir = logDir;
        new File(logDir).mkdirs();
        rollFile();
    }

    public static synchronized AuditLogger init(String logDir) {
        if (instance == null) {
            instance = new AuditLogger(logDir);
        }
        return instance;
    }

    public static AuditLogger get() {
        if (instance == null) {
            throw new IllegalStateException("AuditLogger not initialized. Call AuditLogger.init() first.");
        }
        return instance;
    }

    public void setTelegramNotifier(TelegramNotifier notifier) {
        this.telegramNotifier = notifier;
    }

    // ==================== 日志记录 ====================

    public void logAIDecision(String agent, String input, String output, boolean validated) {
        JSONObject obj = base("AI_DECISION");
        obj.put("agent", agent);
        obj.put("input", input.length() > 500 ? input.substring(0, 500) + "..." : input);
        obj.put("output", output.length() > 500 ? output.substring(0, 500) + "..." : output);
        obj.put("validated", validated);
        write(obj);
    }

    public void logOrder(String side, int qty, double expectedPrice, double fillPrice, int leverage, String source) {
        JSONObject obj = base("ORDER");
        obj.put("side", side);
        obj.put("qty", qty);
        obj.put("expected_price", expectedPrice);
        obj.put("fill_price", fillPrice);
        obj.put("leverage", leverage);
        obj.put("source", source);
        if (expectedPrice > 0) {
            double slippage = Math.abs(fillPrice - expectedPrice) / expectedPrice;
            obj.put("slippage", String.format(Locale.US, "%.6f", slippage));
        }
        write(obj);
    }

    public void logSimulatedOrder(String side, int qty, double price, int leverage, String reason) {
        JSONObject obj = base("SIMULATED_ORDER");
        obj.put("side", side);
        obj.put("qty", qty);
        obj.put("price", price);
        obj.put("leverage", leverage);
        obj.put("reason", reason);
        write(obj);
    }

    public void logRiskCheck(boolean killSwitch, double dailyLoss, double walletBalance, String detail) {
        JSONObject obj = base("RISK_CHECK");
        obj.put("kill_switch", killSwitch);
        obj.put("daily_loss", dailyLoss);
        obj.put("wallet_balance", walletBalance);
        obj.put("detail", detail);
        write(obj);
    }

    public void logSignal(double obi, double cvd, double rsi, double lci, double funding, double compositeScore) {
        JSONObject obj = base("SIGNAL");
        obj.put("obi", fmt(obi));
        obj.put("cvd", fmt(cvd));
        obj.put("rsi", fmt(rsi));
        obj.put("lci", fmt(lci));
        obj.put("funding", fmt(funding));
        obj.put("composite_score", fmt(compositeScore));
        write(obj);
    }

    public void logTradeResult(double pnl, double walletBalance, double winRate, int totalTrades) {
        JSONObject obj = base("TRADE_RESULT");
        obj.put("pnl", fmt(pnl));
        obj.put("wallet_balance", fmt(walletBalance));
        obj.put("win_rate", fmt(winRate));
        obj.put("total_trades", totalTrades);
        write(obj);
    }

    public void logError(String component, String message, String stackTrace) {
        JSONObject obj = base("ERROR");
        obj.put("component", component);
        obj.put("message", message);
        if (stackTrace != null) {
            obj.put("stack", stackTrace.length() > 1000 ? stackTrace.substring(0, 1000) : stackTrace);
        }
        write(obj);
    }

    public void logSystem(String message) {
        JSONObject obj = base("SYSTEM");
        obj.put("message", message);
        write(obj);
    }

    // ==================== 告警 ====================

    public void alert(String alertType, String message) {
        // 去重检查
        long now = System.currentTimeMillis();
        Long lastSent = alertLastSent.get(alertType);
        if (lastSent != null && (now - lastSent) < Config.ALERT_DEDUP_MS) {
            return; // 5 分钟内同类告警不重复发送
        }
        alertLastSent.put(alertType, now);

        // 写告警日志
        JSONObject obj = base("ALERT");
        obj.put("alert_type", alertType);
        obj.put("message", message);
        write(obj);
        writeAlert(alertType + ": " + message);

        // stderr 输出
        System.err.println("[ALERT] " + alertType + ": " + message);

        // Telegram 推送
        if (telegramNotifier != null) {
            telegramNotifier.sendAlert(alertType, message);
        }
    }

    /** 交易执行后推送 Telegram */
    public void notifyTradeExecution(String side, int qty, double entryPrice, int leverage,
                                     double tpPrice, double slPrice, String source) {
        if (telegramNotifier != null) {
            telegramNotifier.sendTradeNotification(side, qty, entryPrice, leverage, tpPrice, slPrice, source);
        }
    }

    /** 每小时汇总推送 */
    public void notifyHourlySummary(double balance, double dailyPnl, double dailyPnlPct,
                                    int wins, int totalTrades, String positionInfo) {
        if (telegramNotifier != null) {
            telegramNotifier.sendHourlySummary(balance, dailyPnl, dailyPnlPct, wins, totalTrades, positionInfo);
        }
    }

    // ==================== 内部方法 ====================

    private JSONObject base(String type) {
        JSONObject obj = new JSONObject();
        obj.put("ts", Instant.now().atOffset(ZoneOffset.UTC).format(TS_FMT));
        obj.put("type", type);
        return obj;
    }

    private synchronized void write(JSONObject obj) {
        checkRoll();
        if (writer != null) {
            writer.println(obj.toString());
            writer.flush();
        }
    }

    private synchronized void writeAlert(String message) {
        checkRoll();
        if (alertWriter != null) {
            alertWriter.println(Instant.now().atOffset(ZoneOffset.UTC).format(TS_FMT) + " " + message);
            alertWriter.flush();
        }
    }

    private void checkRoll() {
        String today = LocalDate.now().format(DATE_FMT);
        if (!today.equals(currentDate)) {
            rollFile();
        }
    }

    private void rollFile() {
        closeQuietly(writer);
        closeQuietly(alertWriter);
        currentDate = LocalDate.now().format(DATE_FMT);
        try {
            writer = new PrintWriter(new FileWriter(logDir + "/trading_" + currentDate + ".jsonl", true));
            alertWriter = new PrintWriter(new FileWriter(logDir + "/ALERT_" + currentDate + ".log", true));
        } catch (IOException e) {
            System.err.println("[AuditLogger] Failed to open log files: " + e.getMessage());
        }
    }

    private void closeQuietly(PrintWriter pw) {
        if (pw != null) {
            try { pw.close(); } catch (Exception ignored) {}
        }
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
