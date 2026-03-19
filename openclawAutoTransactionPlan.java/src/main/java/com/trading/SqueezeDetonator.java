package com.trading;

import java.util.Locale;

/**
 * 波动率压缩爆破模式 (Squeeze Detonator)
 *
 * 原理：SOL横盘2-4小时后必爆发
 * 用布林带宽度(Bandwidth)量化压缩程度：
 *   bandwidth从5% → 3% → 1.5% → 0.8% (逐渐收窄)
 *   < 1.0% = 极度压缩 → 在上下轨设双向陷阱
 *   哪边先触发就跟哪边，止损在中轨（距离极短，亏不了多少）
 *
 * 优势：止损距离短 → 可以用高杠杆 → 爆破时利润暴力
 * SOL每天3-5次squeeze机会
 */
public class SqueezeDetonator {

    // ===== 参数 =====
    private static final int BB_PERIOD = 20;                // 布林带周期
    private static final double SQUEEZE_THRESHOLD = 1.0;    // 带宽<1% = 极度压缩
    private static final double SQUEEZE_READY_THRESHOLD = 1.5; // 带宽<1.5% = 准备阶段
    private static final int BASE_LEVERAGE = 20;            // 基础杠杆（止损距离短所以可以高）
    private static final double STOP_LOSS_MIDDLE = 1.0;     // 止损=中轨（即布林中轨）
    private static final double PROFIT_TARGET_MULTIPLIER = 3.0; // 止盈=止损距离×3

    // ===== 状态 =====
    private enum SqueezeState {
        SCANNING,           // 扫描中
        SQUEEZE_READY,      // 带宽收窄到<1.5%，准备中
        SQUEEZE_ARMED,      // 带宽<1.0%，双向陷阱已设
        POSITION_OPEN       // 已爆破入场
    }

    private SqueezeState state = SqueezeState.SCANNING;

    // ===== 陷阱 =====
    private double longTrapPrice = 0;    // 做多陷阱（上轨+0.1%）
    private double shortTrapPrice = 0;   // 做空陷阱（下轨-0.1%）
    private long trapSetTime = 0;
    private static final long TRAP_EXPIRE_MS = 300000; // 陷阱5分钟过期

    // ===== 带宽历史（用于检测收窄趋势）=====
    private double[] bandwidthHistory = new double[20];
    private int bwIndex = 0;
    private boolean bwFull = false;
    private int consecutiveNarrowing = 0; // 连续收窄计数

    // ===== 仓位 =====
    private double fund;
    private String positionSide = "NONE";
    private double entryPrice = 0;
    private double positionSize = 0;
    private double positionMargin = 0;
    private double stopLossPrice = 0;
    private double takeProfitPrice = 0;
    private double peakROE = 0;

    // ===== 统计 =====
    private double totalProfit = 0;
    private int totalTrades = 0;
    private int winTrades = 0;
    private int squeezeCount = 0;

    private static final double TAKER_FEE = 0.0004;
    private final TradingAccount mainAccount;

    public SqueezeDetonator(double fund, TradingAccount account) {
        this.fund = fund;
        this.mainAccount = account;
        System.out.println("[SQUEEZE] Initialized | fund=" + fmt(fund) + "U | threshold=" + SQUEEZE_THRESHOLD + "%");
    }

    /**
     * 每个tick调用（可以不需要毫秒级，但需要每tick检测）
     */
    public synchronized void onTick(double price) {
        if (!IndicatorCalculator.isReady()) return;

        // 时区检查
        if (!SessionKiller.canOpenNewPosition() && state != SqueezeState.POSITION_OPEN) {
            return;
        }

        double bandwidth = IndicatorCalculator.getBollingerBandwidth(BB_PERIOD);

        // 记录带宽历史
        recordBandwidth(bandwidth);

        switch (state) {
            case SCANNING:
                scanForSqueeze(bandwidth, price);
                break;
            case SQUEEZE_READY:
                checkSqueezeArm(bandwidth, price);
                break;
            case SQUEEZE_ARMED:
                checkTrapTrigger(price);
                break;
            case POSITION_OPEN:
                managePosition(price);
                break;
        }
    }

