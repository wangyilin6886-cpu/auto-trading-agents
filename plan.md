# 完整重构方案：RESTRUCTURE_PLAN.md 全量实现 + 动态杠杆/浮盈加仓/爆仓猎杀

## 设计原则

1. **前向验证优先** — 改完先跑模拟盘 2-4 周，不直接上实盘
2. **参数不回测调优** — 所有权重/阈值基于金融常识设定，通过实时模拟验证，避免过拟合
3. **Kelly 冷启动** — 前 50 笔用固定 3% 仓位，积累真实胜率后再切换 Kelly 公式
4. **权重可配置** — 综合评分权重放入 Config.java，方便前向验证后微调

---

## 现状对比

### 已实现（来自精简版 plan.md 第一轮重构）
- [x] RiskManager.java — 5层风控（KillSwitch、日亏损、连亏冷却、单笔限额）
- [x] DynamicLeverageEngine.java — 动态杠杆 3-25x
- [x] PyramidManager.java — 浮盈加仓（最多3层、ROE>=30%触发）
- [x] LiquidationHunter.java — 爆仓猎杀（forceOrder流、25x、30秒限时）
- [x] BinanceRealAccount.java — synchronized、平仓重试3次、爆仓发真单
- [x] TradingDecisionEngine.java — volatile activeTrap、集成新引擎
- [x] IndicatorCalculator.java — synchronized + ATR
- [x] Main.java — 错误处理、shutdown hook、指数退避重连

### 未实现（RESTRUCTURE_PLAN.md 中的关键缺失）
- [ ] **Config.java** — API Key 环境变量化（当前仍硬编码！）
- [ ] **MarketDataHub.java** — 多流WebSocket（5个流合并）+ 心跳监控
- [ ] **SignalEngine.java** — OBI/CVD/LCI/FundingRate/综合评分
- [ ] **AuditLogger.java** — JSON Lines 审计日志 + 告警
- [ ] **MultiAgentOrchestrator.java** — 三模型重编排 + 输出消毒 + 否决权
- [ ] **OrderManager.java** — 原子化订单 + 成交价追踪 + 滑点监控
- [ ] **AI幻觉校验** — trigger_price ±2%、confidence校验、JSON schema
- [ ] **动态仓位(Kelly)** — Kelly公式 + 波动率调整（冷启动：前50笔固定3%）
- [ ] **Prompt Injection防护** — agent输出消毒
- [ ] **快通道信号系统** — 纯量化毫秒级通道（放宽触发条件）
- [ ] **模拟模式** — SimulationMode（保留 FuturesVirtualAccount 改造）
- [ ] **清理** — 删除 config.properties、DirectKimiClient.java、更新 .gitignore
- [ ] **修复 OpenClawGatewayClient** — cmd.exe → 跨平台兼容
- [ ] **修复空 catch 块** — 全局排查空异常处理
- [ ] **独立温度设置** — V3=0.1, R1=0.3, Kimi=0.3

---

## 完整 6 层架构（目标状态）

