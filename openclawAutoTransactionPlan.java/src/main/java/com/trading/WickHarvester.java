package com.trading;

import java.util.Locale;

/**
 * 插针回收模式 (Wick Harvester)
 *
 * SOL最显著特征：频繁插针（3秒内闪崩1.5%+然后立刻弹回）
 * 本质：大户扫止损单后买回，我们跟着大户吃回弹
 *
 * 检测逻辑：
 *   1. 3秒内价格下跌 > 1.5% → 向下插针候选
 *   2. 等待5秒内回弹 > 针身50% → 确认是插针不是真崩
 *   3. 确认后做多，止盈=插针前价格，止损=针尖下方0.3%
 *
 * 一天约5-10次机会，胜率>80%
 */
public class WickHarvester {

    // ===== 检测参数 =====
    private static final double WICK_THRESHOLD = 0.015;     // 1.5% 闪崩阈值
    private static final long WICK_DETECT_WINDOW_MS = 3000; // 3秒检测窗口
    private static final long WICK_CONFIRM_WINDOW_MS = 5000;// 5秒确认回弹
    private static final double BOUNCE_CONFIRM_RATIO = 0.50;// 回弹超过针身50%确认
    private static final int WICK_LEVERAGE = 15;
    private static final double WICK_STOP_LOSS_PCT = 0.003; // 止损：针尖下方0.3%

    // ===== 高频价格缓冲 =====
    private static final int PRICE_BUFFER_SIZE = 300; // ~5分钟 @1msg/sec
    private double[] priceBuffer = new double[PRICE_BUFFER_SIZE];
    private long[] timeBuffer = new long[PRICE_BUFFER_SIZE];
    private int bufferIndex = 0;
    private boolean bufferFull = false;

    // ===== 插针状态机 =====
    private enum WickState {
        SCANNING,       // 扫描中
        WICK_DETECTED,  // 检测到插针，等待回弹确认
        POSITION_OPEN   // 已入场
    }

    private WickState state = WickState.SCANNING;

    // 插针检测数据
    private double wickPrePrice = 0;     // 插针前价格
    private double wickBottomPrice = 0;  // 针尖价格
    private long wickDetectedTime = 0;   // 检测到插针的时间
    private String wickDirection = "";   // "DOWN" 或 "UP"

    // ===== 仓位管理 =====
    private double fund;
    private String positionSide = "NONE";
    private double entryPrice = 0;
    private double positionSize = 0;
    private double positionMargin = 0;
    private double stopLossPrice = 0;
    private double takeProfitPrice = 0;

    // ===== 统计 =====
    private double totalProfit = 0;
    private int totalTrades = 0;
    private int winTrades = 0;
    private int wicksDetected = 0;

    // ===== 冷却 =====
    private long lastTradeTime = 0;
    private static final long TRADE_COOLDOWN_MS = 10000; // 交易后冷却10秒

    private static final double TAKER_FEE = 0.0004;
    private final TradingAccount mainAccount;

    public WickHarvester(double fund, TradingAccount account) {
        this.fund = fund;
        this.mainAccount = account;
        System.out.println("[WICK HARVESTER] Initialized | fund=" + fmt(fund) + "U | threshold=" + (WICK_THRESHOLD * 100) + "%");
    }

    /**
     * 每条WebSocket消息调用（毫秒级）
     */
    public synchronized void onTick(double price, long timestamp) {
        // 记录价格
        priceBuffer[bufferIndex] = price;
        timeBuffer[bufferIndex] = timestamp;
        bufferIndex = (bufferIndex + 1) % PRICE_BUFFER_SIZE;
        if (bufferIndex == 0) bufferFull = true;

        // 时区检查
        if (!SessionKiller.canOpenNewPosition() && state == WickState.SCANNING) {
            return;
        }

        switch (state) {
            case SCANNING:
                scanForWick(price, timestamp);
                break;
            case WICK_DETECTED:
                confirmWickBounce(price, timestamp);
                break;
            case POSITION_OPEN:
                managePosition(price);
                break;
        }
    }

