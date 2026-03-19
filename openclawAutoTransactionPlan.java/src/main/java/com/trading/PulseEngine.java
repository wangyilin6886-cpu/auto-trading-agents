package com.trading;

import java.util.Locale;

/**
 * Pulse Engine v2.0 - 六模式自适应交易引擎
 *
 * v1.0 四模式 + v2.0 新增：
 *   - 仓位呼吸(Position Breathing)：趋势模式下动态加减仓
 *   - 时区猎杀(SessionKiller)：根据全球时区自动调参
 *   - 新增子引擎接入点：WickHarvester / SqueezeDetonator / LiquidationHunter
 *
 * 核心模式：
 *   CALM     (平静期) → 网格套利，低杠杆高频薅羊毛
 *   TREND    (趋势期) → 动量追踪 + 仓位呼吸，中杠杆顺势而为
 *   STORM    (风暴期) → 级联冲浪，高杠杆骑清算瀑布
 *   HURRICANE(飓风期) → 防御模式，平仓观望保命
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

    // ===== 趋势仓位 (TREND) + 仓位呼吸 =====
    private String trendSide = "NONE";
    private double trendEntryPrice = 0;
    private double trendSize = 0;
    private double trendMargin = 0;
    private int trendLeverage = 10;
    private double trendPeakROE = 0;
    private double trendMaxMargin = 0;       // 呼吸模式：最大保证金上限
    private int breatheInCount = 0;          // 加仓次数
    private int breatheOutCount = 0;         // 减仓次数
    private long lastBreatheTime = 0;        // 上次呼吸时间
    private static final long BREATHE_COOLDOWN_MS = 5000; // 呼吸间隔至少5秒

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
        System.out.println("  PULSE ENGINE v2.0 - Adaptive + Breathing");
        System.out.println("=================================================");
        System.out.println("  Grid fund:    " + fmt(gridFund) + " USDT (35%)");
        System.out.println("  Trend fund:   " + fmt(trendFund) + " USDT (30%)");
        System.out.println("  Surf fund:    " + fmt(surfFund) + " USDT (25%)");
        System.out.println("  Reserve fund: " + fmt(reserveFund) + " USDT (10%)");
        System.out.println("  Total:        " + fmt(fund) + " USDT");
        System.out.println("  Session:      " + SessionKiller.getCurrentSession());
        System.out.println("  Leverage adj: " + SessionKiller.getLeverageMultiplier() + "x");
        System.out.println("=================================================\n");
    }

    // ==========================================
    // 核心入口：每个tick调用
    // ==========================================
    public synchronized void onTick(double price, double volume) {
        // 0. 时区检查
        SessionKiller.checkAndPrintSessionChange();

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

        // 4. 判断模式（时区调整信号门槛）
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
                executeTrendMode(price, momentum, volatility, acceleration);
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
        priceHistory[historyIndex] = price;
        volumeHistory[historyIndex] = volume;
        historyIndex = (historyIndex + 1) % WINDOW;
        if (historyIndex == 0) historyFull = true;

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

    private double calcMomentum() {
        int oldest = historyFull ? historyIndex : 0;
        int newest = (historyIndex - 1 + WINDOW) % WINDOW;
        if (priceHistory[oldest] == 0) return 0;
        return (priceHistory[newest] - priceHistory[oldest]) / priceHistory[oldest];
    }

    private double calcAcceleration() {
        if (!changesFull && changeIndex < 2) return 0;
        int cur = (changeIndex - 1 + periodChanges.length) % periodChanges.length;
        int prev = (changeIndex - 2 + periodChanges.length) % periodChanges.length;
        return periodChanges[cur] - periodChanges[prev];
    }

    private double calcVolumeSurge() {
        double recent = 0, older = 0;
        int newest = (historyIndex - 1 + WINDOW) % WINDOW;
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
        if (vol > VOL_HURRICANE_MIN) return MarketMode.HURRICANE;

        // 时区调整：信号门槛倍率
        double threshold = SessionKiller.getSignalThresholdMultiplier();

        if (vol > VOL_TREND_MAX && volumeSurge >= 2.0 * threshold && Math.abs(accel) > 0.0005) {
            return MarketMode.STORM;
        }
        if (vol > VOL_CALM_MAX && Math.abs(momentum) > 0.001) {
            return MarketMode.TREND;
        }
        return MarketMode.CALM;
    }

    // ==========================================
    // 模式切换处理
    // ==========================================
    private void handleModeSwitch(MarketMode from, MarketMode to, double price) {
        long now = System.currentTimeMillis();
        if (now - lastModeSwitchTime < MODE_SWITCH_COOLDOWN) return;

        System.out.println("\n>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>");
        System.out.println("  [MODE SWITCH] " + from + " -> " + to
                + " | session=" + SessionKiller.getCurrentSession());
        System.out.println("  price=" + fmt(price));
        System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>\n");

        if (to == MarketMode.HURRICANE) {
            if (!trendSide.equals("NONE")) closeTrendPosition(price, "HURRICANE emergency");
            if (!surfSide.equals("NONE")) closeSurfPosition(price, "HURRICANE emergency");
        }

        if (from == MarketMode.STORM && to != MarketMode.STORM) {
            if (!surfSide.equals("NONE")) closeSurfPosition(price, "Exit STORM mode");
            consecutiveAccel = 0;
        }

        if (from == MarketMode.TREND && to != MarketMode.TREND) {
            if (!trendSide.equals("NONE")) closeTrendPosition(price, "Exit TREND mode");
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
    // TREND模式：动量追踪 + 仓位呼吸
    // ==========================================
    private void executeTrendMode(double price, double momentum, double volatility, double acceleration) {
        // 网格照常运转
        gridEngine.onPriceUpdate(price);

        // 时区检查
        if (!SessionKiller.canOpenNewPosition() && trendSide.equals("NONE")) return;

        if (trendSide.equals("NONE")) {
            double rsi = IndicatorCalculator.getRSI(14);
            String trend = IndicatorCalculator.getTrend();

            // 时区调整杠杆
            int adjustedLev = SessionKiller.adjustLeverage(trendLeverage);

            if (momentum > 0.002 && rsi < 65 && trend.equals("BULL")) {
                openTrendPosition("LONG", price, adjustedLev, "TREND momentum=" + fmtPct(momentum) + " RSI=" + fmt(rsi));
            } else if (momentum < -0.002 && rsi > 35 && trend.equals("BEAR")) {
                openTrendPosition("SHORT", price, adjustedLev, "TREND momentum=" + fmtPct(momentum) + " RSI=" + fmt(rsi));
            }
        } else {
            // ========== 仓位呼吸：持仓管理 ==========
            double roe = getTrendROE(price);
            if (roe > trendPeakROE) trendPeakROE = roe;

            // 1. 止损 -10%
            if (roe <= -10.0) {
                closeTrendPosition(price, "TREND stop loss ROE=" + fmt(roe) + "%");
                return;
            }

            // 2. 止盈：峰值ROE>=8%后回撤40%
            if (trendPeakROE >= 8.0 && roe <= trendPeakROE * 0.6) {
                closeTrendPosition(price, "TREND trailing peak=" + fmt(trendPeakROE) + "% now=" + fmt(roe) + "%");
                return;
            }

            // 3. 时区锁利：US_LATE/DEAD时段盈利>5%锁一半
            if (SessionKiller.shouldLockProfit() && roe > 5.0) {
                breatheOut(price, 0.50, "Session lock profit ROE=" + fmt(roe) + "%");
                return;
            }

            // 4. 仓位呼吸逻辑
            long now = System.currentTimeMillis();
            if (now - lastBreatheTime > BREATHE_COOLDOWN_MS) {
                // 吸气（加仓）：加速度 > 0 + ROE > 0 + 仓位未满
                if (acceleration > 0.0002 && roe > 2.0 && trendMargin < trendMaxMargin * 0.80) {
                    boolean correctDir = (trendSide.equals("LONG") && momentum > 0) ||
                                         (trendSide.equals("SHORT") && momentum < 0);
                    if (correctDir) {
                        breatheIn(price, "Accel=" + fmtPct(acceleration) + " ROE=" + fmt(roe) + "%");
                    }
                }

                // 呼气（减仓）：加速度 ≈ 0 或反向
                if (Math.abs(acceleration) < 0.0001 && roe > 3.0 && breatheInCount > 0) {
                    breatheOut(price, 0.30, "Flat accel, lock profit ROE=" + fmt(roe) + "%");
                }

                if (acceleration < -0.0002 && trendSide.equals("LONG") && roe > 0) {
                    breatheOut(price, 0.40, "Decel detected ROE=" + fmt(roe) + "%");
                }
                if (acceleration > 0.0002 && trendSide.equals("SHORT") && roe > 0) {
                    breatheOut(price, 0.40, "Decel detected ROE=" + fmt(roe) + "%");
                }
            }

            // 5. 动量反转平仓
            if (trendSide.equals("LONG") && momentum < -0.001) {
                closeTrendPosition(price, "TREND momentum reversed ROE=" + fmt(roe) + "%");
            } else if (trendSide.equals("SHORT") && momentum > 0.001) {
                closeTrendPosition(price, "TREND momentum reversed ROE=" + fmt(roe) + "%");
            }
        }
    }

    // ==========================================
    // 仓位呼吸：吸气（加仓）
    // ==========================================
    private void breatheIn(double price, String reason) {
        double addMargin = trendFund * 0.15; // 每次加仓15%趋势资金
        if (addMargin < 1.0 || addMargin > trendFund * 0.5) return;

        int adjustedLev = SessionKiller.adjustLeverage(trendLeverage);
        double notional = addMargin * adjustedLev;
        double addQty = notional / price;
        double fee = notional * TAKER_FEE;

        // 更新加权平均入场价
        double totalNotional = trendEntryPrice * trendSize + price * addQty;
        double newTotalSize = trendSize + addQty;
        trendEntryPrice = totalNotional / newTotalSize;

        trendSize = newTotalSize;
        trendMargin += addMargin;
        trendFund -= fee;
        breatheInCount++;
        lastBreatheTime = System.currentTimeMillis();

        System.out.println("[BREATHE IN] +" + fmt(addMargin) + "U margin | total=" + fmt(trendMargin)
                + "U size=" + fmt(trendSize) + " SOL | " + reason);
    }

    // ==========================================
    // 仓位呼吸：呼气（减仓）
    // ==========================================
    private void breatheOut(double price, double fraction, String reason) {
        if (trendSide.equals("NONE") || trendSize <= 0) return;
        fraction = Math.max(0.1, Math.min(0.7, fraction));

        double reduceSize = trendSize * fraction;
        double reduceMargin = trendMargin * fraction;

        // 计算减仓部分的PNL
        double pnl;
        if (trendSide.equals("LONG")) pnl = (price - trendEntryPrice) * reduceSize;
        else pnl = (trendEntryPrice - price) * reduceSize;

        double fee = reduceSize * price * TAKER_FEE;
        double netPnl = pnl - fee;

        trendSize -= reduceSize;
        trendMargin -= reduceMargin;
        trendFund += reduceMargin + netPnl;
        recyclePulseProfit(netPnl, "trend");

        if (netPnl > 0) {
            totalProfit += netPnl;
            winTrades++;
        }
        breatheOutCount++;
        lastBreatheTime = System.currentTimeMillis();

        String tag = netPnl >= 0 ? "+" : "";
        System.out.println("[BREATHE OUT] -" + String.format("%.0f%%", fraction * 100) + " margin=" + fmt(reduceMargin)
                + "U pnl=" + tag + fmt(netPnl) + "U | remaining=" + fmt(trendMargin) + "U | " + reason);

        // 仓位太小就全平
        if (trendMargin < 1.0 || trendSize < 0.001) {
            closeTrendPosition(price, "Breathed out fully");
        }
    }

    // ==========================================
    // STORM模式：级联冲浪 (核心创新)
    // ==========================================
    private void executeStormMode(double price, double acceleration, double momentum, double volumeSurge) {
        if (surfSide.equals("NONE")) {
            if (acceleration > 0.0003) {
                consecutiveAccel++;
            } else if (acceleration < -0.0003) {
                consecutiveAccel--;
            } else {
                consecutiveAccel = 0;
            }

            // 时区调整量能门槛
            double volThreshold = 2.5 * SessionKiller.getSignalThresholdMultiplier();

            if (consecutiveAccel >= 3 && volumeSurge >= volThreshold) {
                int adjustedLev = SessionKiller.adjustLeverage(surfLeverage);
                openSurfPosition("LONG", price, adjustedLev, "STORM cascade UP accel=" + fmtPct(acceleration) + " vol=" + fmt(volumeSurge) + "x");
            } else if (consecutiveAccel <= -3 && volumeSurge >= volThreshold) {
                int adjustedLev = SessionKiller.adjustLeverage(surfLeverage);
                openSurfPosition("SHORT", price, adjustedLev, "STORM cascade DOWN accel=" + fmtPct(acceleration) + " vol=" + fmt(volumeSurge) + "x");
            }
        } else {
            double roe = getSurfROE(price);

            if (roe <= -8.0) {
                closeSurfPosition(price, "STORM stop loss ROE=" + fmt(roe) + "%");
                consecutiveAccel = 0;
                return;
            }

            boolean decelDetected = false;
            if (surfSide.equals("LONG") && acceleration < -0.0002) decelDetected = true;
            if (surfSide.equals("SHORT") && acceleration > 0.0002) decelDetected = true;

            if (decelDetected && roe > 0) {
                closeSurfPosition(price, "STORM decel profit ROE=" + fmt(roe) + "%");
                surfWaveCount++;
                consecutiveAccel = 0;

                if (Math.abs(acceleration) > 0.0005 && volumeSurge >= 2.0) {
                    String reverseSide = surfSide.equals("LONG") ? "SHORT" : "LONG";
                    int adjustedLev = SessionKiller.adjustLeverage(surfLeverage);
                    openSurfPosition(reverseSide, price, adjustedLev, "STORM reverse after decel wave#" + surfWaveCount);
                }
                return;
            }

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
    }

    // ==========================================
    // 趋势仓位管理
    // ==========================================
    private void openTrendPosition(String side, double price, int lev, String reason) {
        if (trendFund < 3.0) return;
        if (!SessionKiller.canOpenNewPosition()) return;

        double margin = trendFund * 0.40;
        double notional = margin * lev;
        double qty = notional / price;
        double fee = notional * TAKER_FEE;

        trendSide = side;
        trendEntryPrice = price;
        trendSize = qty;
        trendMargin = margin;
        trendPeakROE = 0;
        trendMaxMargin = trendFund * 0.80; // 呼吸模式最大保证金=趋势资金的80%
        breatheInCount = 0;
        breatheOutCount = 0;
        trendFund -= fee;

        System.out.println("[PULSE TREND OPEN] " + side + " margin=" + fmt(margin) + "U lev=" + lev
                + "x size=" + fmt(qty) + " SOL | " + reason
                + " | session=" + SessionKiller.getCurrentSession());
    }

    private void closeTrendPosition(double price, String reason) {
        if (trendSide.equals("NONE")) return;

        double pnl;
        if (trendSide.equals("LONG")) pnl = (price - trendEntryPrice) * trendSize;
        else pnl = (trendEntryPrice - price) * trendSize;

        double fee = trendSize * price * TAKER_FEE;
        double netPnl = pnl - fee;

        trendFund += trendMargin + netPnl;
        recyclePulseProfit(netPnl, "trend");

        totalProfit += netPnl;
        totalTrades++;
        if (netPnl > 0) winTrades++;

        String tag = netPnl >= 0 ? "+" : "";
        System.out.println("[PULSE TREND CLOSE] " + trendSide + " " + reason
                + " | pnl=" + tag + fmt(netPnl) + "U fund=" + fmt(trendFund) + "U"
                + " | breathe: in=" + breatheInCount + " out=" + breatheOutCount);

        trendSide = "NONE";
        trendEntryPrice = 0;
        trendSize = 0;
        trendMargin = 0;
        trendPeakROE = 0;
        breatheInCount = 0;
        breatheOutCount = 0;
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
    private void openSurfPosition(String side, double price, int lev, String reason) {
        if (surfFund < 3.0) return;

        double margin = surfFund * 0.50;
        double notional = margin * lev;
        double qty = notional / price;
        double fee = notional * TAKER_FEE;

        surfSide = side;
        surfEntryPrice = price;
        surfSize = qty;
        surfMargin = margin;
        surfFund -= fee;

        System.out.println("[PULSE SURF OPEN] " + side + " margin=" + fmt(margin) + "U lev=" + lev
                + "x size=" + fmt(qty) + " SOL | " + reason);
    }

    private void closeSurfPosition(double price, String reason) {
        if (surfSide.equals("NONE")) return;

        double pnl;
        if (surfSide.equals("LONG")) pnl = (price - surfEntryPrice) * surfSize;
        else pnl = (surfEntryPrice - price) * surfSize;

        double fee = surfSize * price * TAKER_FEE;
        double netPnl = pnl - fee;

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
            toVault = pnl * 0.70;
            surfFund -= toVault;
        } else {
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

        System.out.println("============== [PULSE ENGINE v2.0 STATUS] ==============");
        System.out.println("  MODE: " + currentMode + " | session=" + SessionKiller.getCurrentSession()
                + " | levMul=" + SessionKiller.getLeverageMultiplier() + "x");
        System.out.println("  volatility=" + fmtPct(vol) + " momentum=" + fmtPct(mom) + " accel=" + fmtPct(accel));
        System.out.println("  Fund: " + fmt(activeFund) + "U (" + (fundPnlPct >= 0 ? "+" : "") + fmt(fundPnlPct) + "%)");
        System.out.println("    grid=" + fmt(gridFund) + "U trend=" + fmt(trendFund)
                + "U surf=" + fmt(surfFund) + "U reserve=" + fmt(reserveFund) + "U");
        System.out.println("  Trades: " + totalTrades + " | WinRate: " + fmt(winRate)
                + "% | Profit: " + (totalProfit >= 0 ? "+" : "") + fmt(totalProfit) + "U");

        if (!trendSide.equals("NONE")) {
            double roe = getTrendROE(price);
            System.out.println("  [TREND POS] " + trendSide + " entry=" + fmt(trendEntryPrice)
                    + " size=" + fmt(trendSize) + " margin=" + fmt(trendMargin) + "U"
                    + " ROE=" + (roe >= 0 ? "+" : "") + fmt(roe) + "%"
                    + " breathe: in=" + breatheInCount + " out=" + breatheOutCount);
        }
        if (!surfSide.equals("NONE")) {
            double roe = getSurfROE(price);
            System.out.println("  [SURF POS] " + surfSide + " entry=" + fmt(surfEntryPrice)
                    + " size=" + fmt(surfSize) + " ROE=" + (roe >= 0 ? "+" : "") + fmt(roe) + "%");
        }
        System.out.println("  Waves surfed: " + surfWaveCount);
        System.out.println("========================================================");
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
