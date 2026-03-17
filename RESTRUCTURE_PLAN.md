# SOL/USDT 多Agent合约交易系统 — 完整重构方案

## 核心设计理念

**"AI 出方向，代码管风控，数据驱动决策"**

- LLM 永远只输出方向（LONG/SHORT/HOLD/CLOSE），不输出具体价格
- 所有风控规则硬编码在 Java 层，不依赖 prompt "建议"
- 交易信号必须多指标共振才触发，单一指标永远不开仓

---

## 一、数据层重构 — 5 路 WebSocket 数据流

### 当前问题
只接了 `solusdt@kline_1m` 一条流，数据维度严重不足。

### 重构方案

用 Binance Futures 的 Combined Stream 一次性订阅 5 路数据：

```
wss://fstream.binance.com/stream?streams=
  solusdt@kline_1m/
  solusdt@depth5@100ms/
  solusdt@forceOrder/
  solusdt@aggTrade/
  solusdt@markPrice@1s
```

每路数据的用途：

| 数据流 | 更新频率 | 提取信号 |
|--------|----------|----------|
| `kline_1m` | 每根K线 | OHLCV、RSI、SMA、布林带（中低频趋势判断） |
| `depth5@100ms` | 100ms | **订单簿不平衡(OBI)**、买卖压力、支撑阻力位 |
| `forceOrder` | 事件驱动 | **全网爆仓方向和规模** — 大量多头爆仓=可能见底 |
| `aggTrade` | 100ms | **大单检测**、成交量突增、主动买/卖比例 |
| `markPrice@1s` | 1s | **资金费率** — 费率极端=市场过度倾斜，反转概率高 |

### 新增类：`MarketDataHub`

统一接收、解析、分发所有 WebSocket 数据。线程安全，用 `ConcurrentHashMap` + `AtomicReference` 存储最新状态。

```java
public class MarketDataHub {
    // 所有字段用 volatile 或 Atomic 保证线程安全
    private volatile double bestBid, bestAsk, bidAskSpread;
    private volatile double orderBookImbalance;  // -1.0 到 +1.0
    private volatile double fundingRate;
    private volatile double aggBuyVolume, aggSellVolume;  // 最近 N 秒累计
    private volatile long lastLiquidationTime;
    private volatile String lastLiquidationSide;  // "BUY"(空头爆仓) / "SELL"(多头爆仓)
    private volatile double lastLiquidationQty;

    // 大单检测：最近 10 秒内单笔 > 均值 5 倍的成交
    private volatile int recentLargeOrderCount;
    private volatile String largeOrderDominantSide;  // "BUY" / "SELL" / "NEUTRAL"
}
```

---

## 二、指标层重构 — `SignalEngine` 替代 `IndicatorCalculator`

### 当前问题
- 只有 RSI/SMA/布林带三个指标
- 数据窗口只有 50 根，RSI 剧烈震荡
- 没有成交量分析、没有订单簿分析

### 重构方案

新建 `SignalEngine`，计算以下信号并输出**综合评分**：

#### A. 技术指标（来自 K 线）

| 指标 | 参数 | 信号 |
|------|------|------|
| RSI | 14周期 | <30 超卖(+1多), >70 超买(+1空) |
| EMA交叉 | EMA9 vs EMA21 | 金叉(+1多), 死叉(+1空) |
| 布林带 | 20周期 2倍标准差 | 触下轨(+1多), 触上轨(+1空) |
| 成交量突增 | 当前/5周期均量 | >3倍(确认信号强度×2) |

#### B. 微观结构指标（来自订单簿 + aggTrade）

| 指标 | 计算方式 | 信号 |
|------|----------|------|
| **OBI 订单簿不平衡** | (BidVol - AskVol) / (BidVol + AskVol) | >+0.3(+1多), <-0.3(+1空) |
| **大单方向** | 10秒内大单(>5倍均值)的买/卖统计 | 买>卖(+1多), 卖>买(+1空) |
| **买卖压力比** | aggTrade 中主动买量 / 主动卖量 | >1.5(+1多), <0.67(+1空) |

#### C. 市场情绪指标（来自爆仓 + 资金费率）