    /**
     * 扫描插针：检测3秒内价格剧烈变动
     */
    private void scanForWick(double currentPrice, long now) {
        if (!bufferFull && bufferIndex < 10) return; // 需要一些历史数据

        // 冷却检查
        if (now - lastTradeTime < TRADE_COOLDOWN_MS) return;

        // 遍历最近3秒的价格
        double maxPrice = currentPrice;
        double minPrice = currentPrice;

        int count = bufferFull ? PRICE_BUFFER_SIZE : bufferIndex;
        for (int i = 0; i < count; i++) {
            int idx = (bufferIndex - 1 - i + PRICE_BUFFER_SIZE) % PRICE_BUFFER_SIZE;
            if (now - timeBuffer[idx] > WICK_DETECT_WINDOW_MS) break;
            maxPrice = Math.max(maxPrice, priceBuffer[idx]);
            minPrice = Math.min(minPrice, priceBuffer[idx]);
        }

        // 检测向下插针：最近3秒内从高点跌了>1.5%
        double dropPct = (maxPrice - currentPrice) / maxPrice;
        if (dropPct >= WICK_THRESHOLD) {
            wickPrePrice = maxPrice;
            wickBottomPrice = currentPrice;
            wickDetectedTime = now;
            wickDirection = "DOWN";
            state = WickState.WICK_DETECTED;
            wicksDetected++;
            System.out.println("[WICK DETECT] DOWN flash -" + fmtPct(dropPct)
                    + " | from " + fmt(maxPrice) + " to " + fmt(currentPrice));
            return;
        }

        // 检测向上插针：最近3秒内从低点涨了>1.5%
        double spikePct = (currentPrice - minPrice) / minPrice;
        if (spikePct >= WICK_THRESHOLD) {
            wickPrePrice = minPrice;
            wickBottomPrice = currentPrice;
            wickDetectedTime = now;
            wickDirection = "UP";
            state = WickState.WICK_DETECTED;
            wicksDetected++;
            System.out.println("[WICK DETECT] UP spike +" + fmtPct(spikePct)
                    + " | from " + fmt(minPrice) + " to " + fmt(currentPrice));
        }
    }

    /**
     * 确认回弹：5秒内价格回弹超过针身50%
     */
    private void confirmWickBounce(double price, long now) {
        // 超时未确认，取消
        if (now - wickDetectedTime > WICK_CONFIRM_WINDOW_MS) {
            System.out.println("[WICK CANCEL] No bounce confirmation within 5s");
            state = WickState.SCANNING;
            return;
        }

        if (wickDirection.equals("DOWN")) {
            // 向下插针：更新针尖（可能还在往下走）
            if (price < wickBottomPrice) {
                wickBottomPrice = price;
                return;
            }

            // 检查回弹
            double wickBody = wickPrePrice - wickBottomPrice;
            double bounceAmount = price - wickBottomPrice;
            if (wickBody > 0 && bounceAmount / wickBody >= BOUNCE_CONFIRM_RATIO) {
                // 确认！做多吃回弹
                System.out.println("[WICK CONFIRMED] DOWN wick bounced " + fmtPct(bounceAmount / wickBottomPrice)
                        + " (>" + (int)(BOUNCE_CONFIRM_RATIO * 100) + "% of body)");
                openWickPosition("LONG", price, wickBottomPrice, wickPrePrice);
            }
        } else {
            // 向上插针：更新针顶
            if (price > wickBottomPrice) {
                wickBottomPrice = price;
                return;
            }

            // 检查回落
            double wickBody = wickBottomPrice - wickPrePrice;
            double dropAmount = wickBottomPrice - price;
            if (wickBody > 0 && dropAmount / wickBody >= BOUNCE_CONFIRM_RATIO) {
                // 确认！做空吃回落
                System.out.println("[WICK CONFIRMED] UP wick dropped " + fmtPct(dropAmount / wickBottomPrice)
                        + " (>" + (int)(BOUNCE_CONFIRM_RATIO * 100) + "% of body)");
                openWickPosition("SHORT", price, wickBottomPrice, wickPrePrice);
            }
        }
    }