```
┌─────────────────────────────────────────────────────────┐
│                    Layer 6: AUDIT                        │
│  AuditLogger — 每笔决策/交易/异常全部持久化              │
│  JSON Lines 格式，按天滚动 logs/trading_YYYY-MM-DD.jsonl │
├─────────────────────────────────────────────────────────┤
│                 Layer 5: RISK GUARD                      │
│  RiskManager（已有）                                     │
│  + AI 幻觉校验（trigger_price ±2%, confidence [0,1]）    │
│  + 动态仓位 Kelly 公式                                   │
│  + Prompt Injection 防护                                │
├─────────────────────────────────────────────────────────┤
│              Layer 4: DECISION ENGINE                    │
│  MultiAgentOrchestrator — 替代当前 TradingDecisionEngine │
│  ├─ MIO (Kimi 32K): 宏观体制，60s 周期                  │
│  ├─ RRO (R1 推理): 风险审核 + 否决权，30s 周期           │
│  ├─ TEO (V3 快速): 执行决策，15s 周期                    │
│  └─ 信息隔离 + 输出消毒 + JSON Schema 校验               │
│                                                         │
│  快通道（新增，不经AI）                                   │
│  ├─ |OBI| > 0.4 + CVD方向一致 + BB突破 → 直接执行        │
│  └─ 小仓（基础仓位30%），毫秒级触发                      │
│                                                         │
│  DynamicLeverageEngine（已有）— 3x~25x                  │
│  PyramidManager（已有）— 浮盈加仓                        │
│  LiquidationHunter（已有）— 爆仓猎杀                     │
├─────────────────────────────────────────────────────────┤
│              Layer 3: SIGNAL ENGINE                      │
│  SignalEngine — 纯计算层（替代 IndicatorCalculator）      │
│  ├─ 技术指标: RSI, SMA, Bollinger, ATR                  │
│  ├─ 订单流: OBI (订单簿失衡), CVD (累积量Delta)          │
│  ├─ 爆仓: LCI (爆仓级联强度)                             │
│  ├─ 情绪: Funding Rate Sentiment                        │
│  └─ 综合评分: 加权 Composite Score                       │
├─────────────────────────────────────────────────────────┤
│               Layer 2: EXECUTION                         │
│  OrderManager — 原子化订单管理                           │
│  ├─ 成交价追踪（解析 avgPrice，不用 WebSocket 价格）     │
│  ├─ 原子仓位状态机（ReentrantLock）                      │
│  ├─ 止损原子设置（开仓+止损事务化）                       │
│  ├─ 滑点监控与报告（>0.5%警告 + 增加冷却）              │
│  └─ LIMIT 单支持（降低被夹风险）                         │
│                                                         │
│  BinanceRealAccount（已有，需拆分部分逻辑到OrderManager）│
├─────────────────────────────────────────────────────────┤
│                Layer 1: MARKET DATA                      │
│  MarketDataHub — 线程安全数据中心                        │
│  ├─ solusdt@kline_1m (K线)                              │
│  ├─ solusdt@depth@100ms (订单簿 Top 10)                 │
│  ├─ solusdt@aggTrade (逐笔成交)                         │
│  ├─ solusdt@forceOrder (全网爆仓)                       │
│  ├─ solusdt@markPrice@1s (标记价/资金费率)              │
│  └─ 心跳监控（30s无数据主动断开）+ 指数退避重连          │
│                                                         │
│  Config — 环境变量加载 + 配置管理                        │
└─────────────────────────────────────────────────────────┘
```

---

## 实施计划（8个Phase，按风险驱动优先级）

### Phase 1: 基础设施 — Config + AuditLogger
**目标：消除安全隐患 + 建立审计能力**

#### 1.1 新建 Config.java
- 所有 API Key 改为 `System.getenv()` 读取
- 启动时校验：缺失则抛 RuntimeException 阻止启动
- 删除 Main.java 中硬编码的 API Key
- 删除 config.properties 文件
- 删除 DirectKimiClient.java（明文Key + 已废弃）
- 添加 .gitignore（*.properties, .env, logs/）
- **综合评分权重可配置**：OBI/CVD/RSI/LCI/BB/Funding/Vol 权重存入 Config
- **快通道阈值可配置**：OBI 阈值、CVD 阈值等
- **运行模式配置**：`TRADING_MODE` 环境变量 = `LIVE` 或 `SIMULATION`

#### 1.2 新建 AuditLogger.java
- JSON Lines 格式，每行一条记录
- 记录类型：AI_DECISION, ORDER, RISK_CHECK, SIGNAL, KILL_SWITCH, ERROR
- 文件路径：`logs/trading_YYYY-MM-DD.jsonl`（按天滚动）
- 线程安全：synchronized write 或 BlockingQueue + 后台写入线程
- 所有现有模块接入 AuditLogger
- **告警机制（通过 OpenClaw → Telegram 推送）**：
  - 告警通道：调用 OpenClawGatewayClient 发送消息给一个专用 notification agent，由 OpenClaw 转发到 Telegram
  - Kill Switch 触发 → Telegram 推送 + `logs/ALERT_YYYY-MM-DD.log`
  - 连亏 3 笔 → Telegram 推送
  - 滑点 > 1% → Telegram 推送
  - 日亏损 > 5% → Telegram 推送
  - 每笔交易执行后 → Telegram 摘要（方向/数量/入场价/杠杆/预期止盈止损）
  - 每小时汇总 → Telegram 报告（余额/持仓/今日 PnL/胜率/交易次数）
  - 告警防刷：同类告警 5 分钟内只发一次，防止 Telegram 消息轰炸
  - 告警发送异步（不阻塞交易主线程），失败静默记录到本地日志

