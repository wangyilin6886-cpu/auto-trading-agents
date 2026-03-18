# CLAUDE.md - 巨鲸收割者 5.0 开发记录

## 项目概述

基于 Java 17 的多智能体 AI 驱动 HFT 交易机器人，连接币安 WebSocket 实时行情，通过多层 AI 决策 + 程序化规则执行 SOL/USDT 合约交易。

## 已完成的功能

### 1. TradingAccount 接口抽象（session 核心改造）

- **问题**：`TradingDecisionEngine` 所有方法参数写死 `BinanceRealAccount`，切换到 `FuturesVirtualAccount` 编译报错
- **方案**：新建 `TradingAccount` 接口，定义共同方法契约
- **文件**：
  - `TradingAccount.java` - 新建接口（openPosition, closePosition, checkLiquidation, checkGlobalKillSwitch, getROE, getUnrealizedPNL, getWalletBalance, getPositionSide, getRealizedProfit, printStatus）
  - `BinanceRealAccount.java` - implements TradingAccount
  - `FuturesVirtualAccount.java` - implements TradingAccount，新增 `checkGlobalKillSwitch()`（20% 回撤熔断）
  - `TradingDecisionEngine.java` - 参数类型改为 TradingAccount
  - `Main.java` - 切换为模拟盘模式，移除硬编码 API 密钥

### 2. 决策引擎优化 - AI 失败兜底机制

- **问题**：V3 AI（openclaw gateway）返回值解析全部失败，`parseDecision()` 永远匹配不到有效 JSON，所有决策默认走 HOLD，导致亏损仓位永不平仓
- **证据**：日志中理由全是"多智能体联合推演"（parseReason 的默认返回值）
- **方案**：
  - AI 解析失败时启用程序化规则兜底
  - 有仓位：ROE <= -3% 止损 / ROE >= +5% 止盈
  - 无仓位：RSI < 30 布置做多陷阱 / RSI > 70 布置做空陷阱
  - 日志标记 `(AI离线,程序兜底)` 方便排查

### 3. 短线快进快出策略

- **问题**：横盘行情下 ROE 在 -1.5% ~ +1% 波动，旧阈值（止盈+80%/止损-50%）永远触不到，浮盈全部回吐
- **方案 - 三层保护体系**：

| 层级 | 止盈 | 止损 | 触发条件 |
|------|------|------|----------|
| 程序兜底（AI离线） | +5% ROE | -3% ROE | AI 解析失败时 |
| 硬止盈止损 | +8% ROE | -5% ROE | 无条件触发 |
| 移动止盈 | 峰值ROE回撤超50% | - | peakROE >= 3% 且当前 <= peakROE*0.5 |

### 4. V3 Prompt 升级

- 原来只传 Price/RSI/Macro/Risk/HasPos，AI 不知道持仓盈亏状况
- 现在新增 PosSide/ROE/PNL 信息
- AI 规则阈值同步更新（止损 -3% / 止盈 +5%）

## 当前架构决策

### 账户切换方式

`Main.java` 中注释切换：
```java
// 模拟盘（当前）
FuturesVirtualAccount account = new FuturesVirtualAccount(50.0);
// 实盘（注释掉）
// BinanceRealAccount account = new BinanceRealAccount(API_KEY, SECRET_KEY, 50.0);
```

### 决策优先级（从高到低）

1. 全局熔断 `checkGlobalKillSwitch()` - 总资金回撤 20% 锁死
2. 爆仓检测 `checkLiquidation()` - 保证金耗尽强平
3. 硬止损 -5% ROE / 硬止盈 +8% ROE
4. 移动止盈（峰值 ROE >= 3% 后回撤超 50%）
5. AI V3 决策（如果解析成功）
6. 程序化兜底规则（AI 离线时）

### 关键参数

```
BASE_MARGIN_PCT = 0.05 (5% 仓位)
BASE_LEVERAGE = 10x
COOLDOWN_TICKS = 3 (平仓后冷却 3 个 15s 周期)
陷阱存活时间 = 60s
```

## 未完成的任务

### 高优先级

- [ ] **AI Gateway 修复**：V3 AI 返回值一直解析失败，当前完全靠程序兜底运行。需要排查 `OpenClawGatewayClient.askTraderSync()` 返回的原始格式，修正 `extractTruePayload()` 和 `parseDecision()` 的解析逻辑
- [ ] **实盘验证**：模拟盘跑通后，需要在实盘上验证 `BinanceRealAccount` 是否也正常工作（特别是 API 密钥和签名）

### 中优先级

- [ ] **参数调优**：当前止盈止损阈值（+5%/+8% 止盈，-3%/-5% 止损）是初始估计值，需要根据模拟盘跑一段时间的数据调整
- [ ] **双向开仓**：当前程序兜底只在 RSI < 30 做多 / RSI > 70 做空，中间区域不开仓，可考虑更细的策略
- [ ] **Snowball 杠杆**：`SNOWBALL_LEVERAGE = 20` 已定义但未使用，原设计是盈利后加杠杆滚雪球

### 低优先级

- [ ] **SLF4J 警告**：启动时有 `SLF4J: Failed to load class` 警告，不影响功能但可以在 pom.xml 加依赖消除
- [ ] **VirtualAccount.java 清理**：旧版现货模拟账户，已被 FuturesVirtualAccount 替代，可考虑删除
- [ ] **config.properties 密钥管理**：BinanceRealTrader.java 从 config.properties 读密钥，与 Main.java 硬编码方式不一致，需统一

## 注意事项

### 安全

- Main.java 中的 API 密钥已在模拟盘改造时移除（注释掉），切回实盘时注意不要提交密钥到 Git
- config.properties 和 DirectKimiClient.java 中仍有硬编码的 API 密钥（Binance + Moonshot），注意 .gitignore

### 代码质量

- `BinanceRealAccount` 的 `positionSize` 是 `int` 类型，`FuturesVirtualAccount` 是 `double` 类型，接口用的是各自实现，暂不影响但需注意
- `extractTruePayload()` 用正则提取 JSON，非常脆弱，建议改用 JSON 库解析
- WebSocket `onMessage` 中的 `catch (Exception e) {}` 吞掉了所有异常，调试时不友好

### 运行环境

- Java 17
- 依赖：java-websocket 1.5.3, org.json 20231013
- Maven 构建（需要网络下载依赖）
- 连接币安 WebSocket：`wss://stream.binance.com:9443/ws/solusdt@kline_1m`
