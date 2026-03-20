package com.trading;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 决策引擎 v2.0 - 突破追势 + 动态杠杆 + 分批止盈 + 利滚利
 *
 * 信号分级：
 *   S级(20x)：RSI极端 + 趋势一致 + 量能暴增3x + 突破
 *   A级(15x)：RSI偏强 + 趋势一致 + 突破
 *   B级(10x)：RSI偏强 + 趋势一致
 *
 * 止盈止损：
 *   止损：-15% ROE 一刀切
 *   止盈：+20% 平1/3 → +40% 平1/3 → 剩余移动止盈(回撤30%)
 *
 * 陷阱机制：在突破价附近设伏，价格确认突破后毫秒级入场
 */
public class TradingDecisionEngine {
    private static int tickCount = 0;

    // ===== 止盈止损参数 =====
    private static final double STOP_LOSS_ROE = -15.0;       // 统一止损
    private static final double TP1_ROE = 20.0;              // 第一目标：平1/3
    private static final double TP2_ROE = 40.0;              // 第二目标：平1/3
    private static final double TRAILING_ACTIVATION = 15.0;  // 移动止盈激活阈值
    private static final double TRAILING_DRAWDOWN = 0.30;    // 从峰值回撤30%平仓

    // ===== 仓位管理 =====
    private static final int COOLDOWN_TICKS = 2;             // 平仓后冷却2个周期(30秒)

    // ===== 分批平仓追踪 =====
    private static boolean tp1Hit = false;    // 第一目标已触发
    private static boolean tp2Hit = false;    // 第二目标已触发
    private static double peakROE = 0.0;      // 历史最高ROE

    private static int ticksSinceLastClose = 999;

    // ===== 陷阱 =====
    public static class TacticalTrap {
        final String side;
        final double triggerPrice;
        final long expireTime;
        final String reason;
        final int leverage;
        final double bulletPct;  // 子弹仓占比

        public TacticalTrap(String s, double tp, long exp, String r, int lev, double bp) {
            this.side = s; this.triggerPrice = tp; this.expireTime = exp;
            this.reason = r; this.leverage = lev; this.bulletPct = bp;
        }
    }

    private static volatile TacticalTrap activeTrap = null;  // volatile 保证可见性

    // ==========================================
    // 毫秒级陷阱触发器 (每条消息)
    // ==========================================
    public static void checkFastTrap(double currentPrice, TradingAccount account) {
        if (!account.getPositionSide().equals("NONE")) {
            activeTrap = null;
            return;
        }

        TacticalTrap trap = activeTrap; // 本地副本，防止并发修改
        if (trap == null) return;

        if (System.currentTimeMillis() > trap.expireTime) {
            System.out.println("[TRAP EXPIRED] " + trap.side + " @ " + trap.triggerPrice);
            activeTrap = null;
            return;
        }

        boolean triggered = false;
        if (trap.side.equals("LONG") && currentPrice >= trap.triggerPrice) triggered = true;  // 突破高点做多
        if (trap.side.equals("SHORT") && currentPrice <= trap.triggerPrice) triggered = true; // 跌破低点做空

        if (triggered) {
            System.out.println("\n>> [TRAP TRIGGERED] price=" + fmtP(currentPrice) + " hit " + trap.side + " trap @ " + fmtP(trap.triggerPrice));
            executeFire(trap.side, currentPrice, account, trap.reason, trap.leverage, trap.bulletPct);
            activeTrap = null;
        }
    }