#### 1.3 新建 TelegramNotifier.java
- 封装通过 OpenClawGatewayClient 发送 Telegram 通知的逻辑
- 消息格式化：
  ```
  🔴 KILL SWITCH 触发
  原因：日亏损 -5.2 USDT (10.4%)
  余额：44.8 USDT
  时间：2026-03-18 14:30:05
  ```
  ```
  📊 交易执行
  方向：LONG | 杠杆：10x
  入场：$150.25 | 数量：5 SOL
  止盈：$151.50 (1% ROE)
  止损：$149.50 (-0.5% ROE)
  信号来源：快通道 (OBI=0.38, CVD↑)
  ```
  ```
  📈 每小时报告
  余额：52.3 USDT (+2.3)
  今日 PnL：+4.6%
  胜率：7/10 (70%)
  持仓：LONG 3 SOL @ $150.25
  ```
- 异步发送：用 CompletableFuture，不阻塞主流程
- 限流：同类消息 5 分钟去重（用 HashMap<alertType, lastSentTime>）

#### 1.4 修复全局空 catch 块
- 排查 Main.java, BinanceRealAccount.java 等所有空 catch 块
- 至少记录异常到 AuditLogger + stderr
- 关键操作（下单、平仓）的异常不能静默吞掉

---

### Phase 2: 数据基础设施 — MarketDataHub
**目标：从单流升级为5流，获取订单流/爆仓/资金费率数据**

#### 2.1 新建 MarketDataHub.java
- 单连接合流模式：
  ```
  wss://fstream.binance.com/stream?streams=
    solusdt@kline_1m/solusdt@depth@100ms/solusdt@aggTrade/
    solusdt@forceOrder/solusdt@markPrice@1s
  ```
- 数据分发：根据 `stream` 字段路由到不同处理器
- 心跳监控：30秒无数据 → 主动断开重连
- 指数退避重连：2s → 4s → 8s → 16s → 32s（上限）
- 线程安全数据存储：
  - `ConcurrentLinkedDeque<double[]>` 存订单簿快照
  - `AtomicReference` 存最新 markPrice / fundingRate
  - 现有 IndicatorCalculator 的 kline 数据迁入
- LiquidationHunter 改为从 MarketDataHub 接收 forceOrder 数据（不再独立WebSocket）
- Main.java 简化为启动入口，所有 WebSocket 逻辑移入 MarketDataHub

---

### Phase 3: 信号引擎 — SignalEngine
**目标：从纯RSI升级为7维综合信号**

#### 3.1 新建 SignalEngine.java
合并 IndicatorCalculator 的功能 + 新增4个信号维度：

**保留的技术指标（来自 IndicatorCalculator）：**
- RSI(14)、SMA(20)、Bollinger(20, 2σ)、ATR(14)、成交量倍数

**新增订单流指标：**
- **OBI (Order Book Imbalance)**
  - `OBI = (bid_top10 - ask_top10) / (bid_top10 + ask_top10)`
  - 范围 [-1, +1]，100ms 更新
  - OBI > 0.3 看多，OBI < -0.3 看空

- **CVD (Cumulative Volume Delta)**
  - aggTrade 中 m=false(主动买) → CVD += qty；m=true → CVD -= qty
  - 1分钟和5分钟滚动窗口
  - CVD↑ + 价格↑ → 真实上涨；CVD↓ + 价格↑ → 虚假上涨（背离）

- **LCI (Liquidation Cascade Intensity)**
  - `LCI = sum(liquidation_amount) / rolling_1min_window`
  - LCI飙升 → 级联爆仓正在发生
  - 作为确认信号（不是主信号）

