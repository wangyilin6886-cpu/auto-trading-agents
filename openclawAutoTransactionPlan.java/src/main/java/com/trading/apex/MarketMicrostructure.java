package com.trading.apex;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MarketMicrostructure - 统一实时市场状态对象
 *
 * 所有数据流汇入此对象，每100ms更新一次，是所有策略感知器的唯一输入。
 * 全线程安全，lock-free读取。
 */
public class MarketMicrostructure {

    // === 价格状态 ===
    public volatile double price = 0;
    public volatile double bestBid = 0;
    public volatile double bestAsk = 0;
    public volatile double spreadBps = 0;        // 买卖价差(基点)

    // === 订单簿深度 (depth@100ms) ===
    public volatile double bidTotal5 = 0;         // 买方前5档总量(USDT)
    public volatile double askTotal5 = 0;         // 卖方前5档总量(USDT)
    public volatile double bidTotal10 = 0;        // 买方前10档总量
    public volatile double askTotal10 = 0;        // 卖方前10档总量
    public volatile double depthImbalance = 0;    // (bid-ask)/(bid+ask) [-1,+1]
    public volatile double bidWallPrice = 0;      // 买单墙价格(若>均值5倍)
    public volatile double askWallPrice = 0;      // 卖单墙价格
    public volatile double bidWallSize = 0;       // 买单墙大小
    public volatile double askWallSize = 0;       // 卖单墙大小

    // === 逐笔成交流 (aggTrade) - 滚动窗口统计 ===
    public volatile double takerBuyVol1s = 0;     // 1秒主动买量(USDT)
    public volatile double takerSellVol1s = 0;    // 1秒主动卖量(USDT)
    public volatile double takerDelta1s = 0;      // 1秒净买量
    public volatile double takerDelta5s = 0;      // 5秒净买量
    public volatile double takerDelta30s = 0;     // 30秒净买量
    public volatile int largeBuyCount10s = 0;     // 10秒大单买次数(>1000U)
    public volatile int largeSellCount10s = 0;    // 10秒大单卖次数
    public volatile double avgTradeSize = 0;      // 近100笔平均成交额
    public volatile int tradeCount1s = 0;         // 每秒成交笔数

    // === 爆仓数据流 (forceOrder) ===
    public volatile double liqBuyVol60s = 0;      // 60s多头爆仓总额(USDT)
    public volatile double liqSellVol60s = 0;     // 60s空头爆仓总额(USDT)
    public volatile double liqRate15s = 0;        // 15秒爆仓速率
    public volatile double liqRatePrev15s = 0;    // 前15秒速率(判断加速)
    public volatile boolean liqCascadeActive = false; // 连锁爆仓中
    public volatile String liqDirection = "NONE"; // 爆仓方向 LONG/SHORT/NONE

    // === 标记价格+资金费率 (markPrice@1s) ===
    public volatile double markPrice = 0;
    public volatile double fundingRate = 0;       // 当期费率
    public volatile long nextFundingTime = 0;     // 下次结算时间

    // === 1秒K线衍生指标 ===
    public volatile double volatility10s = 0;     // 10秒波动率
    public volatile double volatility1m = 0;      // 1分钟波动率
    public volatile double volatility5m = 0;      // 5分钟波动率
    public volatile double momentum5s = 0;        // 5秒动量
    public volatile double momentum30s = 0;       // 30秒动量
    public volatile double rsi14s = 50;           // 14秒RSI(超高频)
    public volatile double vwap1m = 0;            // 1分钟VWAP
    public volatile double priceVsVwap = 0;       // 价格偏离VWAP%

    // === 24h滚动统计 (ticker) ===
    public volatile double volume24h = 0;
    public volatile double priceChange24hPct = 0;
    public volatile double high24h = 0;
    public volatile double low24h = 0;

    // === 系统元数据 ===
    public volatile long lastUpdateTime = 0;
    public volatile String symbol = "SOLUSDT";
    private final AtomicLong updateCount = new AtomicLong(0);

    // === 秒级价格历史(用于波动率/RSI计算) ===
    private final double[] priceHistory = new double[300];  // 5分钟
    private final double[] volumeHistory = new double[300];
    private final long[] timeHistory = new long[300];
    private int histIdx = 0;
    private boolean histFull = false;

    // === 逐笔成交缓冲(用于大单统计) ===
    private final double[] tradeAmounts = new double[2000]; // 最近2000笔
    private final boolean[] tradeBuySide = new boolean[2000];
    private final long[] tradeTimes = new long[2000];
    private int tradeIdx = 0;
    private boolean tradeFull = false;

