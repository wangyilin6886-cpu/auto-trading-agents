package com.trading;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Properties;

public class BinanceRealTrader {
    private String apiKey;
    private String secretKey;
    private static final String BASE_URL = "https://api.binance.com";
    private final HttpClient httpClient;

    public BinanceRealTrader() {
        this.httpClient = HttpClient.newBuilder().build();
        loadKeysFromSafeBox();
    }

    // 🛡️ 从本地物理保险箱读取私钥，绝对不硬编码
    private void loadKeysFromSafeBox() {
        try (FileInputStream input = new FileInputStream("config.properties")) {
            Properties prop = new Properties();
            prop.load(input);
            this.apiKey = prop.getProperty("BINANCE_API_KEY");
            this.secretKey = prop.getProperty("BINANCE_SECRET_KEY");
            if (apiKey == null || secretKey == null) {
                throw new Exception("保险箱 config.properties 中未找到 API Key 或 Secret Key！");
            }
        } catch (Exception e) {
            System.err.println("🚨 致命错误：无法读取币安私钥！" + e.getMessage());
            System.exit(1);
        }
    }

    // 🔐 华尔街级 HMAC-SHA256 签名算法
    private String createSignature(String data) throws Exception {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(secretKey.getBytes("UTF-8"), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] hash = sha256_HMAC.doFinal(data.getBytes("UTF-8"));
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    // 💰 第一重火力测试：接管真实资产（查询 USDT 余额）
    public void testConnectionAndGetBalance() {
        try {
            System.out.println("🔐 正在使用 HMAC-SHA256 验证真实币安金库权限...");
            String timestamp = String.valueOf(Instant.now().toEpochMilli());
            String queryString = "timestamp=" + timestamp;
            String signature = createSignature(queryString);
            
            String url = BASE_URL + "/api/v3/account?" + queryString + "&signature=" + signature;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-MBX-APIKEY", apiKey)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                System.out.println("✅ 真实金库接管成功！授权通道已打通！");
                // 简单解析余额（实际操作中可用 JSON 库更优雅地解析）
                if (response.body().contains("\"asset\":\"USDT\"")) {
                    System.out.println("💵 成功读取到您的 USDT 真实资产账户！");
                }
            } else {
                System.err.println("❌ 权限验证失败！币安返回: " + response.body());
            }
        } catch (Exception e) {
            System.err.println("❌ 真实交易网关异常: " + e.getMessage());
        }
    }

    // 🔫 预留的加特林真实开火接口（暂不上膛，验证完鉴权后开启）
    public void executeRealMarketOrder(String symbol, String side, double quantity) {
        // 下一步我们将在这里写入真实的买卖指令
        System.out.println("🚧 真实交易接口已就位，等待资产权限验证通过后解锁。");
    }
}