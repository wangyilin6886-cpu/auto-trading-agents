package com.trading;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 清算猎手模式 (Liquidation Hunter)
 *
 * 通过监控公开数据预判清算级联：
 *   - 资金费率(Funding Rate)：极高=多头拥挤，极低=空头拥挤
 *   - 多空比(Long/Short Ratio)：>2.0=多头过度拥挤
 *
 * 逻辑：
 *   资金费率 > +0.02% AND 多空比 > 2.0 → 多头拥挤 → 做空吃清算瀑布
 *   资金费率 < -0.01% AND 多空比 < 0.8 → 空头拥挤 → 做多吃空头踩踏
 *
 * 每8小时资金费率结算前30分钟是最佳时机(00:00/08:00/16:00 UTC)
 *
 * 数据来源：币安公开REST API（无需API Key）
 */
public class LiquidationHunter {

    // ===== 阈值参数 =====
    private static final double FUNDING_RATE_HIGH = 0.0002;    // +0.02% 多头过度拥挤
    private static final double FUNDING_RATE_LOW = -0.0001;    // -0.01% 空头过度拥挤
    private static final double LONG_SHORT_RATIO_HIGH = 2.0;   // 多空比>2 多头拥挤
    private static final double LONG_SHORT_RATIO_LOW = 0.8;    // 多空比<0.8 空头拥挤
    private static final int HUNT_LEVERAGE = 15;

    // ===== API URLs =====
    private static final String FUNDING_RATE_URL = "https://fapi.binance.com/fapi/v1/fundingRate?symbol=SOLUSDT&limit=1";
    private static final String LONG_SHORT_URL = "https://fapi.binance.com/futures/data/globalLongShortAccountRatio?symbol=SOLUSDT&period=5m&limit=1";

    // ===== 状态 =====
    private double lastFundingRate = 0;
    private double lastLongShortRatio = 1.0;
    private long lastFetchTime = 0;
    private static final long FETCH_INTERVAL_MS = 300000; // 每5分钟获取一次

    // ===== 仓位 =====
    private double fund;
    private String positionSide = "NONE";
    private double entryPrice = 0;
    private double positionSize = 0;
    private double positionMargin = 0;
    private double peakROE = 0;

    // ===== 统计 =====
    private double totalProfit = 0;
    private int totalTrades = 0;
    private int winTrades = 0;
    private int huntsTriggered = 0;

    // ===== 冷却 =====
    private long lastHuntTime = 0;
    private static final long HUNT_COOLDOWN_MS = 600000; // 猎杀后冷却10分钟

    private static final double TAKER_FEE = 0.0004;
    private final TradingAccount mainAccount;

    public LiquidationHunter(double fund, TradingAccount account) {
        this.fund = fund;
        this.mainAccount = account;
        System.out.println("[LIQUIDATION HUNTER] Initialized | fund=" + fmt(fund) + "U");
    }

    /**
     * 定期调用（每tick或每15秒）
     */
    public synchronized void onTick(double price) {
        long now = System.currentTimeMillis();

        // 持仓管理
        if (!positionSide.equals("NONE")) {
            managePosition(price);
            return;
        }

        // 时区检查
        if (!SessionKiller.canOpenNewPosition()) return;

        // 冷却检查
        if (now - lastHuntTime < HUNT_COOLDOWN_MS) return;

        // 定期获取数据
        if (now - lastFetchTime > FETCH_INTERVAL_MS) {
            lastFetchTime = now;
            fetchMarketData();
        }

        // 评估猎杀机会
        evaluateHuntSignal(price);
    }

    /**
     * 获取资金费率和多空比
     */
    private void fetchMarketData() {
        // 异步获取，不阻塞主线程
        new Thread(() -> {
            try {
                // 获取资金费率
                String fundingJson = httpGet(FUNDING_RATE_URL);
                if (fundingJson != null && fundingJson.startsWith("[")) {
                    JSONArray arr = new JSONArray(fundingJson);
                    if (arr.length() > 0) {
                        lastFundingRate = arr.getJSONObject(0).getDouble("fundingRate");
                    }
                }

                // 获取多空比
                String lsJson = httpGet(LONG_SHORT_URL);
                if (lsJson != null && lsJson.startsWith("[")) {
                    JSONArray arr = new JSONArray(lsJson);
                    if (arr.length() > 0) {
                        lastLongShortRatio = arr.getJSONObject(0).getDouble("longShortRatio");
                    }
                }

                System.out.println("[HUNT DATA] fundingRate=" + String.format("%.4f%%", lastFundingRate * 100)
                        + " | L/S ratio=" + String.format("%.2f", lastLongShortRatio));

            } catch (Exception e) {
                System.out.println("[HUNT DATA ERROR] " + e.getMessage());
            }
        }).start();
    }