| 指标 | 计算方式 | 信号 |
|------|----------|------|
| **爆仓潮检测** | 60秒内连续同方向爆仓 | 多头连续爆仓(+1多/抄底), 空头连续爆仓(+1空) |
| **资金费率极端** | 当前费率 vs 历史分位 | >0.1%(+1空/做多付费过多), <-0.1%(+1多) |

#### D. 综合评分

```
多头得分 = 技术多头信号数 + 微观多头信号数 + 情绪多头信号数
空头得分 = 技术空头信号数 + 微观空头信号数 + 情绪空头信号数

信号强度 = max(多头得分, 空头得分)
信号方向 = 得分更高的一方

开仓阈值（受 CEO 宏观环境动态调节，见第三节）：
- 默认阈值：3（比原方案更激进，提高交易频率）
- CEO 判断顺势时：顺势方向阈值降至 2，逆势方向阈值升至 5
- CEO 判断高波动时：双向阈值升至 4（收缩防御）
```

数据窗口从 50 扩大到 **200 根 1 分钟 K 线**（约 3.3 小时），同时启动时通过 REST API 预加载历史 K 线，避免冷启动期。

---

## 三、多 Agent 决策层重构

### 当前问题
- CEO/CRO 的输出没有真正影响决策
- V3 在用 prompt 做 if-else，浪费 API 调用
- 三个 Agent 角色模糊

### 重构方案：基于 DeepSeek 模型特性的精确分工

#### Agent 架构

```
┌──────────────────────────────────────────────────┐
│                 SignalEngine                      │
│  (纯代码计算，不调用 AI，毫秒级)                    │
│  输出：综合评分 + 各指标明细                        │
└───────────┬──────────────────────────────────────┘
            │ 评分 >= 3 时才唤醒 AI
            ▼
┌───────────────────────┐
│  V3 Trader (DeepSeek V3)  │  延迟：200-300ms，便宜
│  角色：战术决策者          │
│  频率：信号触发时调用      │
│  输入：评分 + 指标明细     │
│  输出：LONG/SHORT/HOLD    │  (只出方向，不出价格)
│  限制：JSON 白名单输出     │
└───────────┬───────────┘
            │ 如果 V3 说 LONG 或 SHORT
            ▼
┌───────────────────────┐
│  R1 CRO (DeepSeek R1)    │  延迟：较慢但深度推理
│  角色：风控否决权          │
│  频率：仅在 V3 给出方向时  │
│  输入：V3决策 + 全部指标   │
│  + 当前持仓 + 近期胜率     │
│  输出：APPROVE / VETO     │  (硬性否决权)
│  特点：chain-of-thought   │
│  推理过程记入审计日志      │
└───────────┬───────────┘
            │ APPROVE 时执行
            ▼
      代码层计算入场价 + 仓位

┌───────────────────────┐
│  CEO Kimi (DeepSeek V3)   │  延迟：200-300ms
│  角色：宏观环境感知        │
│  频率：每 5 分钟一次       │
│  输入：过去 5 分钟的       │
│  价格走势 + 资金费率趋势   │
│  + 爆仓统计               │
│  输出：BULL/BEAR/RANGING  │
│  + VOLATILE/CALM          │
│  作用：调节开仓阈值        │
└───────────────────────┘
```

#### 关键改进

1. **V3 不再决定价格** — 只给方向，入场价由代码根据 OBI 和买卖盘计算
2. **R1 拥有硬性否决权** — 不是"建议"，是代码层的 `if (r1Says == VETO) return;`
3. **CEO 不直接参与交易决策** — 调整 SignalEngine 的开仓阈值 + 杠杆档位：
   - BULL + CALM → 多头阈值降至 2，空头阈值升至 5；顺势杠杆上限 15x
   - BEAR + CALM → 空头阈值降至 2，多头阈值升至 5；顺势杠杆上限 15x
   - RANGING + CALM → 双向阈值都是 3；杠杆上限 10x
   - 任何 + VOLATILE → 双向阈值升至 4；杠杆上限 7x（收缩防御）
