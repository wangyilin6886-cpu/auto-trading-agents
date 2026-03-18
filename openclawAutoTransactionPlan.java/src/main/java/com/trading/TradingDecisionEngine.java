package com.trading;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TradingDecisionEngine {
    private static int tickCount = 0;

    private static final double BASE_MARGIN_PCT = 0.05;    
    private static final int BASE_LEVERAGE = 10;           
    private static final int SNOWBALL_LEVERAGE = 20;       
    private static final double TAKE_PROFIT_ROE = 30.0;
    private static final double STOP_LOSS_ROE = -15.0;
    private static final int COOLDOWN_TICKS = 3;           

    private static int ticksSinceLastClose = 999;

    // 🕸️ 战术陷阱类
    public static class TacticalTrap {
        String side;
        double triggerPrice;
        long expireTime;
        String reason;
        public TacticalTrap(String s, double tp, long exp, String r) {
            this.side = s; this.triggerPrice = tp; this.expireTime = exp; this.reason = r;
        }
    }
    
    private static TacticalTrap activeTrap = null;

    // ==========================================
    // ⚡ 极速突击通道 (每 1 毫秒触发)
    // ==========================================
    public static void checkFastTrap(double currentPrice, TradingAccount account) {
        if (!account.getPositionSide().equals("NONE")) {
            activeTrap = null; 
            return;
        }
        if (activeTrap == null) return;

        if (System.currentTimeMillis() > activeTrap.expireTime) {
            System.out.println("⏳ [陷阱失效] 潜伏时间结束 (" + activeTrap.side + " @ " + activeTrap.triggerPrice + ")，自动销毁。");
            activeTrap = null;
            return;
        }

        boolean triggered = false;
        if (activeTrap.side.equals("LONG") && currentPrice <= activeTrap.triggerPrice) triggered = true;
        if (activeTrap.side.equals("SHORT") && currentPrice >= activeTrap.triggerPrice) triggered = true;

        if (triggered) {
            System.out.println("\n⚡⚡⚡ [毫秒级刺杀触发] 当前价格 " + currentPrice + " 触及陷阱线 " + activeTrap.triggerPrice + "！无需请示，直接开火！");
            executeFire(activeTrap.side, currentPrice, account, activeTrap.reason);
            activeTrap = null; 
        }
    }

    // ==========================================
    // 🧠 大脑运筹通道 (每 15 秒思考一次)
    // ==========================================
    public static void evaluateAndAskAI(double currentPrice, OpenClawGatewayClient gateway, TradingAccount account) {
        if (account.checkGlobalKillSwitch()) return; 
        if (!IndicatorCalculator.isReady()) return;
        
        tickCount++;
        ticksSinceLastClose++;

        double rsi = IndicatorCalculator.getRSI(14);
        double volSurge = IndicatorCalculator.getVolumeSurgeMultiplier();

        if (account.checkLiquidation(currentPrice)) { ticksSinceLastClose = 0; return; }

        if (!account.getPositionSide().equals("NONE")) {
            double roe = account.getROE(currentPrice);
            if (roe <= STOP_LOSS_ROE) {
                System.out.println("🚨 [程序化止损] ROE=" + fmtPrice(roe) + "% 触及 " + fmtPrice(STOP_LOSS_ROE) + "% 红线，立即斩仓！");
                account.closePosition(currentPrice, "程序化止损 ROE=" + fmtPrice(roe) + "%");
                ticksSinceLastClose = 0; return;
            }
            if (roe >= TAKE_PROFIT_ROE) {
                System.out.println("💰 [程序化止盈] ROE=" + fmtPrice(roe) + "% 达到 +" + fmtPrice(TAKE_PROFIT_ROE) + "% 目标，落袋为安！");
                account.closePosition(currentPrice, "程序化止盈 ROE=" + fmtPrice(roe) + "%");
                ticksSinceLastClose = 0; return;
            }
        }

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
        } catch (Exception e) {}

        String macro = IntelligenceBoard.getCeoStrategy();
        String risk = IntelligenceBoard.getCroRisk();
        boolean hasPos = !account.getPositionSide().equals("NONE");

        double currentROE = hasPos ? account.getROE(currentPrice) : 0.0;
        double currentPNL = hasPos ? account.getUnrealizedPNL(currentPrice) : 0.0;
        String posSide = account.getPositionSide();

        System.out.println("\n📊 现价=" + fmtPrice(currentPrice) + " | RSI=" + fmtPrice(rsi) +
                (hasPos ? " | ROE=" + fmtPrice(currentROE) + "% | PNL=" + fmtPrice(currentPNL) : ""));

        String positionInfo = hasPos
                ? String.format(Locale.US, ", PosSide='%s', ROE=%.2f%%, PNL=%.4f USDT", posSide, currentROE, currentPNL)
                : "";

        String prompt = String.format(Locale.US,
                "You are an HFT Planner. Output ONLY JSON.\n" +
                "Inputs: Price=%.4f, RSI=%.2f, Macro='%s', Risk='%s', HasPos=%b%s.\n" +
                "Rules:\n" +
                "1. If HasPos=true and ROE < -8%%, output {\"decision\":\"CLOSE\", \"reason\":\"stop loss\"}\n" +
                "2. If HasPos=true and ROE > 20%%, output {\"decision\":\"CLOSE\", \"reason\":\"take profit\"}\n" +
                "3. If HasPos=true and trend reverses against position, output {\"decision\":\"CLOSE\", \"reason\":\"...\"}\n" +
                "4. If HasPos=false, RSI < 40 and Macro != BEAR_TREND, output {\"decision\":\"TRAP_LONG\", \"trigger_price\": <price slightly below current>, \"reason\":\"...\"}\n" +
                "5. If HasPos=false, RSI > 60 and Macro != BULL_TREND, output {\"decision\":\"TRAP_SHORT\", \"trigger_price\": <price slightly above current>, \"reason\":\"...\"}\n" +
                "6. Otherwise output {\"decision\":\"HOLD\"}\n" +
                "Output JSON exactly.",
                currentPrice, rsi, macro, risk, hasPos, positionInfo);

        String decision = "HOLD";
        String reason = "";
        double triggerPrice = 0.0;

        try {
            String raw = safe(gateway.askTraderSync("v3", "trader_exec", prompt));
            String cleanAIOutput = extractTruePayload(raw);

            decision = parseDecision(cleanAIOutput);
            reason = parseReason(cleanAIOutput);
            triggerPrice = parseTriggerPrice(cleanAIOutput);
        } catch (Exception e) {
            System.out.println("⚠️ [AI 通讯异常] " + e.getMessage());
        }

        // 🛑 核心防线：AI 解析失败时(理由为默认值)，启用程序化规则兜底
        boolean aiFailed = reason.equals("多智能体联合推演");
        if (aiFailed && hasPos) {
            if (currentROE <= -8.0) {
                decision = "CLOSE";
                reason = "程序兜底: 亏损超 -8% 强制止损";
            } else if (currentROE >= 15.0) {
                decision = "CLOSE";
                reason = "程序兜底: 盈利超 +15% 主动止盈";
            }
        }
        if (aiFailed && !hasPos && ticksSinceLastClose >= COOLDOWN_TICKS) {
            if (rsi < 30) {
                decision = "TRAP_LONG";
                triggerPrice = currentPrice - 0.02;
                reason = "程序兜底: RSI=" + fmtPrice(rsi) + " 超卖，尝试抄底";
            } else if (rsi > 70) {
                decision = "TRAP_SHORT";
                triggerPrice = currentPrice + 0.02;
                reason = "程序兜底: RSI=" + fmtPrice(rsi) + " 超买，尝试做空";
            }
        }

        // 智能补全触发价格
        if (triggerPrice <= 0 && decision.startsWith("TRAP_")) {
            triggerPrice = decision.equals("TRAP_LONG") ? currentPrice - 0.03 : currentPrice + 0.03;
        }

        System.out.println("🧠 [V3 决策] " + decision + " | " + reason + (aiFailed ? " (AI离线,程序兜底)" : ""));

        if (decision.equals("CLOSE") && hasPos) {
            account.closePosition(currentPrice, reason);
            ticksSinceLastClose = 0;
        }
        else if (decision.startsWith("TRAP_") && !hasPos) {
            if (ticksSinceLastClose < COOLDOWN_TICKS) {
                System.out.println("⏳ 枪管过热，冷却中...");
            } else if (triggerPrice > 0) {
                String side = decision.replace("TRAP_", "");
                triggerPrice = Math.round(triggerPrice * 1000.0) / 1000.0;
                activeTrap = new TacticalTrap(side, triggerPrice, System.currentTimeMillis() + 60000, reason);
                System.out.println("🕸️ [潜伏模式] 狙击手已在 " + triggerPrice + " 布置 " + side + " 陷阱！");
            }
        } else {
            account.printStatus(currentPrice);
        }
    }

    private static void executeFire(String side, double currentPrice, TradingAccount account, String reason) {
        double wallet = account.getWalletBalance();
        double marginToUse = wallet * BASE_MARGIN_PCT;
        if (marginToUse < 10.0) marginToUse = 10.0;
        account.openPosition(side, currentPrice, marginToUse, BASE_LEVERAGE, "陷阱触发: " + reason);
    }

    // 激光提取器：精准剥离出真正的 AI 返回文本
    private static String extractTruePayload(String raw) {
        try {
            Matcher m = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"mediaUrl\"").matcher(raw);
            if (m.find()) {
                return m.group(1).replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\");
            }
        } catch (Exception e) {}
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
        } catch (Exception e) {}
        return 0.0;
    }

    private static String parseReason(String cleanOut) {
        try {
            Matcher m = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"").matcher(cleanOut);
            if (m.find()) return m.group(1);
        } catch (Exception e) {}
        return "多智能体联合推演";
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String fmtPrice(double v) { return String.format(Locale.US, "%.2f", v); }
}