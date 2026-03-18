# SOL 高频交易 Agent 全面重构方案

## 一、当前致命问题总览（你的风险研究 + 我的代码审计）

### 🔴 你的风险研究中已确认的问题
| # | 风险 | 代码位置 | 状态 |
|---|------|---------|------|
| ⑨ | API Key 明文暴露 | Main.java:17-18, DirectKimiClient:12, config.properties, OpenClaw JSON | ❌ |
| ⑧ | 成交价不同步（滑点盲区） | BinanceRealAccount:88 用 WebSocket 价格而非实际 fill price | ❌ |
| ③ | AI 幻觉无校验 | TradingDecisionEngine:120-127 零验证 | ❌ |
| ⑯ | Kill Switch 不独立 | BinanceRealAccount:48-55 跑在 agent 进程内 | ❌ |
| ⑫ | Prompt Injection | TradingDecisionEngine:108-116 agent 输出直接拼入下游 prompt | ❌ |
| ⑥ | 多 Agent 回音室 | 三个 agent 同模型族 + 同数据 + 无独立验证 | ❌ |
| ② | 时间戳幻影信号 | Main:37-39 只有 5s 过滤，LLM 处理期间价格已过期 | ⚠️ |
| ⑤ | 模型更新无感知 | OpenClawGatewayClient 无版本追踪 | ❌ |
| ⑦ | 全部 MARKET 单（被夹） | BinanceRealAccount:182 | ⚠️ |
| ⑭ | 零审计日志 | 全局无日志文件 | ❌ |
| ⑰ | 固定仓位无动态调整 | TradingDecisionEngine:10-11 | ❌ |

### 🔴 我额外发现的致命 Bug（你的风险研究未覆盖）
| # | 新发现 | 严重度 | 说明 |
|---|--------|--------|------|
| A | **activeTrap 竞态条件** | 致命 | WebSocket 线程读 activeTrap，AI 线程写 activeTrap，无同步。可导致幽灵交易或丢失信号 |
| B | **positionSide 内存可见性** | 致命 | 非 volatile 字段，Java 内存模型不保证线程间可见。可能同时开两个方向的仓位 |
| C | **walletBalance 竞态** | 致命 | 两个线程同时读余额都看到足够，同时开仓 → 超杠杆 |
| D | **强平只清本地状态** | 致命 | checkLiquidation() 不发 close order 到交易所！本地显示空仓，实际仓位还在亏钱 |
| E | **ArrayList 非线程安全** | 致命 | IndicatorCalculator 的 prices/volumes 从 WebSocket 线程写，从计算线程读。会抛 ConcurrentModificationException 导致程序崩溃 |
| F | **所有 catch 块为空** | 高危 | Main.java:59, BinanceRealAccount 多处。API 失败 → 静默吞掉 → 状态不一致 |
| G | **止损单非原子设置** | 高危 | openPosition 成功后才设止损，中间网络断了 → 裸仓运行 |
| H | **重连无指数退避** | 中危 | 固定 5 秒重连，Binance 如果抖动会被 ban IP |
| I | **无心跳监控** | 中危 | WebSocket 挂了但没触发 onClose → 僵尸连接，收不到行情但以为还活着 |
| J | **Kill Switch 单向不可恢复** | 中危 | 一旦触发永远无法恢复，除非重启 |

---

## 二、三模型特性分析与角色重新定义

### 模型能力矩阵
| 模型 | 速度 | 推理深度 | 上下文 | 最佳用途 |
|------|------|---------|--------|---------|
| DeepSeek V3 (deepseek-chat) | ⚡ 快 | 中等 | 标准 | 快速结构化输出、执行决策 |
| DeepSeek R1 (deepseek-reasoner) | 🐢 慢 | 🧠 深度推理 | 标准 | 复杂风险推理、异常检测 |
| Kimi (moonshot-v1-32k) | 中等 | 中等 | 📚 32K | 大量数据摘要、宏观分析 |

### 重新定义角色（打破回音室）

