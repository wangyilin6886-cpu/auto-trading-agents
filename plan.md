# 完整重构方案：RESTRUCTURE_PLAN.md 全量实现 + 动态杠杆/浮盈加仓/爆仓猎杀

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
- [ ] **AuditLogger.java** — JSON Lines 审计日志
- [ ] **MultiAgentOrchestrator.java** — 三模型重编排 + 输出消毒 + 否决权
- [ ] **OrderManager.java** — 原子化订单 + 成交价追踪 + 滑点监控
- [ ] **AI幻觉校验** — trigger_price ±2%、confidence校验、JSON schema
- [ ] **动态仓位(Kelly)** — Kelly公式 + 波动率调整
- [ ] **Prompt Injection防护** — agent输出消毒
- [ ] **快通道信号系统** — 纯量化毫秒级通道
- [ ] **清理** — 删除 config.properties、DirectKimiClient.java、更新 .gitignore

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

#### 1.2 新建 AuditLogger.java
- JSON Lines 格式，每行一条记录
- 记录类型：AI_DECISION, ORDER, RISK_CHECK, SIGNAL, KILL_SWITCH, ERROR
- 文件路径：`logs/trading_YYYY-MM-DD.jsonl`（按天滚动）
- 线程安全：synchronized write 或 BlockingQueue + 后台写入线程
- 所有现有模块接入 AuditLogger

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

**综合评分公式：**
```
score = 0.25*OBI + 0.20*CVD_slope + 0.15*RSI + 0.15*LCI + 0.10*BB + 0.10*funding + 0.05*vol_surge
score > 0.6 → 强烈做多
score < -0.6 → 强烈做空
|score| < 0.3 → 观望
```

#### 3.2 IndicatorCalculator.java 处理
- 技术指标计算逻辑迁入 SignalEngine
- IndicatorCalculator 保留为 SignalEngine 的内部组件或删除
- 所有引用改为调用 SignalEngine

---

### Phase 4: 决策引擎升级 — MultiAgentOrchestrator
**目标：打破回音室，实现三模型信息隔离 + 否决权**

#### 4.1 新建 MultiAgentOrchestrator.java
替代当前 TradingDecisionEngine 中的 AI 调用逻辑

**三模型重新编排：**

| 角色 | 模型 | 频率 | 输入数据（信息隔离） | 输出 |
|------|------|------|---------------------|------|
| MIO (Kimi 32K) | moonshot-v1-32k | 60s | 30分钟完整行情摘要 + OBI/CVD/LCI汇总 | 市场体制(TRENDING_UP/DOWN/RANGING/VOLATILE) |
| RRO (R1 推理) | deepseek-reasoner | 30s | 当前仓位 + 近5笔交易 + 异常信号 | 风险等级 + 最大允许仓位 + 理由 |
| TEO (V3 快速) | deepseek-chat | 15s | 技术指标 + 综合评分 + MIO体制 | JSON {action, side, confidence, trigger_price, reason} |

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

#### 4.3 增强 OpenClawGatewayClient.java
- 添加调用超时 + 重试（最多2次）
- 添加模型版本追踪（记录每次调用的 agent 版本）
- 修复 prompt injection：对 prompt 内容转义处理
- 所有调用结果记入 AuditLogger

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
**触发条件（三者同时满足）：**
- |OBI| > 0.4（订单簿极端失衡）
- CVD 方向与 OBI 一致（非 spoofing 确认）
- 价格突破 Bollinger Band（上轨或下轨）

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
- 新增频率限制：最小间隔30秒、每小时≤20笔、每日≤100笔

#### 7.2 DynamicLeverageEngine 增强
- 接入 SignalEngine 综合评分（替代纯 RSI + volume）
- 综合评分 > 0.8 → 额外 +5x 加成
- FundingRate 极端 → 强制降杠杆到 5x

#### 7.3 PyramidManager 增强
- 加仓前检查 SignalEngine 综合评分（要求 > 0.5）
- 加仓触发条件加入 OBI 方向确认

---

### Phase 8: 清理 + 集成测试
**目标：删除废弃代码，确保所有层正确串联**

#### 8.1 删除废弃文件
- 删除 `DirectKimiClient.java`（硬编码Key + 已被 OpenClawGatewayClient 替代）
- 删除 `config.properties`（API Key 明文）
- 删除 `BinanceRealTrader.java`（未使用的 Spot 测试类）
- 删除 `VirtualAccount.java`（未使用的模拟类）
- 删除 `FuturesVirtualAccount.java`（已被真实账户替代）
- 删除 `TestDestruction.java`（demo 测试代码）

#### 8.2 更新 .gitignore
```
*.properties
.env
logs/
kill_switch.flag
```

#### 8.3 更新 pom.xml
- 如需要新依赖（如 JSON Schema 校验库）则添加

#### 8.4 Main.java 最终整合
- 启动顺序：Config → AuditLogger → MarketDataHub → SignalEngine → RiskManager → OrderManager → MultiAgentOrchestrator → TradingDecisionEngine → 就绪
- 所有组件通过 MarketDataHub 获取数据
- 所有交易通过 OrderManager 执行
- 所有决策通过 AuditLogger 记录

---

## 文件清单总览

### 新建文件（6个）
| 文件 | 职责 | Phase |
|------|------|-------|
| Config.java | 环境变量加载 + 配置管理 | 1 |
| AuditLogger.java | JSON Lines 审计日志 | 1 |
| MarketDataHub.java | 多流 WebSocket + 心跳 + 数据分发 | 2 |
| SignalEngine.java | OBI/CVD/LCI/Funding/综合评分 | 3 |
| MultiAgentOrchestrator.java | 三模型编排 + 消毒 + 否决权 | 4 |
| OrderManager.java | 原子订单 + 成交价追踪 + 滑点 | 5 |

### 重构文件（6个）
| 文件 | 改动 | Phase |
|------|------|-------|
| Main.java | 简化为启动入口 | 2, 8 |
| TradingDecisionEngine.java | 接入新引擎 + 快通道 | 4, 6 |
| BinanceRealAccount.java | 拆分订单逻辑到 OrderManager | 5 |
| RiskManager.java | + Kelly仓位 + 频率限制 | 7 |
| DynamicLeverageEngine.java | + 综合评分驱动 | 7 |
| PyramidManager.java | + OBI确认 | 7 |

### 保留不变（2个）
| 文件 | 原因 |
|------|------|
| IntelligenceBoard.java | 已线程安全，仍作为AI输出板使用 |
| OpenClawGatewayClient.java | Phase 4 增强但保留 |

### 删除文件（6个）
| 文件 | 原因 | Phase |
|------|------|-------|
| config.properties | 明文 Key | 1 |
| DirectKimiClient.java | 硬编码Key + 废弃 | 1 |
| BinanceRealTrader.java | 未使用 | 8 |
| VirtualAccount.java | 未使用 | 8 |
| FuturesVirtualAccount.java | 已被真实账户替代 | 8 |
| TestDestruction.java | demo 代码 | 8 |

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
          │  (|OBI|>0.4 + CVD一致 + BB突破)      │
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
   BinanceRealAccount ── REST API
          │
          ▼
   AuditLogger ── 全链路记录
```
