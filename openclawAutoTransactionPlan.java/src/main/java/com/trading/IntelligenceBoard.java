package com.trading;

import java.util.concurrent.atomic.AtomicReference;

public class IntelligenceBoard {
    // 👑 CEO Kimi 的宏观战略备忘录
    private static final AtomicReference<String> ceoMacroStrategy = new AtomicReference<>("WAITING_FOR_KIMI (启动中, 暂无宏观数据)");
    
    // 🛡️ CRO R1 的风控红绿灯
    private static final AtomicReference<String> croRiskStatus = new AtomicReference<>("GREEN (暂无致命风险)");

    public static void updateCeoStrategy(String strategy) {
        ceoMacroStrategy.set(strategy);
    }

    public static void updateCroRisk(String status) {
        croRiskStatus.set(status);
    }

    public static String getCeoStrategy() {
        return ceoMacroStrategy.get();
    }

    public static String getCroRisk() {
        return croRiskStatus.get();
    }
}