    // === 爆仓事件缓冲 ===
    private final double[] liqAmounts = new double[500];
    private final boolean[] liqIsLong = new boolean[500]; // true=多头被爆
    private final long[] liqTimes = new long[500];
    private int liqIdx = 0;
    private boolean liqFull = false;

    // === VWAP累计 ===
    private double vwapPriceVolumeSum = 0;
    private double vwapVolumeSum = 0;
    private long vwapResetTime = 0;

    public MarketMicrostructure() {
        this.vwapResetTime = System.currentTimeMillis();
    }

    /**
     * 记录一笔逐笔成交 (来自 aggTrade stream)
     */
    public synchronized void recordTrade(double tradePrice, double qty, boolean isBuyerMaker, long timestamp) {
        this.price = tradePrice;
        this.lastUpdateTime = timestamp;
        double amount = tradePrice * qty;
        boolean isTakerBuy = !isBuyerMaker; // aggTrade: isBuyerMaker=true means taker is seller

        tradeAmounts[tradeIdx] = amount;
        tradeBuySide[tradeIdx] = isTakerBuy;
        tradeTimes[tradeIdx] = timestamp;
        tradeIdx = (tradeIdx + 1) % tradeAmounts.length;
        if (tradeIdx == 0) tradeFull = true;

        // VWAP累计(每分钟重置)
        if (timestamp - vwapResetTime > 60000) {
            vwapPriceVolumeSum = 0;
            vwapVolumeSum = 0;
            vwapResetTime = timestamp;
        }
        vwapPriceVolumeSum += tradePrice * qty;
        vwapVolumeSum += qty;
        if (vwapVolumeSum > 0) {
            vwap1m = vwapPriceVolumeSum / vwapVolumeSum;
            priceVsVwap = (tradePrice - vwap1m) / vwap1m * 100.0;
        }

        updateCount.incrementAndGet();
    }

    /**
     * 记录1秒K线数据 (来自 kline_1s stream)
     */
    public synchronized void recordKline1s(double close, double volume, long timestamp) {
        priceHistory[histIdx] = close;
        volumeHistory[histIdx] = volume;
        timeHistory[histIdx] = timestamp;
        histIdx = (histIdx + 1) % priceHistory.length;
        if (histIdx == 0) histFull = true;

        recalcIndicators(timestamp);
    }

    /**
     * 更新订单簿深度 (来自 depth@100ms stream)
     */
    public synchronized void updateDepth(double[][] bids, double[][] asks) {
        double bidSum5 = 0, askSum5 = 0;
        double bidSum10 = 0, askSum10 = 0;
        double maxBidSize = 0, maxAskSize = 0;
        double maxBidPrice = 0, maxAskPrice = 0;
        double avgBidSize = 0, avgAskSize = 0;

        int bidLen = Math.min(bids.length, 20);
        int askLen = Math.min(asks.length, 20);

        for (int i = 0; i < bidLen; i++) {
            double pxVol = bids[i][0] * bids[i][1]; // price × qty = USDT value
            if (i < 5) bidSum5 += pxVol;
            if (i < 10) bidSum10 += pxVol;
            avgBidSize += pxVol;
            if (pxVol > maxBidSize) {
                maxBidSize = pxVol;
                maxBidPrice = bids[i][0];
            }
        }

        for (int i = 0; i < askLen; i++) {
            double pxVol = asks[i][0] * asks[i][1];
            if (i < 5) askSum5 += pxVol;
            if (i < 10) askSum10 += pxVol;
            avgAskSize += pxVol;
            if (pxVol > maxAskSize) {
                maxAskSize = pxVol;
                maxAskPrice = asks[i][0];
            }
        }

        this.bidTotal5 = bidSum5;
        this.askTotal5 = askSum5;
        this.bidTotal10 = bidSum10;
        this.askTotal10 = askSum10;

        double total = bidSum5 + askSum5;
        this.depthImbalance = total > 0 ? (bidSum5 - askSum5) / total : 0;

        if (bids.length > 0) this.bestBid = bids[0][0];
        if (asks.length > 0) this.bestAsk = asks[0][0];
        if (bestBid > 0) this.spreadBps = (bestAsk - bestBid) / bestBid * 10000;

        // 检测挂单墙(>平均5倍)
        double avgBid = bidLen > 0 ? avgBidSize / bidLen : 0;
        double avgAsk = askLen > 0 ? avgAskSize / askLen : 0;
        this.bidWallPrice = maxBidSize > avgBid * 5 ? maxBidPrice : 0;
        this.askWallPrice = maxAskSize > avgAsk * 5 ? maxAskPrice : 0;
        this.bidWallSize = maxBidSize > avgBid * 5 ? maxBidSize : 0;
        this.askWallSize = maxAskSize > avgAsk * 5 ? maxAskSize : 0;
    }