    private void recordBandwidth(double bw) {
        double prevBw = bandwidthHistory[(bwIndex - 1 + bandwidthHistory.length) % bandwidthHistory.length];
        bandwidthHistory[bwIndex] = bw;
        bwIndex = (bwIndex + 1) % bandwidthHistory.length;
        if (bwIndex == 0) bwFull = true;

        // 检测连续收窄
        if (bwFull && bw < prevBw) {
            consecutiveNarrowing++;
        } else {
            consecutiveNarrowing = 0;
        }
    }

    /**
     * 扫描：寻找带宽收窄信号
     */
    private void scanForSqueeze(double bandwidth, double price) {
        if (bandwidth < SQUEEZE_READY_THRESHOLD && consecutiveNarrowing >= 3) {
            state = SqueezeState.SQUEEZE_READY;
            System.out.println("[SQUEEZE] Ready phase | bandwidth=" + fmtPct(bandwidth)
                    + " narrowing=" + consecutiveNarrowing + " ticks");
        }
    }

    /**
     * 准备阶段：带宽继续收窄到阈值，设置双向陷阱
     */
    private void checkSqueezeArm(double bandwidth, double price) {
        // 带宽回升 → 取消
        if (bandwidth > SQUEEZE_READY_THRESHOLD * 1.2) {
            state = SqueezeState.SCANNING;
            consecutiveNarrowing = 0;
            return;
        }

        // 极度压缩 → 设陷阱
        if (bandwidth < SQUEEZE_THRESHOLD) {
            double upper = IndicatorCalculator.getBollingerUpper(BB_PERIOD);
            double lower = IndicatorCalculator.getBollingerLower(BB_PERIOD);

            longTrapPrice = upper * 1.001;   // 上轨+0.1%
            shortTrapPrice = lower * 0.999;  // 下轨-0.1%
            trapSetTime = System.currentTimeMillis();

            state = SqueezeState.SQUEEZE_ARMED;
            squeezeCount++;

            System.out.println("\n[SQUEEZE ARMED] #" + squeezeCount + " bandwidth=" + fmtPct(bandwidth));
            System.out.println("  LONG trap @ " + fmt(longTrapPrice) + " (above upper=" + fmt(upper) + ")");
            System.out.println("  SHORT trap @ " + fmt(shortTrapPrice) + " (below lower=" + fmt(lower) + ")");
            System.out.println("  Middle (SL ref) = " + fmt(IndicatorCalculator.getBollingerMiddle(BB_PERIOD)));
        }
    }

    /**
     * 检测陷阱触发
     */
    private void checkTrapTrigger(double price) {
        // 过期检查
        if (System.currentTimeMillis() - trapSetTime > TRAP_EXPIRE_MS) {
            System.out.println("[SQUEEZE] Trap expired after 5min");
            state = SqueezeState.SCANNING;
            return;
        }

        double middle = IndicatorCalculator.getBollingerMiddle(BB_PERIOD);

        // 上破 → 做多
        if (price >= longTrapPrice) {
            System.out.println("[SQUEEZE BREAK UP] price=" + fmt(price) + " >= trap=" + fmt(longTrapPrice));
            openSqueezePosition("LONG", price, middle);
            return;
        }

        // 下破 → 做空
        if (price <= shortTrapPrice) {
            System.out.println("[SQUEEZE BREAK DOWN] price=" + fmt(price) + " <= trap=" + fmt(shortTrapPrice));
            openSqueezePosition("SHORT", price, middle);
        }
    }

    /**
     * 开仓
     */
    private void openSqueezePosition(String side, double price, double middle) {
        if (fund < 3.0) {
            state = SqueezeState.SCANNING;
            return;
        }

        int adjustedLev = SessionKiller.adjustLeverage(BASE_LEVERAGE);
        double margin = fund * 0.40; // 用40%资金（因为止损距离短，风险可控）
        double notional = margin * adjustedLev;
        double qty = notional / price;
        double fee = notional * TAKER_FEE;

        double slDistance = Math.abs(price - middle);

        if (side.equals("LONG")) {
            stopLossPrice = middle;                                // 中轨止损
            takeProfitPrice = price + slDistance * PROFIT_TARGET_MULTIPLIER; // 止盈=3倍止损距离
        } else {
            stopLossPrice = middle;
            takeProfitPrice = price - slDistance * PROFIT_TARGET_MULTIPLIER;
        }

        positionSide = side;
        entryPrice = price;
        positionSize = qty;
        positionMargin = margin;
        peakROE = 0;
        fund -= fee;

        state = SqueezeState.POSITION_OPEN;

        double slPct = slDistance / price * 100;
        System.out.println("[SQUEEZE OPEN] " + side + " margin=" + fmt(margin) + "U lev=" + adjustedLev
                + "x size=" + fmt(qty) + " SOL"
                + " | SL=" + fmt(stopLossPrice) + "(" + fmtPct(slPct / 100) + ")"
                + " TP=" + fmt(takeProfitPrice));
    }

