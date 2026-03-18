package com.trading;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TradingDecisionEngine {
    private static int tickCount = 0;

    private static final double BASE_MARGIN_PCT = 0.05;    
    private static final int BASE_LEVERAGE = 10;           
    private static final int SNOWBALL_LEVERAGE = 20;       
    private static final double TAKE_PROFIT_ROE = 80.0;    
    private static final double STOP_LOSS_ROE = -50.0;     
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
                System.out.println("🚨 [系统最高警报] 触发实盘 -50% ROE，启动断臂求生！");
                account.closePosition(currentPrice, "物理止损熔断");
                ticksSinceLastClose = 0; return;
            }
            if (roe >= TAKE_PROFIT_ROE) {
                System.out.println("💰 [系统最高指令] 利润达到目标，强制落袋为安！");
                account.closePosition(currentPrice, "机械止盈");
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

        System.out.println("\n📊 现价=" + fmtPrice(currentPrice) + " | RSI=" + fmtPrice(rsi));

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
            
            // 🛑 核心修复：只提取 payload 里面真正的 AI 回复，过滤掉系统规则
            String cleanAIOutput = extractTruePayload(raw);
            
            String decision = parseDecision(cleanAIOutput);
            String reason = parseReason(cleanAIOutput);
            double triggerPrice = parseTriggerPrice(cleanAIOutput);

            // 🛑 智能补全：如果大模型太笨没输出价格，系统自动补齐！
            if (triggerPrice <= 0 && decision.startsWith("TRAP_")) {
                triggerPrice = decision.equals("TRAP_LONG") ? currentPrice - 0.03 : currentPrice + 0.03;
            }

            System.out.println("🧠 [V3 大脑推演] 决策: " + decision + " | 理由: " + reason);

            if (decision.equals("CLOSE") && hasPos) {
                account.closePosition(currentPrice, "AI 战术撤退: " + reason);
                ticksSinceLastClose = 0;
            } 
            else if (decision.startsWith("TRAP_") && !hasPos) {
                if (ticksSinceLastClose < COOLDOWN_TICKS) {
                    System.out.println("⏳ 枪管过热，冷却中...");
                } else if (triggerPrice > 0) {
                    String side = decision.replace("TRAP_", ""); 
                    triggerPrice = Math.round(triggerPrice * 1000.0) / 1000.0; // 格式化为3位小数
                    activeTrap = new TacticalTrap(side, triggerPrice, System.currentTimeMillis() + 60000, reason);
                    System.out.println("🕸️ [潜伏模式] 狙击手已在 " + triggerPrice + " 布置 " + side + " 陷阱！等待猎物踩雷...");
                }
            } else {
                account.printStatus(currentPrice);
            }
        } catch (Exception e) {
            System.out.println("❌ [大脑通讯断裂] 继续依靠底层程序防护: " + e.getMessage());
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