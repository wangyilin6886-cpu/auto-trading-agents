package com.trading;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class OpenClawGatewayClient {
    private final String openclawExe;

    public OpenClawGatewayClient(String openclawExe) {
        this.openclawExe = openclawExe;
    }

    // ⚡ V3 极速调用 (带 JSON 格式输出)
    public String askTraderSync(String agentId, String sessionId, String prompt) throws Exception {
        return executeCommand(agentId, sessionId, prompt, true, 20000);
    }

    // 👑🛡️ Kimi 和 R1 专用的异步后台调用
    public CompletableFuture<String> askAgentAsync(String agentId, String sessionId, String prompt, long timeoutMs) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeCommand(agentId, sessionId, prompt, false, timeoutMs);
            } catch (Exception e) {
                return "ERROR: " + e.getMessage();
            }
        });
    }

    private String executeCommand(String agentId, String sessionId, String prompt, boolean requireJson, long timeoutMs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("cmd.exe"); cmd.add("/c");
        
        String safePrompt = prompt.replace("\"", "'").replace("\n", " ");
        
        // 核心：基于官方白皮书的完美 Agent 路由命令
        StringBuilder cmdBuilder = new StringBuilder("set NO_PROXY=127.0.0.1,localhost && ");
        cmdBuilder.append(openclawExe).append(" agent ")
                  .append("--agent ").append(agentId).append(" ")
                  .append("--session-id ").append(sessionId).append(" ")
                  .append("--message \"").append(safePrompt).append("\" ");

        if (requireJson) cmdBuilder.append("--json ");

        cmd.add(cmdBuilder.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line).append("\n");
        }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new Exception("Timeout!");
        }
        return out.toString().trim();
    }
}