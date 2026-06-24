# CanalAndWell 水渠连接系统概要

## 架构概述

水渠方块使用**定向状态机**（非四向盲扫），严格复现原版 Canal 模组的 `data_dump.txt` 脚本逻辑。整个系统用**单一 `BlockCanal` + `TileEntityCanal`** 替代原版 18+ 种方块互切。

### 数据分工

| 存储位置 | 内容 | 说明 |
|----------|------|------|
| `BlockCanal` metadata (4bit) | 形状状态 (7种) + 封闭标志 (bit 3) | 持久化，决定顶面贴图 |
| `TileEntityCanal` | `isWet` 布尔值 | 湿水状态，控制干/湿贴图切换 |

### Metadata 编码

| Bits | 字段 | 值 |
|------|------|-----|
| bit 0 | 朝向 (facing) | 0=NS轴, 1=EW轴 |
| bit 1-2 | 形态 (type) | 0=STRAIGHT, 1=T_LEFT, 2=T_RIGHT, 3=CROSS |
| bit 3 | 封闭 (closed) | 0=正常连接, 1=封闭锁定 |

封闭标志独立于形状/朝向，仅 STRAIGHT 可被玩家手动封闭（通过潜行右键）。

### 颜色说明

在以下图示中：
- **红色边框**：当前考察的方块
- **蓝色实线**：当前方块已有的连接方向
- **绿色虚线**：新放置的相邻水渠
- **黄色虚线**：因新放置而新增的连接方向
- **`[X]`**：该方向的方块类型标注

---

## 形状定义

| Meta | 常量 | 原版对应 | 连接方向 | 贴图(干/湿) |
|------|------|----------|----------|-------------|
| 0 | STRAIGHT_NS | canaldry | N+S | canalTop / canalTopWet |
| 1 | STRAIGHT_EW | canaldryE | E+W | canalTopE / canalTopWetE |
| 2 | T_NW | canaldryN-W | N+S+W | canalTopC2 / canalTopWetC2 |
| 3 | T_NE | canaldryN-E | N+S+E | canalTopC1 / canalTopWetC1 |
| 4 | T_EN | canaldryE-N | E+W+N | canalTopC3 / canalTopWetC3 |
| 5 | T_ES | canaldryE-S | E+W+S | canalTopC4 / canalTopWetC4 |
| 6 | CROSS | canaldryC | N+S+E+W | canalTopC / canalTopWetC |
| 8 | CLOSED_NS | canalclose | N+S (锁定) | canalTopClose |
| 9 | CLOSED_EW | canalcloseE | E+W (锁定) | canalTopCloseE |

> **注意**：CLOSED 标志 (bit 3=1) 可与任意形状组合，但实际游戏中仅 STRAIGHT 会被玩家封闭。

---

## 封闭水渠 (Closed Canal)

### 概述

封闭水渠是对应原版 `canalclose` / `canalcloseE` 的功能。它相当于**清除逻辑**：水渠被"锁定"为直管形态，不再参与自动连接状态转换。但**在主干轴向（直接连接方向）上已有的连接会被保留**。

### 操作方式

| 操作 | 效果 |
|------|------|
| **潜行右键** 任意水渠 | 变为封闭直管，朝向由玩家面向决定（同放置逻辑） |
| **潜行右键** 封闭水渠 | 解除封闭，保持直管形状 |

### 可见性规则

封闭水渠对邻居的可见性取决于**封闭水渠自身的主干轴向**：

```
封闭 NS 水渠 (轴向 N-S):
         N (可见 ✓)
         ↑
    ┌────┼────┐
    │    │    │
W ←┼─ [CLOSED] ─┼→ E  (不可见 ✗)
    │    │    │
    └────┼────┘
         ↓
         S (可见 ✓)
```

- **轴向邻居**（NS 封闭水渠的 N/S）：`hasCanal()` 返回 `true` → 连接保留
- **垂直邻居**（NS 封闭水渠的 E/W）：`hasCanal()` 返回 `false` → 等效于清除

