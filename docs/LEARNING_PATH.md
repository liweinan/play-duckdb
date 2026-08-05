# Learning Path / 自学路径

面向「报表层：Parquet → DuckDB → Java」的工程练习。数据为**虚构样例**，与真实银行系统无关。

## 和岗位能力的对应关系

| 岗位描述（抽象） | 本项目练习点 |
|------------------|--------------|
| Vendor 产出 Parquet | `SeedParquetJob`：`COPY ... TO (FORMAT PARQUET, PARTITION_BY ...)` |
| DuckDB 查询与统计 | `QueryParquetJob` + `sql/02_analytics.sql` |
| 逻辑层隔离 | SQL `VIEW` 隐藏路径；文档中说明 Iceberg 是下一步 |
| Java 读数出报表 | `ReportJob`：JDBC + `COPY` 出 CSV |

## 建议学习顺序（约 1–2 晚）

### 1. 先跑通（30 分钟）

```bash
export http_proxy=http://localhost:7890
export https_proxy=http://localhost:7890
./run.sh all
```

看：

- `data/generated/collateral_position/as_of_date=.../*.parquet`
- `reports/report_*.csv`

### 2. 读懂三阶段代码（1 小时）

1. `SeedParquetJob` — 表结构、分区导出  
2. `QueryParquetJob` — `read_parquet` + view  
3. `ReportJob` — 聚合报表  

对照 `sql/02_analytics.sql` 里的窗口函数与 JOIN。

### 3. 自己改一题（1 小时）

任选：

- 新增一种 `asset_type`，重新 `seed` + `report`
- 在 `02_analytics.sql` 增加「按币种汇总 open loan」
- 让 `ReportJob` 再输出一份 JSON（可用纯字符串拼接或额外依赖）

### 4. Iceberg 概念（阅读，可选动手）

Apache Iceberg ≈ **表格式**：在 Parquet（等）文件之上提供：

- schema 演进
- snapshot / time travel
- 隐藏分区细节

DuckDB 可通过 `INSTALL iceberg; LOAD iceberg;` 查询部分 Iceberg 表（需 catalog/元数据）。  
本样例用 **Hive 分区目录 + VIEW** 降低门槛；工作中若团队已有 Iceberg catalog，再把 `read_parquet` 换成 Iceberg scan。

## 常用 DuckDB SQL 片段

```sql
-- 扫分区 Parquet
SELECT * FROM read_parquet('data/generated/collateral_position/**/*.parquet',
                           hive_partitioning=true);

-- 只看某一天（分区裁剪）
SELECT * FROM read_parquet('data/generated/collateral_position/**/*.parquet',
                           hive_partitioning=true)
WHERE as_of_date = DATE '2026-08-02';

-- 导出
COPY (SELECT 1 AS x) TO 'out.parquet' (FORMAT PARQUET);
```

## 本地不配 Docker 时

```bash
export http_proxy=http://localhost:7890 https_proxy=http://localhost:7890
mvn -q -DskipTests package
java -jar target/play-duckdb-1.0.0.jar all
```

## 安全提醒

- 勿把真实客户、真实持仓、真实系统名写进样例仓库  
- LinkedIn / 公开文档只用泛化表述（Java、reporting、Parquet、DuckDB）
