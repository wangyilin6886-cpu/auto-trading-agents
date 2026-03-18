package com.trading;

/**
 * 配置中心 — 所有敏感信息从环境变量读取，所有可调参数集中管理。
 *
 * 环境变量清单：
 *   BINANCE_API_KEY     — 币安 API Key（必须）
 *   BINANCE_SECRET_KEY  — 币安 API Secret（必须）
 *   TRADING_MODE        — LIVE 或 SIMULATION（默认 SIMULATION）
 *   INITIAL_CAPITAL     — 初始资金，默认 50.0
 *   ALERT_WEBHOOK_URL   — 可选，外部告警 webhook
 */
public class Config {

    // ==================== 运行模式 ====================
    public static final String TRADING_MODE = env("TRADING_MODE", "SIMULATION");
    public static final boolean IS_LIVE = "LIVE".equalsIgnoreCase(TRADING_MODE);

    // ==================== 币安 API ====================
    public static final String BINANCE_API_KEY;
    public static final String BINANCE_SECRET_KEY;
    public static final double INITIAL_CAPITAL;

    static {
        if (IS_LIVE) {
            // 实盘模式必须提供 API Key
            BINANCE_API_KEY = requireEnv("BINANCE_API_KEY");
            BINANCE_SECRET_KEY = requireEnv("BINANCE_SECRET_KEY");
        } else {
            // 模拟模式允许不提供（用空值占位）
            BINANCE_API_KEY = env("BINANCE_API_KEY", "");
            BINANCE_SECRET_KEY = env("BINANCE_SECRET_KEY", "");
        }
        INITIAL_CAPITAL = doubleEnv("INITIAL_CAPITAL", 50.0);
    }

    // ==================== 交易对 ====================
    public static final String SYMBOL = "SOLUSDT";

    // ==================== OpenClaw Agent IDs ====================
    public static final String AGENT_MIO = "kimi";       // 宏观情报官
    public static final String AGENT_RRO = "r1";         // 风险推理官
    public static final String AGENT_TEO = "v3";         // 战术执行官
    public static final String AGENT_TELEGRAM = "v3";    // Telegram 通知（绑定在 v3 上）

    // ==================== Session IDs ====================
    public static final String SESSION_MIO = "ceo_macro";
    public static final String SESSION_RRO = "cro_risk";
    public static final String SESSION_TEO = "trader_exec";
    public static final String SESSION_TELEGRAM = "tg_notify";

    // ==================== 模型温度 ====================
    public static final double TEMPERATURE_TEO = 0.1;    // V3：确定性执行
    public static final double TEMPERATURE_RRO = 0.3;    // R1：探索性推理
    public static final double TEMPERATURE_MIO = 0.3;    // Kimi：宏观分析

    // ==================== 综合评分权重（可调） ====================
    public static final double WEIGHT_OBI = doubleEnv("WEIGHT_OBI", 0.25);
    public static final double WEIGHT_CVD = doubleEnv("WEIGHT_CVD", 0.20);
    public static final double WEIGHT_RSI = doubleEnv("WEIGHT_RSI", 0.15);
    public static final double WEIGHT_LCI = doubleEnv("WEIGHT_LCI", 0.15);
    public static final double WEIGHT_BB = doubleEnv("WEIGHT_BB", 0.10);
    public static final double WEIGHT_FUNDING = doubleEnv("WEIGHT_FUNDING", 0.10);
    public static final double WEIGHT_VOL = doubleEnv("WEIGHT_VOL", 0.05);

    // ==================== 快通道阈值 ====================
    public static final double FAST_OBI_THRESHOLD = doubleEnv("FAST_OBI_THRESHOLD", 0.30);
    public static final double FAST_POSITION_PCT = 0.30;      // 快通道仓位 = 基础仓位的 30%
    public static final int FAST_MAX_LEVERAGE = 15;            // 快通道杠杆上限
    public static final double FAST_TP_ROE = 1.0;             // 快通道止盈 1% ROE
    public static final double FAST_SL_ROE = -0.5;            // 快通道止损 0.5% ROE
    public static final long FAST_MAX_HOLD_MS = 60_000;       // 快通道最大持仓 60 秒