### 效果示例

**场景：封闭水渠 + 垂直方向放置新水渠 → 不形成 T 型**

```
初始：封闭 NS 水渠

     [NS.closed]
     
在东侧放置 EW 水渠：

  [NS.closed]  [EW.new]

EW.new 检查 W 侧：封闭 NS 的轴向为 N-S，EW.new 从 E 方向接近
→ 接近方向 E 不在封闭 NS 的轴向 {N, S} 中
→ hasCanal 返回 false
→ EW.new 保持 STRAIGHT_EW，不形成 T 型
```

**场景：封闭水渠 + 轴向放置新水渠 → 正常连接**

```
初始：封闭 NS 水渠

     [NS.closed]
     
在北侧放置 NS 水渠：

     [NS.new]
       ↑
       │
  [NS.closed]

NS.new 检查 S 侧：封闭 NS 的轴向为 N-S，NS.new 从 N 方向接近
→ 接近方向 N 在封闭 NS 的轴向 {N, S} 中
→ hasCanal 返回 true
→ 正常连接（但封闭水渠自身形状不变）
```

### 状态转换中的行为

- 封闭水渠自身：`applyTransition()` 检测到封闭标志后立即返回，**不执行任何状态转换**
- T/CROSS 降级：从 `isAir()` 改为 `!hasCanal()` 判断，使封闭垂直水渠触发正确的降级
- 封闭水渠仍可湿水并传播水流，不受封闭标志影响

---

## 状态转换图

```
                    W侧有水渠              E侧有水渠
STRAIGHT_NS(0) ────────────→ T_NW(2)    ────────────→ T_NE(3)
       ↑                         │                         ↑
       │ W侧空气                  │ E侧有水渠               │ E侧空气
       │                         ↓                         │
       └──────────────────── CROSS(6) ─────────────────────┘
                             ↑    ↑
                    N侧有水渠 │    │ S侧有水渠
                             │    │
       ┌─────────────────────┘    └─────────────────────┐
       │ N侧空气                                         │ S侧空气
       ↓                                                 ↓
STRAIGHT_EW(1) ────────────→ T_EN(4)    ────────────→ T_ES(5)
       ↑                         │                         ↑
       │ N侧有水渠               │ S侧有水渠               │ S侧空气
       └──────────────────── CROSS(6) ─────────────────────┘
```

---

## 五类具体场景

### 场景 1：端点放置异向水渠 → 保持直管

```
初始：单独 STRAIGHT_EW 方块

        [air]
         ↑
[air] ← [EW] → [air]
         ↓
        [air]

在东侧放置 NS 水渠：

[air]           [air]
  ↑               ↑
[EW] → [NS]     [EW] → [NS]
  ↓               ↓
[air]           [air]

状态检查：STRAIGHT_EW 检查 N/S 侧 → 均为 air → 保持 STRAIGHT_EW
结果：两端点各自保持直管形状
```

### 场景 2：直管中段 + 异向水渠 → 三联通 T 型

```
初始：NS 直管中段（上下均有水渠连接）

        [NS]
         ↑
         │  (N连接)
    ┌────┘
    │
  [NS]     ← 当前考察
    │
    └────┐
         │  (S连接)
         ↓
        [NS]

在东侧放置 EW 水渠：

        [NS]
         ↑
    ┌────┤
    │    │
  [NS] → [EW]    ← 新放置
    │
    └────┐
         ↓
        [NS]

状态检查：STRAIGHT_NS 检查 E 侧 → 有水渠 → 转换为 T_NE
结果：NS 主干(N+S) + E 分支 → 三联通 T_NE
```

### 场景 3：三联通 + 对面再放水渠 → 四联通

