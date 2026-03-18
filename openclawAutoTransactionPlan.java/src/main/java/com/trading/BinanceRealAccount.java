package com.trading;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.json.JSONObject;

public class BinanceRealAccount implements TradingAccount {

    private final String apiKey;
    private final String secretKey;
    private final HttpClient httpClient;
    private static final String BASE_URL = "https://fapi.binance.com";

    private final double initialCapital; 
    private double walletBalance; 
    private double realizedProfit = 0.0;

    private String positionSide = "NONE"; 
    private int positionSize = 0; 
    private double entryPrice = 0.0;
    private int leverage = 1;
    private double isolatedMargin = 0.0;

    private static final double MAINT_MARGIN_RATE = 0.01; 
    private boolean isGlobalKilled = false;

    public BinanceRealAccount(String apiKey, String secretKey, double initialCapital) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.initialCapital = initialCapital;
        this.walletBalance = initialCapital;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        
        System.out.println("🛡️ [军火库] 实盘防线已激活：逐仓隔离 + 物理防断网单 + 全局熔断");
        setMarginTypeToIsolated();
    }

    public boolean checkGlobalKillSwitch() {
        if (isGlobalKilled) return true; 
        if (walletBalance <= initialCapital * 0.80) {
            isGlobalKilled = true;
            System.out.println("💀💀💀 [最高灾难] 触发 20% 全局最大回撤！拔掉电源，彻底锁死！ 💀💀💀");
        }
        return isGlobalKilled;
    }

    // ==========================================
    // ⚔️ 真实开火指令 (挂载装甲版 + 强制火力保底)
    // ==========================================
    public void openPosition(String side, double price, double marginAmount, int lev, String reason) {
        if (checkGlobalKillSwitch()) return; 
        if (!positionSide.equals("NONE")) return; 

        // 🛑 修复 -2019 报错：强制保证至少买 1 个 SOL
        double exactQty = (marginAmount * lev) / price;
        int qty = (int) Math.max(1.0, exactQty); 
        
        // 如果算出来的保证金连买 1 个 SOL 都不够，强行提取足够的保证金
        double actualMarginUsed = (qty * price) / lev;
        if (this.walletBalance < actualMarginUsed) {
            System.out.println("⚠️ [余额警告] 你的总资金已经不足以开启哪怕最小的仓位了！需要充值！");
            return;
        }

        if (this.leverage != lev) {
            setLeverage(lev);
            this.leverage = lev;
        }

        String binanceSide = side.equals("LONG") ? "BUY" : "SELL";

        System.out.println("\n🔥 [实盘拔枪] 正在发送指令...");
        boolean success = sendOrder(binanceSide, qty, false);
        
        if (success) {
            this.positionSide = side.toUpperCase();
            this.positionSize = qty;
            this.entryPrice = price; 
            this.isolatedMargin = actualMarginUsed; // 记录真实扣除的保证金
            this.walletBalance -= (qty * price * 0.0004); // 预扣手续费

            System.out.println("   ‣ 方向: " + (side.equals("LONG") ? "🟩 做多" : "🟥 做空") + " | 数量: " + qty + " | 杠杆: " + lev + "x");
            
            // 挂载物理止损单
            double stopPct = 0.60 / lev; 
            double stopPrice = side.equals("LONG") ? price * (1 - stopPct) : price * (1 + stopPct);
            setPhysicalStopLoss(binanceSide, stopPrice);
        } else {
            System.out.println("❌ [实盘拔枪失败] 订单被交易所拒绝！");
        }
        printStatus(price);
    }

    // ==========================================
    // 🛡️ 真实撤退指令 (打扫战场版)
    // ==========================================
    public void closePosition(double price, String reason) {
        if (positionSide.equals("NONE")) return;

        String closeSide = positionSide.equals("LONG") ? "SELL" : "BUY";
        System.out.println("\n🪂 [实盘撤退] 正在发送平仓指令...");
        
        boolean success = sendOrder(closeSide, this.positionSize, true);

        if (success) {
            double pnl = getUnrealizedPNL(price);
            double closeFee = (this.positionSize * price) * 0.0004;
            
            this.walletBalance += (pnl - closeFee);
            this.realizedProfit += (pnl - closeFee);

            System.out.println("   ‣ 触发原因: " + reason);
            System.out.println("   ‣ 净盈亏: " + (pnl - closeFee > 0 ? "🟩 +" : "🟥 ") + fmt(pnl - closeFee) + " USDT");

            this.positionSide = "NONE";
            this.positionSize = 0;
            this.entryPrice = 0;
            this.isolatedMargin = 0;

            cancelAllOpenOrders();

        } else {
            System.out.println("❌ [致命警报] 平仓指令未执行！需要人工介入！");
        }
        checkGlobalKillSwitch(); 
        printStatus(price);
    }

    // ==========================================
    // 🔗 物理装甲与清理接口 (终极修复版)
    // ==========================================
    private void setPhysicalStopLoss(String entrySide, double stopPrice) {
        try {
            String stopSide = entrySide.equals("BUY") ? "SELL" : "BUY";
            String formattedPrice = String.format(Locale.US, "%.3f", stopPrice);

            String query = "symbol=SOLUSDT&side=" + stopSide 
                         + "&type=STOP_MARKET"
                         + "&stopPrice=" + formattedPrice 
                         + "&closePosition=true"
                         + "&timeInForce=GTC" 
                         + "&recvWindow=60000"
                         + "&timestamp=" + System.currentTimeMillis();
            
            String res = postPrivate("/fapi/v1/order", query);
            
            if (res.contains("orderId")) {
                System.out.println("🛡️ [物理装甲] 拔网线级容错止损单已送达币安机房! (触发价: " + formattedPrice + ")");
            } else {
                System.out.println("⚠️ [装甲挂载失败] " + res);
            }
        } catch (Exception e) {
            System.out.println("⚠️ [装甲挂载异常] " + e.getMessage());
        }
    }

    private void cancelAllOpenOrders() {
        try {
            String query = "symbol=SOLUSDT&recvWindow=60000&timestamp=" + System.currentTimeMillis();
            deletePrivate("/fapi/v1/allOpenOrders", query);
            System.out.println("🧹 [战场清理] 备用物理装甲已卸载，干干净净。");
        } catch (Exception e) {}
    }

    // ==========================================
    // 🔗 基础通讯底层
    // ==========================================
    private boolean sendOrder(String side, int qty, boolean reduceOnly) {
        try {
            String uniqueClientId = "OC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            
            String query = "symbol=SOLUSDT&side=" + side + "&type=MARKET&quantity=" + qty 
                         + "&newClientOrderId=" + uniqueClientId
                         + "&recvWindow=60000"
                         + "&timestamp=" + System.currentTimeMillis();
            
            if (reduceOnly) {
                query += "&reduceOnly=true";
            }
            
            String res = postPrivate("/fapi/v1/order", query);
            JSONObject json = new JSONObject(res);
            if (json.has("orderId")) {
                System.out.println("✅ [实盘成交] 订单号: " + json.getLong("orderId"));
                return true;
            } else {
                System.out.println("⚠️ API 拒绝: " + res);
                return false;
            }
        } catch (Exception e) {
            System.out.println("❌ 网络请求异常: " + e.getMessage());
            return false;
        }
    }

    private void setMarginTypeToIsolated() {
        try {
            String query = "symbol=SOLUSDT&marginType=ISOLATED&recvWindow=60000&timestamp=" + System.currentTimeMillis();
            postPrivate("/fapi/v1/marginType", query);
        } catch (Exception e) {}
    }

    private void setLeverage(int lev) {
        try {
            String query = "symbol=SOLUSDT&leverage=" + lev + "&recvWindow=60000&timestamp=" + System.currentTimeMillis();
            postPrivate("/fapi/v1/leverage", query);
        } catch (Exception e) {}
    }

    private String postPrivate(String endpoint, String query) throws Exception {
        String signature = signHMAC(query, this.secretKey);
        String url = BASE_URL + endpoint + "?" + query + "&signature=" + signature;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", this.apiKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String deletePrivate(String endpoint, String query) throws Exception {
        String signature = signHMAC(query, this.secretKey);
        String url = BASE_URL + endpoint + "?" + query + "&signature=" + signature;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", this.apiKey)
                .DELETE()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String signHMAC(String data, String key) throws Exception {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secret_key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secret_key);
        byte[] hash = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    public boolean checkLiquidation(double currentPrice) {
        if (positionSide.equals("NONE")) return false;
        double pnl = getUnrealizedPNL(currentPrice);
        if (pnl <= -isolatedMargin * (1 - MAINT_MARGIN_RATE)) {
            System.out.println("\n💀 [毁灭打击] 触发强制平仓！");
            this.walletBalance -= isolatedMargin; 
            this.realizedProfit -= isolatedMargin;
            this.positionSide = "NONE";
            this.positionSize = 0;
            this.entryPrice = 0;
            this.isolatedMargin = 0;
            checkGlobalKillSwitch();
            return true;
        }
        return false;
    }

    public double getUnrealizedPNL(double currentPrice) {
        if (positionSide.equals("LONG")) return (currentPrice - entryPrice) * positionSize;
        if (positionSide.equals("SHORT")) return (entryPrice - currentPrice) * positionSize;
        return 0.0;
    }

    public double getROE(double currentPrice) {
        if (isolatedMargin == 0) return 0.0;
        return (getUnrealizedPNL(currentPrice) / isolatedMargin) * 100.0;
    }

    public double getRealizedProfit() { return realizedProfit; }
    public double getWalletBalance() { return walletBalance; }
    public String getPositionSide() { return positionSide; }

    public void printStatus(double price) {
        System.out.println("---------------------------------------------------------");
        if(isGlobalKilled) System.out.println("🚫 [系统状态] 瘫痪中 (触发全局止损)");
        System.out.println("🏦 [影子金库] 净余额: " + fmt(walletBalance) + " USDT | 纯利润: " + (realizedProfit>=0?"+":"") + fmt(realizedProfit) + " USDT");
        if (!positionSide.equals("NONE")) {
            System.out.println("📦 [实盘持仓] " + positionSide + " | 数量: " + positionSize + " SOL | 杠杆: " + leverage + "x");
            System.out.println("   ‣ 浮动盈亏: " + (getUnrealizedPNL(price)>=0?"🟩 +":"🟥 ") + fmt(getUnrealizedPNL(price)) + " USDT (ROE: " + fmt(getROE(price)) + "%)");
        } else {
            System.out.println("🛡️ [实盘战局] 游击隐蔽中 (空仓)");
        }
        System.out.println("---------------------------------------------------------");
    }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}