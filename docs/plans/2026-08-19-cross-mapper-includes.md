# Cross-Mapper Include Resolution Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Resolve MyBatis `<include>` references across Mapper XML files, including nested unqualified references inside an external fragment.

**Architecture:** Parse the XML files in the input scope once, then build a global fragment registry keyed by the fully qualified `namespace.id`. Each registered fragment retains its declaring namespace so nested includes resolve in the correct Mapper context; neither resolution nor tests assume that namespace segments match directory or file names. Rendering still produces output only for the requested input file, or for every Mapper when the input is a directory.

**Tech Stack:** Java/JDK DOM APIs, PowerShell fixture comparison, Markdown documentation.

---

### Task 1: Add a cross-directory regression fixture

**Files:**
- Create: `testdata/cross-input/shared/ReusableSql.xml`
- Create: `testdata/cross-input/business/order/UserMapper.xml`
- Create: `testdata/cross-output/business/order/UserMapper.sql`

**Step 1:** Define `shared/ReusableSql.xml` with namespace `xxx.yyy.aaa.ccc`, fragment `bb`, and a nested local fragment, deliberately making its directory and filename differ from the namespace.

**Step 2:** Define `business/order/UserMapper.xml` with `<include refid="xxx.yyy.aaa.ccc.bb">` and an include property.

**Step 3:** Record the expected expanded SQL without unresolved-include comments.

### Task 2: Verify and expose the global fragment-index conversion path

**Files:**
- Modify: `MybatisXmlToSql.java`
- Create: `MybatisXmlToSqlTest.java`

**Step 1:** Add a callable `convert(Path input, Path output)` entry point so a no-dependency regression test can invoke conversion without editing `main`.

**Step 2:** Parse all XML files in the input scope into Mapper document records.

**Step 3:** Register every `<sql>` as `namespace.id`, retaining its source path and declaring namespace; reject duplicate fully qualified IDs.

**Step 4:** Resolve qualified references globally and unqualified references against the active namespace.

**Step 5:** While rendering an included fragment, switch the active namespace to its declaring Mapper and use its qualified ID for cycle detection.

**Step 6:** Add a Java regression harness that writes into a temporary output directory, compares the cross-directory result byte-for-byte with the checked-in expected SQL, and checks that no unresolved include remains.

### Task 3: Document the behavior

**Files:**
- Modify: `README.md`
- Modify: `docs/plans/2026-08-17-mybatis-xml-to-sql-design.md`

**Step 1:** Explain directory-wide indexing and single-file sibling-tree indexing.

**Step 2:** Document qualified cross-Mapper `refid`, nested resolution, property propagation, duplicate fragment errors, and cycle detection.

**Step 3:** Explicitly state that namespace resolution is independent of XML directory and filename, and document the regression-test command.

### Task 4: Verify regressions

**Files:**
- Verify: `testdata/output/UserMapper.sql`
- Verify: `testdata/cross-output/business/order/UserMapper.sql`
- Test: `MybatisXmlToSqlTest.java`

**Step 1:** Run `javac MybatisXmlToSql.java`; expect successful compilation.

**Step 2:** Run `java MybatisXmlToSqlTest`; expect both the original fixture and cross-directory fixture comparisons to pass.

**Step 3:** Confirm the generated cross-directory SQL expands `xxx.yyy.aaa.ccc.bb` and its nested local include without unresolved-include comments.

**Step 4:** Remove generated `.class` artifacts. No Git commit is made unless requested by the user.
