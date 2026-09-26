# 本地多维空间索引服务（spatial-index-java）

一个纯本地运行的多维空间索引服务，支持二维 / 三维的点与轴对齐矩形对象的
**新增（含更新）、删除、范围查询、最近邻（kNN）查询**，数据仅保存在本地磁盘，
进程重启后无需重建即可继续查询，崩溃后已提交数据不丢失。

基于 Spring Boot 3 / Java 17，无任何外部数据库或网络依赖。

---

## 1. 快速开始

```bash
mvn spring-boot:run
# 或
mvn package && java -jar target/spatial-index-1.0-SNAPSHOT.jar
```

服务默认监听 `8080`，数据默认写入 `./data/spatial-index`。

### 1.1 对象写入 / 更新（重复 id 即原子 upsert）

```bash
curl -X POST http://localhost:8080/api/spatial/objects \
  -H 'Content-Type: application/json' \
  -d '{
        "id": "obj-1",
        "shape": "RECTANGLE",
        "dim": 3,
        "minX": 0, "minY": 0, "minZ": 0,
        "maxX": 10, "maxY": 10, "maxZ": 10,
        "payload": "任意可选字符串负载"
      }'
```

点对象只需提供 `minX/minY[/minZ]`：

```json
{ "id": "p1", "shape": "POINT", "dim": 2, "minX": 1.0, "minY": 2.0 }
```

字段说明：

| 字段 | 含义 |
| --- | --- |
| `id` | 字符串，全局唯一标识；重复写入即为更新 |
| `shape` | `POINT` 或 `RECTANGLE` |
| `dim` | `2` 或 `3`（默认 2） |
| `minX/minY/minZ/maxX/maxY/maxZ` | 包围盒坐标；点只需 min |
| `payload` | 可选字符串负载，随对象一起持久化 |

### 1.2 删除

```bash
curl -X DELETE http://localhost:8080/api/spatial/objects/obj-1
# 200 {"status":"deleted", ...}；对象不存在时返回 404
```

删除一个不存在（或已删除）的 id 是确定性的空操作：返回 `404`，不写入任何记录。

### 1.3 按 id 获取

```bash
curl http://localhost:8080/api/spatial/objects/obj-1
```

### 1.4 范围查询（边界包含）

```bash
curl -X POST http://localhost:8080/api/spatial/query/range \
  -H 'Content-Type: application/json' \
  -d '{"dim":2,"minX":0,"minY":0,"maxX":5,"maxY":5}'
```

返回**所有与查询框相交（含边界相触）**的对象，按 `id` 升序排列，保证结果确定、可复现；
无结果时返回 `[]`。

### 1.5 最近邻查询

```bash
curl -X POST http://localhost:8080/api/spatial/query/knn \
  -H 'Content-Type: application/json' \
  -d '{"dim":3,"x":0,"y":0,"z":0,"k":10}'
```

返回最多 `k` 个存活对象，距离为点到对象包围盒的**最小欧氏距离**，排序键为
`(distance 升序, id 字典序升序)`，因此距离相同的对象返回顺序完全确定。
每条结果额外带 `distance` 字段。

### 1.6 健康检查

```bash
curl http://localhost:8080/api/spatial/health
# {"status":"UP","segments":[...]}
```

---

## 2. 确定性行为约定

| 场景 | 行为 |
| --- | --- |
| 范围边界 | **包含语义**：对象与查询盒在面 / 边 / 点上相触也算命中 |
| 空结果 | 返回空数组 `[]`，不报错 |
| 重复标识写入 | 原子 upsert：用新版本整体替换旧版本（位置、形状、负载） |
| 同距离 kNN | 距离相同时按 `id` 字典序排序，顺序确定 |
| 删除已删除 / 不存在 id | 返回 `404`，不产生写记录 |
| 并发读写 | 每个操作看到的是某一时刻已提交写入的完整快照，不会出现“半条”对象或重复对象 |
| 2D / 3D 混用 | 2D 对象嵌入 z=0 平面；2D 查询只比较 x/y，3D 查询比较 x/y/z |

---

## 3. 架构与持久化语义

整体为 **LSM-Tree（日志结构合并）+ 内存 / 磁盘 R-Tree**：

```text
写入 ──► 活动内存表(Memtable) + WAL(顺序追加, fsync)
            │ 写满
            ▼
       冻结内存表 ──► 后台 flush ──► 不可变段文件(seg-N.seg, STR 批量加载 R-Tree)
                                        │ 段数达到阈值
                                        ▼
                                   后台合并(compaction) ──► 单段
       manifest.json 原子记录当前生效的段集合
```

### 3.1 已提交（committed）的定义

一次 `put/delete` 在方法成功返回前，记录已经：

1. 赋予全局单调递增序列号 `sequence`；
2. 追加进 WAL 帧（`magic + 长度 + CRC32 + 内容`）；
3. 当 `spatial.index.fsync=true`（默认）时执行 `fsync`，落盘后才对读者可见。

因此**方法成功返回 = 已提交 = 崩溃后必然存在**。

### 3.2 崩溃恢复

启动时：

