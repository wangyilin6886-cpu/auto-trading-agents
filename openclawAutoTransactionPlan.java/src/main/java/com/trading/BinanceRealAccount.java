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
    private double vaultBalance;
    private double bulletBalance;
    private double realizedProfit = 0.0;

    private String positionSide = "NONE";
    private double positionSize = 0.0;   // 修复：int → double
    private double entryPrice = 0.0;
    private int leverage = 1;
    private double isolatedMargin = 0.0;

    private static final double TAKER_FEE = 0.0004;
    private static final double MAINT_MARGIN_RATE = 0.01;
    private static final double VAULT_RATIO = 0.70;
    private static final double BULLET_RATIO = 0.30;

    private boolean isGlobalKilled = false;
    private int totalTrades = 0;
    private int winTrades = 0;

    public BinanceRealAccount(String apiKey, String secretKey, double initialCapital) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.initialCapital = initialCapital;
        this.vaultBalance = initialCapital * VAULT_RATIO;
        this.bulletBalance = initialCapital * BULLET_RATIO;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        System.out.println("[REAL] Account initialized. vault=" + fmt(vaultBalance) + " bullet=" + fmt(bulletBalance));
        setMarginTypeToIsolated();
    }

    public synchronized boolean checkGlobalKillSwitch() {
        if (isGlobalKilled) return true;
        double total = getWalletBalance();
        if (total <= initialCapital * 0.60) {
            isGlobalKilled = true;
            System.out.println("[KILL SWITCH] Total balance dropped below 60%! System locked.");
        }
        return isGlobalKilled;
    }

    public synchronized void openPosition(String side, double price, double marginAmount, int lev, String reason) {
        if (checkGlobalKillSwitch()) return;
        if (!positionSide.equals("NONE")) return;

        // 计算下单数量 (SOL精度为小数点后1位)
        double exactQty = (marginAmount * lev) / price;
        // SOL 最小下单精度是 0.1
        double qty = Math.floor(exactQty * 10) / 10.0;
        if (qty < 0.1) qty = 0.1;

        double actualMarginUsed = (qty * price) / lev;
        if (this.bulletBalance < actualMarginUsed) {
            System.out.println("[REJECT] Bullet balance insufficient: " + fmt(bulletBalance));
            return;
        }

        if (this.leverage != lev) {
            setLeverage(lev);
            this.leverage = lev;
        }

        String binanceSide = side.equals("LONG") ? "BUY" : "SELL";
        boolean success = sendOrder(binanceSide, qty, false);

        if (success) {
            this.positionSide = side.toUpperCase();
            this.positionSize = qty;
            this.entryPrice = price;
            this.isolatedMargin = actualMarginUsed;
            this.bulletBalance -= actualMarginUsed; // 保证金从子弹仓冻结
            double fee = qty * price * TAKER_FEE;
            this.bulletBalance -= fee;

            System.out.println(">> [REAL OPEN] " + side + " " + qty + " SOL @ " + fmt(price) + " | lev=" + lev + "x");

            // 物理止损单：ROE -20% 的价格
            double stopPct = 0.20 / lev;
            double stopPrice = side.equals("LONG") ? price * (1 - stopPct) : price * (1 + stopPct);
            setPhysicalStopLoss(binanceSide, stopPrice);
        } else {
            System.out.println("[FAIL] Order rejected by exchange");
        }
        printStatus(price);
    }

    public synchronized void closePosition(double price, String reason) {
        closePartial(price, 1.0, reason);
    }

    public synchronized void closePartial(double price, double fraction, String reason) {
        if (positionSide.equals("NONE")) return;
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        double closeSize = positionSize * fraction;
        // SOL 精度
        closeSize = Math.floor(closeSize * 10) / 10.0;
        if (closeSize < 0.1 && fraction < 0.999) return; // 太小不值得分批
        if (fraction >= 0.999) closeSize = positionSize; // 全平

        String closeSide = positionSide.equals("LONG") ? "SELL" : "BUY";
        // 实盘下单量需要转换
        boolean success = sendOrder(closeSide, closeSize, true);

        if (success) {
            double pnl;
            if (positionSide.equals("LONG")) pnl = (price - entryPrice) * closeSize;
            else pnl = (entryPrice - price) * closeSize;
            double closeFee = closeSize * price * TAKER_FEE;
            double netPnl = pnl - closeFee;

            double closeMargin = isolatedMargin * fraction;
            this.bulletBalance += closeMargin;
            recycleProfit(netPnl);
            this.realizedProfit += netPnl;
            this.totalTrades++;
            if (netPnl > 0) this.winTrades++;

            System.out.println(">> [REAL CLOSE " + String.format("%.0f%%", fraction * 100) + "] " + reason + " | pnl=" + (netPnl >= 0 ? "+" : "") + fmt(netPnl));

            this.positionSize -= closeSize;
            this.isolatedMargin -= closeMargin;

            if (this.positionSize < 0.05 || fraction >= 0.999) {
                this.positionSide = "NONE";
                this.positionSize = 0;
                this.entryPrice = 0;
                this.isolatedMargin = 0;
                cancelAllOpenOrders();
            }
        } else {
            System.out.println("[CRITICAL] Close order failed! Manual intervention needed!");
        }
        checkGlobalKillSwitch();
        printStatus(price);
    }

    // ==========================================
    // 利润回流
    // ==========================================
    public synchronized void recycleProfit(double pnl) {
        if (pnl > 0) {
            this.vaultBalance += pnl * 0.50;
            this.bulletBalance += pnl * 0.50;
        } else {
            this.bulletBalance += pnl;
            if (this.bulletBalance < 0) {
                double deficit = -this.bulletBalance;
                this.bulletBalance = 0;
                double canTake = Math.min(deficit, this.vaultBalance * 0.1);
                this.vaultBalance -= canTake;
                this.bulletBalance += canTake;
            }
        }
    }

    // ==========================================
    // 通讯底层
    // ==========================================
    private boolean sendOrder(String side, double qty, boolean reduceOnly) {
        try {
            String uniqueClientId = "OC_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
            String formattedQty = String.format(Locale.US, "%.1f", qty);

            String query = "symbol=SOLUSDT&side=" + side + "&type=MARKET&quantity=" + formattedQty
                         + "&newClientOrderId=" + uniqueClientId
                         + "&recvWindow=60000"
                         + "&timestamp=" + System.currentTimeMillis();
            if (reduceOnly) query += "&reduceOnly=true";

            String res = postPrivate("/fapi/v1/order", query);
            JSONObject json = new JSONObject(res);
            if (json.has("orderId")) {
                System.out.println("[ORDER OK] id=" + json.getLong("orderId") + " qty=" + formattedQty);
                return true;
            } else {
                System.out.println("[ORDER FAIL] " + res);
                return false;
            }
        } catch (Exception e) {
            System.out.println("[ORDER ERROR] " + e.getMessage());
            return false;
        }
    }

    private void setPhysicalStopLoss(String entrySide, double stopPrice) {
        try {
            String stopSide = entrySide.equals("BUY") ? "SELL" : "BUY";
            String formattedPrice = String.format(Locale.US, "%.3f", stopPrice);
            String query = "symbol=SOLUSDT&side=" + stopSide
                         + "&type=STOP_MARKET&stopPrice=" + formattedPrice
                         + "&closePosition=true&timeInForce=GTC"
                         + "&recvWindow=60000&timestamp=" + System.currentTimeMillis();
            String res = postPrivate("/fapi/v1/order", query);
            if (res.contains("orderId")) {
                System.out.println("[STOP LOSS] Physical stop set @ " + formattedPrice);
            } else {
                System.out.println("[STOP LOSS FAIL] " + res);
            }
        } catch (Exception e) {
            System.out.println("[STOP LOSS ERROR] " + e.getMessage());
        }
    }

    private void cancelAllOpenOrders() {
        try {
            String query = "symbol=SOLUSDT&recvWindow=60000&timestamp=" + System.currentTimeMillis();
            deletePrivate("/fapi/v1/allOpenOrders", query);
        } catch (Exception e) {}
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

    // ==========================================
    // 盈亏计算 + Getters
    // ==========================================
    public synchronized boolean checkLiquidation(double currentPrice) {
        if (positionSide.equals("NONE")) return false;
        double pnl = getUnrealizedPNL(currentPrice);
        if (pnl <= -isolatedMargin * (1 - MAINT_MARGIN_RATE)) {
            System.out.println("[LIQUIDATION] Position wiped out. Lost margin: " + fmt(isolatedMargin));
            this.realizedProfit -= isolatedMargin;
            this.totalTrades++;
            this.positionSide = "NONE";
            this.positionSize = 0;
            this.entryPrice = 0;
            this.isolatedMargin = 0;
            checkGlobalKillSwitch();
            return true;
        }
        return false;
    }

    public synchronized double getUnrealizedPNL(double currentPrice) {
        if (positionSide.equals("LONG")) return (currentPrice - entryPrice) * positionSize;
        if (positionSide.equals("SHORT")) return (entryPrice - currentPrice) * positionSize;
        return 0.0;
    }

    public synchronized double getROE(double currentPrice) {
        if (isolatedMargin == 0) return 0.0;
        return (getUnrealizedPNL(currentPrice) / isolatedMargin) * 100.0;
    }

    public synchronized double getRealizedProfit() { return realizedProfit; }
    public synchronized double getWalletBalance() { return vaultBalance + bulletBalance + isolatedMargin; }
    public synchronized double getVaultBalance() { return vaultBalance; }
    public synchronized double getBulletBalance() { return bulletBalance; }
    public synchronized double allocateFromBullet(double amount) {
        double actual = Math.min(amount, bulletBalance);
        bulletBalance -= actual;
        return actual;
    }
    public synchronized String getPositionSide() { return positionSide; }
    public synchronized double getPositionSize() { return positionSize; }
    public synchronized double getEntryPrice() { return entryPrice; }

    public synchronized void printStatus(double price) {
        double total = getWalletBalance();
        System.out.println("---------------------------------------------------------");
        if (isGlobalKilled) System.out.println("[SYSTEM] KILLED - all trading halted");
        System.out.println("[REAL ACCOUNT] total=" + fmt(total) + "U | vault=" + fmt(vaultBalance) + "U | bullet=" + fmt(bulletBalance) + "U");
        System.out.println("  profit=" + (realizedProfit >= 0 ? "+" : "") + fmt(realizedProfit) + "U | trades=" + totalTrades);
        if (!positionSide.equals("NONE")) {
            double pnl = getUnrealizedPNL(price);
            System.out.println("[POSITION] " + positionSide + " " + fmt(positionSize) + " SOL | lev=" + leverage + "x");
            System.out.println("  pnl=" + (pnl >= 0 ? "+" : "") + fmt(pnl) + "U (ROE=" + fmt(getROE(price)) + "%)");
        } else {
            System.out.println("[POSITION] FLAT");
        }
        System.out.println("---------------------------------------------------------");
    }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}