**CEO Kimi → 市场情报官（MIO）**
- 利用 32K 上下文优势，输入最近 30 分钟的完整行情摘要
- 输出：市场体制判断（TRENDING_UP / TRENDING_DOWN / RANGING / VOLATILE）
- 调用频率：每 60 秒（宏观不需要高频）
- 独立数据源：接收已计算的 OBI、CVD、爆仓统计汇总

**CRO R1 → 风险推理官（RRO）**
- 利用深度推理优势，进行多步骤风险分析
- 输出：风险等级 + 最大允许仓位 + 理由
- 调用频率：每 30 秒，或当检测到异常信号时紧急调用
- 独立判断：对 V3 的决策进行事后审核（而非事前提供数据被 V3 盲从）

**Trader V3 → 战术执行官（TEO）**
- 利用速度优势，快速输出结构化交易指令
- 输出：严格 JSON {action, side, confidence, trigger_price, reason}
- 调用频率：每 15 秒
- 约束：必须在 R1 给出的风险框架内操作

### 打破回音室的关键设计
1. **信息隔离**：三个 agent 看到不同粒度的数据
2. **否决权机制**：R1 可以否决 V3 的决策（confidence < threshold 或 risk = HIGH）
3. **输出消毒**：agent 输出经过严格 JSON schema 校验后才传递，防止 prompt injection
4. **独立温度**：V3 用 temperature=0.1（确定性执行），R1 用 0.3（探索性推理）

---

## 三、新增数据源与信号系统

### WebSocket 多流订阅（单连接，合流模式）
```
wss://fstream.binance.com/stream?streams=
  solusdt@kline_1m/
  solusdt@depth@100ms/
  solusdt@aggTrade/
  solusdt@forceOrder/
  solusdt@markPrice@1s
```

### 新增信号指标

#### 1. 订单簿失衡 (OBI - Order Book Imbalance)
```
OBI = (bid_volume_top10 - ask_volume_top10) / (bid_volume_top10 + ask_volume_top10)
```
- 范围：[-1, +1]
- OBI > 0.3 → 买压强，看多信号
- OBI < -0.3 → 卖压强，看空信号
- 100ms 更新频率，配合 aggTrade 验证（防止挂单欺骗/spoofing）

#### 2. 累积量能 Delta (CVD - Cumulative Volume Delta)
```
每笔 aggTrade: 如果 m=false（主动买），CVD += quantity；否则 CVD -= quantity
滚动窗口：1 分钟和 5 分钟
```
- CVD 持续上升 + 价格上升 → 真实上涨趋势
- CVD 下降 + 价格上升 → 虚假上涨（背离，反转信号）
- 比单纯 RSI 更早发现趋势反转

#### 3. 爆仓强度指标 (LCI - Liquidation Cascade Intensity)
```
LCI = sum(liquidation_amount) / rolling_1min_window
```
- LCI 突然飙升 → 级联爆仓正在发生
- 多头爆仓级联 → 可能触底（做多机会）
- 空头爆仓级联 → 可能见顶（做空机会）
- **关键**：作为确认信号而非主信号（单独 alpha 已衰减）

#### 4. 资金费率情绪 (Funding Sentiment)
```
funding_rate from markPrice stream
```
- 正费率 > 0.01% → 多头过度拥挤，空头有优势
- 负费率 < -0.01% → 空头过度拥挤，多头有优势
- 费率极端时（>0.05%）不开新仓

#### 5. 综合信号评分
```java
double score =
    w1 * normalize(OBI) +        // 0.25
    w2 * normalize(CVD_slope) +   // 0.20
    w3 * normalize(RSI_signal) +  // 0.15
    w4 * normalize(LCI_signal) +  // 0.15
    w5 * normalize(BB_position) + // 0.10
    w6 * normalize(funding) +     // 0.10
    w7 * normalize(vol_surge);    // 0.05

// score > 0.6 → 强烈做多
// score < -0.6 → 强烈做空
// |score| < 0.3 → 观望
```

---

## 四、完整架构设计（6 层）

