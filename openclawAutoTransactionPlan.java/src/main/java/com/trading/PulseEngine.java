package com.trading;

import java.util.Locale;

/**
 * Pulse Engine v1.0 - 四模式自动切换交易引擎
 *
 * 根据实时市场波动率/动量/加速度自动切换交易模式：
 *   CALM     (平静期) → 网格套利，低杠杆高频薅羊毛
 *   TREND    (趋势期) → 动量追踪，中杠杆顺势而为
 *   STORM    (风暴期) → 级联冲浪，高杠杆骑清算瀑布
 *   HURRICANE(飓风期) → 防御模式，平仓观望保命
 *
 * 核心创新：加速度检测 + 级联冲浪（STORM模式）
 *   - 连续3个周期加速 → 入场骑浪
 *   - 检测减速 → 退出并反转
 */
public class PulseEngine {

    // ===== 模式枚举 =====
    public enum MarketMode {
        CALM,       // 波动率 < 0.15%
        TREND,      // 0.15% ~ 0.5% + 有方向
        STORM,      // > 0.5% + 量能暴增
        HURRICANE   // > 1.0% 极端波动
    }

    // ===== 市场状态指标 =====
    private static final int WINDOW = 30;       // 检测窗口（30个tick，约7.5分钟）
    private double[] priceHistory = new double[WINDOW];
    private double[] volumeHistory = new double[WINDOW];
    private int historyIndex = 0;
    private boolean historyFull = false;

    // ===== 加速度检测 =====
    private double[] periodChanges = new double[10]; // 最近10个周期的价格变化率
    private int changeIndex = 0;
    private boolean changesFull = false;
    private int consecutiveAccel = 0;   // 连续加速计数

    // ===== 当前模式 =====
    private MarketMode currentMode = MarketMode.CALM;
    private long lastModeSwitchTime = 0;
    private static final long MODE_SWITCH_COOLDOWN = 15000; // 模式切换冷却15秒

    // ===== 子引擎资金池 =====
    private double gridFund;     // 网格资金 (CALM模式)
    private double trendFund;    // 趋势资金 (TREND模式)
    private double surfFund;     // 冲浪资金 (STORM模式)
    private double reserveFund;  // 储备金 (HURRICANE模式回收)
    private final double totalFund;

    // ===== 网格子引擎 (CALM) =====
    private final GridTradingEngine gridEngine;

    // ===== 趋势仓位 (TREND) =====
    private String trendSide = "NONE";
    private double trendEntryPrice = 0;
    private double trendSize = 0;
    private double trendMargin = 0;
    private int trendLeverage = 10;
    private double trendPeakROE = 0;

    // ===== 冲浪仓位 (STORM) =====
    private String surfSide = "NONE";
    private double surfEntryPrice = 0;
    private double surfSize = 0;
    private double surfMargin = 0;
    private int surfLeverage = 15;
    private int surfWaveCount = 0;      // 骑了几波

    // ===== 统计 =====
    private double totalProfit = 0;
    private int totalTrades = 0;
    private int winTrades = 0;
    private long lastStatusTime = 0;

    // ===== 交易参数 =====
    private static final double TAKER_FEE = 0.0004;

    // ===== 波动率阈值 =====
    private static final double VOL_CALM_MAX = 0.0015;      // < 0.15%
    private static final double VOL_TREND_MAX = 0.005;       // < 0.5%
    private static final double VOL_HURRICANE_MIN = 0.01;    // > 1.0%

    // ===== 关联主账户 =====
    private final TradingAccount mainAccount;