    /**
     * 持仓管理
     */
    private void managePosition(double price) {
        if (positionSide.equals("NONE")) {
            state = SqueezeState.SCANNING;
            return;
        }

        double roe = getROE(price);
        if (roe > peakROE) peakROE = roe;

        // 止损
        if (positionSide.equals("LONG") && price <= stopLossPrice) {
            closeSqueezePosition(price, "SQUEEZE SL hit");
            return;
        }
        if (positionSide.equals("SHORT") && price >= stopLossPrice) {
            closeSqueezePosition(price, "SQUEEZE SL hit");
            return;
        }

        // 止盈
        if (positionSide.equals("LONG") && price >= takeProfitPrice) {
            closeSqueezePosition(price, "SQUEEZE TP hit");
            return;
        }
        if (positionSide.equals("SHORT") && price <= takeProfitPrice) {
            closeSqueezePosition(price, "SQUEEZE TP hit");
            return;
        }

        // 移动止盈：ROE>=15%后回撤40%
        if (peakROE >= 15.0 && roe <= peakROE * 0.6) {
            closeSqueezePosition(price, "SQUEEZE trailing peak=" + fmtPct(peakROE / 100) + " now=" + fmtPct(roe / 100));
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
    private void closeSqueezePosition(double price, String reason) {
        double pnl;
        if (positionSide.equals("LONG")) pnl = (price - entryPrice) * positionSize;
        else pnl = (entryPrice - price) * positionSize;

        double fee = positionSize * price * TAKER_FEE;
        double netPnl = pnl - fee;

        fund += positionMargin + netPnl;
        totalProfit += netPnl;
        totalTrades++;
        if (netPnl > 0) winTrades++;

        // 利润65%回流主账户
        if (netPnl > 0) {
            double toVault = netPnl * 0.65;
            fund -= toVault;
            mainAccount.recycleProfit(toVault);
        }

        String tag = netPnl >= 0 ? "+" : "";
        double roe = getROE(price);
        System.out.println("[SQUEEZE CLOSE] " + positionSide + " " + reason
                + " ROE=" + (roe >= 0 ? "+" : "") + fmtPct(roe / 100)
                + " | pnl=" + tag + fmt(netPnl) + "U | fund=" + fmt(fund) + "U");

        positionSide = "NONE";
        entryPrice = 0;
        positionSize = 0;
        positionMargin = 0;
        peakROE = 0;
        state = SqueezeState.SCANNING;
        consecutiveNarrowing = 0;
    }

    // ===== 状态输出 =====
    public synchronized void printStatus() {
        double bandwidth = IndicatorCalculator.isReady() ? IndicatorCalculator.getBollingerBandwidth(BB_PERIOD) : 0;
        double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0;
        System.out.println("[SQUEEZE STATUS] fund=" + fmt(fund) + "U | profit=" + (totalProfit >= 0 ? "+" : "") + fmt(totalProfit)
                + "U | trades=" + totalTrades + " | winRate=" + fmtPct2(winRate)
                + " | squeezes=" + squeezeCount + " | bw=" + fmtPct(bandwidth / 100)
                + " | state=" + state);
    }

    public synchronized double getTotalProfit() { return totalProfit; }
    public synchronized double getFund() { return fund; }
    public synchronized boolean hasPosition() { return !positionSide.equals("NONE"); }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
    private String fmtPct(double v) { return String.format(Locale.US, "%.2f%%", v * 100); }
    private String fmtPct2(double v) { return String.format(Locale.US, "%.1f%%", v); }
}
