# Correlated If Conditions Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make repeated MyBatis `<if>` tests share one condition decision so split INSERT columns and values are always emitted together.

**Architecture:** Normalize each substituted `test` expression into a condition key and carry selected condition states through every SQL variant. Compatible variants may be combined; contradictory states are rejected. The over-limit renderer groups alternatives by the same key so all occurrences are selected together.

**Tech Stack:** Java 8, DOM, the repository's framework-free regression test runner.

---

### Task 1: Add regression coverage

**Files:**
- Modify: `MybatisXmlToSqlTest.java`

**Steps:**
1. Add a normal-enumeration INSERT whose column and value trims repeat the same tests.
2. Assert that only the four realizable condition assignments are generated.
3. Add an over-limit INSERT with three repeated tests.
4. Assert that every fallback SQL contains matching columns and values.
5. Run the test and confirm it fails against the node-independent implementation.

### Task 2: Correlate condition decisions

**Files:**
- Modify: `MybatisXmlToSql.java`

**Steps:**
1. Add normalized condition-state metadata to `Variant`.
2. Mark both true and false `<if>` branches with the same key.
3. Filter contradictory combinations while preserving compatible repeated decisions.
4. Scope condition states inside representative `foreach` iterations.
5. Group individual fallback alternatives by condition key.
6. Replace the node-count precheck with bounded compatible combination.

### Task 3: Document and verify

**Files:**
- Modify: `README.md`
- Modify: `docs/ARCHITECTURE.md`
- Modify: `docs/USAGE.md`

**Steps:**
1. Document the condition normalization and correlation boundary.
2. Document compatible-state enumeration and over-limit fallback behavior.
3. Compile with Java 8-compatible source and run all regression tests.
4. Review the final diff for unrelated changes.