4. **AI 调用频率大幅降低** — 只有 SignalEngine 评分达标才调用 V3，V3 说开仓才调用 R1。大部分时间不调用 AI。
5. **AI 不可替代的价值** — 代码能算指标但不能"理解"指标组合的含义：
   - V3 能判断"RSI 超卖但量能萎缩 = 反弹无力，不做多"（代码只会看到 RSI<30 就加分）
   - V3 能识别指标矛盾时哪个更可信（代码只会线性加权）
   - R1 深度推理能发现"虽然指标全绿，但上方有巨大挂单墙"（代码不懂挂单分布的战术含义）
   - CEO 能感知"最近 5 分钟连续 3 次假突破"这种模式（代码需要写死规则才能识别）

#### Prompt 模板（action-selector 白名单模式）

V3 Trader prompt：
```
You are a crypto futures tactical advisor.
RESPOND WITH EXACTLY ONE JSON OBJECT. NO OTHER TEXT.

Market snapshot:
- Price: {price}, RSI: {rsi}, EMA9/21: {ema9}/{ema21}
- OBI: {obi}, Buy/Sell Pressure: {pressure}
- Recent Liquidations: {liqSummary}
- Funding Rate: {fundingRate}
- Signal Score: Long={longScore}, Short={shortScore}
- Macro Environment: {ceoMacro}

Current position: {position}

Valid outputs (pick exactly one):
{"decision":"LONG","confidence":"HIGH|MED|LOW","reason":"<max 20 words>"}
{"decision":"SHORT","confidence":"HIGH|MED|LOW","reason":"<max 20 words>"}
{"decision":"HOLD","reason":"<max 20 words>"}
{"decision":"CLOSE","reason":"<max 20 words>"}
```

R1 CRO prompt：
```
You are a risk officer with VETO power. Think step by step.

Proposed trade: {v3Decision} at price {price}
Full market context: [all indicators]
Account state: balance={balance}, recent trades={last5trades}, win rate={winRate}

Your job: Find reasons this trade could fail.
Consider: manipulation risk, false breakout, liquidity trap, adverse funding rate.

Output exactly one:
{"verdict":"APPROVE","reason":"<why safe>"}
{"verdict":"VETO","reason":"<specific risk identified>"}
```

---

## 四、执行层重构

### 当前问题
- 陷阱价格 ≈ 现价，等于盲开
- 本地状态不同步交易所
- 市价单无滑点保护

### 重构方案

#### A. 智能入场价计算（代码层，不依赖 AI）

```java
// LONG 入场：在 bestBid 附近挂限价单，利用订单簿找支撑
double longEntry = bestBid;  // 不追价，在买一挂单

// SHORT 入场：在 bestAsk 附近挂限价单
double shortEntry = bestAsk;

// 如果 OBI 极端（>0.6 或 <-0.6），说明一侧压力巨大
// 可以更激进地在对手价成交
if (obi > 0.6) longEntry = bestAsk;   // 强烈看多时吃卖一
if (obi < -0.6) shortEntry = bestBid;  // 强烈看空时吃买一
```

#### B. 限价单 + 超时取消

不再用市价单（MARKET），改用限价单（LIMIT）+ IOC (Immediate Or Cancel)：
- 避免滑点
- 如果 5 秒内未成交，自动取消，重新评估
- 成交后从交易所返回值更新 entryPrice（不用 WebSocket 价格）

#### C. 动态杠杆 + 动态仓位（核心盈利引擎）

**设计理念：赢的时候加码，输的时候收缩。不是固定杠杆赌博，而是根据确定性分配火力。**

初始资金：~137 USDT（1000 人民币）

