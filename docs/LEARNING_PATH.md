# Learning Path / 自学路径

面向「中间层 Spark 写 Iceberg，runtime DuckDB 出报表」的工程练习。数据为**虚构样例**。

## 和岗位能力的对应关系

| 岗位描述（抽象） | 本项目练习点 |
|------------------|--------------|
| Spark 写湖仓表 | `spark/jobs/seed_iceberg.py`：JdbcCatalog + MinIO |
| Catalog 指针 | Postgres `iceberg_tables.metadata_location` |
| DuckDB runtime 查询 | `QueryParquetJob` + `sql/02_analytics.sql` |
| 分层 SQL | `LayeredAnalyticsJob` + `sql/03_layered.sql` |
| 逻辑层隔离 | SQL `VIEW` 隐藏 metadata 路径 |
| Java 读数出报表 | `ReportJob`：JDBC + `COPY` 出 CSV |

## 建议学习顺序（约 1–2 晚）

### 1. 先跑通（30 分钟）

```bash
./run.sh all
```

看：

- MinIO 控制台 `http://localhost:9001` 下 `warehouse/` 的 `metadata/` 与 `data/`
- `psql` 里 `iceberg_tables.metadata_location`
- `reports/report_*.csv`

### 2. 读懂三阶段（1 小时）

1. `seed_iceberg.py` — Spark 建表、分区、INSERT  
2. `IcebergTables` — 读 Postgres 指针，再 `iceberg_scan`  
3. `ReportJob` — 聚合报表  

对照 `sql/02_analytics.sql` 里的窗口函数与 JOIN。

不要对表根目录做 `iceberg_scan('s3://warehouse/demo/...')`：JdbcCatalog 不写 `version-hint.text`，失败 commit 可能留下未登记的 metadata 文件。

### 3. 自己改一题（1 小时）

任选：

- 在 `seed_iceberg.py` 再 `INSERT` 一行，重新 `seed` + `report`，确认 DuckDB 读到新 snapshot
- 在 `02_analytics.sql` 增加「按币种汇总 open loan」
- 让 `ReportJob` 再输出一份 JSON

### 4. Catalog 在干什么

Iceberg catalog 只保管「当前 metadata.json 地址」这一枚可变指针。数据文件写出后不改。Spark commit 用 Postgres 事务切换指针；DuckDB 读同一枚指针，才能和 Spark 看到同一个 current snapshot。

### 5. 分层 SQL（`./run.sh layers`）

对照 [`sql/03_layered.sql`](../sql/03_layered.sql)。文件开头的巨型 `WITH` 是**反例（不执行）**：最新日、OPEN 过滤、JOIN、两份报表揉在一起，出错时无法问「是 OPEN 口径丢了行，还是 JOIN 炸了」。

实际执行的是按**业务名字**拆开的层：

| 层 | 对象 | 为何这种形式 |
|----|------|----------------|
| 源 | `v_collateral_position` / `v_securities_loan` | Java 扫 Iceberg，藏 S3 路径 |
| 语义 | `v_latest_as_of` / `v_latest_positions` / `v_open_loans` | VIEW。口径只写一处；DuckDB 仍可内联 |
| 复用 | `t_open_loans_enriched` | TEMP TABLE。对账和下方两份输出都读它，只 JOIN 一次 |
| 对账 | `SELECT step, n, qty, mv` | 每层后打行数 / 数量 / 市值 |
| 报表 | 薄 `SELECT` | 不再写 JOIN，口径对齐 `ReportJob` |

不要为了「看起来像步骤」把每一层都 `CREATE TABLE`。VIEW/CTE 会被内联；该物化的是贵且复用、或必须对账的那一层。`OPEN` 的定义只放在 `v_open_loans`。

跑完看 stdout 的 checksum，以及 `reports/report_layered_open_loans_*.csv`。

## 常用 DuckDB SQL 片段

```sql
INSTALL iceberg; LOAD iceberg;
INSTALL httpfs; LOAD httpfs;

CREATE SECRET (
  TYPE S3,
  KEY_ID 'admin',
  SECRET 'password',
  ENDPOINT 'localhost:9000',
  URL_STYLE 'path',
  USE_SSL false
);

SELECT * FROM iceberg_scan('s3://warehouse/demo/collateral_position/metadata/<file>.metadata.json',
                           allow_moved_paths = true);
```

## 安全提醒

- 勿把真实客户、真实持仓、真实系统名写进样例仓库
- LinkedIn / 公开文档只用泛化表述（Java、Spark、Iceberg、DuckDB）
