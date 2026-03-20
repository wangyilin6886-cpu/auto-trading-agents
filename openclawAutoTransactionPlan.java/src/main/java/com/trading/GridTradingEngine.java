package com.trading;

import java.util.Locale;

/**
 * 网格交易引擎 - 完全独立运行，与突破策略资金隔离
 *
 * 原理：
 *   在当前价格上下各铺 N 条网格线，间距为价格的 gridSpacingPct%
 *   价格下穿网格线 → 开多 (做多等反弹)
 *   价格上穿网格线 → 开空 (做空等回落)
 *   每个格子有对应的止盈线(相邻格子)，价格回到止盈线就平仓赚一格利润
 *   价格突破网格边界 → 止损所有该方向仓位，重新铺网格
 *
 * 资金管理：
 *   独立资金池，与突破策略互不干涉
 *   利润自动滚入资金池（复利）
 *   每格仓位 = 资金池 / (格子数 * 2) * 杠杆
 */
public class GridTradingEngine {

    // ===== 网格参数 =====
    private final int gridLevels;           // 单边格子数（上下各几格）
    private final double gridSpacingPct;    // 格距百分比（如 0.003 = 0.3%）
    private final int leverage;             // 网格杠杆

    // ===== 资金池 =====
    private double gridFund;                // 网格专属资金
    private double totalProfit = 0.0;       // 累计利润
    private int totalTrades = 0;
    private int winTrades = 0;

    // ===== 网格状态 =====
    private double gridCenter;              // 网格中心价格
    private double[] gridLines;             // 所有网格线价格
    private GridCell[] cells;               // 每个格子的仓位状态
    private boolean initialized = false;
    private double lastPrice = 0;

    // ===== 交易所参数 =====
    private static final double TAKER_FEE = 0.0004;

    // ===== 网格重铺参数 =====
    private long lastRebalanceTime = 0;
    private static final long REBALANCE_COOLDOWN = 60000; // 重铺冷却60秒

    // ===== 利润回流到主账户 =====
    private final TradingAccount mainAccount;

    /**
     * 格子仓位
     */
    static class GridCell {
        String side = "NONE";       // LONG / SHORT / NONE
        double entryPrice = 0;
        double size = 0;            // SOL 数量
        double margin = 0;          // 冻结保证金
        double tpPrice = 0;         // 止盈价格（对应的相邻格线）

        void reset() {
            side = "NONE"; entryPrice = 0; size = 0; margin = 0; tpPrice = 0;
        }

        boolean hasPosition() { return !side.equals("NONE"); }
    }

    public GridTradingEngine(double fund, int levels, double spacingPct, int lev, TradingAccount account) {
        this.gridFund = fund;
        this.gridLevels = levels;
        this.gridSpacingPct = spacingPct;
        this.leverage = lev;
        this.mainAccount = account;

        // gridLines: 2*levels + 1 条线 (从低到高)
        // cells: 2*levels 个格子 (格线之间的空间)
        this.gridLines = new double[2 * levels + 1];
        this.cells = new GridCell[2 * levels];
        for (int i = 0; i < cells.length; i++) cells[i] = new GridCell();

        System.out.println("[GRID] Engine created: fund=" + fmt(fund) + "U | levels=" + levels
                + " | spacing=" + String.format("%.2f%%", spacingPct * 100)
                + " | leverage=" + lev + "x");
    }

    /**
     * 初始化网格（第一次收到价格时调用）
     */
    private void initGrid(double centerPrice) {
        this.gridCenter = centerPrice;
        buildGridLines(centerPrice);
        this.initialized = true;
        this.lastRebalanceTime = System.currentTimeMillis();

        System.out.println("[GRID] Initialized around " + fmt(centerPrice));
        System.out.println("[GRID] Range: " + fmt(gridLines[0]) + " ~ " + fmt(gridLines[gridLines.length - 1]));
        System.out.println("[GRID] Per-cell margin: " + fmt(getPerCellMargin()) + "U | notional: " + fmt(getPerCellMargin() * leverage) + "U");
    }

