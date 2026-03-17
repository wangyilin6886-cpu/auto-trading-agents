package com.trading;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class DirectKimiClient {
    // ⚠️ 警告：跑通后请务必去 Moonshot 后台重置此 Key！
    private static final String API_KEY = "sk-lcovlOcuvuMCqUiTGZbGq0vtS8F2QAOHLg1zPJW4D4IfG1y2"; 
    private static final String URL = "https://api.moonshot.cn/v1/chat/completions";
    private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public static CompletableFuture<String> askKimiAsync(String prompt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String safePrompt = prompt.replace("\"", "\\\"").replace("\n", " ");
                String jsonBody = "{"
                        + "\"model\": \"moonshot-v1-8k\","
                        + "\"messages\": [{\"role\": \"user\", \"content\": \"" + safePrompt + "\"}],"
                        + "\"temperature\": 0.3"
                        + "}";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + API_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                
                // 极简解析 JSON 拿到 content
                String resBody = response.body();
                int contentStart = resBody.indexOf("\"content\":\"");
                if (contentStart == -1) return "Kimi战略解析失败";
                int textStart = contentStart + 11;
                int textEnd = resBody.indexOf("\"", textStart);
                return resBody.substring(textStart, textEnd).replace("\\n", " ").trim();
                
            } catch (Exception e) {
                return "Kimi连接异常: " + e.getMessage();
            }
        });
    }
}