package com.trading;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 交易决策引擎 — 集成动态杠杆 + 浮盈加仓 + 追踪止盈。
 *
 * 修复：
 * - activeTrap 用 volatile + synchronized 消除竞态
 * - 集成 RiskManager 风控门禁
 * - 集成 DynamicLeverageEngine 动态杠杆
 * - 集成 PyramidManager 浮盈加仓
 * - 追踪止盈：ROE>30%激活，回撤50%利润平仓
 */
public class TradingDecisionEngine {

    private static int tickCount = 0;

    // ===== 策略参数 =====
    private static final double BASE_MARGIN_PCT = 0.05;   // 基础仓位用5%本金
    private static final double TAKE_PROFIT_ROE = 80.0;    // 固定止盈线
    private static final double STOP_LOSS_ROE = -50.0;     // 固定止损线
    private static final int COOLDOWN_TICKS = 3;

    // ===== 追踪止盈 =====
    private static final double TRAILING_ACTIVATE_ROE = 30.0;  // ROE>30%激活追踪
    private static final double TRAILING_CALLBACK_PCT = 0.50;  // 回撤50%利润平仓
    private static volatile double peakROE = 0;
    private static volatile boolean trailingActive = false;

    private static int ticksSinceLastClose = 999;

    // ===== 陷阱系统（线程安全） =====
    public static class TacticalTrap {
        final String side;
        final double triggerPrice;
        final long expireTime;
        final String reason;
        public TacticalTrap(String s, double tp, long exp, String r) {
            this.side = s; this.triggerPrice = tp; this.expireTime = exp; this.reason = r;
        }
    }

    private static volatile TacticalTrap activeTrap = null;
    private static final Object trapLock = new Object();

    // ===== 组件引用 =====
    private static RiskManager riskManager;
    private static PyramidManager pyramidManager = new PyramidManager();

    public static void init(RiskManager rm) {
        riskManager = rm;
    }

    // ==========================================
    // ⚡ 极速通道 (WebSocket线程每tick调用)
    // ==========================================
    public static void checkFastTrap(double currentPrice, BinanceRealAccount account) {
        // 持仓时不触发新陷阱
        if (!account.getPositionSide().equals("NONE")) {
            synchronized (trapLock) {
                activeTrap = null;
            }
            return;
        }

        TacticalTrap trap;
        synchronized (trapLock) {
            trap = activeTrap;
        }
        if (trap == null) return;

        // 过期检查
        if (System.currentTimeMillis() > trap.expireTime) {
            System.out.println("⏳ [陷阱失效] " + trap.side + " @ " + trap.triggerPrice + " 已过期");
            synchronized (trapLock) { activeTrap = null; }
            return;
        }

        // 触发检查
        boolean triggered = false;
        if (trap.side.equals("LONG") && currentPrice <= trap.triggerPrice) triggered = true;
        if (trap.side.equals("SHORT") && currentPrice >= trap.triggerPrice) triggered = true;

        if (triggered) {
            synchronized (trapLock) { activeTrap = null; }
            System.out.println("\n⚡⚡⚡ [陷阱触发] 价格 " + currentPrice + " 触及 " + trap.triggerPrice);
            executeFire(trap.side, currentPrice, account, trap.reason);
        }
    }