```
初始：T_NE（N+S+E 三联通，缺 W）

        [NS]
         ↑
    ┌────┤
    │    │
  [air]←[T_NE]→[EW]
    │
    └────┐
         ↓
        [NS]

在 W 侧放置水渠：

        [NS]
         ↑
    ┌────┤
    │    │
  [EW]→[T_NE]→[EW]
    │
    └────┐
         ↓
        [NS]

状态检查：T_NE 检查 W 侧 → 有水渠 → 转换为 CROSS
结果：四联通十字
```

### 场景 4：破坏水渠 → 自动缩回

```
初始：四联通 CROSS

        [NS]
         ↑
    ┌────┼────┐
    │    │    │
  [EW]←[CROSS]→[EW]
    │    │    │
    └────┼────┘
         ↓
        [NS]

破坏东侧 EW 水渠：

        [NS]
         ↑
    ┌────┤
    │    │
  [EW]←[CROSS]  [air]   ← 已破坏
    │    │
    └────┼────┘
         ↓
        [NS]

状态检查：CROSS 检查 E 侧 → air → 转换为 T_NW
结果：自动缩回为三联通 T_NW（N+S+W）
```

### 场景 5：三联通分支消失 → 还原直管

```
初始：T_NE（N+S+E）

        [NS]
         ↑
    ┌────┤
    │    │
  [air]←[T_NE]→[EW]
    │
    └────┐
         ↓
        [NS]

破坏东侧 EW 水渠：

        [NS]
         ↑
         │
    ┌────┘
    │
  [air] [T_NE]  [air]   ← 已破坏
    │
    └────┐
         ↓
        [NS]

状态检查：T_NE 检查 E 侧 → air → 转换为 STRAIGHT_NS
结果：还原为 NS 直管
```

---

## 状态转换规则表

| 当前形状 | 检查方向 | 条件 | 目标形状 |
|----------|----------|------|----------|
| STRAIGHT_NS | W | 有水渠 | T_NW |
| STRAIGHT_NS | E | 有水渠 | T_NE |
| STRAIGHT_EW | N | 有水渠 | T_EN |
| STRAIGHT_EW | S | 有水渠 | T_ES |
| **T_NW** | **E** | **有水渠 + N/S 都有水渠** | **CROSS** |
| **T_NW** | **E** | **有水渠 + 仅 N 有水渠** | **T_EN** |
| **T_NW** | **E** | **有水渠 + 仅 S 有水渠** | **T_ES** |
| **T_NW** | **E** | **有水渠 + N/S 皆空** | **STRAIGHT_EW** |
| T_NW | W | 无可见水渠 | STRAIGHT_NS |
| **T_NE** | **W** | **有水渠 + N/S 都有水渠** | **CROSS** |
| **T_NE** | **W** | **有水渠 + 仅 N 有水渠** | **T_EN** |
| **T_NE** | **W** | **有水渠 + 仅 S 有水渠** | **T_ES** |
| **T_NE** | **W** | **有水渠 + N/S 皆空** | **STRAIGHT_EW** |
| T_NE | E | 无可见水渠 | STRAIGHT_NS |
| **T_EN** | **S** | **有水渠 + E/W 都有水渠** | **CROSS** |
| **T_EN** | **S** | **有水渠 + 仅 E 有水渠** | **T_NE** |
| **T_EN** | **S** | **有水渠 + 仅 W 有水渠** | **T_NW** |
| **T_EN** | **S** | **有水渠 + E/W 皆空** | **STRAIGHT_NS** |
| T_EN | N | 无可见水渠 | STRAIGHT_EW |
| **T_ES** | **N** | **有水渠 + E/W 都有水渠** | **CROSS** |
| **T_ES** | **N** | **有水渠 + 仅 E 有水渠** | **T_NE** |
| **T_ES** | **N** | **有水渠 + 仅 W 有水渠** | **T_NW** |
| **T_ES** | **N** | **有水渠 + E/W 皆空** | **STRAIGHT_NS** |
| T_ES | S | 无可见水渠 | STRAIGHT_EW |
| CROSS | W | 无可见水渠 | T_NE |
| CROSS | E | 无可见水渠 | T_NW |
| CROSS | N | 无可见水渠 | T_ES |
| CROSS | S | 无可见水渠 | T_EN |
| **CLOSED** | **任意** | **封闭标志=1** | **保持原状（不转换）** |