```
┌─────────────────────────────────────────────────────┐
│                    Layer 6: AUDIT                     │
│  AuditLogger - 每笔决策/交易/异常全部持久化到文件      │
├─────────────────────────────────────────────────────┤
│                 Layer 5: RISK GUARD                   │
│  RiskManager - 独立风控层，可否决任何交易指令           │
│  ├─ AI 输出校验（幻觉防护）                           │
│  ├─ 仓位限制（动态 Kelly 公式）                       │
│  ├─ 独立 Kill Switch（文件信号机制）                   │
│  ├─ 冷却期管理                                       │
│  └─ 最大日亏损限制                                    │
├─────────────────────────────────────────────────────┤
│              Layer 4: DECISION ENGINE                 │
│  MultiAgentOrchestrator                              │
│  ├─ MIO (Kimi 32K): 宏观体制，60s 周期               │
│  ├─ RRO (R1 推理): 风险审核，30s 周期                 │
│  ├─ TEO (V3 快速): 执行决策，15s 周期                 │
│  └─ 否决权 + 输出消毒 + Schema 校验                   │
├─────────────────────────────────────────────────────┤
│              Layer 3: SIGNAL ENGINE                   │
│  SignalEngine - 纯计算层                              │
│  ├─ 技术指标: RSI, SMA, Bollinger                    │
│  ├─ 订单流: OBI, CVD, Aggressor Ratio                │
│  ├─ 爆仓: LCI (Liquidation Cascade Intensity)        │
│  ├─ 情绪: Funding Rate Sentiment                     │
│  └─ 综合评分: Composite Signal Score                  │
├─────────────────────────────────────────────────────┤
│               Layer 2: EXECUTION                      │
│  OrderManager - 原子化订单管理                        │
│  ├─ 成交价追踪（解析 avgPrice）                       │
│  ├─ 原子仓位状态机（synchronized）                    │
│  ├─ 止损原子设置（开仓+止损事务化）                    │
│  ├─ 滑点监控与报告                                    │
│  └─ LIMIT 单支持（降低被夹风险）                      │
├─────────────────────────────────────────────────────┤
│                Layer 1: MARKET DATA                   │
│  MarketDataHub - 线程安全的数据中心                    │
│  ├─ kline_1m (K线)                                   │
│  ├─ depth@100ms (订单簿 Top 10)                      │
│  ├─ aggTrade (逐笔成交)                              │
│  ├─ forceOrder (全网爆仓)                             │
│  ├─ markPrice@1s (标记价/资金费率)                    │
│  └─ 心跳监控 + 指数退避重连                           │
└─────────────────────────────────────────────────────┘
```

---

## 五、风控体系详细设计

### 5.1 AI 幻觉防护（对应风险 ③）
```java
// 硬性校验规则
boolean validateAIOutput(TradeSignal signal, double currentPrice) {
    // 1. trigger_price 必须在当前价 ±2% 以内
    if (Math.abs(signal.triggerPrice - currentPrice) / currentPrice > 0.02) return false;

    // 2. action 必须是白名单值 [LONG, SHORT, HOLD, CLOSE]
    if (!ALLOWED_ACTIONS.contains(signal.action)) return false;

    // 3. confidence 必须在 [0, 1] 范围
    if (signal.confidence < 0 || signal.confidence > 1) return false;

    // 4. JSON schema 严格校验（防止格式异常）
    if (!matchesSchema(signal.rawJson)) return false;

    return true;
}
```

### 5.2 动态仓位管理（对应风险 ⑰）
```java
// Kelly 公式简化版 + 波动率调整
double calcPositionSize(double winRate, double avgWinLoss, double volatility) {
    double kelly = winRate - (1 - winRate) / avgWinLoss;
    kelly = Math.max(0, Math.min(kelly, 0.25)); // 上限 25%（半 Kelly）

    // 高波动时自动缩小仓位
    double volAdjust = BASE_VOLATILITY / volatility;
    volAdjust = Math.max(0.3, Math.min(volAdjust, 1.5));

    return kelly * volAdjust * walletBalance;
}
```