    // ==========================================
    // 🧠 大脑通道 (每15秒思考一次)
    // ==========================================
    public static void evaluateAndAskAI(double currentPrice, OpenClawGatewayClient gateway, BinanceRealAccount account) {
        if (riskManager != null && riskManager.isKilled()) return;
        if (!IndicatorCalculator.isReady()) return;

        tickCount++;
        ticksSinceLastClose++;

        double rsi = IndicatorCalculator.getRSI(14);
        double volSurge = IndicatorCalculator.getVolumeSurgeMultiplier();

        // 爆仓检测
        if (account.checkLiquidation(currentPrice)) {
            ticksSinceLastClose = 0;
            resetTrailing();
            pyramidManager.reset();
            return;
        }

        // ===== 持仓管理 =====
        if (!account.getPositionSide().equals("NONE")) {
            double roe = account.getROE(currentPrice);

            // 固定止损
            if (roe <= STOP_LOSS_ROE) {
                System.out.println("🚨 [止损触发] ROE=" + fmtPrice(roe) + "%");
                account.closePosition(currentPrice, "系统止损 ROE=" + fmtPrice(roe) + "%");
                ticksSinceLastClose = 0;
                resetTrailing();
                pyramidManager.reset();
                return;
            }

            // 固定止盈
            if (roe >= TAKE_PROFIT_ROE) {
                System.out.println("💰 [止盈触发] ROE=" + fmtPrice(roe) + "%");
                account.closePosition(currentPrice, "系统止盈 ROE=" + fmtPrice(roe) + "%");
                ticksSinceLastClose = 0;
                resetTrailing();
                pyramidManager.reset();
                return;
            }

            // 追踪止盈
            if (roe >= TRAILING_ACTIVATE_ROE) {
                if (!trailingActive) {
                    trailingActive = true;
                    peakROE = roe;
                    System.out.println("📈 [追踪止盈激活] ROE=" + fmtPrice(roe) + "% 开始追踪");
                }
                if (roe > peakROE) {
                    peakROE = roe;
                }
                // 从峰值回撤50%利润 → 平仓
                double drawdownFromPeak = peakROE - roe;
                if (drawdownFromPeak >= peakROE * TRAILING_CALLBACK_PCT) {
                    System.out.println("📈 [追踪止盈触发] 峰值ROE=" + fmtPrice(peakROE) + "% 当前=" + fmtPrice(roe) + "% 回撤锁利");
                    account.closePosition(currentPrice, "追踪止盈: 峰值" + fmtPrice(peakROE) + "% → " + fmtPrice(roe) + "%");
                    ticksSinceLastClose = 0;
                    resetTrailing();
                    pyramidManager.reset();
                    return;
                }
            }

            // 浮盈加仓检查
            String macro = IntelligenceBoard.getCeoStrategy();
            double pyramidMargin = pyramidManager.checkPyramid(currentPrice, roe, macro);
            if (pyramidMargin > 0) {
                int lev = account.getLeverage();
                boolean added = account.addToPosition(currentPrice, pyramidMargin, lev, "浮盈加仓第" + (pyramidManager.getPyramidCount() + 1) + "层");
                if (added) {
                    int addedQty = (int) Math.max(1, (pyramidMargin * lev) / currentPrice);
                    pyramidManager.recordPyramidLayer(currentPrice, addedQty, pyramidMargin);

                    // 加仓后止损上移
                    double safeStop = pyramidManager.getSafeStopPrice();
                    if (safeStop > 0) {
                        account.updateStopLoss(safeStop);
                    }
                }
            }
        }

        // ===== AI决策 =====
        try {
            if (tickCount == 1 || tickCount % 15 == 0) {
                String ceoPrompt = String.format(Locale.US, "You are Macro CEO. Price=%.4f, RSI=%.2f. Output ONLY [MACRO: RANGING/BULL_TREND/BEAR_TREND]", currentPrice, rsi);
                gateway.askAgentAsync("kimi", "ceo_macro", ceoPrompt, 60000).thenAccept(res -> IntelligenceBoard.updateCeoStrategy(safe(res)));
            }
            if (tickCount == 1 || tickCount % 5 == 0 || rsi > 70 || rsi < 30) {
                String riskPrompt = String.format(Locale.US, "You are CRO. Price=%.4f RSI=%.2f VolSurge=%.2fx. Output ONLY [RISK: SAFE/HIGH_MANIPULATION]", currentPrice, rsi, volSurge);
                gateway.askAgentAsync("r1", "cro_risk", riskPrompt, 60000).thenAccept(res -> {
                    String flag = safe(res).contains("HIGH_MANIPULATION") ? "🔴 HIGH_MANIPULATION" : "🟢 SAFE";
                    IntelligenceBoard.updateCroRisk(flag);
                });
            }
        } catch (Exception e) {
            System.err.println("⚠️ [AI通讯异常] " + e.getMessage());
        }

        String macro = IntelligenceBoard.getCeoStrategy();
        String risk = IntelligenceBoard.getCroRisk();
        boolean hasPos = !account.getPositionSide().equals("NONE");

        System.out.println("\n📊 现价=" + fmtPrice(currentPrice) + " | RSI=" + fmtPrice(rsi)
                + " | 宏观=" + macro + " | 风控=" + risk);

        String prompt = String.format(Locale.US,
                "You are an HFT Planner. Output ONLY JSON.\n" +
                "Inputs: Price=%.4f, RSI=%.2f, Macro='%s', Risk='%s', HasPos=%b.\n" +
                "Rules:\n" +
                "1. If HasPos=true, and trend reverses, output {\"decision\":\"CLOSE\", \"reason\":\"...\"}\n" +
                "2. If HasPos=false, RSI < 45 and Macro != BEAR_TREND, predict a dip to buy. Output {\"decision\":\"TRAP_LONG\", \"trigger_price\": <price lower than current>, \"reason\":\"...\"}\n" +
                "3. If HasPos=false, RSI > 55 and Macro != BULL_TREND, predict a peak to sell. Output {\"decision\":\"TRAP_SHORT\", \"trigger_price\": <price higher than current>, \"reason\":\"...\"}\n" +
                "4. Otherwise output {\"decision\":\"HOLD\"}\n" +
                "Output JSON exactly.",
                currentPrice, rsi, macro, risk, hasPos);

        try {
            String raw = safe(gateway.askTraderSync("v3", "trader_exec", prompt));
            String cleanAIOutput = extractTruePayload(raw);

            String decision = parseDecision(cleanAIOutput);
            String reason = parseReason(cleanAIOutput);
            double triggerPrice = parseTriggerPrice(cleanAIOutput);

            // AI没输出价格时自动补齐
            if (triggerPrice <= 0 && decision.startsWith("TRAP_")) {
                triggerPrice = decision.equals("TRAP_LONG") ? currentPrice - 0.03 : currentPrice + 0.03;
            }

            System.out.println("🧠 [AI决策] " + decision + " | " + reason);

            if (decision.equals("CLOSE") && hasPos) {
                account.closePosition(currentPrice, "AI撤退: " + reason);
                ticksSinceLastClose = 0;
                resetTrailing();
                pyramidManager.reset();
            }
            else if (decision.startsWith("TRAP_") && !hasPos) {
                if (ticksSinceLastClose < COOLDOWN_TICKS) {
                    System.out.println("⏳ 冷却中...");
                } else if (triggerPrice > 0) {
                    String side = decision.replace("TRAP_", "");
                    triggerPrice = Math.round(triggerPrice * 1000.0) / 1000.0;
                    synchronized (trapLock) {
                        activeTrap = new TacticalTrap(side, triggerPrice, System.currentTimeMillis() + 60000, reason);
                    }
                    System.out.println("🕸️ [陷阱部署] " + side + " @ " + triggerPrice);
                }
            } else {
                account.printStatus(currentPrice);
            }
        } catch (Exception e) {
            System.out.println("❌ [AI通讯断裂] " + e.getMessage());
        }
    }