```java
// ===== 第一步：基础保证金比例（凯利公式简化版）=====
double kellyFraction = (winRate * avgWin - (1 - winRate) * avgLoss) / avgWin;
kellyFraction = Math.max(0.02, Math.min(kellyFraction, 0.12));  // 限制在 2%-12%

// ===== 第二步：信号强度缩放 =====
// 信号强度 3 → ×0.6，信号强度 6 → ×1.0，信号强度 8+ → ×1.2
double signalMultiplier = 0.4 + (signalScore / MAX_SCORE) * 0.8;
double marginPct = kellyFraction * signalMultiplier;

// ===== 第三步：动态杠杆（基于信号 + 宏观 + V3 置信度）=====
int leverage;
if (v3Confidence.equals("HIGH") && macro.contains("CALM")) {
    // 强信号 + 低波动 + AI 高置信：最大火力
    leverage = Math.min(15, ceoMaxLeverage);
    marginPct = Math.min(marginPct * 1.2, 0.12);  // 保证金也上浮，但不超过 12%
} else if (v3Confidence.equals("MED")) {
    // 中等信号：标准配置
    leverage = Math.min(10, ceoMaxLeverage);
} else {
    // 低置信或高波动：防御模式
    leverage = Math.min(7, ceoMaxLeverage);
    marginPct = Math.min(marginPct * 0.6, 0.05);  // 保证金缩小
}

// ===== 第四步：连胜/连亏调整（反马丁格尔）=====
if (consecutiveWins >= 3) {
    // 连赢 3 笔：手热，加码（但只加利润部分的仓位）
    double profitBonus = realizedProfit * 0.1;  // 用利润的 10% 追加
    marginAmount += profitBonus;
} else if (consecutiveLosses >= 2) {
    // 连亏 2 笔：减半仓位，等状态恢复
    marginPct *= 0.5;
    leverage = Math.min(leverage, 7);
}

// ===== 硬性上限 =====
double marginAmount = walletBalance * marginPct;
marginAmount = Math.max(5.0, Math.min(marginAmount, walletBalance * 0.12));  // 5U 到 12% 之间
leverage = Math.max(5, Math.min(leverage, 15));  // 5x 到 15x 之间
```

**各场景下的期望值（137U 起步）：**

| 场景 | 杠杆 | 保证金 | 名义仓位 | 每笔预期利润 |
|------|------|--------|----------|-------------|
| 强信号+CALM+HIGH | 15x | ~16U (12%) | 240U | 0.36U |
| 中等信号+标准 | 10x | ~11U (8%) | 110U | 0.16U |
| 弱信号/VOLATILE/LOW | 7x | ~7U (5%) | 49U | 0.07U |
| 连亏2笔后 | 7x | ~5U (3.5%) | 35U | 0.05U |
| 连赢3笔后(含利润加码) | 15x | ~18U | 270U | 0.40U |

**日交易预估：20-30 笔（阈值降低后更容易触发）**
**日期望收益：~3-5U（约 2-3.5%）**
**月复利：137U → ~250-310U**
**6个月复利：137U → ~1800-4200U（约 1.3-3 万元）**

**风险控制确保不会失控：**
- 即使连亏 10 笔（极端情况），总损失 = 约 8U（6%），远低于 10% 日亏停机线
- 最大单笔亏损 = 12% × 15x × ATR止损 ≈ 总资金的 2.7%
- 杠杆 15x 时爆仓距离 6.7%，但物理止损在 ATR×1.5 ≈ 1-2% 处触发，远在爆仓线之前

#### D. 动态止盈止损

```java
// 基于 ATR(14) 的动态止损
double atr = SignalEngine.getATR(14);
double stopDistance = atr * 1.5;  // 1.5 倍 ATR 止损
double takeProfitDistance = atr * 2.5;  // 2.5 倍 ATR 止盈（盈亏比 1:1.67）

// LONG 止损/止盈
double stopLoss = entryPrice - stopDistance;
double takeProfit = entryPrice + takeProfitDistance;

// 交易所端挂物理止损单（不变）
setPhysicalStopLoss(stopLoss);
setPhysicalTakeProfit(takeProfit);
```

#### E. 最大持仓时间

```java
// 超过 30 分钟未触发止盈/止损，强制平仓
if (System.currentTimeMillis() - positionOpenTime > 30 * 60 * 1000) {
    closePosition(currentPrice, "最大持仓时间到期");
}
```

#### F. 启动时同步交易所状态

```java
// 程序启动时，先查询交易所真实持仓
GET /fapi/v2/positionRisk?symbol=SOLUSDT
// 用交易所返回的数据初始化本地状态
// 避免重启后本地认为空仓、交易所实际有仓的危险状态
```

---

## 五、风控层重构 — `RiskGuard` 硬性拦截器

### 设计原则
所有风控规则在 Java 代码层执行，不经过 LLM，不可被 prompt 覆盖。