### 5.3 独立 Kill Switch（对应风险 ⑯）
```java
// 文件信号机制 - 即使 JVM 崩溃，外部脚本也可以写入
// kill_switch.flag 文件存在 → 停止一切交易
// 每次交易前检查：
boolean isKilled() {
    return new File("kill_switch.flag").exists()
        || walletBalance <= initialCapital * 0.80  // 20% 最大回撤
        || dailyLoss >= initialCapital * 0.10;     // 10% 日亏损限制
}
```

### 5.4 多级冷却机制
```
亏损后冷却：
  - 连亏 2 笔 → 冷却 60 秒
  - 连亏 3 笔 → 冷却 300 秒
  - 连亏 5 笔 → 触发 Kill Switch，需人工确认恢复

频率限制：
  - 最小交易间隔：30 秒
  - 每小时最多交易：20 笔
  - 每日最多交易：100 笔
```

### 5.5 Prompt Injection 防护（对应风险 ⑫）
```java
// Agent 输出消毒
String sanitizeAgentOutput(String raw) {
    // 1. 提取纯 JSON，丢弃所有自由文本
    JSONObject json = extractAndValidateJSON(raw);

    // 2. 只取白名单字段
    String strategy = json.optString("strategy", "UNKNOWN");
    if (!ALLOWED_STRATEGIES.contains(strategy)) strategy = "UNKNOWN";

    // 3. 不将原始文本传递给下游 agent
    return strategy; // 只传递结构化数据，不传原文
}
```

### 5.6 滑点防护（对应风险 ⑧）
```java
// 从 Binance 响应提取真实成交价
double extractFillPrice(String orderResponse) {
    JSONObject resp = new JSONObject(orderResponse);
    return resp.getDouble("avgPrice"); // 使用实际成交均价
}

// 滑点监控
double slippage = Math.abs(fillPrice - expectedPrice) / expectedPrice;
if (slippage > 0.005) { // 0.5% 滑点警告
    logger.warn("HIGH_SLIPPAGE", slippage);
    cooldownTicks += 5; // 增加冷却
}
```

---

## 六、并发安全修复

### 6.1 原子仓位状态机
```java
public class AtomicPositionManager {
    private final ReentrantLock positionLock = new ReentrantLock();
    private volatile String positionSide = "NONE";
    private volatile double entryPrice = 0;
    private volatile int positionSize = 0;

    public boolean tryOpenPosition(String side, double price, int qty) {
        if (!positionLock.tryLock()) return false; // 非阻塞，拿不到锁直接放弃
        try {
            if (!"NONE".equals(positionSide)) return false;
            // ... 发送订单 + 设置止损（原子化）
            // ... 只有全部成功才更新状态
            return true;
        } finally {
            positionLock.unlock();
        }
    }
}
```

### 6.2 线程安全指标计算
```java
// 用 CopyOnWriteArrayList 或 ConcurrentLinkedDeque 替代 ArrayList
private static final ConcurrentLinkedDeque<Double> prices = new ConcurrentLinkedDeque<>();
```

### 6.3 WebSocket 心跳 + 指数退避重连
```java
// 心跳检测：如果 30 秒没收到数据，主动断开重连
// 重连策略：2s → 4s → 8s → 16s → 32s（上限）
```

---

## 七、交易频率提升方案

### 当前问题：15 秒评估一次，经常半天不交易

### 解决方案：双通道信号系统

**快通道（毫秒级，纯量化）**
- 触发条件：OBI 极端 (|OBI| > 0.4) + CVD 方向一致 + 价格突破 Bollinger Band
- 不经过 AI 决策，直接执行
- 仓位：小仓（基础仓位的 30%）
- 目标：捕捉订单流驱动的短期脉冲

**慢通道（15 秒级，AI 决策）**
- 保持现有 AI 决策流程但加强
- 仓位：标准仓位
- 综合评分 > 0.6 才触发
- 目标：捕捉趋势性机会

**触发频率预估**
- 快通道：SOL 波动期每小时可能触发 5-15 次
- 慢通道：每小时 2-5 次
- 总计：比现在提升 5-10 倍交易频率

---

