package com.trading;

public interface TradingAccount {
    // 开仓
    void openPosition(String side, double price, double marginAmount, int lev, String reason);

    // 全部平仓
    void closePosition(double price, String reason);

    // 分批平仓：平掉当前仓位的 fraction（0.0~1.0）
    void closePartial(double price, double fraction, String reason);

    // 爆仓检测
    boolean checkLiquidation(double currentPrice);

    // 全局熔断
    boolean checkGlobalKillSwitch();

    // 盈亏计算
    double getUnrealizedPNL(double currentPrice);
    double getROE(double currentPrice);
    double getRealizedProfit();

    // 资金管理
    double getWalletBalance();      // 总资金
    double getVaultBalance();       // 安全金库
    double getBulletBalance();      // 子弹仓
    double allocateFromBullet(double amount); // 从子弹仓划拨资金给子引擎
    void recycleProfit(double pnl); // 利润回流：50%进金库，50%加子弹

    // 仓位信息
    String getPositionSide();
    double getPositionSize();       // 当前持仓数量
    double getEntryPrice();         // 开仓价格

    // 状态输出
    void printStatus(double price);
}