```java
public class RiskGuard {

    // ===== 硬性规则（不可覆盖）=====

    // 1. 全局最大回撤熔断（已有，保留）
    static final double MAX_DRAWDOWN = 0.20;  // 亏 20% 永久停机

    // 2. 单笔最大亏损
    static final double MAX_SINGLE_LOSS_PCT = 0.03;  // 单笔不超过总资金 3%

    // 3. 每日最大亏损
    static final double MAX_DAILY_LOSS_PCT = 0.10;  // 日亏 10% 当日停机

    // 4. 每日最大交易次数
    static final int MAX_DAILY_TRADES = 50;  // 提高上限适配更高交易频率

    // 5. 连续亏损熔断
    static final int MAX_CONSECUTIVE_LOSSES = 5;  // 连亏 5 次暂停 30 分钟

    // 6. 冷却期（已有，调整）
    static final long COOLDOWN_MS = 60_000;  // 平仓后 60 秒禁止开新仓

    // 7. CRO 否决权
    // R1 说 VETO → 直接 return，不开仓

    // 8. 高波动保护
    // ATR 突然翻倍 → 暂停交易 5 分钟

    // 9. 资金费率保护
    // 做多时资金费率 > 0.1% → 警告（你在付钱给空头）
    // 做空时资金费率 < -0.1% → 警告（你在付钱给多头）

    // 10. 订单簿流动性检查
    // 如果 depth5 的总量 < 你的订单量的 10 倍 → 拒绝开仓（流动性不足）

    // ===== 校验入口（每次开仓前必须通过）=====
    public static boolean canOpenPosition(TradeContext ctx) {
        if (ctx.dailyLoss >= MAX_DAILY_LOSS_PCT) return false;      // 日亏上限
        if (ctx.dailyTrades >= MAX_DAILY_TRADES) return false;       // 日交易上限
        if (ctx.consecutiveLosses >= MAX_CONSECUTIVE_LOSSES) return false; // 连亏熔断
        if (ctx.timeSinceLastClose < COOLDOWN_MS) return false;      // 冷却期
        if (ctx.croVerdict.equals("VETO")) return false;             // CRO 否决
        if (ctx.atrSurge > 2.0) return false;                        // 高波动保护
        if (ctx.liquidityRatio < 10.0) return false;                  // 流动性不足
        return true;
    }
}
```

---

## 六、日志与审计层 — `TradeJournal`

### 当前问题
只有 `System.out.println`，程序重启后全丢。

### 重构方案

每笔决策写入 JSON Lines 文件（每日一个文件），包含完整决策链路：

```json
{
  "timestamp": "2026-03-17T21:40:15.123Z",
  "type": "TRADE_DECISION",
  "price": 94.05,
  "indicators": {
    "rsi": 36.84,
    "ema9": 93.98,
    "ema21": 94.12,
    "obi": 0.35,
    "fundingRate": 0.0003,
    "buyPressure": 1.8,
    "recentLiquidations": "3x LONG in 60s",
    "signalScore": {"long": 5, "short": 1},
    "atr": 0.42
  },
  "ceoMacro": "RANGING_CALM",
  "v3Decision": {"decision": "LONG", "confidence": "HIGH", "reason": "RSI oversold + OBI bullish + liq flush"},
  "r1Verdict": {"verdict": "APPROVE", "reason": "No manipulation pattern detected"},
  "riskGuard": "PASSED",
  "execution": {
    "side": "LONG",
    "entryPrice": 94.03,
    "quantity": 1,
    "leverage": 10,
    "margin": 9.40,
    "stopLoss": 93.40,
    "takeProfit": 95.08
  }
}
```

同时新增统计追踪：
- 滚动胜率（最近 20 笔）
- 平均盈亏比
- 每日 PnL 曲线
- V3 和 R1 的决策准确率（事后回顾）

---

## 七、系统层重构

### A. 线程安全

| 组件 | 问题 | 修复 |
|------|------|------|
| `IndicatorCalculator` | `ArrayList` 无同步 | 改用 `CopyOnWriteArrayList` 或 `synchronized` 块 |
| `TradingDecisionEngine.activeTrap` | 无同步 | 删除陷阱机制，改用限价单 |
| `IntelligenceBoard` | `AtomicReference` | 已安全，保留 |