1. 读取 `manifest.json`，加载其中列出的全部段文件（mmap）；
2. 扫描所有 `wal-*.log`，回放序列号高于“已落盘段最大序列号”的完整帧；
3. WAL 尾部**不完整或 CRC 校验失败的帧被丢弃**（代表崩溃时未写完的写入），
   不会出现半条记录，也不会破坏索引；
4. 清理所有 `*.tmp` 残留文件（未发布的段 / manifest）。

恢复后：已提交对象不丢失、不重复；未完成写入不出现在索引中。

### 3.3 段文件与 manifest 的原子发布

- 段先写到 `seg-N.seg.tmp`，`fsync` 后 **原子 rename** 成 `seg-N.seg`，再 fsync 目录；
- `manifest.json` 同样通过 `manifest.json.tmp + 原子 rename` 替换；
- 因此崩溃时要么是旧 manifest、要么是新 manifest，绝不会引用到半成品段。

### 3.4 并发一致性

- 所有写入由一把**公平读写锁的写锁串行化**（单写者）；
- 读者在读锁内获取一致快照后执行查询：活动内存表受锁保护，
  冻结内存表与段均为**不可变**结构，可被任意多线程并发 mmap 读取；
- 跨来源按“新 → 旧”合并，同一 id 取序列号最大（最先遇到）的版本，
  天然屏蔽更新旧值与墓碑；
- 因而读取只能看到完整的已提交写入，不会部分写入或对象丢失，结果可复现。

### 3.5 后台合并不阻塞读写

- flush 与 compaction 都在独立守护线程执行；
- 合并的重活（读旧段、归并、构建新 R-Tree、写新段）**不持锁**，读写照常进行；
- 仅在最后用读锁快照段集合、用极短写锁完成段指针与 manifest 的原子切换；
- 全量合并按最新版本去重并**清除墓碑**，从而回收已删除对象占用的空间；
- 合并前后范围查询与 kNN 查询结果一致（有自动化测试保证），合并可重复执行且幂等。

### 3.6 为什么查询不会退化成全表扫描

- 每个内存表与每个段都是一棵 **STR 批量加载的静态 R-Tree**；
- 范围查询沿包围盒剪枝，kNN 用分支定界（best-first + 距离上下界），
  只有可能落在当前截断距离内的子树才会被访问；
- 段文件采用内存映射，节点常驻、记录体（id/负载）按需解码，
  大数据量下堆内存占用平稳。

---

## 4. 配置项

前缀：`spatial.index`（也可用环境变量 / 系统属性覆盖）。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `data-dir` | `data/spatial-index` | 本地数据目录，存放 WAL、段文件、manifest；不存在会自动创建 |
| `memtable-max-records` | `2000` | 活动内存表累积多少条写操作后刷盘（越小刷盘越频繁） |
| `merge-threshold` | `4` | 已提交段数量达到该值时触发后台合并 |
| `rtree-node-capacity` | `32` | STR R-Tree 节点容量（扇出） |
| `fsync` | `true` | 每次提交 / 段发布是否 fsync。关闭可提升吞吐，但崩溃可能丢失最近写入（仅限测试 / 可丢数据场景） |

也可用环境变量覆盖默认目录：

```bash
SPATIAL_INDEX_DIR=/var/lib/spatial-index java -jar app.jar
```

---

## 5. 磁盘文件

| 文件 | 作用 |
| --- | --- |
| `wal-<id>.log` | 活动 / 未刷盘内存表的预写日志，帧带 CRC；刷盘成功后删除 |
| `seg-<id>.seg` | 不可变段：文件头 + 序列化 R-Tree 节点 + 记录区（小端二进制） |
| `manifest.json` | 当前生效段的原子清单 |
| `*.tmp` | 发布过程中的临时文件，崩溃后启动时自动清理 |

---

## 6. 测试

```bash
mvn test
```

覆盖内容：

- `EnvelopeTest`：边界包含 / 相触、2D/3D 距离、非法坐标；
- `StaticRTreeTest`：STR 建树、范围剪枝、kNN 排序与等距 tie-break、墓碑、3D；
- `SegmentRoundTripTest`：段文件二进制往返、payload/形状/墓碑保留、损坏临时文件不发布；
- `EngineBasicTest`：upsert/删除/范围/kNN、边界点、空结果、重复 id、重启恢复；
- `ConcurrencyTest`：多线程并发写入 + 读取期间无异常、无重复 / 半写入、结果可复现；
- `CrashRecoveryTest`：WAL 尾部撕裂帧 / CRC 损坏、temp 残留清理、合并后重启不丢不重；
- `MergeConsistencyTest`：合并前后范围与 kNN 结果一致、合并期间持续读写一致；
- `PerformanceBoundaryTest`：2 万对象下查询保持索引级延迟、删除后段被合并回收；
- `HttpApiTest`：HTTP 全流程、400/404 语义、空结果与确定性排序。

性能冒烟（`fsync=false`，本机）约为：5 万+ 写入 / 秒，点范围查询约 10µs，
k=10 最近邻约百微秒级；合并后段数量保持在常数。