    private void buildGridLines(double center) {
        int mid = gridLevels; // 中间位置的index
        gridLines[mid] = center;
        for (int i = 1; i <= gridLevels; i++) {
            gridLines[mid + i] = center * (1 + gridSpacingPct * i);
            gridLines[mid - i] = center * (1 - gridSpacingPct * i);
        }
    }

    /**
     * 每格可用保证金
     */
    private double getPerCellMargin() {
        // 最多同时开 gridLevels 个仓位（单边），留 20% 备用金
        double usableFund = gridFund * 0.80;
        return usableFund / gridLevels;
    }

    /**
     * 核心：每次收到价格都调用
     */
    public synchronized void onPriceUpdate(double currentPrice) {
        if (!initialized) {
            initGrid(currentPrice);
            lastPrice = currentPrice;
            return;
        }

        // 1. 检查已有仓位的止盈
        checkTakeProfits(currentPrice);

        // 2. 检查是否触发新格子
        checkGridTriggers(currentPrice);

        // 3. 检查是否需要重铺网格（价格漂移太远）
        checkRebalance(currentPrice);

        lastPrice = currentPrice;
    }

    /**
     * 止盈检查：遍历所有有仓位的格子
     */
    private void checkTakeProfits(double price) {
        for (int i = 0; i < cells.length; i++) {
            GridCell cell = cells[i];
            if (!cell.hasPosition()) continue;

            boolean shouldClose = false;
            if (cell.side.equals("LONG") && price >= cell.tpPrice) shouldClose = true;
            if (cell.side.equals("SHORT") && price <= cell.tpPrice) shouldClose = true;

            if (shouldClose) {
                closeGridCell(cell, price, "TP hit");
            }
        }
    }

    /**
     * 新格子触发：价格穿越了哪条网格线
     */
    private void checkGridTriggers(double price) {
        if (lastPrice == 0) return;

        for (int i = 0; i < gridLines.length; i++) {
            double line = gridLines[i];

            // 价格从上方穿越到下方 → 在这条线开多
            if (lastPrice > line && price <= line) {
                int cellIndex = i - 1; // 格线下方的格子
                if (cellIndex >= 0 && cellIndex < cells.length) {
                    openGridCell(cellIndex, "LONG", price, i);
                }
            }

            // 价格从下方穿越到上方 → 在这条线开空
            if (lastPrice < line && price >= line) {
                int cellIndex = i; // 格线上方的格子
                if (cellIndex >= 0 && cellIndex < cells.length) {
                    openGridCell(cellIndex, "SHORT", price, i);
                }
            }
        }
    }

    /**
     * 在指定格子开仓
     */
    private void openGridCell(int cellIndex, String side, double price, int lineIndex) {
        GridCell cell = cells[cellIndex];

        // 如果格子已有同方向仓位，跳过
        if (cell.hasPosition()) {
            // 如果方向相反，先平旧仓再开新仓
            if (!cell.side.equals(side)) {
                closeGridCell(cell, price, "Reverse");
            } else {
                return;
            }
        }

        double margin = getPerCellMargin();
        if (margin < 1.0 || margin > gridFund) return; // 资金不够

        double notional = margin * leverage;
        double qty = notional / price;
        double fee = notional * TAKER_FEE;

        // 计算止盈价格（相邻的格线）
        double tpPrice;
        if (side.equals("LONG")) {
            // 多单止盈 = 上方一条格线
            tpPrice = (lineIndex + 1 < gridLines.length) ? gridLines[lineIndex + 1] : gridLines[lineIndex] * (1 + gridSpacingPct);
        } else {
            // 空单止盈 = 下方一条格线
            tpPrice = (lineIndex - 1 >= 0) ? gridLines[lineIndex - 1] : gridLines[lineIndex] * (1 - gridSpacingPct);
        }

        cell.side = side;
        cell.entryPrice = price;
        cell.size = qty;
        cell.margin = margin;
        cell.tpPrice = tpPrice;

        gridFund -= (margin + fee); // 冻结保证金 + 扣手续费

        System.out.println("[GRID OPEN] " + side + " cell#" + cellIndex
                + " entry=" + fmt(price) + " tp=" + fmt(tpPrice)
                + " size=" + fmt(qty) + " SOL | margin=" + fmt(margin) + "U");
    }