## 八、API Key 安全化方案（对应风险 ⑨）

```java
// 所有 Key 改为环境变量读取
String apiKey = System.getenv("BINANCE_API_KEY");
String secretKey = System.getenv("BINANCE_SECRET_KEY");

if (apiKey == null || secretKey == null) {
    throw new RuntimeException("Missing BINANCE API credentials in environment variables");
}

// config.properties 中的 key 删除
// .gitignore 添加 *.properties, .env
// 提醒用户：已经 push 到 git 的 key 必须立即吊销并重新生成
```

---

## 九、审计日志系统（对应风险 ⑭）

```
日志格式（JSON Lines，每行一条）：
{"ts":"2026-03-18T12:00:00Z","type":"AI_DECISION","agent":"V3","input":{...},"output":{...},"validated":true}
{"ts":"2026-03-18T12:00:01Z","type":"ORDER","side":"BUY","qty":10,"expected_price":150.25,"fill_price":150.30,"slippage":0.00033}
{"ts":"2026-03-18T12:00:01Z","type":"RISK_CHECK","kill_switch":false,"daily_loss":-2.5,"position_size":10}
{"ts":"2026-03-18T12:00:05Z","type":"SIGNAL","obi":0.35,"cvd":120.5,"rsi":65.2,"lci":0,"composite_score":0.45}

文件：logs/trading_YYYY-MM-DD.jsonl（按天滚动）
```

---

## 十、文件重构清单

### 新建文件
| 文件 | 职责 |
|------|------|
| `MarketDataHub.java` | 多流 WebSocket 管理 + 线程安全数据存储 |
| `SignalEngine.java` | 所有信号计算（OBI, CVD, LCI, Funding, 综合评分）|
| `RiskManager.java` | 独立风控层（Kill Switch、幻觉校验、仓位管理）|
| `OrderManager.java` | 原子化订单管理 + 成交价追踪 + 滑点监控 |
| `AuditLogger.java` | JSON Lines 审计日志 |
| `MultiAgentOrchestrator.java` | 三模型协调 + 输出消毒 + 否决权 |
| `AtomicPositionManager.java` | 线程安全仓位状态机 |
| `Config.java` | 环境变量加载 + 配置管理 |

### 重构文件
| 文件 | 改动 |
|------|------|
| `Main.java` | 简化为启动入口，移除业务逻辑 |
| `TradingDecisionEngine.java` | 拆分为 SignalEngine + MultiAgentOrchestrator |
| `BinanceRealAccount.java` | 拆分为 OrderManager + AtomicPositionManager |
| `IndicatorCalculator.java` | 合并入 SignalEngine，修复线程安全 |
| `IntelligenceBoard.java` | 合并入 MultiAgentOrchestrator |
| `OpenClawGatewayClient.java` | 添加超时、重试、版本追踪 |

### 删除文件
| 文件 | 原因 |
|------|------|
| `config.properties` | 明文 Key 必须删除 |
| `DirectKimiClient.java` | 通过 OpenClaw 统一调用，不再直连 |

---

## 十一、实施顺序（风险驱动优先级）

### Phase 1: 止血（修复致命 Bug）
1. Config.java - API Key 环境变量化
2. AtomicPositionManager.java - 修复所有竞态条件
3. 修复 checkLiquidation 实际发送 close order
4. 修复 IndicatorCalculator 线程安全
5. 修复所有空 catch 块

### Phase 2: 数据基础设施
6. MarketDataHub.java - 多流 WebSocket + 心跳 + 重连
7. SignalEngine.java - OBI, CVD, LCI, Funding, 综合评分

### Phase 3: 风控体系
8. RiskManager.java - Kill Switch + 幻觉校验 + 动态仓位
9. AuditLogger.java - 完整日志

### Phase 4: 决策引擎升级
10. MultiAgentOrchestrator.java - 三模型重新编排 + 消毒 + 否决权
11. OrderManager.java - 成交价追踪 + 滑点监控

### Phase 5: 交易频率提升
12. 快通道信号系统（纯量化，毫秒级）
13. 双通道整合测试
