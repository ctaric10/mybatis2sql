# MyBatis XML to SQL extractor design

## GitHub research

- `gumuxiansheng/mybatis-sql-parser` is the closest project found. It walks
  MyBatis `SqlNode` objects, extracts OGNL conditions, generates parameter
  combinations, and calls `getBoundSql`. It depends on MyBatis, Spring,
  Guava, and Maven, and does not exhaustively combine every dynamic node.
- `fdamken/mybatis-mapper-parser` is a generic Mapper XML parser. It does not
  render or enumerate dynamic SQL.
- `Coco-king/mybatis-sql-parser` is currently an IntelliJ plugin template and
  has no relevant extraction implementation.

## Chosen design

The extractor is one Java source file and uses only JDK APIs. DOM parses each
Mapper XML without loading its external DTD. Each statement is converted to a
list of variants. Sequential nodes form a Cartesian product; `if` contributes
included and excluded paths; `choose` contributes every `when` and the
`otherwise` path. `where`, `set`, and `trim` apply their prefix/suffix rules to
each result. `sql/include` fragments are expanded recursively across Mapper
documents by looking up their fully qualified `namespace.id`.

All Mapper documents in the input scope are parsed before rendering, and each
fragment record retains its declaring namespace and source path. This lets an
external fragment resolve its own nested unqualified includes rather than
incorrectly using the statement's namespace. Include properties flow through
nested references. Fully qualified fragment IDs must be unique, and cycle
detection uses those qualified IDs so cycles spanning several documents are
reported. A qualified reference is resolved exactly as `namespace.id`; the
namespace is not converted into a directory or filename. Directory input uses
that directory as the recursive indexing scope, so defining and consuming XML
files may be in different subdirectories. Single-file input recursively indexes
the file's containing directory while emitting output only for the requested
file; callers should use a common ancestor directory as input when dependencies
live in sibling directories.

`foreach` has two generated representatives: one item and two items. Empty
loop output is omitted because surrounding static text can otherwise produce
broken SQL such as a bare `IN`. The two-item form renders the body twice and
uses the XML `separator` between them. Variants are deduplicated before output
conversion, then every remaining `#{...}` and `${...}` placeholder is written
as the single-quoted string `'?'`.

The `main` method contains editable local variables for the input and output
paths, so the command does not accept path arguments. The input can be either
one XML file or a directory. One source XML creates one same-stem `.sql` file.
Directory input is scanned recursively and relative paths are preserved.
Duplicate SQL text within one statement is removed. A configurable variant
limit prevents accidental exponential memory usage.

## Verification

Compile with `javac MybatisXmlToSql.java`, run it against the test Mapper, and
check that the output contains variants for `if`, `choose`, `include`, `trim`,
and both `foreach` modes. `MybatisXmlToSqlTest` runs the original fixture and a
cross-directory fixture where `business/order/UserMapper.xml` references
`xxx.yyy.aaa.ccc.bb` from `shared/ReusableSql.xml`. This verifies qualified
includes without filename assumptions, nested local includes in an external
fragment, property propagation, and relative output-directory preservation.