    // ==========================================
    // 15秒决策大脑
    // ==========================================
    public static void evaluateAndAskAI(double currentPrice, OpenClawGatewayClient gateway, TradingAccount account) {
        if (account.checkGlobalKillSwitch()) return;
        if (!IndicatorCalculator.isReady()) return;

        tickCount++;
        ticksSinceLastClose++;

        double rsi = IndicatorCalculator.getRSI(14);
        double volSurge = IndicatorCalculator.getVolumeSurgeMultiplier();
        String trend = IndicatorCalculator.getTrend();
        String breakout = IndicatorCalculator.checkBreakout(20);

        if (account.checkLiquidation(currentPrice)) { resetState(); return; }

        // ==========================================
        // 有仓位：止盈止损管理
        // ==========================================
        if (!account.getPositionSide().equals("NONE")) {
            double roe = account.getROE(currentPrice);

            // 更新历史最高ROE
            if (roe > peakROE) peakROE = roe;

            // 1. 硬止损 -15%
            if (roe <= STOP_LOSS_ROE) {
                System.out.println("[STOP LOSS] ROE=" + fmtP(roe) + "% hit " + fmtP(STOP_LOSS_ROE) + "%");
                account.closePosition(currentPrice, "STOP LOSS ROE=" + fmtP(roe) + "%");
                resetState(); return;
            }

            // 2. 分批止盈第一目标 +20%：平1/3
            if (!tp1Hit && roe >= TP1_ROE) {
                tp1Hit = true;
                System.out.println("[TP1] ROE=" + fmtP(roe) + "% -> close 1/3");
                account.closePartial(currentPrice, 0.333, "TP1 ROE=" + fmtP(roe) + "%");
                return;
            }

            // 3. 分批止盈第二目标 +40%：再平1/3
            if (tp1Hit && !tp2Hit && roe >= TP2_ROE) {
                tp2Hit = true;
                System.out.println("[TP2] ROE=" + fmtP(roe) + "% -> close 1/3");
                account.closePartial(currentPrice, 0.50, "TP2 ROE=" + fmtP(roe) + "%"); // 剩余的50% = 原来的1/3
                return;
            }

            // 4. 移动止盈：峰值ROE >= 15% 后回撤超30%
            if (peakROE >= TRAILING_ACTIVATION && roe <= peakROE * (1 - TRAILING_DRAWDOWN)) {
                System.out.println("[TRAILING STOP] peak=" + fmtP(peakROE) + "% -> now=" + fmtP(roe) + "% drawdown");
                account.closePosition(currentPrice, "TRAILING peak=" + fmtP(peakROE) + "% now=" + fmtP(roe) + "%");
                resetState(); return;
            }

            // 5. 反向趋势保护：趋势反转立刻跑
            String posSide = account.getPositionSide();
            if (posSide.equals("LONG") && trend.equals("BEAR") && roe < 5.0) {
                System.out.println("[TREND REVERSAL] LONG but trend turned BEAR, closing");
                account.closePosition(currentPrice, "Trend reversal BEAR, ROE=" + fmtP(roe) + "%");
                resetState(); return;
            }
            if (posSide.equals("SHORT") && trend.equals("BULL") && roe < 5.0) {
                System.out.println("[TREND REVERSAL] SHORT but trend turned BULL, closing");
                account.closePosition(currentPrice, "Trend reversal BULL, ROE=" + fmtP(roe) + "%");
                resetState(); return;
            }

            // 有仓位时只管仓位，不开新仓
            printTick(currentPrice, rsi, trend, breakout, volSurge, account);
            return;
        }

        // ==========================================
        // 无仓位：寻找开仓信号
        // ==========================================

        // 冷却期
        if (ticksSinceLastClose < COOLDOWN_TICKS) {
            System.out.println("[COOLDOWN] " + (COOLDOWN_TICKS - ticksSinceLastClose) + " ticks remaining");
            return;
        }

        // 异步更新 CEO/CRO 情报（不阻塞主决策）
        updateIntelligence(currentPrice, rsi, volSurge, gateway);

        // 尝试 AI V3 决策
        String aiDecision = tryAIDecision(currentPrice, rsi, trend, volSurge, account, gateway);

        // 如果 AI 给出了有效决策就用 AI 的
        if (aiDecision != null) return;

        // ==========================================
        // 程序化策略：信号分级
        // ==========================================
        String signal = evaluateSignal(currentPrice, rsi, trend, breakout, volSurge);

        if (signal.equals("NONE")) {
            printTick(currentPrice, rsi, trend, breakout, volSurge, account);
            return;
        }

        // 解析信号
        String[] parts = signal.split("\\|");
        String side = parts[0];         // LONG or SHORT
        String grade = parts[1];        // S, A, B
        int lev = Integer.parseInt(parts[2]);
        double bulletPct = Double.parseDouble(parts[3]);
        String reason = parts[4];

        // 计算陷阱触发价（突破确认价）
        double triggerPrice;
        if (side.equals("LONG")) {
            // 做多：在近期高点上方设伏，等价格突破确认
            double recentHigh = IndicatorCalculator.getRecentHigh(10);
            triggerPrice = Math.max(currentPrice - currentPrice * 0.001, recentHigh); // 近期高点或现价-0.1%
        } else {
            // 做空：在近期低点下方设伏
            double recentLow = IndicatorCalculator.getRecentLow(10);
            triggerPrice = Math.min(currentPrice + currentPrice * 0.001, recentLow);
        }
        triggerPrice = Math.round(triggerPrice * 1000.0) / 1000.0;

        // 如果已经突破了，直接开仓不设陷阱
        if ((side.equals("LONG") && breakout.equals("BREAK_UP")) ||
            (side.equals("SHORT") && breakout.equals("BREAK_DOWN"))) {
            System.out.println("\n>> [DIRECT ENTRY] " + grade + "-signal confirmed breakout!");
            executeFire(side, currentPrice, account, reason, lev, bulletPct);
        } else {
            // 设置陷阱等待确认
            activeTrap = new TacticalTrap(side, triggerPrice, System.currentTimeMillis() + 90000, reason, lev, bulletPct);
            System.out.println("[TRAP SET] " + grade + "-signal | " + side + " @ " + fmtP(triggerPrice) + " | lev=" + lev + "x | " + reason);
        }
    }

