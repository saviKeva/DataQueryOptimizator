# Cost-Based SQL Query Optimizer & Execution Estimator

A **Java** tool that loads database schema statistics and buffer size (`M`) from JSON, validates `SELECT` queries, and estimates execution plans and costs in **Block Transfers (BT)** and **Seek Cost (SC)**.

## Features
- **Schema & Statistics Loader (`schema`, `Main`):** Parses buffer size, table/row/block counts, attribute selectivity metadata, and B+ Tree/Hash/composite indexes from JSON.
- **Syntax & Semantic Validation (`validator`):** Uses **JSqlParser** to verify tables, aliases, and attributes across `FROM`, `JOIN`, `WHERE`, and `SELECT` clauses.
- **Cost-Based Optimization (`QueryOptimizer`):**
  - Evaluates selection pushdown vs. deferred filtering to preserve indexes for joins.
  - Estimates selectivity and index access costs (clustered, non-clustered, hash, and composite indexes).
  - Performs greedy join enumeration across **BNLJ**, **INLJ**, **Sort-Merge Join**, and **Hash Join**.
  - Tracks intermediate result sizes against memory buffer `M` and calculates disk spill and external sorting (`ORDER BY`) costs.

## Project Structure
- `test/Main.java` — Entry point, JSON loading, and batch query execution.
- `schema/` — Data models (`Schema`, `Table`, `Attribute`, `Index`).
- `validator/` — Query validation (`SQLValidator`) and column extraction (`ColumnExtractor`).
- `optimizer/QueryOptimizer.java` — Execution plan selection and BT/SC cost estimation.

## Tech Stack
- **Java** | **JSqlParser** | **Jackson**