    // ==========================================
    // 🔥 开火执行（集成动态杠杆）
    // ==========================================
    private static void executeFire(String side, double currentPrice, BinanceRealAccount account, String reason) {
        // 风控门禁
        if (riskManager != null) {
            String rejectReason = riskManager.canTrade(account.getWalletBalance());
            if (rejectReason != null) {
                System.out.println("🚫 [风控拦截开火] " + rejectReason);
                return;
            }
        }

        double wallet = account.getWalletBalance();
        double marginToUse = wallet * BASE_MARGIN_PCT;
        if (marginToUse < 10.0) marginToUse = 10.0;

        // 风控裁剪保证金
        if (riskManager != null) {
            marginToUse = riskManager.clipMargin(marginToUse, wallet);
        }

        // 动态杠杆计算
        double rsi = IndicatorCalculator.getRSI(14);
        double volSurge = IndicatorCalculator.getVolumeSurgeMultiplier();
        String macro = IntelligenceBoard.getCeoStrategy();
        String risk = IntelligenceBoard.getCroRisk();

        int dynamicLev = DynamicLeverageEngine.calculate(side, rsi, volSurge, macro, risk);

        account.openPosition(side, currentPrice, marginToUse, dynamicLev, "陷阱触发: " + reason);

        // 记录基础层给浮盈加仓管理器
        if (!account.getPositionSide().equals("NONE")) {
            int qty = account.getPositionSize();
            pyramidManager.recordBaseLayer(side, currentPrice, qty, marginToUse);
            resetTrailing();
        }
    }

    private static void resetTrailing() {
        trailingActive = false;
        peakROE = 0;
    }

    // ==========================================
    // 解析工具
    // ==========================================
    private static String extractTruePayload(String raw) {
        try {
            Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"mediaUrl\"").matcher(raw);
            if (m.find()) {
                return m.group(1).replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\");
            }
        } catch (Exception e) { /* fall through */ }
        return raw;
    }

    private static String parseDecision(String cleanOut) {
        String s = cleanOut.toUpperCase();
        if (s.contains("\"DECISION\": \"CLOSE\"") || s.contains("\"DECISION\":\"CLOSE\"")) return "CLOSE";
        if (s.contains("\"DECISION\": \"TRAP_LONG\"") || s.contains("\"DECISION\":\"TRAP_LONG\"")) return "TRAP_LONG";
        if (s.contains("\"DECISION\": \"TRAP_SHORT\"") || s.contains("\"DECISION\":\"TRAP_SHORT\"")) return "TRAP_SHORT";
        return "HOLD";
    }

    private static double parseTriggerPrice(String cleanOut) {
        try {
            Matcher m = Pattern.compile("\"trigger_price\"\\s*:\\s*([0-9.]+)").matcher(cleanOut);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Exception e) { /* fall through */ }
        return 0.0;
    }

    private static String parseReason(String cleanOut) {
        try {
            Matcher m = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"").matcher(cleanOut);
            if (m.find()) return m.group(1);
        } catch (Exception e) { /* fall through */ }
        return "多智能体联合推演";
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String fmtPrice(double v) { return String.format(Locale.US, "%.2f", v); }
}
