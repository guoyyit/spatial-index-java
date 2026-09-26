# spatial-index-java

本地多维空间索引服务。支持二维/三维点与矩形对象的新增、删除、更新、
范围查询与最近邻查询，数据持久化在本地目录，进程重启或崩溃后无需
重建即可继续查询。

## 架构

LSM（Log-Structured Merge）结构：

```
写入 ──> WAL (fsync, 提交点) ──> Memtable (有界内存缓冲)
                                    │ 超过阈值
                                    ▼ 后台刷盘
                              Segment (不可变文件 + STR 打包 R-tree)
                                    │ 段数过多
                                    ▼ 后台合并 (不阻塞读写)
                              单个合并 Segment，回收覆盖/删除的空间
```

- **WAL**：每条记录带长度 + CRC32 帧。崩溃导致的半截尾记录在恢复时
  被检测并忽略。`spatial.wal-sync=true`（默认）时，写调用返回前完成
  fsync —— 这是**提交点**：返回成功的写入保证崩溃后不丢失。
- **Memtable**：内存写缓冲，按 id 存最新版本（含删除墓碑）。大小受
  `spatial.memtable-max-entries` 限制，写满后冻结并后台刷盘。
- **Segment**：不可变文件（tmp 写入 + fsync + 原子 rename），打开时
  在内存中构建 STR 打包 R-tree，范围/最近邻查询经 R-tree 剪枝，
  不会退化为全量扫描。
- **Manifest**：记录当前一致的段集合与已刷盘序列号。tmp + 原子
  rename 更新，崩溃后要么看到旧状态要么看到新状态，不会半写。
- **恢复**：加载 manifest 指定的段，清理孤儿 tmp/段文件，按序重放
  所有 WAL 中序列号大于已刷盘序列号的记录。

## 一致性语义

- **读写并发**：写操作持有写锁（WAL 追加 + fsync + 应用 memtable 为
  一个临界区），读操作持有读锁。读取只能看到**已提交的完整写入**；
  批量写入（`/api/objects/batch`）对读者是原子的，要么全见要么全不见。
- **可见性**：每个 id 的最新版本由序列号决定。范围/最近邻查询只返回
  最新版本；已删除对象（墓碑）不会出现在任何查询结果中。
- **确定性**：范围查询结果按 id 排序；最近邻查询按 (距离, id) 排序，
  距离相同按 id 字典序，重复执行结果完全一致。边界比较一律为闭区间
  （落在查询边界上的对象算命中）。重复 id 的写入即更新，后写覆盖先写。
  删除不存在的 id 是确定性空操作，返回 `existed=false`。
- **合并一致性**：合并期间读者继续使用旧段，合并产物通过 manifest
  原子切换，合并前后范围查询与最近邻查询结果完全一致。
- **崩溃恢复**：刷盘/合并中途崩溃时，重启后自动清理孤儿文件并恢复到
  最近一致状态：不出现重复对象、不丢失已提交对象、不损坏索引。

## 持久化语义

| 事件 | 保证 |
|---|---|
| 写调用返回（wal-sync=true） | 数据已 fsync 到 WAL，崩溃后可恢复 |
| 进程重启 | 已提交数据自动恢复，无需重建索引 |
| 刷盘/合并中途崩溃 | manifest 仍指向旧一致状态，WAL 重放补齐 |
| WAL 尾部撕裂写 | CRC 校验失败，恢复时截断忽略 |
| wal-sync=false | 写可能只到 OS 缓冲，崩溃可丢失最近未同步写入 |

## 配置项（application.properties）

| 配置 | 默认 | 说明 |
|---|---|---|
| `server.address` | `127.0.0.1` | 仅监听本地回环 |
| `server.port` | `8080` | HTTP 端口 |
| `spatial.data-dir` | `./data` | 数据目录（WAL/段/manifest） |
| `spatial.memtable-max-entries` | `10000` | 触发后台刷盘的 memtable 条目数 |
| `spatial.wal-sync` | `true` | 写返回前 fsync WAL（提交持久性） |
| `spatial.merge-segment-threshold` | `4` | 触发后台合并的段数量 |

## REST API

所有接口为本地 HTTP（默认 `http://127.0.0.1:8080`）。坐标为 2 或 3 维
数组；混合维度比较时缺失维度按 0 处理（二维对象位于 z=0 平面）。
`payload` 为可选 Base64 字符串。

| 方法 | 路径 | 说明 |
|---|---|---|
| `PUT` | `/api/objects` | 新增/更新单个对象 `{id,min,max,payload?}` |
| `POST` | `/api/objects/batch` | 原子批量写入 `{objects:[...]}` |
| `DELETE` | `/api/objects/{id}` | 删除，返回 `existed` |
| `GET` | `/api/objects/{id}` | 按 id 查询，不存在返回 404 |
| `POST` | `/api/query/range` | 范围查询 `{min,max,mode?,limit?}`，`mode` 为 `CONTAINS`（默认，边界包含语义）或 `INTERSECTS` |
| `POST` | `/api/query/knn` | 最近邻 `{point,k}`，返回按 (距离,id) 排序的数组 |
| `POST` | `/api/admin/flush` | 强制刷盘 |
| `POST` | `/api/admin/compact` | 强制合并并等待完成 |
| `GET` | `/api/stats` | 引擎统计（序列号、memtable/段数量、存活对象数等） |

示例：

```bash
curl -X PUT localhost:8080/api/objects -H 'Content-Type: application/json' \
  -d '{"id":"a","min":[1,1],"max":[1,1]}'
curl -X POST localhost:8080/api/query/knn -H 'Content-Type: application/json' \
  -d '{"point":[0,0],"k":5}'
```

## 运行与测试

```bash
mvn spring-boot:run   # 启动本地服务
mvn test              # 运行全部自动化测试
```

测试覆盖：R-tree 对照暴力法的正确性、CRUD 与边界值、重复 id、并发
读写原子性与可复现性、崩溃恢复（撕裂 WAL、孤儿段、反复崩溃）、合并
前后一致性、删除空间回收、持续写入/删除下的内存与延迟边界。
