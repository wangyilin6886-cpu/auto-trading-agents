# 重构方案：5层防爆 + 极限激进策略

## 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│                        Main.java                             │
│  WebSocket + synchronized 价格分发                            │
├──────────────┬───────────────┬───────────────────────────────┤
│              │               │                               │
│  TradingDecisionEngine      LiquidationHunter               │
│  (AI决策 + 陷阱)            (爆仓猎杀)                       │
│              │               │                               │
├──────────────┴───────────────┴───────────────────────────────┤
│                    RiskManager (风控中枢)                      │
│  文件级KillSwitch | 每日亏损限额 | 连亏冷却 | 单笔暴露上限     │
├──────────────────────────────────────────────────────────────┤
│              DynamicLeverageEngine (动态杠杆)                  │
│  信号强度 × RSI极端度 → 3x~25x                               │
├──────────────────────────────────────────────────────────────┤
│              PyramidManager (浮盈加仓)                         │
│  只用浮盈追加 | 止损锁利润 | 最多3层                           │
├──────────────────────────────────────────────────────────────┤
│                 BinanceRealAccount (交易所通讯)                │
│  synchronized | 平仓重试3次 | 爆仓发真实平仓单                 │
└──────────────────────────────────────────────────────────────┘
```

## 第一步：新建 RiskManager.java — 风控中枢

职责：
- 文件级 Kill Switch (`/tmp/trading_killswitch` 存在即停，重启无法绕过)
- 内存级 Kill Switch (20% 最大回撤)
- 单日亏损限额 (本金 10%)
- 连续亏损冷却 (连亏3笔 → 冷却30分钟)
- 单笔最大暴露校验 (保证金 ≤ 本金5%)

所有开仓前必须过 `RiskManager.canTrade()` 门禁。

## 第二步：新建 DynamicLeverageEngine.java — 动态杠杆

输入：RSI、成交量倍数、宏观趋势、风控状态
输出：3x ~ 25x 杠杆

```
杠杆计算逻辑：
  基础杠杆 = 3x

  RSI极端加成：
    RSI < 20 或 > 80 → +8x   (极端超买超卖，反转概率高)
    RSI < 30 或 > 70 → +4x   (较强信号)
    否则 → +0x

  成交量加成：
    成交量 > 3倍均量 → +6x   (巨鲸进场)
    成交量 > 2倍均量 → +3x   (放量)
    否则 → +0x

  宏观顺势加成：
    做多 + BULL_TREND → +3x
    做空 + BEAR_TREND → +3x
    否则 → +0x

  风控限制：
    HIGH_MANIPULATION → 强制 cap 到 5x
    最终杠杆 = min(计算结果, 25)
```

## 第三步：新建 PyramidManager.java — 浮盈加仓

规则：
- 仅当浮盈 > 0 时才能加仓
- 加仓金额 = 浮盈的50%（不动本金）
- 最多加仓3层 (基础仓 + 3次追加)
- 每次加仓后，止损线上移到上一层入场价（保底不亏）
- 加仓触发条件：ROE ≥ 30% 且 趋势方向一致

数据结构：
```java
class PyramidLayer {
    double entryPrice;
    int qty;
    double margin;
}
List<PyramidLayer> layers; // 最多4层
```

## 第四步：新建 LiquidationHunter.java — 爆仓猎杀

原理：当市场出现大规模爆仓时，价格会急速冲到爆仓价位然后反弹。猎杀策略在检测到爆仓潮时顺势极速进出。

实现：
- 监听 `wss://fstream.binance.com/ws/solusdt@forceOrder` (实时爆仓流)
- 5秒窗口内累计爆仓量
- 触发条件：5秒内爆仓量 > 均量的5倍
- 开仓方向：与爆仓方向一致（多头爆仓→做空，空头爆仓→做多）
- 杠杆：25x (极速单，持仓极短)
- 止盈：0.3% ROE
- 止损：0.15% ROE
- 最大持仓时间：30秒 (超时强平)

## 第五步：修复 BinanceRealAccount.java — 5个致命bug

1. **所有共享字段加 volatile 或 synchronized**
   - `positionSide`, `positionSize`, `entryPrice`, `walletBalance`, `isGlobalKilled` 全部用 synchronized getter/setter

2. **checkLiquidation() 发真实平仓单**
   - 检测到爆仓条件 → 调用 sendOrder() 平仓 → 再清理本地状态

3. **closePosition() 失败重试3次**
   - 失败后 sleep 1s → 重试 → 3次都失败 → 触发文件级 Kill Switch 停止一切

4. **setMarginType / setLeverage 失败抛异常**
   - 不再 catch 后静默，失败则阻止开仓

5. **支持浮盈加仓**
   - openPosition 支持已有仓位时追加
   - 记录多层仓位信息

## 第六步：修复 TradingDecisionEngine.java

1. `activeTrap` 改为 `volatile` + 操作用 `synchronized`
2. 集成 DynamicLeverageEngine（替代固定 BASE_LEVERAGE=10）
3. 集成 PyramidManager（持仓时检查加仓条件）
4. 集成 RiskManager（开仓前过门禁）
5. 添加追踪止盈逻辑（ROE>30% 激活，回撤50%利润平仓）

## 第七步：修复 Main.java

1. 所有 `catch (Exception e) {}` 改为有意义的错误处理
2. WebSocket 断线重连加指数退避
3. 启动 LiquidationHunter 的独立 WebSocket
4. 添加 JVM shutdown hook 安全退出

## 第八步：修复 IndicatorCalculator.java

1. `prices` 和 `volumes` 改用 `synchronized` 保护
2. 新增 ATR (Average True Range) 计算，供动态杠杆使用

## 文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| RiskManager.java | 新建 | 风控中枢 |
| DynamicLeverageEngine.java | 新建 | 动态杠杆 3-25x |
| PyramidManager.java | 新建 | 浮盈加仓管理器 |
| LiquidationHunter.java | 新建 | 爆仓猎杀引擎 |
| BinanceRealAccount.java | 重写 | 修复5个致命bug + 支持加仓 |
| TradingDecisionEngine.java | 重写 | 集成所有新引擎 |
| Main.java | 重写 | 错误处理 + 双WebSocket |
| IndicatorCalculator.java | 修改 | 线程安全 + ATR |
| IntelligenceBoard.java | 不变 | 已经是线程安全的 |

## 最坏情况数学验证（1000元本金）

```
单笔最大保证金 = 1000 × 5% = 50元
25x杠杆，止损线 = 0.6/25 = 2.4%
单笔最大亏损 = 50 × (1 + 手续费) ≈ 51元 (5.1%本金)

每日最大亏损 = 10% = 100元 (触发日限额停机)
全局最大亏损 = 20% = 200元 (触发永久熔断)

连亏3笔 → 冷却30分钟（防止情绪化连续亏损）
血本无归概率 ≈ 0（需要穿透5层防护同时失效）
```
