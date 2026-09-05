-- =============================================================================
-- 02_analytics.sql — Executed by QueryParquetJob after Iceberg views exist.
-- Views v_collateral_position / v_securities_loan are created in Java from
-- iceberg_scan(<postgres metadata_location>).
-- =============================================================================

-- Demo 1: inventory by asset type per day
SELECT as_of_date,
       asset_type,
       ROUND(SUM(market_value), 2) AS total_market_value,
       COUNT(*) AS position_rows
FROM v_collateral_position
GROUP BY as_of_date, asset_type
ORDER BY as_of_date, asset_type;

-- Demo 2: account concentration (which accounts hold the most MV?)
SELECT as_of_date,
       account_id,
       ROUND(SUM(market_value), 2) AS total_mv
FROM v_collateral_position
GROUP BY as_of_date, account_id
ORDER BY as_of_date, total_mv DESC;

-- Demo 3: open loans joined to lender positions (simplified enrichment)
SELECT l.as_of_date,
       l.loan_id,
       l.lender_acct,
       l.instrument_id,
       l.quantity AS loan_qty,
       l.fee_bps,
       p.quantity AS lender_pos_qty,
       p.market_value AS lender_pos_mv
FROM v_securities_loan l
LEFT JOIN v_collateral_position p
  ON l.as_of_date = p.as_of_date
 AND l.lender_acct = p.account_id
 AND l.instrument_id = p.instrument_id
WHERE l.status = 'OPEN'
ORDER BY l.as_of_date, l.loan_id;

-- Demo 4: day-over-day MV change for ACC-A bonds (window function practice)
WITH bond_a AS (
  SELECT as_of_date,
         SUM(market_value) AS mv
  FROM v_collateral_position
  WHERE account_id = 'ACC-A' AND asset_type = 'BOND'
  GROUP BY as_of_date
)
SELECT as_of_date,
       ROUND(mv, 2) AS mv,
       ROUND(mv - LAG(mv) OVER (ORDER BY as_of_date), 2) AS dod_change
FROM bond_a
ORDER BY as_of_date;