    // ==================== 慢通道（AI）阈值 ====================
    public static final double COMPOSITE_LONG_THRESHOLD = 0.6;
    public static final double COMPOSITE_SHORT_THRESHOLD = -0.6;
    public static final double COMPOSITE_NEUTRAL_ZONE = 0.3;

    // ==================== 风控参数 ====================
    public static final double MAX_DAILY_LOSS_PCT = 0.10;
    public static final double MAX_DRAWDOWN_PCT = 0.20;
    public static final double MAX_SINGLE_MARGIN_PCT = 0.05;
    public static final int MAX_CONSECUTIVE_LOSSES = 3;
    public static final long COOLDOWN_DURATION_MS = 30 * 60 * 1000;

    // 频率限制
    public static final long MIN_TRADE_INTERVAL_MS = 30_000;
    public static final int MAX_TRADES_PER_HOUR = 20;
    public static final int MAX_TRADES_PER_DAY = 100;

    // ==================== Kelly 公式 ====================
    public static final int KELLY_COLD_START_TRADES = 50;         // 前 50 笔用固定仓位
    public static final double KELLY_COLD_START_PCT = 0.03;       // 冷启动 3% 仓位
    public static final double KELLY_MAX_PCT = 0.25;              // 半 Kelly 上限 25%
    public static final int KELLY_RECALC_INTERVAL = 20;           // 每 20 笔重算

    // ==================== 告警 ====================
    public static final String ALERT_WEBHOOK_URL = env("ALERT_WEBHOOK_URL", "");
    public static final long ALERT_DEDUP_MS = 5 * 60 * 1000;     // 同类告警 5 分钟去重

    // ==================== 日志 ====================
    public static final String LOG_DIR = "logs";

    // ==================== Kill Switch ====================
    public static final String KILL_SWITCH_FILE = "kill_switch.flag";

    // ==================== WebSocket ====================
    public static final String WS_FUTURES_BASE = "wss://fstream.binance.com";
    public static final long WS_HEARTBEAT_TIMEOUT_MS = 30_000;
    public static final long WS_MAX_RECONNECT_DELAY_MS = 32_000;

    // ==================== 工具方法 ====================
    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isEmpty()) ? val : defaultValue;
    }

    private static String requireEnv(String key) {
        String val = System.getenv(key);
        if (val == null || val.isEmpty()) {
            throw new RuntimeException("Missing required environment variable: " + key);
        }
        return val;
    }

    private static double doubleEnv(String key, double defaultValue) {
        String val = System.getenv(key);
        if (val == null || val.isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(val);
        } catch (NumberFormatException e) {
            System.err.println("[Config] Invalid double for " + key + "=" + val + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    /** 启动时打印配置摘要（不打印敏感信息） */
    public static void printSummary() {
        System.out.println("=====================================================");
        System.out.println("  Trading Mode:     " + TRADING_MODE);
        System.out.println("  Symbol:           " + SYMBOL);
        System.out.println("  Initial Capital:  " + INITIAL_CAPITAL + " USDT");
        System.out.println("  API Key:          " + (BINANCE_API_KEY.isEmpty() ? "(not set)" : maskKey(BINANCE_API_KEY)));
        System.out.println("  Weights:          OBI=" + WEIGHT_OBI + " CVD=" + WEIGHT_CVD + " RSI=" + WEIGHT_RSI
                + " LCI=" + WEIGHT_LCI + " BB=" + WEIGHT_BB + " Fund=" + WEIGHT_FUNDING + " Vol=" + WEIGHT_VOL);
        System.out.println("  Fast OBI Thresh:  " + FAST_OBI_THRESHOLD);
        System.out.println("  Kelly Cold Start: " + KELLY_COLD_START_TRADES + " trades @ " + (KELLY_COLD_START_PCT * 100) + "%");
        System.out.println("=====================================================");
    }

    private static String maskKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
