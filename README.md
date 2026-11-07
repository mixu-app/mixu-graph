# mixu-graph

时序叙事图引擎：把「读者读到哪里」变成可查询的图状态。

推理小说只是第一个用例。互动叙事、视觉小说、剧情游戏都有同一个问题：**故事世界的时间**
和**读者获知的时间**不是一条线。这个库只做这件事，因此可以被别的项目直接复用。

Apache-2.0，无 copyleft 传染；客户端（MPL-2.0）只是它的消费者之一。

## 三个时间轴

| 概念 | 字段 | 含义 |
|---|---|---|
| 故事时间 | `story_time` | 事件在虚构世界内何时发生，可为区间、部分时间、未知或有争议 |
| 揭示时间 | `first_reveal` | 读者最早能在哪个 reveal ordinal 知道这条信息（edition 绑定） |
| 状态时间 | `transition.reveal_ordinal` | 后来的证据在何时改变了早先声称的解释 |

`created_at`（用户笔记的创建时间）是第四个时间，属于 overlay，不属于图谱本身。

## 投影（本库的核心）

给定 edition 与 `r = graph_view_position`，输出一个**已经过滤好**的图视图：

1. 解析 locator → edition 内的 reveal ordinal。
2. 取 `first_reveal <= r` 的 entity 与 name。
3. 取 `first_reveal <= r` 的 assertion / evidence。
4. 重放 `transition.reveal_ordinal <= r` 的状态变更。
5. 仅在 `identity_link.first_reveal <= r` 时合并身份。
6. 按 overlay 可见性策略附加用户标注。
7. 搜索索引与检索上下文都从**同一个投影**生成。

规则：不做「全图取出来、前端隐藏节点」。过滤必须发生在查询层，否则 FTS、计数、坐标都会泄漏。

## 计划中的 API（Kotlin，示意）

```kotlin
interface GraphView {
    fun at(ordinal: RevealOrdinal): Projection
}

interface Projection {
    fun entity(id: EntityId): RevealedEntity?
    fun assertionsAbout(id: EntityId): List<RevealedAssertion>
    fun neighborhood(id: EntityId, depth: Int): List<RevealedRelation>
    fun search(query: String): List<RevealedHit>
}
```

具体签名会随 M1 任务确定；这里固定的是**语义**：任何返回类型都不允许携带未到揭示边界的数据。

## 非目标

- 不做渲染（→ [mixu-renderer](https://github.com/mixu-app/mixu-renderer)）。
- 不做访问控制或账号授权（→ 服务端 + [mixu-spoiler](https://github.com/mixu-app/mixu-spoiler) 的客户端侧策略）。
- 不做文本抽取（→ 私有 worker）。
- 不做「全知视角」的重建：本库描述的是**读者已知**，不是书里最终的真相。

## 质量门禁

```sh
just check     # markdownlint + (存在 wrapper 时) gradle check
```

测试策略与 fixture 见 [`docs/design.md`](docs/design.md) 与
[mixu-fixtures](https://github.com/mixu-app/mixu-fixtures)。反例优先：一个泄漏测试必须先能在
错误实现上失败。

## 许可证

[Apache-2.0](LICENSE)，含专利授权。第三方依赖登记在 `THIRD-PARTY.md`（当前无依赖）。
名称与 Logo 不在授权范围内，见 [商标政策](https://github.com/mixu-app/.github/blob/main/TRADEMARKS.md)。