> **轴向切换说明**：T 型水渠两侧分支都满时（如 T_EN 的 N+S 都有水渠），
> 不再直接升级为 CROSS，而是检查主干两端（E/W）的完整性：
> - 主干两端都有水渠 → CROSS（真四联通）
> - 主干仅一端有水渠 → 切换轴向形成三联通 T
> - 主干两端皆空 → 两分支自成直管（如 T_NW 的 N/S 皆空 + E/W 有水渠 → STRAIGHT_EW）
>
> 这修复了"朝西流向顶端 + 南北水渠 → 错误形成十字"的问题，
> 以及"孤立 T 型补全对侧 → 应变为直管"的场景。
>
> **降级条件变更**：T/CROSS 降级判断从 `isAir()` 改为 `!hasCanal()`。
> 这样当分支方向存在封闭水渠（垂直轴向可见=false）时，`hasCanal()` 返回 false，
> 触发正确的降级还原，无需等待方块被挖掉。同时不影响原版行为
> （空气方块 `hasCanal()` 也返回 false）。

---

## 触发时机

| 事件 | 行为 |
|------|------|
| `onBlockAdded` | 自身 + 邻居执行状态转换 |
| `onNeighborBlockChange`（水渠/水/空气） | 自身 + 邻居执行状态转换 |
| `onBlockPlacedBy` | 设置初始朝向（NS/EW），后续 `onBlockAdded` 会修正 |
| `breakBlock` | 清理流水 + 通知邻居执行状态转换 |
| `updateTick`（每 10 tick） | 定时状态转换（清理孤立连接）+ 水传播 |

---

## 贴图映射

| 形状 | 干贴图 | 湿贴图 | 对应原版贴图名 |
|------|--------|--------|---------------|
| STRAIGHT_NS | iconTop | iconWet | canalTop / canalTopWet |
| STRAIGHT_EW | iconTopE | iconWetE | canalTopE / canalTopWetE |
| CROSS | iconTopC | iconWetC | canalTopC / canalTopWetC |
| T_NW (NS+W) | iconTopC2 | iconWetC2 | canalTopC2 / canalTopWetC2 |
| T_NE (NS+E) | iconTopC1 | iconWetC1 | canalTopC1 / canalTopWetC1 |
| T_EN (EW+N) | iconTopC3 | iconWetC3 | canalTopC3 / canalTopWetC3 |
| T_ES (EW+S) | iconTopC4 | iconWetC4 | canalTopC4 / canalTopWetC4 |

---

## 水流系统（参考 data_dump.txt 原版脚本）

### 原版逻辑摘要

原版 Canal 模组（`data_dump.txt`）水流逻辑要点：

| 功能 | 原版实现 | 本模组实现 |
|------|----------|------------|
| 水源检测 | `onUpdate` 检查邻位 `water meta=0` | `detectWaterSource()` BFS 溯源 |
| 变湿触发 | 邻位有水或湿水渠 → 自身变湿 | 同原版 + `propagateImmediate()` 即时传播 |
| 水输出 | `setBlockAndMetadata(x,y,z±1, water, 1)` — 流动水 | `spawnFlowingWater()` — 可配置流体/meta，仅连接方向 |
| 水清除 | `onBreak` 清除 meta=1 流水 | `cleanupFlowingWater()` — 变干时清除，不限于 meta |
| 湿→干 | `onBreak` 中湿邻居转干（链式） | `shouldStayWet()` BFS 溯源 + `checkShouldDry()` 延迟链式 |
| 封闭切断 | 无（原版未实现封闭逻辑） | 封闭水渠变干 + 连锁干燥下游 |
| 玻璃瓶取水 | `onActivated` 检测 `glass_bottle` | 同原版，`Config.enableBottleFill` 控制 |