- **Funding Rate Sentiment**
  - 正费率 > 0.01% → 多头拥挤，空头优势
  - 负费率 < -0.01% → 空头拥挤，多头优势
  - 极端费率 > 0.05% → 禁止新开仓

**综合评分公式（权重从 Config.java 读取，可调）：**
```
score = w1*OBI + w2*CVD_slope + w3*RSI + w4*LCI + w5*BB + w6*funding + w7*vol_surge
默认：0.25 + 0.20 + 0.15 + 0.15 + 0.10 + 0.10 + 0.05 = 1.0
score > 0.6 → 强烈做多
score < -0.6 → 强烈做空
|score| < 0.3 → 观望
```
- 权重通过前向模拟验证，**不通过回测优化**（避免过拟合）

#### 3.2 删除 IndicatorCalculator.java
- 技术指标计算逻辑全部迁入 SignalEngine
- 删除 IndicatorCalculator.java
- 所有引用改为调用 SignalEngine

---

### Phase 4: 决策引擎升级 — MultiAgentOrchestrator
**目标：打破回音室，实现三模型信息隔离 + 否决权**

#### 4.1 新建 MultiAgentOrchestrator.java
替代当前 TradingDecisionEngine 中的 AI 调用逻辑

**三模型重新编排：**

| 角色 | 模型 | 频率 | 温度 | 输入数据（信息隔离） | 输出 |
|------|------|------|------|---------------------|------|
| MIO (Kimi 32K) | moonshot-v1-32k | 60s | 0.3 | 30分钟完整行情摘要 + OBI/CVD/LCI汇总 | 市场体制(TRENDING_UP/DOWN/RANGING/VOLATILE) |
| RRO (R1 推理) | deepseek-reasoner | 30s | 0.3 | 当前仓位 + 近5笔交易 + 异常信号 | 风险等级 + 最大允许仓位 + 理由 |
| TEO (V3 快速) | deepseek-chat | 15s | 0.1 | 技术指标 + 综合评分 + MIO体制 | JSON {action, side, confidence, trigger_price, reason} |

**否决权机制：**
- TEO 输出 → RRO 事后审核
- RRO 风险 = HIGH 或 TEO confidence < 0.6 → 否决
- 被否决的指令记入 AuditLogger

**输出消毒（Prompt Injection 防护）：**
- 提取纯 JSON，丢弃所有自由文本
- 只取白名单字段（action, side, confidence, trigger_price, reason）
- 不将原始文本传递给下游 agent

**AI 幻觉校验：**
- trigger_price 必须在当前价 ±2% 以内
- action 必须是白名单 [LONG, SHORT, HOLD, CLOSE]
- confidence 必须在 [0, 1] 范围
- JSON schema 严格校验

#### 4.2 重构 TradingDecisionEngine.java
- AI 调用逻辑移入 MultiAgentOrchestrator
- 保留：activeTrap 机制、快速检查、执行逻辑
- 集成 SignalEngine 的综合评分（替代纯 RSI 判断）
- 集成 MultiAgentOrchestrator（替代直接调用 OpenClawGatewayClient）
- **修复竞态条件**：`tickCount`, `peakROE`, `trailingActive`, `ticksSinceLastClose` 改为 volatile

#### 4.3 修复 OpenClawGatewayClient.java
- **修复平台兼容性**：检测 OS，Linux/Mac 用 `/bin/sh -c`，Windows 用 `cmd.exe /c`
- 添加调用超时 + 重试（最多2次）
- 添加模型版本追踪（记录每次调用的 agent 版本）
- 修复 prompt injection：对 prompt 内容转义处理
- 修复 shell 元字符注入漏洞
- 所有调用结果记入 AuditLogger

#### 4.4 合并 IntelligenceBoard.java 到 MultiAgentOrchestrator
- IntelligenceBoard 的 AtomicReference 功能并入 MultiAgentOrchestrator
- MultiAgentOrchestrator 内部维护 MIO/RRO 的最新输出
- 删除独立的 IntelligenceBoard.java

---

### Phase 5: 执行层升级 — OrderManager
**目标：原子化订单 + 成交价追踪 + 滑点监控**

