# CLAUDE.md - 巨鲸收割者 6.0 开发记录

## 项目概述

基于 Java 17 的多智能体 AI 驱动 HFT 交易机器人，连接币安 WebSocket 实时行情，通过 Pulse Engine 四模式自适应切换 + 突破策略 + AI 决策执行 SOL/USDT 合约交易。

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

### 5. Pulse Engine v1.0 - 四模式自适应引擎

- **文件**：`PulseEngine.java` - 全新核心引擎
- **模式检测**：基于波动率/动量/加速度/量能实时切换

| 模式 | 波动率条件 | 交易策略 | 杠杆 |
|------|-----------|----------|------|
| CALM | < 0.15% | 网格套利 (6格×0.25%×5x) | 5x |
| TREND | 0.15%~0.5% + 有方向 | 动量追踪 | 10x |
| STORM | > 0.5% + 量能2x+ + 有加速 | 级联冲浪 | 15x |
| HURRICANE | > 1.0% | 防御模式，全部平仓 | - |

- **核心创新 - 级联冲浪 (STORM模式)**：
  - 加速度 = 当前周期变化率 - 上一周期变化率
  - 连续3个周期加速 + 量能≥2.5x → 入场骑浪
  - 检测减速 → 获利退出并反向开仓
  - 利润70%锁入主账户金库

- **资金分配**：
  - Grid: 35% | Trend: 30% | Surf: 25% | Reserve: 10%

- **利润回流**：
  - 网格利润: 80%复利 / 20%回主账户
  - 趋势利润: 50%回主账户
  - 冲浪利润: 70%回主账户（高风险收益要锁利润）

### 6. 模拟/实盘命令行切换

- **方案**：命令行参数切换，无需改代码
  - `java -jar bot.jar` → 模拟盘 (默认)
  - `java -jar bot.jar --real` → 实盘 (需设环境变量)
  - `java -jar bot.jar --capital=200` → 自定义本金
  - 实盘需要: `export BINANCE_API_KEY=xxx && export BINANCE_SECRET_KEY=xxx`

## 当前架构决策

### 引擎架构 (v6.0)

```
Main.java
├── PulseEngine (60% of bullet) ← 新增
│   ├── CALM → GridTradingEngine (内置)
│   ├── TREND → 动量追踪仓位
│   ├── STORM → 级联冲浪仓位
│   └── HURRICANE → 防御模式
└── TradingDecisionEngine (40% of bullet) ← 原有突破策略
    ├── 信号分级 S/A/B
    ├── 分批止盈 TP1/TP2/Trailing
    └── AI V3 + 程序化兜底
```

### 决策优先级（从高到低）

1. 全局熔断 `checkGlobalKillSwitch()` - 总资金跌破60%锁死
2. 爆仓检测 `checkLiquidation()` - 保证金耗尽强平
3. HURRICANE模式 - 极端波动紧急平仓
4. Pulse Engine 模式自动切换 (CALM/TREND/STORM)
5. 突破策略硬止损 -15% ROE / 分批止盈 +20%/+40%/Trailing
6. AI V3 决策（如果解析成功）
7. 程序化兜底规则（AI 离线时）

### 关键参数

```
# 突破策略
STOP_LOSS_ROE = -15%
TP1_ROE = +20% (close 1/3)
TP2_ROE = +40% (close 1/3)
TRAILING = peak>=15% drawdown 30%
COOLDOWN_TICKS = 2 (30秒)

# Pulse Engine
VOL_CALM_MAX = 0.15%
VOL_TREND_MAX = 0.5%
VOL_HURRICANE_MIN = 1.0%
TREND_LEVERAGE = 10x, STOP -10%, TRAILING peak>=8% drawdown 40%
SURF_LEVERAGE = 15x, STOP -8%, 连续3加速入场
MODE_SWITCH_COOLDOWN = 15秒
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