    /**
     * 评估猎杀信号
     */
    private void evaluateHuntSignal(double price) {
        // 多头过度拥挤 → 做空猎杀
        if (lastFundingRate > FUNDING_RATE_HIGH && lastLongShortRatio > LONG_SHORT_RATIO_HIGH) {
            System.out.println("\n[HUNT SIGNAL] BEARS HUNT! Longs overcrowded"
                    + " | fundingRate=" + fmtPct2(lastFundingRate * 100) + "%"
                    + " | L/S=" + String.format("%.2f", lastLongShortRatio));
            openHuntPosition("SHORT", price, "Longs overcrowded FR=" + fmtPct2(lastFundingRate * 100) + "% LS=" + String.format("%.2f", lastLongShortRatio));
            return;
        }

        // 空头过度拥挤 → 做多猎杀
        if (lastFundingRate < FUNDING_RATE_LOW && lastLongShortRatio < LONG_SHORT_RATIO_LOW) {
            System.out.println("\n[HUNT SIGNAL] BULLS HUNT! Shorts overcrowded"
                    + " | fundingRate=" + fmtPct2(lastFundingRate * 100) + "%"
                    + " | L/S=" + String.format("%.2f", lastLongShortRatio));
            openHuntPosition("LONG", price, "Shorts overcrowded FR=" + fmtPct2(lastFundingRate * 100) + "% LS=" + String.format("%.2f", lastLongShortRatio));
        }
    }

    /**
     * 开仓
     */
    private void openHuntPosition(String side, double price, String reason) {
        if (fund < 3.0) return;

        int adjustedLev = SessionKiller.adjustLeverage(HUNT_LEVERAGE);
        double margin = fund * 0.35; // 用35%
        double notional = margin * adjustedLev;
        double qty = notional / price;
        double fee = notional * TAKER_FEE;

        positionSide = side;
        entryPrice = price;
        positionSize = qty;
        positionMargin = margin;
        peakROE = 0;
        fund -= (margin + fee); // 冻结保证金 + 扣手续费
        huntsTriggered++;
        lastHuntTime = System.currentTimeMillis();

        System.out.println("[HUNT OPEN] " + side + " margin=" + fmt(margin) + "U lev=" + adjustedLev
                + "x size=" + fmt(qty) + " SOL | " + reason);
    }

    /**
     * 持仓管理
     */
    private void managePosition(double price) {
        double roe = getROE(price);
        if (roe > peakROE) peakROE = roe;

        // 止损 -8%
        if (roe <= -8.0) {
            closeHuntPosition(price, "HUNT stop loss ROE=" + fmtPct2(roe) + "%");
            return;
        }

        // 止盈 +20%
        if (roe >= 20.0) {
            closeHuntPosition(price, "HUNT take profit ROE=" + fmtPct2(roe) + "%");
            return;
        }

        // 移动止盈：峰值>=10%后回撤40%
        if (peakROE >= 10.0 && roe <= peakROE * 0.6) {
            closeHuntPosition(price, "HUNT trailing peak=" + fmtPct2(peakROE) + "% now=" + fmtPct2(roe) + "%");
            return;
        }

        // 持仓超过30分钟 + ROE > 0 → 获利退出（清算级联不会持续太久）
        if (System.currentTimeMillis() - lastHuntTime > 1800000 && roe > 0) {
            closeHuntPosition(price, "HUNT timeout 30min ROE=" + fmtPct2(roe) + "%");
            return;
        }

        // 持仓超过1小时 → 无条件退出
        if (System.currentTimeMillis() - lastHuntTime > 3600000) {
            closeHuntPosition(price, "HUNT force exit 1hr ROE=" + fmtPct2(roe) + "%");
        }
    }

    private double getROE(double price) {
        if (positionMargin == 0) return 0;
        double pnl;
        if (positionSide.equals("LONG")) pnl = (price - entryPrice) * positionSize;
        else pnl = (entryPrice - price) * positionSize;
        return (pnl / positionMargin) * 100.0;
    }

    /**
     * 平仓
     */
    private void closeHuntPosition(double price, String reason) {
        double pnl;
        if (positionSide.equals("LONG")) pnl = (price - entryPrice) * positionSize;
        else pnl = (entryPrice - price) * positionSize;

        double fee = positionSize * price * TAKER_FEE;
        double netPnl = pnl - fee;

        fund += positionMargin + netPnl;
        totalProfit += netPnl;
        totalTrades++;
        if (netPnl > 0) winTrades++;

        // 利润70%回流（高风险策略要锁利润）
        if (netPnl > 0) {
            double toVault = netPnl * 0.70;
            fund -= toVault;
            mainAccount.recycleProfit(toVault);
        }

        String tag = netPnl >= 0 ? "+" : "";
        System.out.println("[HUNT CLOSE] " + positionSide + " " + reason
                + " | pnl=" + tag + fmt(netPnl) + "U | fund=" + fmt(fund) + "U");

        positionSide = "NONE";
        entryPrice = 0;
        positionSize = 0;
        positionMargin = 0;
        peakROE = 0;
    }

    /**
     * HTTP GET请求
     */
    private String httpGet(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code != 200) return null;

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) sb.append(line);
            in.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 状态输出 =====
    public synchronized void printStatus() {
        double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0;
        System.out.println("[HUNT STATUS] fund=" + fmt(fund) + "U | profit=" + (totalProfit >= 0 ? "+" : "") + fmt(totalProfit)
                + "U | trades=" + totalTrades + " | winRate=" + fmtPct2(winRate) + "%"
                + " | hunts=" + huntsTriggered
                + " | FR=" + fmtPct2(lastFundingRate * 100) + "% | LS=" + String.format("%.2f", lastLongShortRatio)
                + " | pos=" + positionSide);
    }

    public synchronized double getTotalProfit() { return totalProfit; }
    public synchronized double getFund() { return fund; }
    public synchronized double getLastFundingRate() { return lastFundingRate; }
    public synchronized double getLastLongShortRatio() { return lastLongShortRatio; }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
    private String fmtPct2(double v) { return String.format(Locale.US, "%.2f", v); }
}