    public PulseEngine(double fund, TradingAccount account) {
        this.totalFund = fund;
        this.mainAccount = account;

        // 资金分配: 网格35% / 趋势30% / 冲浪25% / 储备10%
        this.gridFund = fund * 0.35;
        this.trendFund = fund * 0.30;
        this.surfFund = fund * 0.25;
        this.reserveFund = fund * 0.10;

        // 初始化网格子引擎 (6格 × 0.25% × 5x)
        this.gridEngine = new GridTradingEngine(gridFund, 6, 0.0025, 5, account);

        System.out.println("=================================================");
        System.out.println("  PULSE ENGINE v1.0 - Adaptive Mode Switching");
        System.out.println("=================================================");
        System.out.println("  Grid fund:    " + fmt(gridFund) + " USDT (35%)");
        System.out.println("  Trend fund:   " + fmt(trendFund) + " USDT (30%)");
        System.out.println("  Surf fund:    " + fmt(surfFund) + " USDT (25%)");
        System.out.println("  Reserve fund: " + fmt(reserveFund) + " USDT (10%)");
        System.out.println("  Total:        " + fmt(fund) + " USDT");
        System.out.println("=================================================\n");
    }

    // ==========================================
    // 核心入口：每个tick调用
    // ==========================================
    public synchronized void onTick(double price, double volume) {
        // 1. 记录历史数据
        recordData(price, volume);

        // 2. 不够数据先只跑网格
        if (!historyFull) {
            gridEngine.onPriceUpdate(price);
            return;
        }

        // 3. 检测市场状态
        double volatility = calcVolatility();
        double momentum = calcMomentum();
        double acceleration = calcAcceleration();
        double volumeSurge = calcVolumeSurge();

        // 4. 判断模式
        MarketMode newMode = detectMode(volatility, momentum, acceleration, volumeSurge);
        if (newMode != currentMode) {
            handleModeSwitch(currentMode, newMode, price);
        }

        // 5. 执行当前模式逻辑
        switch (currentMode) {
            case CALM:
                executeCalmMode(price);
                break;
            case TREND:
                executeTrendMode(price, momentum, volatility);
                break;
            case STORM:
                executeStormMode(price, acceleration, momentum, volumeSurge);
                break;
            case HURRICANE:
                executeHurricaneMode(price);
                break;
        }

        // 6. 定期打印状态 (每2分钟)
        long now = System.currentTimeMillis();
        if (now - lastStatusTime > 120000) {
            lastStatusTime = now;
            printFullStatus(price);
        }
    }

    // ==========================================
    // 数据记录
    // ==========================================
    private void recordData(double price, double volume) {
        // 记录价格/成交量
        priceHistory[historyIndex] = price;
        volumeHistory[historyIndex] = volume;
        historyIndex = (historyIndex + 1) % WINDOW;
        if (historyIndex == 0) historyFull = true;

        // 计算周期变化率 (每5个tick算一个周期)
        if (historyFull && historyIndex % 5 == 0) {
            int prevIdx = (historyIndex - 5 + WINDOW) % WINDOW;
            double change = (price - priceHistory[prevIdx]) / priceHistory[prevIdx];
            periodChanges[changeIndex] = change;
            changeIndex = (changeIndex + 1) % periodChanges.length;
            if (changeIndex == 0) changesFull = true;
        }
    }

    // ==========================================
    // 市场状态指标计算
    // ==========================================

    /** 波动率 = 窗口内价格标准差 / 均价 */
    private double calcVolatility() {
        double sum = 0, sumSq = 0;
        for (int i = 0; i < WINDOW; i++) {
            sum += priceHistory[i];
            sumSq += priceHistory[i] * priceHistory[i];
        }
        double mean = sum / WINDOW;
        double variance = sumSq / WINDOW - mean * mean;
        return Math.sqrt(Math.max(0, variance)) / mean;
    }

    /** 动量 = 当前价格相对窗口起点的变化率 */
    private double calcMomentum() {
        int oldest = historyFull ? historyIndex : 0;
        int newest = (historyIndex - 1 + WINDOW) % WINDOW;
        if (priceHistory[oldest] == 0) return 0;
        return (priceHistory[newest] - priceHistory[oldest]) / priceHistory[oldest];
    }