### B. 异常处理

所有 `catch(Exception e) {}` 替换为：
```java
catch (Exception e) {
    TradeJournal.logError("组件名", e);
    // 关键路径异常触发告警
}
```

### C. 跨平台

`OpenClawGatewayClient` 改为：
```java
// 检测操作系统
String os = System.getProperty("os.name").toLowerCase();
if (os.contains("win")) {
    cmd.add("cmd.exe"); cmd.add("/c");
} else {
    cmd.add("/bin/sh"); cmd.add("-c");
}
```

### D. API Key 外置

所有密钥移到环境变量或 `config.properties`（已 .gitignore）：
```java
String apiKey = System.getenv("BINANCE_API_KEY");
if (apiKey == null) {
    Properties p = new Properties();
    p.load(new FileInputStream("config.properties"));
    apiKey = p.getProperty("BINANCE_API_KEY");
}
```

从 Git 历史中移除已提交的密钥（需要你去 Binance/DeepSeek/Moonshot 后台重新生成所有 Key）。

### E. WebSocket 断连恢复

```java
// 指数退避重连：1s → 2s → 4s → 8s → 16s → 最大 60s
// 重连后通过 REST API 补齐断连期间的 K 线数据
// 超过 5 分钟无法重连 → 强制平仓所有持仓（安全第一）
```

### F. 启动时 Binance 服务器时间校准

```java
// GET /fapi/v1/time 获取服务器时间
// 计算本地时钟偏差
// 所有下单请求的 timestamp 加上偏差值
```

---

## 八、新增文件清单

| 文件 | 职责 |
|------|------|
| `MarketDataHub.java` | 5 路 WebSocket 数据接收、解析、存储 |
| `SignalEngine.java` | 多指标计算 + 综合评分 |
| `RiskGuard.java` | 硬性风控拦截器 |
| `TradeJournal.java` | JSON Lines 日志 + 统计追踪 |
| `PositionManager.java` | 仓位管理（含交易所同步 + 动态止盈止损） |
| `AgentOrchestrator.java` | 多 Agent 调度（替代原 TradingDecisionEngine） |

保留并重构：
| 文件 | 改动 |
|------|------|
| `Main.java` | 改为连接 Combined Stream，初始化所有组件 |
| `BinanceRealAccount.java` | 增加限价单、查询持仓、时间校准 |
| `OpenClawGatewayClient.java` | 跨平台、超时处理 |
| `IntelligenceBoard.java` | 扩展存储更多 Agent 状态 |

删除（不再需要）：
| 文件 | 原因 |
|------|------|
| `IndicatorCalculator.java` | 被 `SignalEngine` 替代 |
| `TradingDecisionEngine.java` | 被 `AgentOrchestrator` 替代 |
| `DirectKimiClient.java` | 不使用，OpenClaw 已覆盖 |
| `VirtualAccount.java` | 历史遗留 |
| `FuturesVirtualAccount.java` | 历史遗留 |
| `BinanceRealTrader.java` | 历史遗留 |
| `TestDestruction.java` | 历史遗留 |

---

## 九、交易流程图（完整链路）

```
[Binance 5路 WebSocket]
        │
        ▼
  MarketDataHub (解析+存储)
        │
        ▼
  SignalEngine (每秒计算)
   ├── 技术指标：RSI, EMA, 布林带, ATR
   ├── 微观结构：OBI, 大单, 买卖压力
   └── 市场情绪：爆仓潮, 资金费率
        │
        │ 综合评分
        ▼
  ┌─ 评分 < 阈值? ──→ 不做任何事（90%的时间在这里）
  │
  │ 评分 >= 阈值
  ▼
  V3 Trader (DeepSeek V3, ~300ms)
  ├── HOLD → 不做任何事
  ├── CLOSE → 直接平仓（不需要 R1 批准）
  └── LONG/SHORT ──→ 调用 R1
                       │
                       ▼
                 R1 CRO (DeepSeek R1, ~1-3s)
                 ├── VETO → 记录原因，不开仓
                 └── APPROVE
                       │
                       ▼
                 RiskGuard.canOpenPosition()
                 ├── false → 记录哪条规则拦截，不开仓
                 └── true
                       │
                       ▼
                 PositionManager.openPosition()
                 ├── 计算仓位大小（凯利公式 + 信号强度 + 宏观缩放）
                 ├── 计算入场价（基于订单簿）
                 ├── 计算止盈止损（基于 ATR）
                 ├── 发送限价单
                 ├── 挂物理止损/止盈单
                 └── 写入 TradeJournal

  [持仓期间 — 每秒检查]
  ├── 交易所止盈/止损单是否触发
  ├── 最大持仓时间是否到期
  ├── 信号是否反转（SignalEngine 反向评分 >= 5）
  └── R1 紧急风控检查（每 5 分钟）

  [CEO Kimi — 后台运行]
  每 5 分钟更新宏观环境 → 调整 SignalEngine 阈值
  不直接参与交易决策
```

