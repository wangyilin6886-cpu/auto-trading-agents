package com.trading;

public interface TradingAccount {
    void openPosition(String side, double price, double marginAmount, int lev, String reason);
    void closePosition(double price, String reason);
    boolean checkLiquidation(double currentPrice);
    boolean checkGlobalKillSwitch();
    double getUnrealizedPNL(double currentPrice);
    double getROE(double currentPrice);
    double getRealizedProfit();
    double getWalletBalance();
    String getPositionSide();
    void printStatus(double price);
}
