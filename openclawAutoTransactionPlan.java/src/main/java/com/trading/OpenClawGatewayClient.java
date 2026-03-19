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
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    public OpenClawGatewayClient(String openclawExe) {
        this.openclawExe = openclawExe;
    }

    public String askTraderSync(String agentId, String sessionId, String prompt) throws Exception {
        return executeCommand(agentId, sessionId, prompt, true, 20000);
    }

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
        String safePrompt = prompt.replace("\"", "'").replace("\n", " ");

        if (IS_WINDOWS) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            StringBuilder cmdBuilder = new StringBuilder("set NO_PROXY=127.0.0.1,localhost && ");
            cmdBuilder.append(openclawExe).append(" agent ")
                      .append("--agent ").append(agentId).append(" ")
                      .append("--session-id ").append(sessionId).append(" ")
                      .append("--message \"").append(safePrompt).append("\" ");
            if (requireJson) cmdBuilder.append("--json ");
            cmd.add(cmdBuilder.toString());
        } else {
            // Linux / Mac
            cmd.add("/bin/sh");
            cmd.add("-c");
            StringBuilder cmdBuilder = new StringBuilder("NO_PROXY=127.0.0.1,localhost ");
            cmdBuilder.append(openclawExe).append(" agent ")
                      .append("--agent ").append(agentId).append(" ")
                      .append("--session-id ").append(sessionId).append(" ")
                      .append("--message '").append(safePrompt.replace("'", "'\\''")).append("' ");
            if (requireJson) cmdBuilder.append("--json ");
            cmd.add(cmdBuilder.toString());
        }

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
            throw new Exception("AI agent timeout after " + timeoutMs + "ms");
        }
        return out.toString().trim();
    }
}
