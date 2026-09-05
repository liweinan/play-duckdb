-- =============================================================================
-- 03_layered.sql — Executed by LayeredAnalyticsJob after Iceberg source views exist.
--
-- Why this file exists
-- --------------------
-- A single 80-line WITH that does "latest date + filter OPEN + join positions +
-- two report shapes" is hard to reconcile (which layer lost rows?), hard to
-- change (OPEN's definition lives in one buried predicate), and cannot be reused
-- by a second report without copy-paste.
--
-- DuckDB inlines VIEW / CTE into one physical plan. Do NOT CREATE TABLE every
-- step just to make the SQL look like a pipeline — that adds I/O and can stop
-- the optimizer fusing scans. Split on *business names*. Materialize only a
-- layer that is expensive *and* reused (or that you must checksum).
--
-- Layers in this script
-- ---------------------
--   source     v_collateral_position / v_securities_loan   (Java / Iceberg)
--   semantic   v_latest_as_of / v_latest_positions / v_open_loans   (VIEW)
--   reused     t_open_loans_enriched   (TEMP TABLE — joined once, read twice)
--   debug      SELECT step, n, qty/mv after each layer
--   report     thin SELECT from the named layers (same grain as ReportJob)
--
-- Fictional demo data only. OPEN / latest as_of_date are practice labels.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- ANTI-PATTERN (do not execute)
-- One giant WITH: latest-as-of, OPEN filter, join, and two report grains.
-- If inventory numbers look wrong you cannot ask "did v_open_loans already
-- drop SETTLED, or did the join explode?" — there is no named surface.
--
-- WITH latest AS (
--     SELECT MAX(as_of_date) AS d FROM v_collateral_position
-- ),
-- latest_pos AS (
--     SELECT p.* FROM v_collateral_position p, latest
--     WHERE p.as_of_date = latest.d
-- ),
-- open_loans AS (
--     SELECT * FROM v_securities_loan WHERE status = 'OPEN'
-- ),
-- enriched AS (
--     SELECT l.*, p.quantity AS lender_pos_qty, p.market_value AS lender_pos_mv
--     FROM open_loans l
--     LEFT JOIN latest_pos p
--       ON l.lender_acct = p.account_id AND l.instrument_id = p.instrument_id
-- )
-- SELECT 'inventory' AS report, ... FROM latest_pos
-- UNION ALL
-- SELECT 'open_loans', ... FROM enriched
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- Semantic VIEWs
-- These stay views so DuckDB can still inline them into later statements.
-- Change the OPEN predicate or "latest day" rule in exactly one place.
-- -----------------------------------------------------------------------------

-- Latest reporting date across all position rows (one row).
CREATE OR REPLACE VIEW v_latest_as_of AS
SELECT MAX(as_of_date) AS as_of_date
FROM v_collateral_position;

SELECT 'v_latest_as_of' AS step,
       COUNT(*) AS n,
       CAST(NULL AS DOUBLE) AS qty,
       CAST(NULL AS DOUBLE) AS mv
FROM v_latest_as_of;

-- Positions on that date only. Inventory reports should read this, not the
-- raw source with a repeated MAX(...) subquery.
CREATE OR REPLACE VIEW v_latest_positions AS
SELECT p.*
FROM v_collateral_position p
INNER JOIN v_latest_as_of d
  ON p.as_of_date = d.as_of_date;

SELECT 'v_latest_positions' AS step,
       COUNT(*) AS n,
       ROUND(SUM(quantity), 2) AS qty,
       ROUND(SUM(market_value), 2) AS mv
FROM v_latest_positions;

-- Business set: open securities loans. SETTLED stays out. If tomorrow "open"
-- means something else, edit only this view.
CREATE OR REPLACE VIEW v_open_loans AS
SELECT *
FROM v_securities_loan
WHERE status = 'OPEN';

SELECT 'v_open_loans' AS step,
       COUNT(*) AS n,
       ROUND(SUM(quantity), 2) AS qty,
       CAST(NULL AS DOUBLE) AS mv
FROM v_open_loans;

-- -----------------------------------------------------------------------------
-- One TEMP TABLE
-- Enrichment is joined once and then used by the checksum *and* two report
-- SELECTs. Materialize here. Do not also CTAS v_open_loans — that layer is
-- cheap and only has one consumer besides this join.
-- -----------------------------------------------------------------------------

CREATE OR REPLACE TEMP TABLE t_open_loans_enriched AS
SELECT l.as_of_date,
       l.loan_id,
       l.lender_acct,
       l.borrower_acct,
       l.instrument_id,
       l.quantity AS loan_qty,
       l.fee_bps,
       l.status,
       p.quantity AS lender_pos_qty,
       p.market_value AS lender_pos_mv
FROM v_open_loans l
LEFT JOIN v_latest_positions p
  ON l.lender_acct = p.account_id
 AND l.instrument_id = p.instrument_id;

SELECT 't_open_loans_enriched' AS step,
       COUNT(*) AS n,
       ROUND(SUM(loan_qty), 2) AS qty,
       ROUND(SUM(lender_pos_mv), 2) AS mv
FROM t_open_loans_enriched;

-- -----------------------------------------------------------------------------
-- Thin reports
-- No new joins. Grain matches ReportJob inventory / open-loan CSVs so you can
-- compare stdout here with reports/report_*.csv.
-- -----------------------------------------------------------------------------

SELECT p.as_of_date,
       p.asset_type,
       p.currency,
       COUNT(*) AS position_count,
       ROUND(SUM(p.market_value), 2) AS total_market_value
FROM v_latest_positions p
GROUP BY p.as_of_date, p.asset_type, p.currency
ORDER BY p.asset_type, p.currency;

SELECT as_of_date,
       COUNT(*) AS open_loan_count,
       ROUND(SUM(loan_qty), 2) AS total_qty,
       ROUND(AVG(fee_bps), 2) AS avg_fee_bps
FROM t_open_loans_enriched
GROUP BY as_of_date
ORDER BY as_of_date;