#### 5.1 新建 OrderManager.java
从 BinanceRealAccount 中提取订单管理逻辑：

**原子仓位状态机：**
- ReentrantLock 保护开仓/平仓操作
- 开仓 + 止损设置事务化（一个失败则全部回滚）
- 非阻塞 tryLock（拿不到锁直接放弃，防止阻塞）

**成交价追踪：**
- 从 Binance 响应解析 `avgPrice`（真实成交均价）
- 不再使用 WebSocket 价格作为入场价
- 本地 entryPrice 用真实 fill price 更新

**滑点监控：**
- `slippage = |fillPrice - expectedPrice| / expectedPrice`
- 滑点 > 0.5% → 警告 + 增加5个 tick 冷却
- 滑点 > 1.0% → 触发 RiskManager 紧急审查

**LIMIT 单支持：**
- 默认使用 LIMIT 单（价格 = 当前价 ±0.1%）
- LIMIT 单 5 秒未成交 → 自动取消 + 转 MARKET 单
- **LIMIT → MARKET 互斥锁**：取消 LIMIT 和发送 MARKET 用同一把锁 + 状态检查，防止双倍仓位
- 减少被 MEV/夹子攻击的风险

#### 5.2 简化 BinanceRealAccount.java
- 保留：API 通信（sendOrder, postPrivate, signHMAC）
- 移出：仓位状态管理 → OrderManager
- 保留：setLeverage, setMarginType, cancelAllOpenOrders
- 成为纯 REST API 通信层

---

### Phase 6: 快通道信号系统
**目标：毫秒级纯量化通道，不经 AI，提升交易频率 5-10 倍**

#### 6.1 在 TradingDecisionEngine 中新增快通道
**触发条件（两个条件 AND 即可，放宽阈值）：**
- |OBI| > 0.3（订单簿显著失衡） + CVD 方向与 OBI 一致
- **或** |OBI| > 0.3 + 价格突破 Bollinger Band
- **或** CVD 方向一致 + 价格突破 Bollinger Band
- （阈值可通过 Config.java 配置，前向验证后调整）

**执行参数：**
- 仓位：基础仓位的 30%（小仓试探）
- 杠杆：由 DynamicLeverageEngine 计算（但 cap 到 15x）
- 止盈：1% ROE
- 止损：0.5% ROE
- 最大持仓：60 秒

**与慢通道(AI)的关系：**
- 快通道和慢通道可以共存（不同仓位）
- 快通道仓位 + 慢通道仓位 不能超过 RiskManager 总暴露限额
- 快通道优先级低于 RiskManager（任何风控否决优先）

---

### Phase 7: RiskManager 增强 + DynamicLeverageEngine/PyramidManager 升级
**目标：Kelly 公式动态仓位 + 综合评分驱动杠杆**

#### 7.1 RiskManager 增强
- 新增 Kelly 公式动态仓位计算：
  ```
  kelly = winRate - (1 - winRate) / avgWinLoss
  kelly = clamp(kelly, 0, 0.25)  // 半 Kelly，上限 25%
  volAdjust = BASE_VOL / currentVol  // 高波动缩仓
  positionSize = kelly * volAdjust * walletBalance
  ```
- **冷启动策略**：前 50 笔交易用固定 3% 仓位，期间累积真实 winRate 和 avgWinLoss
- 第 51 笔起切换 Kelly 公式，且每 20 笔重新计算一次历史胜率
- winRate/avgWinLoss 持久化到审计日志，重启后可恢复
- 新增频率限制：最小间隔30秒、每小时≤20笔、每日≤100笔

#### 7.2 DynamicLeverageEngine 增强
- 接入 SignalEngine 综合评分（替代纯 RSI + volume）
- 综合评分 > 0.8 → 额外 +5x 加成
- FundingRate 极端 → 强制降杠杆到 5x

#### 7.3 PyramidManager 增强
- 加仓前检查 SignalEngine 综合评分（要求 > 0.5）
- 加仓触发条件加入 OBI 方向确认

---

### Phase 8: 模拟模式 + 清理 + 集成
**目标：安全验证 + 删除废弃代码 + 确保所有层正确串联**