    /**
     * 记录爆仓事件 (来自 forceOrder stream)
     */
    public synchronized void recordLiquidation(String side, double qty, double price, long timestamp) {
        double amount = qty * price;
        boolean isLong = side.equalsIgnoreCase("SELL"); // SELL = long被强平

        liqAmounts[liqIdx] = amount;
        liqIsLong[liqIdx] = isLong;
        liqTimes[liqIdx] = timestamp;
        liqIdx = (liqIdx + 1) % liqAmounts.length;
        if (liqIdx == 0) liqFull = true;

        recalcLiquidation(timestamp);
    }

    /**
     * 更新标记价格+资金费率 (来自 markPrice stream)
     */
    public void updateMarkPrice(double mark, double funding, long nextFunding) {
        this.markPrice = mark;
        this.fundingRate = funding;
        this.nextFundingTime = nextFunding;
    }

    /**
     * 更新24h滚动统计 (来自 ticker stream)
     */
    public void updateTicker(double vol24h, double changePct, double high, double low) {
        this.volume24h = vol24h;
        this.priceChange24hPct = changePct;
        this.high24h = high;
        this.low24h = low;
    }

    /**
     * 每100ms调用一次：重算逐笔成交衍生指标
     */
    public synchronized void recalcTradeFlow(long now) {
        double buyVol1s = 0, sellVol1s = 0;
        double buyVol5s = 0, sellVol5s = 0;
        double buyVol30s = 0, sellVol30s = 0;
        int largeBuy10s = 0, largeSell10s = 0;
        int count1s = 0;
        double totalAmount = 0;
        int totalCount = 0;

        int len = tradeFull ? tradeAmounts.length : tradeIdx;
        for (int i = 0; i < len; i++) {
            long age = now - tradeTimes[i];
            if (age > 30000) continue;
            double amt = tradeAmounts[i];
            boolean buy = tradeBuySide[i];

            if (age <= 30000) {
                if (buy) buyVol30s += amt; else sellVol30s += amt;
            }
            if (age <= 5000) {
                if (buy) buyVol5s += amt; else sellVol5s += amt;
            }
            if (age <= 1000) {
                if (buy) buyVol1s += amt; else sellVol1s += amt;
                count1s++;
            }
            if (age <= 10000 && amt >= 1000) {
                if (buy) largeBuy10s++; else largeSell10s++;
            }
            totalAmount += amt;
            totalCount++;
        }

        this.takerBuyVol1s = buyVol1s;
        this.takerSellVol1s = sellVol1s;
        this.takerDelta1s = buyVol1s - sellVol1s;
        this.takerDelta5s = buyVol5s - sellVol5s;
        this.takerDelta30s = buyVol30s - sellVol30s;
        this.largeBuyCount10s = largeBuy10s;
        this.largeSellCount10s = largeSell10s;
        this.tradeCount1s = count1s;
        this.avgTradeSize = totalCount > 0 ? totalAmount / totalCount : 0;
    }

    /**
     * 重算爆仓相关指标
     */
    private void recalcLiquidation(long now) {
        double liqBuy60 = 0, liqSell60 = 0;
        double rate15 = 0, ratePrev15 = 0;

        int len = liqFull ? liqAmounts.length : liqIdx;
        for (int i = 0; i < len; i++) {
            long age = now - liqTimes[i];
            if (age > 60000) continue;
            double amt = liqAmounts[i];
            if (liqIsLong[i]) liqBuy60 += amt; else liqSell60 += amt;
            if (age <= 15000) rate15 += amt;
            else if (age <= 30000) ratePrev15 += amt;
        }

        this.liqBuyVol60s = liqBuy60;
        this.liqSellVol60s = liqSell60;
        this.liqRate15s = rate15;
        this.liqRatePrev15s = ratePrev15;

        double totalLiq60 = liqBuy60 + liqSell60;
        this.liqCascadeActive = totalLiq60 > 200000 && rate15 > ratePrev15 * 1.3;
        if (liqCascadeActive) {
            this.liqDirection = liqBuy60 > liqSell60 ? "LONG" : "SHORT";
        } else if (totalLiq60 < 50000) {
            this.liqDirection = "NONE";
        }
    }