    /** 加速度 = 当前周期变化率 - 上一周期变化率 (核心创新) */
    private double calcAcceleration() {
        if (!changesFull && changeIndex < 2) return 0;
        int cur = (changeIndex - 1 + periodChanges.length) % periodChanges.length;
        int prev = (changeIndex - 2 + periodChanges.length) % periodChanges.length;
        return periodChanges[cur] - periodChanges[prev];
    }

    /** 成交量涌浪倍数 */
    private double calcVolumeSurge() {
        double recent = 0, older = 0;
        int newest = (historyIndex - 1 + WINDOW) % WINDOW;
        // 最近5个 vs 之前10个的均值
        for (int i = 0; i < 5; i++) {
            int idx = (newest - i + WINDOW) % WINDOW;
            recent += volumeHistory[idx];
        }
        for (int i = 5; i < 15; i++) {
            int idx = (newest - i + WINDOW) % WINDOW;
            older += volumeHistory[idx];
        }
        recent /= 5.0;
        older /= 10.0;
        return older == 0 ? 1.0 : recent / older;
    }

    // ==========================================
    // 模式检测
    // ==========================================
    private MarketMode detectMode(double vol, double momentum, double accel, double volumeSurge) {
        // HURRICANE: 极端波动
        if (vol > VOL_HURRICANE_MIN) return MarketMode.HURRICANE;

        // STORM: 高波动 + 量能暴增 + 有加速
        if (vol > VOL_TREND_MAX && volumeSurge >= 2.0 && Math.abs(accel) > 0.0005) {
            return MarketMode.STORM;
        }

        // TREND: 中等波动 + 有明确方向
        if (vol > VOL_CALM_MAX && Math.abs(momentum) > 0.001) {
            return MarketMode.TREND;
        }

        // CALM: 低波动
        return MarketMode.CALM;
    }