#### 8.1 改造 FuturesVirtualAccount → SimulationMode
- **不删除 FuturesVirtualAccount**，改名为 `SimulationAccount.java`
- 实现与 BinanceRealAccount 相同的接口（openPosition/closePosition/sendOrder）
- 接收真实行情（从 MarketDataHub），但下单只记录不发送
- 模拟成交：MARKET 单用当前价 + 随机滑点(0-0.1%)，LIMIT 单检查价格匹配
- 模拟手续费：0.04% taker, 0.02% maker
- 所有模拟交易记入 AuditLogger（type=SIMULATED_ORDER）
- 通过 `Config.TRADING_MODE` 切换：`SIMULATION` 用 SimulationAccount，`LIVE` 用 BinanceRealAccount

#### 8.2 前向验证流程
```
第 1-2 周：SIMULATION 模式
  - 真实行情 + 真实 AI 决策 + 模拟下单
  - 每日检查 AuditLogger：胜率、最大回撤、信号质量
  - 观察快通道触发频率是否合理
  - 观察 AI 决策延迟是否影响入场

第 3 周：LIVE 模式 + 最小仓位
  - INITIAL_CAPITAL 设为最低值（如 20 USDT）
  - Kelly 冷启动（固定 3% 仓位）
  - 验证实际滑点、成交价追踪是否正常

第 4 周+：正常运行
  - 根据前 3 周数据微调 Config 中的权重/阈值
  - Kelly 公式接管仓位管理
```

#### 8.3 删除废弃文件
- 删除 `DirectKimiClient.java`（硬编码Key + 已被 OpenClawGatewayClient 替代）
- 删除 `config.properties`（API Key 明文）
- 删除 `BinanceRealTrader.java`（未使用的 Spot 测试类）
- 删除 `VirtualAccount.java`（Spot 模拟，不需要）
- ~~删除 `FuturesVirtualAccount.java`~~ → 改造为 SimulationAccount.java
- 删除 `TestDestruction.java`（demo 测试代码）
- 删除 `IntelligenceBoard.java`（已合并入 MultiAgentOrchestrator）

#### 8.4 更新 .gitignore
```
*.properties
.env
logs/
kill_switch.flag
```

#### 8.5 更新 pom.xml
- 如需要新依赖（如 JSON Schema 校验库）则添加

#### 8.6 Main.java 最终整合
- 启动顺序：Config → AuditLogger → MarketDataHub → SignalEngine → RiskManager → OrderManager → MultiAgentOrchestrator → TradingDecisionEngine → 就绪
- 根据 Config.TRADING_MODE 选择 SimulationAccount 或 BinanceRealAccount
- 所有组件通过 MarketDataHub 获取数据
- 所有交易通过 OrderManager 执行
- 所有决策通过 AuditLogger 记录
- 启动时打印运行模式（SIMULATION / LIVE）到 stderr + AuditLogger

---

## 文件清单总览

### 新建文件（8个）
| 文件 | 职责 | Phase |
|------|------|-------|
| Config.java | 环境变量 + 配置管理 + 权重/阈值 | 1 |
| AuditLogger.java | JSON Lines 审计日志 + 告警 | 1 |
| TelegramNotifier.java | 通过 OpenClaw 推送 Telegram 通知 | 1 |
| MarketDataHub.java | 多流 WebSocket + 心跳 + 数据分发 | 2 |
| SignalEngine.java | OBI/CVD/LCI/Funding/综合评分 | 3 |
| MultiAgentOrchestrator.java | 三模型编排 + 消毒 + 否决权 + 温度控制 | 4 |
| OrderManager.java | 原子订单 + 成交价追踪 + 滑点 + LIMIT互斥锁 | 5 |
| SimulationAccount.java | 模拟交易（改造自 FuturesVirtualAccount） | 8 |

