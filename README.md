# Gene Set Enrichment Service

本地可复现的基因集合富集分析服务（Spring Boot 3 / Java 17）。调用方提交背景集合、
待分析集合与基因→类别注释，服务异步计算每个类别的富集结果，支持超时、取消、
并发隔离与结果持久化。

## 运行

```bash
mvn package -DskipTests
java -jar target/gene-set-enrichment-java-1.0-SNAPSHOT.jar
# 默认端口 8080，结果持久化目录 ./data/results
```

配置项（`application.properties` 或命令行 `--key=value`）：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `enrichment.data-dir` | `./data/results` | 任务结果 JSON 持久化目录，重启后自动恢复 |
| `enrichment.max-concurrent` | `4` | 并行执行的分析任务数上限 |
| `enrichment.max-queue` | `100` | 排队任务数上限，超出后新提交返回 429 |

## API

### 提交分析 `POST /api/v1/analyses`

```json
{
  "background": ["TP53", "BRCA1", "..."],
  "query": ["tp53", "myc"],
  "annotations": { "dna_repair": ["TP53", "BRCA1"], "kinases": ["EGFR"] },
  "parameters": {
    "correctionMethod": "BH",
    "minCategorySize": 1,
    "maxCategorySize": 2147483647,
    "caseSensitive": false,
    "timeoutMillis": 60000
  }
}
```

`parameters` 整体可省略（取默认值）。返回 `{"taskId": "...", "status": "PENDING"}`。

### 查询任务 `GET /api/v1/analyses/{taskId}`

返回任务记录：状态、生效参数、结果（完成时）、错误码（失败时）。

### 列出任务 `GET /api/v1/analyses`

按创建时间升序返回全部任务。

### 取消任务 `POST /api/v1/analyses/{taskId}/cancel`

仅对 `PENDING`/`RUNNING` 任务有效；已终态任务返回 409。取消成功的任务
**不会**携带任何部分结果（`result` 为 `null`）。

## 统计口径

对每个类别（经清洗、且大小落在 `[minCategorySize, maxCategorySize]` 内）：

- `backgroundSize` N：清洗后背景集合大小
- `querySize` n：清洗后待分析集合大小
- `categorySize` K：类别基因中出现在背景集合内的数量
- `intersectionSize` k：待分析集合与类别的交集大小
- `foldEnrichment` = (k/n) / (K/N)，即观察频率与期望频率之比
- `direction`：k ≥ n·K/N 为 `OVER_REPRESENTED`，否则 `UNDER_REPRESENTED`
- `pValue`：超几何分布单侧尾概率
  - 过表达：P(X ≥ k)，X ~ Hypergeometric(N, K, n)
  - 欠表达：P(X ≤ k)
  - 计算在 log 空间进行（Lanczos log-Γ），大总体下不溢出
- `adjustedPValue`：对本次运行中**实际参与检验的全部类别**的原始 p 值做校正

### 多重检验校正策略（`correctionMethod`）

| 取值（别名） | 方法 |
|---|---|
| `BONFERRONI` | Bonferroni：min(1, p·m) |
| `BENJAMINI_HOCHBERG`（`BH`、`FDR`） | Benjamini-Hochberg 逐步法 |
| `BENJAMINI_YEKUTIELI`（`BY`） | Benjamini-Yekutieli（BH × 调和数 c(m)） |

校正确定性：相同 p 值必得相同校正值；实现经 R `p.adjust` 参考值校验。

## 输入清洗与忽略项报告

提交时按顺序清洗，所有被丢弃的输入都在结果的 `ignored` 数组中按遇到顺序报告：

| `reason` | 含义 |
|---|---|
| `BLANK` | 空白或 null 条目（trim 后为空） |
| `DUPLICATE` | 同一列表内的重复项（保留首次出现） |
| `UNKNOWN_GENE` | 不在背景集合中的基因（query 与 annotation 均按背景过滤） |
| `EMPTY_CATEGORY` | 过滤后不含任何背景基因的类别（该类别不参与检验） |

默认大小写不敏感（统一转小写比较）；`caseSensitive: true` 时按原样精确匹配。
清洗后 query 无任何背景基因、或没有任何有效类别时，任务以
`FAILED` / `INVALID_REQUEST` 结束。

## 任务生命周期与并发语义

- 状态机：`PENDING → RUNNING → COMPLETED / FAILED / CANCELLED / TIMED_OUT`，
  终态不可逆（取消/超时与正常完成之间的竞争不会"复活"任务）。
- 每个任务按 `timeoutMillis` 计时（0 表示不限时），超时任务标记 `TIMED_OUT`。
- 工作线程池与队列均有上限；队列满时提交返回 429，任务间状态互不污染，
  单个任务失败不影响其他任务。
- 每次状态变更原子写入 `enrichment.data-dir/<taskId>.json`（临时文件 + 原子移动）。
  重启后已完成/失败/取消的记录原样恢复；重启时仍处于 `PENDING`/`RUNNING`
  的任务标记为 `FAILED` / `SERVICE_RESTARTED`。

## 错误码

| HTTP | code | 场景 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 请求体缺失/字段非法/JSON 无法解析/未知校正方法 |
| 404 | `TASK_NOT_FOUND` | 任务不存在 |
| 409 | `TASK_NOT_CANCELLABLE` | 取消已终态的任务 |
| 429 | `TASK_QUEUE_FULL` | 排队队列已满，稍后重试 |
| 500 | `INTERNAL_ERROR` | 未预期错误 |

任务级错误码（记录在任务的 `errorCode` 字段）：`INVALID_REQUEST`、
`INTERNAL_ERROR`、`TASK_QUEUE_FULL`、`TASK_CANCELLED`、`TASK_TIMEOUT`、
`SERVICE_RESTARTED`。

## 可复现性

同一输入与同一参数下：类别结果的数值与顺序（按 adjustedPValue、pValue、
categoryId 排序）逐次一致；输入列表顺序不影响输出；结果中回显实际生效的
全部参数。测试覆盖：输入清洗、超几何与校正数值（对照 R）、取消与超时、
并发隔离、队列上限、持久化与重启恢复。

```bash
mvn test
```