    // ==========================================
    // 模式切换处理
    // ==========================================
    private void handleModeSwitch(MarketMode from, MarketMode to, double price) {
        long now = System.currentTimeMillis();
        if (now - lastModeSwitchTime < MODE_SWITCH_COOLDOWN) return;

        System.out.println("\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
        System.out.println("  [MODE SWITCH] " + from + " -> " + to);
        System.out.println("  price=" + fmt(price));
        System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n");

        // 切到HURRICANE：紧急平掉趋势和冲浪仓位
        if (to == MarketMode.HURRICANE) {
            if (!trendSide.equals("NONE")) {
                closeTrendPosition(price, "HURRICANE emergency");
            }
            if (!surfSide.equals("NONE")) {
                closeSurfPosition(price, "HURRICANE emergency");
            }
        }

        // 切离STORM：平冲浪仓位
        if (from == MarketMode.STORM && to != MarketMode.STORM) {
            if (!surfSide.equals("NONE")) {
                closeSurfPosition(price, "Exit STORM mode");
            }
            consecutiveAccel = 0;
        }

        // 切离TREND：平趋势仓位
        if (from == MarketMode.TREND && to != MarketMode.TREND) {
            if (!trendSide.equals("NONE")) {
                closeTrendPosition(price, "Exit TREND mode");
            }
        }

        currentMode = to;
        lastModeSwitchTime = now;
    }

    // ==========================================
    // CALM模式：网格引擎运转
    // ==========================================
    private void executeCalmMode(double price) {
        gridEngine.onPriceUpdate(price);
    }

    // ==========================================
    // TREND模式：动量追踪
    // ==========================================
    private void executeTrendMode(double price, double momentum, double volatility) {
        // 网格照常运转
        gridEngine.onPriceUpdate(price);

        if (trendSide.equals("NONE")) {
            // 开仓条件：动量明确 + RSI配合
            double rsi = IndicatorCalculator.getRSI(14);
            String trend = IndicatorCalculator.getTrend();

            // 做多：动量为正 + RSI不过热 + 趋势BULL
            if (momentum > 0.002 && rsi < 65 && trend.equals("BULL")) {
                openTrendPosition("LONG", price, "TREND momentum=" + fmtPct(momentum) + " RSI=" + fmt(rsi));
            }
            // 做空：动量为负 + RSI不过冷 + 趋势BEAR
            else if (momentum < -0.002 && rsi > 35 && trend.equals("BEAR")) {
                openTrendPosition("SHORT", price, "TREND momentum=" + fmtPct(momentum) + " RSI=" + fmt(rsi));
            }
        } else {
            // 持仓管理
            double roe = getTrendROE(price);
            if (roe > trendPeakROE) trendPeakROE = roe;

            // 止损 -10%
            if (roe <= -10.0) {
                closeTrendPosition(price, "TREND stop loss ROE=" + fmt(roe) + "%");
                return;
            }

            // 止盈：峰值ROE>=8%后回撤40%
            if (trendPeakROE >= 8.0 && roe <= trendPeakROE * 0.6) {
                closeTrendPosition(price, "TREND trailing peak=" + fmt(trendPeakROE) + "% now=" + fmt(roe) + "%");
                return;
            }

            // 动量反转平仓
            if (trendSide.equals("LONG") && momentum < -0.001) {
                closeTrendPosition(price, "TREND momentum reversed ROE=" + fmt(roe) + "%");
            } else if (trendSide.equals("SHORT") && momentum > 0.001) {
                closeTrendPosition(price, "TREND momentum reversed ROE=" + fmt(roe) + "%");
            }
        }
    }

    // ==========================================
    // STORM模式：级联冲浪 (核心创新)
    // ==========================================
    private void executeStormMode(double price, double acceleration, double momentum, double volumeSurge) {
        // 网格暂停，专注冲浪
        // (不调用gridEngine.onPriceUpdate，避免极端波动下网格大量止损)

        if (surfSide.equals("NONE")) {
            // === 入场检测：连续加速 ===
            // 加速度方向一致且递增 → 级联正在发生
            if (acceleration > 0.0003) {
                consecutiveAccel++;
            } else if (acceleration < -0.0003) {
                consecutiveAccel--;
            } else {
                consecutiveAccel = 0;
            }

            // 连续3个周期加速 → 入场骑浪
            if (consecutiveAccel >= 3 && volumeSurge >= 2.5) {
                // 价格在加速上涨 → 做多骑浪（空头清算级联）
                openSurfPosition("LONG", price, "STORM cascade UP accel=" + fmtPct(acceleration) + " vol=" + fmt(volumeSurge) + "x");
            } else if (consecutiveAccel <= -3 && volumeSurge >= 2.5) {
                // 价格在加速下跌 → 做空骑浪（多头清算级联）
                openSurfPosition("SHORT", price, "STORM cascade DOWN accel=" + fmtPct(acceleration) + " vol=" + fmt(volumeSurge) + "x");
            }
        } else {
            // === 持仓管理：检测减速 ===
            double roe = getSurfROE(price);

            // 紧急止损 -8%
            if (roe <= -8.0) {
                closeSurfPosition(price, "STORM stop loss ROE=" + fmt(roe) + "%");
                consecutiveAccel = 0;
                return;
            }

            // 检测减速/反转 → 获利退出
            boolean decelDetected = false;
            if (surfSide.equals("LONG") && acceleration < -0.0002) decelDetected = true;
            if (surfSide.equals("SHORT") && acceleration > 0.0002) decelDetected = true;

            if (decelDetected && roe > 0) {
                closeSurfPosition(price, "STORM decel profit ROE=" + fmt(roe) + "%");
                surfWaveCount++;
                consecutiveAccel = 0;

                // 减速后反转：如果加速度足够大，立刻反向开仓
                if (Math.abs(acceleration) > 0.0005 && volumeSurge >= 2.0) {
                    String reverseSide = surfSide.equals("LONG") ? "SHORT" : "LONG";
                    // surfSide已被closeSurfPosition重置为NONE
                    openSurfPosition(reverseSide, price, "STORM reverse after decel wave#" + surfWaveCount);
                }
                return;
            }

            // 已盈利但加速度归零 → 安全退出
            if (roe > 2.0 && Math.abs(acceleration) < 0.0001) {
                closeSurfPosition(price, "STORM flat accel exit ROE=" + fmt(roe) + "%");
                consecutiveAccel = 0;
            }
        }
    }

    // ==========================================
    // HURRICANE模式：防御
    // ==========================================
    private void executeHurricaneMode(double price) {
        // 全部平仓已在handleModeSwitch中完成
        // 这里只做监控，等波动率下降自动切回
    }

    // ==========================================
    // 趋势仓位管理
    // ==========================================
    private void openTrendPosition(String side, double price, String reason) {
        if (trendFund < 3.0) return;

        double margin = trendFund * 0.40; // 每次用40%趋势资金
        double notional = margin * trendLeverage;
        double qty = notional / price;
        double fee = notional * TAKER_FEE;

        trendSide = side;
        trendEntryPrice = price;
        trendSize = qty;
        trendMargin = margin;
        trendPeakROE = 0;
        trendFund -= fee;

        System.out.println("[PULSE TREND OPEN] " + side + " margin=" + fmt(margin) + "U lev=" + trendLeverage
                + "x size=" + fmt(qty) + " SOL | " + reason);
    }

    private void closeTrendPosition(double price, String reason) {
        if (trendSide.equals("NONE")) return;

        double pnl;
        if (trendSide.equals("LONG")) {
            pnl = (price - trendEntryPrice) * trendSize;
        } else {
            pnl = (trendEntryPrice - price) * trendSize;
        }
        double fee = trendSize * price * TAKER_FEE;
        double netPnl = pnl - fee;

        // 利润分配
        trendFund += trendMargin + netPnl;
        recyclePulseProfit(netPnl, "trend");

        totalProfit += netPnl;
        totalTrades++;
        if (netPnl > 0) winTrades++;

        String tag = netPnl >= 0 ? "+" : "";
        System.out.println("[PULSE TREND CLOSE] " + trendSide + " " + reason
                + " | pnl=" + tag + fmt(netPnl) + "U fund=" + fmt(trendFund) + "U");

        trendSide = "NONE";
        trendEntryPrice = 0;
        trendSize = 0;
        trendMargin = 0;
        trendPeakROE = 0;
    }

    private double getTrendROE(double price) {
        if (trendMargin == 0) return 0;
        double pnl;
        if (trendSide.equals("LONG")) pnl = (price - trendEntryPrice) * trendSize;
        else pnl = (trendEntryPrice - price) * trendSize;
        return (pnl / trendMargin) * 100.0;
    }

    // ==========================================
    // 冲浪仓位管理
    // ==========================================
    private void openSurfPosition(String side, double price, String reason) {
        if (surfFund < 3.0) return;

        double margin = surfFund * 0.50; // 冲浪用50%资金（高风险高回报）
        double notional = margin * surfLeverage;
        double qty = notional / price;
        double fee = notional * TAKER_FEE;

        surfSide = side;
        surfEntryPrice = price;
        surfSize = qty;
        surfMargin = margin;
        surfFund -= fee;

        System.out.println("[PULSE SURF OPEN] " + side + " margin=" + fmt(margin) + "U lev=" + surfLeverage
                + "x size=" + fmt(qty) + " SOL | " + reason);
    }

    private void closeSurfPosition(double price, String reason) {
        if (surfSide.equals("NONE")) return;

        double pnl;
        if (surfSide.equals("LONG")) {
            pnl = (price - surfEntryPrice) * surfSize;
        } else {
            pnl = (surfEntryPrice - price) * surfSize;
        }
        double fee = surfSize * price * TAKER_FEE;
        double netPnl = pnl - fee;

        // 利润分配
        surfFund += surfMargin + netPnl;
        recyclePulseProfit(netPnl, "surf");

        totalProfit += netPnl;
        totalTrades++;
        if (netPnl > 0) winTrades++;

        String tag = netPnl >= 0 ? "+" : "";
        System.out.println("[PULSE SURF CLOSE] " + surfSide + " " + reason
                + " | pnl=" + tag + fmt(netPnl) + "U fund=" + fmt(surfFund) + "U");

        surfSide = "NONE";
        surfEntryPrice = 0;
        surfSize = 0;
        surfMargin = 0;
    }

    private double getSurfROE(double price) {
        if (surfMargin == 0) return 0;
        double pnl;
        if (surfSide.equals("LONG")) pnl = (price - surfEntryPrice) * surfSize;
        else pnl = (surfEntryPrice - price) * surfSize;
        return (pnl / surfMargin) * 100.0;
    }

    // ==========================================
    // 利润回流
    // ==========================================
    private void recyclePulseProfit(double pnl, String source) {
        if (pnl <= 0) return;

        double toVault;
        if (source.equals("surf")) {
            // 冲浪利润70%回流主账户（高风险收益要锁利润）
            toVault = pnl * 0.70;
            surfFund -= toVault;
        } else {
            // 趋势利润50%回流
            toVault = pnl * 0.50;
            trendFund -= toVault;
        }
        mainAccount.recycleProfit(toVault);
    }

    // ==========================================
    // 状态输出
    // ==========================================
    public synchronized void printFullStatus(double price) {
        double activeFund = gridFund + trendFund + surfFund + reserveFund;
        double fundPnlPct = (activeFund - totalFund) / totalFund * 100.0;
        double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0;

        double vol = historyFull ? calcVolatility() : 0;
        double mom = historyFull ? calcMomentum() : 0;
        double accel = historyFull ? calcAcceleration() : 0;

        System.out.println("============== [PULSE ENGINE STATUS] ==============");
        System.out.println("  MODE: " + currentMode + " | volatility=" + fmtPct(vol)
                + " momentum=" + fmtPct(mom) + " accel=" + fmtPct(accel));
        System.out.println("  Fund: " + fmt(activeFund) + "U (" + (fundPnlPct >= 0 ? "+" : "") + fmt(fundPnlPct) + "%)");
        System.out.println("    grid=" + fmt(gridFund) + "U trend=" + fmt(trendFund)
                + "U surf=" + fmt(surfFund) + "U reserve=" + fmt(reserveFund) + "U");
        System.out.println("  Trades: " + totalTrades + " | WinRate: " + fmt(winRate)
                + "% | Profit: " + (totalProfit >= 0 ? "+" : "") + fmt(totalProfit) + "U");

        if (!trendSide.equals("NONE")) {
            double roe = getTrendROE(price);
            System.out.println("  [TREND POS] " + trendSide + " entry=" + fmt(trendEntryPrice)
                    + " size=" + fmt(trendSize) + " ROE=" + (roe >= 0 ? "+" : "") + fmt(roe) + "%");
        }
        if (!surfSide.equals("NONE")) {
            double roe = getSurfROE(price);
            System.out.println("  [SURF POS] " + surfSide + " entry=" + fmt(surfEntryPrice)
                    + " size=" + fmt(surfSize) + " ROE=" + (roe >= 0 ? "+" : "") + fmt(roe) + "%");
        }
        System.out.println("  Waves surfed: " + surfWaveCount);
        System.out.println("===================================================");
    }

    // ==========================================
    // Getters
    // ==========================================
    public synchronized MarketMode getCurrentMode() { return currentMode; }
    public synchronized double getTotalProfit() { return totalProfit; }
    public synchronized int getTotalTrades() { return totalTrades; }

    public synchronized GridTradingEngine getGridEngine() { return gridEngine; }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
    private String fmtPct(double v) { return String.format(Locale.US, "%.4f%%", v * 100); }
}