    // ==========================================
    // 信号评估：返回 "SIDE|GRADE|LEV|BULLET_PCT|REASON" 或 "NONE"
    // ==========================================
    private static String evaluateSignal(double price, double rsi, String trend, String breakout, double volSurge) {

        // --- S级信号 (20x, 50%子弹仓) ---
        // 做多：RSI<25 + 趋势BULL + 放量3x + 突破
        if (rsi < 25 && trend.equals("BULL") && volSurge >= 3.0 && breakout.equals("BREAK_UP")) {
            return "LONG|S|20|0.50|S-signal: RSI=" + fmtP(rsi) + " BULL vol=" + fmtP(volSurge) + "x breakup";
        }
        // 做空：RSI>75 + 趋势BEAR + 放量3x + 突破
        if (rsi > 75 && trend.equals("BEAR") && volSurge >= 3.0 && breakout.equals("BREAK_DOWN")) {
            return "SHORT|S|20|0.50|S-signal: RSI=" + fmtP(rsi) + " BEAR vol=" + fmtP(volSurge) + "x breakdown";
        }

        // --- A级信号 (15x, 30%子弹仓) ---
        // 做多：RSI<35 + 趋势BULL + (突破 或 放量2x)
        if (rsi < 35 && trend.equals("BULL") && (breakout.equals("BREAK_UP") || volSurge >= 2.0)) {
            return "LONG|A|15|0.30|A-signal: RSI=" + fmtP(rsi) + " BULL " + (breakout.equals("BREAK_UP") ? "breakup" : "vol=" + fmtP(volSurge) + "x");
        }
        // 做空：RSI>65 + 趋势BEAR + (突破 或 放量2x)
        if (rsi > 65 && trend.equals("BEAR") && (breakout.equals("BREAK_DOWN") || volSurge >= 2.0)) {
            return "SHORT|A|15|0.30|A-signal: RSI=" + fmtP(rsi) + " BEAR " + (breakout.equals("BREAK_DOWN") ? "breakdown" : "vol=" + fmtP(volSurge) + "x");
        }

        // --- B级信号 (10x, 20%子弹仓) ---
        // 做多：RSI<40 + 趋势不是BEAR
        if (rsi < 40 && !trend.equals("BEAR")) {
            return "LONG|B|10|0.20|B-signal: RSI=" + fmtP(rsi) + " trend=" + trend + " looking for bounce";
        }
        // 做空：RSI>60 + 趋势不是BULL
        if (rsi > 60 && !trend.equals("BULL")) {
            return "SHORT|B|10|0.20|B-signal: RSI=" + fmtP(rsi) + " trend=" + trend + " looking for drop";
        }

        return "NONE";
    }

