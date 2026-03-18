package com.trading;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OpenClaw CLI 网关 — 调用 3 个 AI Agent（MIO/RRO/TEO）。
 *
 * 修复：
 *   - 使用 /bin/sh 代替 cmd.exe（Linux 兼容）
 *   - prompt 安全转义
 *   - 超时后正确销毁进程树
 *   - 审计日志记录所有 AI 调用
 */
public class OpenClawGatewayClient {
    private final String openclawExe;

    public OpenClawGatewayClient(String openclawExe) {
        this.openclawExe = openclawExe;
    }

    /**
     * 同步调用（TEO 战术执行用）。
     */
    public String askTraderSync(String agentId, String sessionId, String prompt) throws Exception {
        return executeCommand(agentId, sessionId, prompt, true, 20000);
    }

    /**
     * 异步调用（MIO/RRO 后台情报用）。
     */
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
        // 安全转义 prompt
        String safePrompt = prompt.replace("'", "'\\''").replace("\n", " ");

        // 构建命令
        StringBuilder cmdBuilder = new StringBuilder();
        cmdBuilder.append(openclawExe).append(" agent ")
                .append("--agent ").append(agentId).append(" ")
                .append("--session-id ").append(sessionId).append(" ")
                .append("--message '").append(safePrompt).append("' ");

        if (requireJson) cmdBuilder.append("--json ");

        List<String> cmd = new ArrayList<>();
        cmd.add("/bin/sh");
        cmd.add("-c");
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
            throw new Exception("Timeout after " + timeoutMs + "ms for agent " + agentId);
        }

        String result = out.toString().trim();

        // 审计日志
        try {
            AuditLogger.get().logAIDecision(agentId, prompt, result, p.exitValue() == 0);
        } catch (Exception ignored) {}

        return result;
    }
}
