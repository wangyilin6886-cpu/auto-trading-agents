package com.trading.apex;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * CoinSelector - 动态多币种选择器
 *
 * 每小时扫描币安合约市场，选出最适合超高频交易的Top N币种
 *
 * 评分维度:
 *   1. 波动率(24h) × 0.35    - 越波动越赚钱
 *   2. 成交量(24h) × 0.30    - 越大滑点越小
 *   3. 资金费率绝对值 × 0.15  - 极端费率=套利机会
 *   4. 价差(估算) × 0.20     - 越小执行成本越低
 */
public class CoinSelector {

    private static final String TICKER_URL = "https://fapi.binance.com/fapi/v1/ticker/24hr";
    private static final String FUNDING_URL = "https://fapi.binance.com/fapi/v1/premiumIndex";
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10)).build();

    // 候选池: 流动性好的主流合约币种
    private static final Set<String> CANDIDATE_POOL = Set.of(
        "BTCUSDT", "ETHUSDT", "SOLUSDT", "DOGEUSDT", "WIFUSDT",
        "PEPEUSDT", "SUIUSDT", "AVAXUSDT", "LINKUSDT", "ADAUSDT",
        "XRPUSDT", "ARBUSDT", "OPUSDT", "DOTUSDT", "MATICUSDT",
        "NEARUSDT", "APTUSDT", "SEIUSDT", "TIAUSDT", "JUPUSDT",
        "WLDUSDT", "BONKUSDT", "ENAUSDT", "FETUSDT", "RENDERUSDT"
    );

    private List<CoinScore> lastSelection = new ArrayList<>();
    private long lastSelectionTime = 0;
    private static final long SELECTION_INTERVAL_MS = 3600000; // 1小时

    public static class CoinScore implements Comparable<CoinScore> {
        public final String symbol;
        public final double totalScore;
        public final double volatility;
        public final double volume24h;
        public final double fundingRate;
        public final double priceChangePct;

        public CoinScore(String symbol, double totalScore, double volatility,
                         double volume24h, double fundingRate, double priceChangePct) {
            this.symbol = symbol;
            this.totalScore = totalScore;
            this.volatility = volatility;
            this.volume24h = volume24h;
            this.fundingRate = fundingRate;
            this.priceChangePct = priceChangePct;
        }

        @Override
        public int compareTo(CoinScore o) {
            return Double.compare(o.totalScore, this.totalScore); // 降序
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "%s score=%.2f vol=%.2f%% volume=%.0fM funding=%.4f%%",
                symbol, totalScore, volatility, volume24h / 1_000_000, fundingRate * 100);
        }
    }

    /**
     * 获取当前推荐的交易币种(Top N)
     */
    public List<CoinScore> getTopCoins(int n) {
        long now = System.currentTimeMillis();
        if (now - lastSelectionTime < SELECTION_INTERVAL_MS && !lastSelection.isEmpty()) {
            return lastSelection.subList(0, Math.min(n, lastSelection.size()));
        }
        refresh();
        return lastSelection.subList(0, Math.min(n, lastSelection.size()));
    }

    /**
     * 刷新评分
     */
    public void refresh() {
        try {
            Map<String, double[]> tickerData = fetchTickerData();
            Map<String, Double> fundingData = fetchFundingData();

            List<CoinScore> scores = new ArrayList<>();

            // 归一化需要的最大值
            double maxVol = 0, maxVolume = 0, maxFunding = 0;
            for (String sym : CANDIDATE_POOL) {
                if (!tickerData.containsKey(sym)) continue;
                double[] td = tickerData.get(sym);
                maxVol = Math.max(maxVol, td[0]);
                maxVolume = Math.max(maxVolume, td[1]);
                double fr = Math.abs(fundingData.getOrDefault(sym, 0.0));
                maxFunding = Math.max(maxFunding, fr);
            }

            for (String sym : CANDIDATE_POOL) {
                if (!tickerData.containsKey(sym)) continue;
                double[] td = tickerData.get(sym);
                double volatility = td[0]; // 24h price change abs %
                double volume = td[1];     // 24h quote volume
                double changePct = td[2];  // 24h price change %
                double funding = fundingData.getOrDefault(sym, 0.0);

                // 归一化评分 (0-1)
                double volScore = maxVol > 0 ? volatility / maxVol : 0;
                double volumeScore = maxVolume > 0 ? volume / maxVolume : 0;
                double fundingScore = maxFunding > 0 ? Math.abs(funding) / maxFunding : 0;

                // 最低成交量门槛(日成交<5000万U的排除)
                if (volume < 50_000_000) continue;

                double total = volScore * 0.35 + volumeScore * 0.30 + fundingScore * 0.15;

                // 价差评分: 成交量越大价差通常越小
                double spreadScore = Math.min(1.0, volume / 500_000_000); // 5亿U以上满分
                total += spreadScore * 0.20;

                scores.add(new CoinScore(sym, total, volatility, volume, funding, changePct));
            }

            Collections.sort(scores);
            lastSelection = scores;
            lastSelectionTime = System.currentTimeMillis();

            System.out.println("\n[COIN SELECTOR] Refreshed at " + new Date());
            for (int i = 0; i < Math.min(5, scores.size()); i++) {
                System.out.println("  #" + (i + 1) + " " + scores.get(i));
            }

        } catch (Exception e) {
            System.out.println("[COIN SELECTOR] Refresh failed: " + e.getMessage());
        }
    }

    private Map<String, double[]> fetchTickerData() throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(TICKER_URL))
            .timeout(Duration.ofSeconds(10)).build();
        String body = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();
        JSONArray arr = new JSONArray(body);

        Map<String, double[]> result = new HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String sym = obj.getString("symbol");
            if (!CANDIDATE_POOL.contains(sym)) continue;
            double changePct = Math.abs(Double.parseDouble(obj.getString("priceChangePercent")));
            double volume = Double.parseDouble(obj.getString("quoteVolume"));
            double rawChangePct = Double.parseDouble(obj.getString("priceChangePercent"));
            result.put(sym, new double[]{changePct, volume, rawChangePct});
        }
        return result;
    }

    private Map<String, Double> fetchFundingData() throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(FUNDING_URL))
            .timeout(Duration.ofSeconds(10)).build();
        String body = httpClient.send(req, HttpResponse.BodyHandlers.ofString()).body();
        JSONArray arr = new JSONArray(body);

        Map<String, Double> result = new HashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String sym = obj.getString("symbol");
            if (!CANDIDATE_POOL.contains(sym)) continue;
            double rate = Double.parseDouble(obj.getString("lastFundingRate"));
            result.put(sym, rate);
        }
        return result;
    }
}