    // ==========================================
    // 开火执行
    // ==========================================
    private static void executeFire(String side, double price, TradingAccount account, String reason, int lev, double bulletPct) {
        double bullet = account.getBulletBalance();
        double marginToUse = bullet * bulletPct;
        if (marginToUse < 5.0) marginToUse = 5.0; // 最低5U
        if (marginToUse > bullet) marginToUse = bullet;
        account.openPosition(side, price, marginToUse, lev, reason);
    }

    // ==========================================
    // AI V3 尝试（解析成功才用，失败走程序化）
    // ==========================================
    private static String tryAIDecision(double price, double rsi, String trend, double volSurge, TradingAccount account, OpenClawGatewayClient gateway) {
        try {
            String macro = IntelligenceBoard.getCeoStrategy();
            String risk = IntelligenceBoard.getCroRisk();

            String prompt = String.format(Locale.US,
                    "You are an HFT Planner. Output ONLY valid JSON, no other text.\n" +
                    "Inputs: Price=%.4f, RSI=%.2f, Trend=%s, VolSurge=%.2fx, Macro='%s', Risk='%s'.\n" +
                    "Rules:\n" +
                    "1. If RSI<35 and Trend=BULL, output {\"decision\":\"TRAP_LONG\", \"trigger_price\": %.3f, \"leverage\": 15, \"reason\":\"...\"}\n" +
                    "2. If RSI>65 and Trend=BEAR, output {\"decision\":\"TRAP_SHORT\", \"trigger_price\": %.3f, \"leverage\": 15, \"reason\":\"...\"}\n" +
                    "3. Otherwise output {\"decision\":\"HOLD\"}\n" +
                    "Output JSON exactly.",
                    price, rsi, trend, volSurge, macro, risk,
                    price * 0.999, price * 1.001);

            String raw = safe(gateway.askTraderSync("v3", "trader_exec", prompt));
            String clean = extractTruePayload(raw);

            String decision = parseDecision(clean);
            String reason = parseReason(clean);

            // 如果 AI 的 reason 不是默认值，说明解析成功
            if (!reason.equals("AI_PARSE_FAIL")) {
                double triggerPrice = parseTriggerPrice(clean);
                int aiLev = parseLeverage(clean);
                if (aiLev <= 0) aiLev = 10;

                System.out.println("[AI V3] decision=" + decision + " | " + reason);

                if (decision.startsWith("TRAP_") && triggerPrice > 0) {
                    String side = decision.replace("TRAP_", "");
                    activeTrap = new TacticalTrap(side, triggerPrice, System.currentTimeMillis() + 90000, "AI: " + reason, aiLev, 0.25);
                    System.out.println("[AI TRAP] " + side + " @ " + fmtP(triggerPrice) + " lev=" + aiLev + "x");
                    return decision;
                }
                if (decision.equals("HOLD")) return decision;
            }
        } catch (Exception e) {
            System.out.println("[AI OFFLINE] " + e.getMessage());
        }
        return null; // AI 失败，交给程序化策略
    }