### 重构文件（7个）
| 文件 | 改动 | Phase |
|------|------|-------|
| Main.java | 简化为启动入口 + 模式切换 | 2, 8 |
| TradingDecisionEngine.java | 接入新引擎 + 快通道 + 修复竞态变量 | 4, 6 |
| BinanceRealAccount.java | 拆分订单逻辑到 OrderManager | 5 |
| OpenClawGatewayClient.java | 跨平台兼容 + 超时重试 + 消毒 | 4 |
| RiskManager.java | + Kelly仓位(冷启动) + 频率限制 | 7 |
| DynamicLeverageEngine.java | + 综合评分驱动 | 7 |
| PyramidManager.java | + OBI确认 + 修复层数off-by-one | 7 |

### 删除文件（6个）
| 文件 | 原因 | Phase |
|------|------|-------|
| config.properties | 明文 Key | 1 |
| DirectKimiClient.java | 硬编码Key + 废弃 | 1 |
| BinanceRealTrader.java | 未使用的 Spot 类 | 8 |
| VirtualAccount.java | Spot 模拟，不需要 | 8 |
| TestDestruction.java | demo 代码 | 8 |
| IntelligenceBoard.java | 合并入 MultiAgentOrchestrator | 4 |
| IndicatorCalculator.java | 合并入 SignalEngine | 3 |

---

## 数据流总览

```
Binance WebSocket (5 streams)
        │
        ▼
   MarketDataHub ──────────────────────────────┐
   │ kline    │ depth  │ aggTrade │ forceOrder │ markPrice
   ▼          ▼        ▼          ▼            ▼
   SignalEngine (纯计算)
   ├─ RSI, SMA, BB, ATR (来自 kline)
   ├─ OBI (来自 depth)
   ├─ CVD (来自 aggTrade)
   ├─ LCI (来自 forceOrder)
   ├─ Funding (来自 markPrice)
   └─ Composite Score (综合评分)
          │
          ├──────────── 快通道 ─────────────────┐
          │  (|OBI|>0.3 + CVD一致 或其他组合)     │
          │                                     │
          ▼                                     ▼
   MultiAgentOrchestrator ─── 慢通道 ──→ TradingDecisionEngine
   ├─ MIO (Kimi): 宏观体制                ├─ activeTrap 机制
   ├─ RRO (R1): 风险审核 + 否决            ├─ DynamicLeverageEngine
   └─ TEO (V3): 执行决策                   ├─ PyramidManager
          │                                └─ LiquidationHunter
          │       幻觉校验 + 消毒
          ▼
   RiskManager ── canTrade() 门禁
          │
          ▼
   OrderManager ── 原子执行
   ├─ LIMIT 单 (优先)
   ├─ 成交价追踪 (avgPrice)
   ├─ 滑点监控
   └─ 止损原子设置
          │
          ▼
   BinanceRealAccount / SimulationAccount ── 按 Config.TRADING_MODE 切换
          │
          ▼
   AuditLogger ── 全链路记录 + 告警
```

---

## 已知风险与缓解措施

| 风险 | 严重度 | 缓解措施 |
|------|--------|---------|
| AI 决策延迟 20-40s，入场价已不利 | 中 | 快通道补偿 + TEO trigger_price ±2% 校验 |
| 综合评分权重未经验证 | 中 | 权重可配置 + 前向模拟验证 + 不回测调优 |
| OBI 易被 spoofing 操纵 | 中 | CVD 交叉验证 + OBI 权重不宜过高 |
| LIMIT 转 MARKET 可能双倍仓位 | 高 | 互斥锁 + 状态检查（Phase 5 解决） |
| Kelly 冷启动期无历史数据 | 低 | 前 50 笔固定 3% 仓位 |
| 单币种 SOLUSDT 低波动期空转 | 低 | 暂不解决，后续版本扩展多币种 |

---

## 关于回测的说明

**本方案不使用回测来优化参数。** 原因：

1. LLM 决策无法回测（模型版本会变、同 prompt 不保证同输出）
2. 量化参数（权重、阈值）通过回测优化会过拟合（backtest overfitting）
3. 正确做法是**前向验证**（forward testing）：
   - 参数基于金融常识 / 业界经验设定
   - 用实时行情 + SimulationAccount 跑 2-4 周
   - 观察真实表现后微调
   - 微调后再跑 1-2 周验证
   - 确认稳定后切换 LIVE 模式