    /**
     * 开仓
     */
    private void openWickPosition(String side, double price, double wickTip, double wickBase) {
        if (fund < 3.0) {
            state = WickState.SCANNING;
            return;
        }

        int adjustedLev = SessionKiller.adjustLeverage(WICK_LEVERAGE);
        double margin = fund * 0.30; // 每次用30%插针资金
        double notional = margin * adjustedLev;
        double qty = notional / price;
        double fee = notional * TAKER_FEE;

        if (side.equals("LONG")) {
            stopLossPrice = wickTip * (1 - WICK_STOP_LOSS_PCT);  // 针尖下方0.3%
            takeProfitPrice = wickBase * 0.998;                   // 插针前价格×0.998（留0.2%安全边际）
        } else {
            stopLossPrice = wickTip * (1 + WICK_STOP_LOSS_PCT);
            takeProfitPrice = wickBase * 1.002;
        }

        positionSide = side;
        entryPrice = price;
        positionSize = qty;
        positionMargin = margin;
        fund -= (margin + fee); // 冻结保证金 + 扣手续费

        state = WickState.POSITION_OPEN;
        lastTradeTime = System.currentTimeMillis();

        System.out.println("[WICK OPEN] " + side + " margin=" + fmt(margin) + "U lev=" + adjustedLev
                + "x size=" + fmt(qty) + " SOL"
                + " | SL=" + fmt(stopLossPrice) + " TP=" + fmt(takeProfitPrice));
    }

    /**
     * 持仓管理
     */
    private void managePosition(double price) {
        if (positionSide.equals("NONE")) {
            state = WickState.SCANNING;
            return;
        }

        // 止损
        if (positionSide.equals("LONG") && price <= stopLossPrice) {
            closeWickPosition(price, "WICK stop loss");
            return;
        }
        if (positionSide.equals("SHORT") && price >= stopLossPrice) {
            closeWickPosition(price, "WICK stop loss");
            return;
        }

        // 止盈
        if (positionSide.equals("LONG") && price >= takeProfitPrice) {
            closeWickPosition(price, "WICK take profit");
            return;
        }
        if (positionSide.equals("SHORT") && price <= takeProfitPrice) {
            closeWickPosition(price, "WICK take profit");
            return;
        }

        // 超时保护：持仓超过60秒强制平仓（插针回收是超短线）
        if (System.currentTimeMillis() - lastTradeTime > 60000) {
            closeWickPosition(price, "WICK timeout 60s");
        }
    }

    /**
     * 平仓
     */
    private void closeWickPosition(double price, String reason) {
        double pnl;
        if (positionSide.equals("LONG")) {
            pnl = (price - entryPrice) * positionSize;
        } else {
            pnl = (entryPrice - price) * positionSize;
        }
        double fee = positionSize * price * TAKER_FEE;
        double netPnl = pnl - fee;

        fund += positionMargin + netPnl;
        totalProfit += netPnl;
        totalTrades++;
        if (netPnl > 0) winTrades++;

        // 利润60%回流主账户（插针是高确定性策略，多锁利润）
        if (netPnl > 0) {
            double toVault = netPnl * 0.60;
            fund -= toVault;
            mainAccount.recycleProfit(toVault);
        }

        String tag = netPnl >= 0 ? "+" : "";
        System.out.println("[WICK CLOSE] " + positionSide + " " + reason
                + " | pnl=" + tag + fmt(netPnl) + "U | fund=" + fmt(fund) + "U"
                + " | total: " + totalTrades + " trades, " + winTrades + " wins");

        positionSide = "NONE";
        entryPrice = 0;
        positionSize = 0;
        positionMargin = 0;
        state = WickState.SCANNING;
        lastTradeTime = System.currentTimeMillis();
    }

    // ===== 状态输出 =====
    public synchronized void printStatus() {
        double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0;
        System.out.println("[WICK STATUS] fund=" + fmt(fund) + "U | profit=" + (totalProfit >= 0 ? "+" : "") + fmt(totalProfit)
                + "U | trades=" + totalTrades + " | winRate=" + fmtPct2(winRate)
                + " | wicks_detected=" + wicksDetected + " | state=" + state);
    }

    public synchronized double getTotalProfit() { return totalProfit; }
    public synchronized double getFund() { return fund; }
    public synchronized boolean hasPosition() { return !positionSide.equals("NONE"); }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
    private String fmtPct(double v) { return String.format(Locale.US, "%.2f%%", v * 100); }
    private String fmtPct2(double v) { return String.format(Locale.US, "%.1f%%", v); }
}