### 水流生成规则

```
spawnFlowingWater():
  对每个连接方向:
    if 邻位是空气 && 下方非空气:
      → 放置 Config.flowingFluidBlockName (默认 minecraft:water)
        metadata = Config.flowingFluidMeta (默认 0，完整水源方块)
```

关键差异：默认输出**完整水源方块** (meta=0) 而非原版的流动水 (meta=1)。可在配置中调整。

### 水流清除规则（变干时）

```
cleanupFlowingWater():
  if !Config.cleanupFlowingWater → 跳过
  对每个连接方向:
    if 邻位是配置流体或原版水 && 下方非空气:
      → 清除该水方块
```

清除条件与生成条件对称：仅清除有支撑面的水方块，避免误删自然水体。

### 溯源干燥机制

```
水渠 X 湿润状态下每 tick 运行:
  shouldStayWet():
    if 邻位有直接水源方块 → true (保持湿润)
    否则 BFS 沿湿润水渠邻居搜索 ≤ waterFlowRange 深度:
      找到直接水源 → true
      未找到 → false

  if !shouldStayWet():
    setWet(false)
    cleanupFlowingWater()         // 清除 X 产出的水
    scheduleNeighbors(chainDryDelay)  // 延迟通知邻居也检查
```

### 延迟参数

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `tickRate` | 10 | 主 tick 间隔（0.5s） |
| `chainDryDelay` | 5 | 干燥传播每跳延迟（0.25s） |
| `waterSpreadDelay` | 10 | 湿润传播每跳延迟（0.5s） |
| `waterFlowRange` | 64 | BFS 溯源最大深度 |

### 配置参数完整列表

| 参数 | 默认值 | 类别 | 说明 |
|------|--------|------|------|
| `tickRate` | 10 | water flow | 水流检查间隔 (tick) |
| `waterFlowRange` | 64 | water flow | 水流最大传播距离 (方块) |
| `cleanupFlowingWater` | true | water flow | 破坏/变干时是否清理流水 |
| `flowingFluidBlockName` | `minecraft:water` | water flow | 输出的流体方块 ID |
| `flowingFluidMeta` | 0 | water flow | 输出流体 metadata（0=水源） |
| `chainDryDelay` | 5 | water flow | 连锁干燥每跳延迟 (tick) |
| `waterSpreadDelay` | 10 | water flow | 湿润传播每跳延迟 (tick) |
| `enableBottleFill` | true | interaction | 是否允许玻璃瓶取水 |
| `debugLogging` | false | debug | 详细日志输出 |

### 与原版的关键设计差异

1. **单一方块替代 18+ 方块** — 原版有 `canaldry / canalwet / canaldryE / canalwetE / canaldryC / canalwetC / canaldryN-W / ...` 等 18+ 种独立方块来回切换。本模组使用 metadata 状态机 + TileEntity isWet 标记，单一方块类完成所有连接形态。

2. **定向状态机替代全向扫描** — 原版 `onUpdate` 中逐方向硬编码检查相邻方块名，本模组使用相对方向 (LEFT/RIGHT) 和旋转编码消除 NS/EW 重复。

3. **封闭水渠** — 原版通过 `onNeighborChange` 中检测 "非水渠非水非空气" 方块来自动封闭。本模组提供主动的潜行右键切换。

4. **输出水源方块** — 原版输出 meta=1 流动水，本模组默认输出 meta=0 完整水源方块（可配置）。

5. **BFS 溯源干燥** — 原版在 `onBreak` 中手动枚举所有湿邻居类型并转干。本模组使用 BFS 沿湿润路径自动搜索可达水源。
