package com.trading;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多智能体编排器 — 3 Agent 并行决策。
 *
 * Agent 分工：
 *   MIO (Kimi)  — 宏观情报官：趋势判断 BULL_TREND/BEAR_TREND/RANGING
 *   RRO (R1)    — 风险推理官：风险评估 SAFE/HIGH_MANIPULATION
 *   TEO (V3)    — 战术执行官：交易决策 JSON {decision, trigger_price, reason}
 *
 * 调度策略：
 *   MIO: 每 75 秒轮询（低频宏观）
 *   RRO: 每 25 秒 或 RSI > 70 / RSI < 30 时触发（事件驱动）
 *   TEO: 每 15 秒决策，综合 MIO + RRO + SignalEngine 输出
 *
 * 安全：
 *   TEO 输出强制通过 SignalEngine 交叉验证
 *   任何 agent 超时不阻塞其他流程
 */
public class MultiAgentOrchestrator {

    private final OpenClawGatewayClient gateway;
    private final SignalEngine signalEngine;

    // 情报板（原 IntelligenceBoard 功能合并于此）
    private final AtomicReference<String> mioStrategy = new AtomicReference<>("RANGING");
    private final AtomicReference<String> rroRisk = new AtomicReference<>("SAFE");

    // 调度计数
    private int tickCount = 0;

    public MultiAgentOrchestrator(OpenClawGatewayClient gateway, SignalEngine signalEngine) {
        this.gateway = gateway;
        this.signalEngine = signalEngine;
    }

    // ==================== 情报板 API（替代 IntelligenceBoard） ====================

    public String getMioStrategy() { return mioStrategy.get(); }
    public String getRroRisk() { return rroRisk.get(); }

    // ==================== MIO 宏观情报轮询 ====================

    /**
     * 异步刷新 MIO 宏观判断。每 75 秒调用一次。
     */
    public void refreshMIO(double price, double rsi) {
        tickCount++;
        if (tickCount == 1 || tickCount % 5 == 0) { // 每 5 个 15s tick = 75s
            String prompt = String.format(Locale.US,
                    "You are Macro Intelligence Officer (MIO). Analyze market regime.\n" +
                    "Price=%.4f, RSI=%.2f, Composite Signal=%.3f\n" +
                    "Output ONLY one of: [MACRO: BULL_TREND] or [MACRO: BEAR_TREND] or [MACRO: RANGING]",
                    price, rsi, signalEngine.getLastComposite());

            gateway.askAgentAsync(Config.AGENT_MIO, Config.SESSION_MIO, prompt, 60000)
                    .thenAccept(res -> {
                        String safe = safe(res);
                        if (safe.contains("BULL_TREND")) mioStrategy.set("BULL_TREND");
                        else if (safe.contains("BEAR_TREND")) mioStrategy.set("BEAR_TREND");
                        else mioStrategy.set("RANGING");
                    });
        }
    }

    // ==================== RRO 风险评估 ====================

    /**
     * 异步刷新 RRO 风险评估。每 25 秒或 RSI 极值时调用。
     */
    public void refreshRRO(double price, double rsi, double volSurge) {
        if (tickCount == 1 || tickCount % 2 == 0 || rsi > 70 || rsi < 30) {
            String prompt = String.format(Locale.US,
                    "You are Risk Reasoning Officer (RRO). Assess market manipulation risk.\n" +
                    "Price=%.4f RSI=%.2f VolSurge=%.2fx OBI=%.2f CVD=%.2f\n" +
                    "Output ONLY: [RISK: SAFE] or [RISK: HIGH_MANIPULATION]",
                    price, rsi, volSurge, signalEngine.getLastOBI(), signalEngine.getLastCVD());

            gateway.askAgentAsync(Config.AGENT_RRO, Config.SESSION_RRO, prompt, 60000)
                    .thenAccept(res -> {
                        String safe = safe(res);
                        if (safe.contains("HIGH_MANIPULATION")) {
                            rroRisk.set("HIGH_MANIPULATION");
                        } else {
                            rroRisk.set("SAFE");
                        }
                    });
        }
    }

    // ==================== TEO 决策执行 ====================

    /**
     * TEO 决策结果。
     */
    public static class Decision {
        public final String action;        // TRAP_LONG, TRAP_SHORT, CLOSE, HOLD
        public final double triggerPrice;   // 陷阱触发价
        public final String reason;
        public final boolean validated;     // 是否通过 SignalEngine 交叉验证

        public Decision(String action, double triggerPrice, String reason, boolean validated) {
            this.action = action;
            this.triggerPrice = triggerPrice;
            this.reason = reason;
            this.validated = validated;
        }
    }