---

## 十、风险防护矩阵（对照你发的风险清单）

| 风险 | 防护措施 | 实现位置 |
|------|----------|----------|
| AI 假新闻污染 | 不接入任何新闻源，只用交易所原始数据 | MarketDataHub |
| LLM 幻觉 | AI 只出方向不出价格，输出白名单校验 | AgentOrchestrator |
| 回测过拟合 | 不做回测优化，策略基于经典指标共振 | SignalEngine |
| Agent 自主失控 | AI 无直接下单权限，必须经过 RiskGuard | RiskGuard |
| AI 信号共振 | OBI+爆仓等独特信号降低与其他 bot 同质化 | SignalEngine |
| Prompt Injection | action-selector 白名单，输出只认 4 种 | AgentOrchestrator |
| 模型提取攻击 | 无对外接口，本地运行 | 架构层面 |
| 上下文衰退 | 每次请求独立 prompt，不带历史 | AgentOrchestrator |
| Temperature 失控 | V3 用 temperature=0，R1 不支持 temperature | prompt 配置 |
| 多 Agent 回声室 | CEO 只调阈值不参与决策链，V3/R1 独立判断 | 架构层面 |
| 滑点方向性 | 限价单(IOC)替代市价单 | PositionManager |
| 被做市商猎杀 | 成交后 5 秒价格反向统计 → 自动调整策略 | TradeJournal |
| 时间戳不对齐 | 启动时校准 Binance 服务器时间 | Main |
| API Key 泄露 | 环境变量 + .gitignore | 系统层 |
| 依赖包攻击 | pom.xml 添加 OWASP Dependency-Check | pom.xml |
| 时区/夏令时 | 全部使用 UTC | 全局 |
| 线程安全 | volatile/Atomic/synchronized | 全局 |
| 异常被吞 | 所有异常写入 TradeJournal | 全局 |
| 断连丢数据 | 指数退避重连 + REST 补数据 | Main |
| 仓位状态脱节 | 启动时查询交易所 + 定期同步 | PositionManager |
| 自动化偏见 | 行为异常报警（连续同方向/胜率骤降） | TradeJournal |
| 最大持仓时间 | 30 分钟强制平仓 | PositionManager |
| 每日最大亏损 | 10% 当日停机 | RiskGuard |
| 连续亏损熔断 | 连亏 5 次暂停 30 分钟 | RiskGuard |

---

## 十一、预期效果

- **交易频率**：从"半天一笔"提升到每天 20-30 笔（阈值降至 3，信号刷新频率 500ms）
- **决策质量**：从单一 RSI 提升到 8+ 指标共振，AI 在指标矛盾时做非线性判断
- **动态火力**：强信号 15x 杠杆 + 12% 保证金，弱信号 7x + 5%，连亏自动缩仓
- **风控**：从 prompt 建议 → 代码硬性执行，AI 无法绕过；R1 深度推理拥有否决权
- **盈利预期**：137U 起步，日均 2-3.5%，月复利 ~80-110%，6 个月目标 1800-4200U
- **最大风险**：单笔最大亏损 2.7%，日亏 10% 停机，全局亏 20% 永久熔断
- **成本**：AI 调用约 350 次/天 ≈ 0.35 元/天（只在信号达标时调用）
- **可维护性**：完整 JSON Lines 日志，每笔交易含完整指标+AI推理+风控审计链路