    /**
     * 平仓一个格子
     */
    private void closeGridCell(GridCell cell, double price, String reason) {
        if (!cell.hasPosition()) return;

        double pnl;
        if (cell.side.equals("LONG")) {
            pnl = (price - cell.entryPrice) * cell.size;
        } else {
            pnl = (cell.entryPrice - price) * cell.size;
        }

        double closeFee = cell.size * price * TAKER_FEE;
        double netPnl = pnl - closeFee;

        // 利润直接滚入网格资金池（复利核心）
        gridFund += cell.margin + netPnl;
        totalProfit += netPnl;
        totalTrades++;
        if (netPnl > 0) winTrades++;

        // 利润的一部分回流主账户金库
        if (netPnl > 0) {
            double toVault = netPnl * 0.20; // 网格利润的20%回流金库
            gridFund -= toVault;
            mainAccount.recycleProfit(toVault);
        }

        String tag = netPnl >= 0 ? "+" : "";
        System.out.println("[GRID CLOSE] " + cell.side + " " + reason
                + " entry=" + fmt(cell.entryPrice) + " exit=" + fmt(price)
                + " pnl=" + tag + fmt(netPnl) + "U"
                + " | fund=" + fmt(gridFund) + "U");

        cell.reset();
    }

    /**
     * 重铺网格：价格偏离中心超过一半网格范围时
     */
    private void checkRebalance(double price) {
        long now = System.currentTimeMillis();
        if (now - lastRebalanceTime < REBALANCE_COOLDOWN) return;

        double upperBound = gridLines[gridLines.length - 1];
        double lowerBound = gridLines[0];
        double range = upperBound - lowerBound;

        // 价格偏离中心超过范围的 40%，重铺
        if (Math.abs(price - gridCenter) > range * 0.40) {
            System.out.println("[GRID REBALANCE] Price " + fmt(price) + " drifted from center " + fmt(gridCenter));

            // 先平掉所有亏损仓位
            closeAllPositions(price, "Rebalance");

            // 以当前价格为新中心重铺
            buildGridLines(price);
            gridCenter = price;
            lastRebalanceTime = now;

            System.out.println("[GRID] New range: " + fmt(gridLines[0]) + " ~ " + fmt(gridLines[gridLines.length - 1]));
        }
    }

    /**
     * 平掉所有仓位
     */
    private void closeAllPositions(double price, String reason) {
        for (GridCell cell : cells) {
            if (cell.hasPosition()) {
                closeGridCell(cell, price, reason);
            }
        }
    }

    /**
     * 状态输出（每隔一段时间由 Main 调用）
     */
    public synchronized void printStatus(double price) {
        int openLongs = 0, openShorts = 0;
        double totalUnrealizedPnl = 0;

        for (GridCell cell : cells) {
            if (!cell.hasPosition()) continue;
            if (cell.side.equals("LONG")) {
                openLongs++;
                totalUnrealizedPnl += (price - cell.entryPrice) * cell.size;
            } else {
                openShorts++;
                totalUnrealizedPnl += (cell.entryPrice - price) * cell.size;
            }
        }

        double winRate = totalTrades > 0 ? (winTrades * 100.0 / totalTrades) : 0;

        System.out.println("========== [GRID STATUS] ==========");
        System.out.println("  fund=" + fmt(gridFund) + "U | profit=" + (totalProfit >= 0 ? "+" : "") + fmt(totalProfit) + "U");
        System.out.println("  trades=" + totalTrades + " | winRate=" + fmt(winRate) + "%");
        System.out.println("  open: " + openLongs + " LONG + " + openShorts + " SHORT");
        System.out.println("  unrealized=" + (totalUnrealizedPnl >= 0 ? "+" : "") + fmt(totalUnrealizedPnl) + "U");
        System.out.println("  range: " + fmt(gridLines[0]) + " ~ " + fmt(gridLines[gridLines.length - 1]));
        System.out.println("===================================");
    }

    public synchronized double getTotalProfit() { return totalProfit; }
    public synchronized double getGridFund() { return gridFund; }
    public synchronized int getTotalTrades() { return totalTrades; }

    private String fmt(double v) { return String.format(Locale.US, "%.4f", v); }
}