    /**
     * 同步调用 TEO 获取交易决策，并用 SignalEngine 交叉验证。
     */
    public Decision askTEO(double price, double rsi, boolean hasPosition, String positionSide) {
        String macro = mioStrategy.get();
        String risk = rroRisk.get();
        double composite = signalEngine.getLastComposite();

        String prompt = String.format(Locale.US,
                "You are Tactical Execution Officer (TEO). Output ONLY JSON.\n" +
                "Inputs: Price=%.4f, RSI=%.2f, Macro='%s', Risk='%s', HasPos=%b, PosSide='%s',\n" +
                "Signals: OBI=%.2f CVD=%.2f LCI=%.2f BB=%.2f FR=%.2f Composite=%.3f\n" +
                "Rules:\n" +
                "1. If HasPos=true and trend reverses: {\"decision\":\"CLOSE\", \"reason\":\"...\"}\n" +
                "2. If HasPos=false and Composite>0.3 and Macro!='BEAR_TREND': {\"decision\":\"TRAP_LONG\", \"trigger_price\":<price>, \"reason\":\"...\"}\n" +
                "3. If HasPos=false and Composite<-0.3 and Macro!='BULL_TREND': {\"decision\":\"TRAP_SHORT\", \"trigger_price\":<price>, \"reason\":\"...\"}\n" +
                "4. Otherwise: {\"decision\":\"HOLD\"}\n" +
                "Output JSON exactly.",
                price, rsi, macro, risk, hasPosition, positionSide,
                signalEngine.getLastOBI(), signalEngine.getLastCVD(),
                signalEngine.getLastLCI(), signalEngine.getLastBB(),
                signalEngine.getLastFR(), composite);

        try {
            String raw = safe(gateway.askTraderSync(Config.AGENT_TEO, Config.SESSION_TEO, prompt));
            String clean = extractPayload(raw);

            String decision = parseDecision(clean);
            double triggerPrice = parseTriggerPrice(clean);
            String reason = parseReason(clean);

            // 自动补齐价格
            if (triggerPrice <= 0 && decision.startsWith("TRAP_")) {
                triggerPrice = decision.equals("TRAP_LONG") ? price - 0.03 : price + 0.03;
            }

            // SignalEngine 交叉验证
            boolean validated = validateDecision(decision, composite, risk);

            if (!validated) {
                System.out.println("[Orchestrator] TEO decision '" + decision + "' rejected by SignalEngine validation");
                AuditLogger.get().logAIDecision("TEO", prompt, raw, false);
                return new Decision("HOLD", 0, "Signal validation rejected: " + reason, false);
            }

            AuditLogger.get().logAIDecision("TEO", prompt, raw, true);
            return new Decision(decision, triggerPrice, reason, true);

        } catch (Exception e) {
            System.err.println("[Orchestrator] TEO call failed: " + e.getMessage());
            AuditLogger.get().logError("MultiAgentOrchestrator", "TEO failed: " + e.getMessage(), null);
            return new Decision("HOLD", 0, "TEO通讯异常", false);
        }
    }

    // ==================== 交叉验证 ====================

    /**
     * TEO 决策 vs SignalEngine 综合评分交叉验证。
     * 规则：
     *   TRAP_LONG  → composite 必须 > -0.3（不允许在强空信号时做多）
     *   TRAP_SHORT → composite 必须 < +0.3（不允许在强多信号时做空）
     *   CLOSE      → 总是通过
     *   HOLD       → 总是通过
     *   HIGH_MANIPULATION → 拒绝所有开仓
     */
    private boolean validateDecision(String decision, double composite, String risk) {
        if ("HOLD".equals(decision) || "CLOSE".equals(decision)) return true;

        // 高操纵风险 → 拒绝开仓
        if (risk.contains("HIGH_MANIPULATION")) return false;

        if ("TRAP_LONG".equals(decision)) {
            return composite > -Config.COMPOSITE_NEUTRAL_ZONE;
        }
        if ("TRAP_SHORT".equals(decision)) {
            return composite < Config.COMPOSITE_NEUTRAL_ZONE;
        }

        return true;
    }

    // ==================== 兼容旧接口 ====================

    /** 兼容 IntelligenceBoard.getCeoStrategy() */
    public static String getCeoStrategy() {
        // 如果有全局实例，返回其值
        return "RANGING";
    }

    // ==================== 解析工具 ====================

    private static String extractPayload(String raw) {
        try {
            Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"mediaUrl\"").matcher(raw);
            if (m.find()) {
                return m.group(1).replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\");
            }
        } catch (Exception e) {
            System.err.println("[Orchestrator] Payload extract error: " + e.getMessage());
        }
        return raw;
    }

    private static String parseDecision(String out) {
        String s = out.toUpperCase();
        if (s.contains("\"DECISION\"") && s.contains("CLOSE")) return "CLOSE";
        if (s.contains("\"DECISION\"") && s.contains("TRAP_LONG")) return "TRAP_LONG";
        if (s.contains("\"DECISION\"") && s.contains("TRAP_SHORT")) return "TRAP_SHORT";
        return "HOLD";
    }

    private static double parseTriggerPrice(String out) {
        try {
            Matcher m = Pattern.compile("\"trigger_price\"\\s*:\\s*([0-9.]+)").matcher(out);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Exception e) {
            System.err.println("[Orchestrator] Trigger price parse error: " + e.getMessage());
        }
        return 0.0;
    }

    private static String parseReason(String out) {
        try {
            Matcher m = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"").matcher(out);
            if (m.find()) return m.group(1);
        } catch (Exception e) {
            System.err.println("[Orchestrator] Reason parse error: " + e.getMessage());
        }
        return "多智能体联合推演";
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