    /**
     * 重算秒级技术指标(波动率/动量/RSI)
     */
    private void recalcIndicators(long now) {
        int len = histFull ? priceHistory.length : histIdx;
        if (len < 15) return;

        // 波动率: 标准差/均值
        double sum10 = 0, sum60 = 0, sum300 = 0;
        int cnt10 = 0, cnt60 = 0, cnt300 = 0;
        double[] returns10 = new double[10];
        double[] returns60 = new double[60];

        for (int i = 1; i < len; i++) {
            int cur = (histIdx - 1 - i + priceHistory.length * 2) % priceHistory.length;
            int prev = (cur - 1 + priceHistory.length) % priceHistory.length;
            if (priceHistory[prev] == 0) continue;
            double ret = (priceHistory[cur] - priceHistory[prev]) / priceHistory[prev];

            if (cnt10 < 10) { returns10[cnt10] = ret; cnt10++; sum10 += ret; }
            if (cnt60 < 60) { returns60[cnt60] = ret; cnt60++; sum60 += ret; }
            if (cnt300 < 300) { cnt300++; sum300 += ret; }
        }

        if (cnt10 >= 5) {
            double mean = sum10 / cnt10;
            double var = 0;
            for (int i = 0; i < cnt10; i++) var += (returns10[i] - mean) * (returns10[i] - mean);
            this.volatility10s = Math.sqrt(var / cnt10) * 100; // %
        }
        if (cnt60 >= 10) {
            double mean = sum60 / cnt60;
            double var = 0;
            for (int i = 0; i < cnt60; i++) var += (returns60[i] - mean) * (returns60[i] - mean);
            this.volatility1m = Math.sqrt(var / cnt60) * 100;
        }

        // 动量: 简单价格变化率
        if (len >= 5) {
            int now5 = (histIdx - 1 + priceHistory.length) % priceHistory.length;
            int ago5 = (histIdx - 5 + priceHistory.length) % priceHistory.length;
            if (priceHistory[ago5] > 0) {
                this.momentum5s = (priceHistory[now5] - priceHistory[ago5]) / priceHistory[ago5] * 100;
            }
        }
        if (len >= 30) {
            int now30 = (histIdx - 1 + priceHistory.length) % priceHistory.length;
            int ago30 = (histIdx - 30 + priceHistory.length) % priceHistory.length;
            if (priceHistory[ago30] > 0) {
                this.momentum30s = (priceHistory[now30] - priceHistory[ago30]) / priceHistory[ago30] * 100;
            }
        }

        // RSI-14s: 基于秒级收盘价
        if (len >= 15) {
            double gainSum = 0, lossSum = 0;
            for (int i = 1; i <= 14 && i < len; i++) {
                int cur = (histIdx - i + priceHistory.length) % priceHistory.length;
                int prev = (cur - 1 + priceHistory.length) % priceHistory.length;
                double change = priceHistory[cur] - priceHistory[prev];
                if (change > 0) gainSum += change;
                else lossSum -= change;
            }
            double avgGain = gainSum / 14;
            double avgLoss = lossSum / 14;
            if (avgLoss == 0) this.rsi14s = 100;
            else {
                double rs = avgGain / avgLoss;
                this.rsi14s = 100 - (100 / (1 + rs));
            }
        }
    }

    public long getUpdateCount() { return updateCount.get(); }

    public String snapshot() {
        return String.format(Locale.US,
            "[MKT] %s px=%.4f bid=%.4f ask=%.4f spread=%.1fbps | " +
            "depth=%.1f%% | delta1s=%.0f 5s=%.0f 30s=%.0f | " +
            "largeBuy=%d largeSell=%d | liq60s=%.0f/%.0f %s | " +
            "vol10s=%.3f%% vol1m=%.3f%% | mom5s=%.3f%% | rsi=%.1f | vwap=%.4f (%.3f%%)",
            symbol, price, bestBid, bestAsk, spreadBps,
            depthImbalance * 100, takerDelta1s, takerDelta5s, takerDelta30s,
            largeBuyCount10s, largeSellCount10s,
            liqBuyVol60s, liqSellVol60s, liqCascadeActive ? "CASCADE!" : "",
            volatility10s, volatility1m, momentum5s, rsi14s, vwap1m, priceVsVwap);
    }
}