    // ==========================================
    // CEO/CRO 情报更新
    // ==========================================
    private static void updateIntelligence(double price, double rsi, double volSurge, OpenClawGatewayClient gateway) {
        try {
            if (tickCount == 1 || tickCount % 15 == 0) {
                String ceoPrompt = String.format(Locale.US, "You are Macro CEO. Price=%.4f, RSI=%.2f. Output ONLY [MACRO: RANGING/BULL_TREND/BEAR_TREND]", price, rsi);
                gateway.askAgentAsync("kimi", "ceo_macro", ceoPrompt, 60000)
                       .thenAccept(res -> IntelligenceBoard.updateCeoStrategy(safe(res)));
            }
            if (tickCount == 1 || tickCount % 5 == 0 || rsi > 70 || rsi < 30) {
                String riskPrompt = String.format(Locale.US, "You are CRO. Price=%.4f RSI=%.2f VolSurge=%.2fx. Output ONLY [RISK: SAFE/HIGH_MANIPULATION]", price, rsi, volSurge);
                gateway.askAgentAsync("r1", "cro_risk", riskPrompt, 60000)
                       .thenAccept(res -> {
                           String flag = safe(res).contains("HIGH_MANIPULATION") ? "HIGH_MANIPULATION" : "SAFE";
                           IntelligenceBoard.updateCroRisk(flag);
                       });
            }
        } catch (Exception e) {
            // 情报更新失败不影响主流程
        }
    }

    // ==========================================
    // 状态重置
    // ==========================================
    private static void resetState() {
        peakROE = 0;
        tp1Hit = false;
        tp2Hit = false;
        ticksSinceLastClose = 0;
    }

    // ==========================================
    // 日志输出
    // ==========================================
    private static void printTick(double price, double rsi, String trend, String breakout, double volSurge, TradingAccount account) {
        boolean hasPos = !account.getPositionSide().equals("NONE");
        StringBuilder sb = new StringBuilder();
        sb.append("[TICK] price=").append(fmtP(price))
          .append(" RSI=").append(fmtP(rsi))
          .append(" trend=").append(trend)
          .append(" break=").append(breakout)
          .append(" vol=").append(fmtP(volSurge)).append("x");
        if (hasPos) {
            sb.append(" | ROE=").append(fmtP(account.getROE(price))).append("%")
              .append(" peak=").append(fmtP(peakROE)).append("%")
              .append(" tp1=").append(tp1Hit).append(" tp2=").append(tp2Hit);
        }
        sb.append(" | bullet=").append(fmtP(account.getBulletBalance())).append("U");
        System.out.println(sb.toString());
    }

    // ==========================================
    // 解析工具
    // ==========================================
    private static String extractTruePayload(String raw) {
        if (raw == null) return "";
        // 尝试多种格式提取
        // 格式1: {"text": "...", "mediaUrl": ...}
        try {
            Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"mediaUrl\"").matcher(raw);
            if (m.find()) return m.group(1).replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Exception e) {}
        // 格式2: 直接就是 JSON
        try {
            Matcher m = Pattern.compile("\\{[^{}]*\"decision\"[^{}]*\\}").matcher(raw);
            if (m.find()) return m.group(0);
        } catch (Exception e) {}
        return raw;
    }

    private static String parseDecision(String cleanOut) {
        if (cleanOut == null) return "HOLD";
        String s = cleanOut.toUpperCase();
        if (s.contains("\"DECISION\"") && s.contains("CLOSE")) return "CLOSE";
        if (s.contains("\"DECISION\"") && s.contains("TRAP_LONG")) return "TRAP_LONG";
        if (s.contains("\"DECISION\"") && s.contains("TRAP_SHORT")) return "TRAP_SHORT";
        return "HOLD";
    }

    private static double parseTriggerPrice(String cleanOut) {
        try {
            Matcher m = Pattern.compile("\"trigger_price\"\\s*:\\s*([0-9.]+)").matcher(cleanOut);
            if (m.find()) return Double.parseDouble(m.group(1));
        } catch (Exception e) {}
        return 0.0;
    }

    private static int parseLeverage(String cleanOut) {
        try {
            Matcher m = Pattern.compile("\"leverage\"\\s*:\\s*([0-9]+)").matcher(cleanOut);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Exception e) {}
        return 0;
    }

    private static String parseReason(String cleanOut) {
        try {
            Matcher m = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"").matcher(cleanOut);
            if (m.find()) return m.group(1);
        } catch (Exception e) {}
        return "AI_PARSE_FAIL";
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String fmtP(double v) { return String.format(Locale.US, "%.2f", v); }
}
