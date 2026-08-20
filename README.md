# mybatis2sql

`mybatis2sql` 是一个只依赖 JDK 的 MyBatis Mapper XML SQL 提取器。它会递归读取
Mapper XML，将动态标签可能产生的结构分支展开，并为每个 Mapper 生成同名 `.sql`
文件，便于代码审查、SQL 归档和人工分析。

> 这个工具枚举的是 SQL 的结构分支，不会执行 OGNL 表达式，也不会连接数据库。

## 特性

- 零第三方依赖，JDK 8 及以上版本即可编译运行。
- 支持单个 XML 或目录输入；目录模式保留原有相对目录结构。
- 支持 `if`、`choose`、`when`、`otherwise`、`foreach`、`where`、`set`、
  `trim`、`sql`、`include`、`property` 和 `bind`。
- 支持同一 Mapper 与跨 Mapper 的 SQL 片段引用，包括嵌套引用和属性传递。
- 检测重复的全限定片段 ID 和跨 Mapper 循环引用。
- 自动将最终未展开的 `#{...}` 与 `${...}` 参数替换为单引号字符串 `'?'`。
- 在动态节点拼接前移除源 SQL 的 `--` 行注释，避免单行化后注释误吞后续 SQL。
- 同一语句中相同的 `if test` 共享选择结果，保证动态 INSERT 的列和值同步出现。
- 默认限制单条语句的可达组合数；超限时自动改为逐条件生成，避免动态分支爆炸。

## 快速开始

项目没有 Maven 或 Gradle 依赖，直接使用 JDK 自带工具即可。

```shell
javac -encoding UTF-8 MybatisXmlToSql.java
```

在 `MybatisXmlToSql.java` 的 `main` 方法中设置输入和输出路径：

```java
String inputPath = "testdata/input";
String outputPath = "testdata/output";
```

然后运行：

```shell
java MybatisXmlToSql
```

也可以在其他 Java 代码中直接调用：

```java
MybatisXmlToSql.convert(
        Paths.get("src/main/resources/mapper"),
        Paths.get("generated-sql"));
```

## 输入与输出

目录输入会被递归扫描，每个包含 SQL 语句的 Mapper XML 都会生成一个同名 SQL
文件。输出目录保留输入文件的相对路径：

```text
mapper/                         generated-sql/
├── order/                     ├── order/
│   └── OrderMapper.xml   ->   │   └── OrderMapper.sql
└── shared/                    └── shared/
    └── CommonSql.xml              # 只有片段定义，不生成文件
```

每种 SQL 之前会写入动态分支注释，例如：

```sql
-- statement: selectByFilter [select]
-- variant 1/2: if(name != null)=false
SELECT id, name FROM users;

-- variant 2/2: if(name != null)=true
SELECT id, name FROM users WHERE name = '?';
```

Mapper SQL 中的 `--` 注释只删除到当前行末，后续行仍会正常参与生成。单引号、
双引号或反引号内的 `--` 不会被当作注释，`/* ... */` 块注释也会原样保留。
这里的清理只针对源 SQL；工具生成的语句、变体和分支说明注释不受影响。

## 支持规则概览

| 标签 | 处理方式 |
| --- | --- |
| `if` | 生成不包含、包含内容两种结构；相同规范化 test 共享状态 |
| `choose` | 为每个 `when` 和 `otherwise` 分别生成结果 |
| `foreach` | 生成一个元素、两个元素两种代表形式 |
| `where` / `set` / `trim` | 应用前后缀与覆盖规则 |
| `sql` / `include` | 递归展开本地或跨 Mapper 片段 |
| `property` | 将属性传入被引用片段及其嵌套引用 |
| `bind` | 不产生 SQL 文本，因此忽略 |

完整用法、跨 Mapper 示例、错误处理和已知限制见[使用指南](docs/USAGE.md)。
实现原理见[架构说明](docs/ARCHITECTURE.md)。

## 测试

回归测试不依赖第三方测试框架：

```shell
javac -encoding UTF-8 MybatisXmlToSql.java MybatisXmlToSqlTest.java
java MybatisXmlToSqlTest
```

成功时会输出：

```text
All MybatisXmlToSql regression tests passed.
```

## 项目结构

```text
.
├── MybatisXmlToSql.java       # 转换器实现与公共 convert 方法
├── MybatisXmlToSqlTest.java   # 无第三方依赖的回归测试
├── testdata/                  # 输入样例与预期 SQL
├── docs/                      # 使用、架构与设计文档
└── .github/workflows/ci.yml   # GitHub Actions 自动测试
```

## 已知限制

- 只关联属性替换后去首尾、压缩空白得到相同文本的 `if test`，不推导 OGNL 逻辑
  等价关系；例如 `a != null` 与 `null != a` 仍按两个条件处理。
- `foreach` 只用一个元素和两个元素代表非空集合，不枚举任意集合长度。
- `foreach` 的每个代表元素使用独立条件作用域，因为循环条件可能依赖 item/index。
- 空 `foreach` 不生成输出，以免与外部静态文本组合成残缺 SQL。
- 仅自动移除 `--` 行注释，不移除 `/* ... */` 块注释。
- 工具用于静态分析，不保证生成的每个分支都能在特定数据库方言中直接执行。

默认单条语句最多枚举 10,000 个可达兼容组合。结果超过上限时，程序会放弃该语句
的排列组合并从头按逐条件模式重新生成：每个唯一条件单独生成一条 SQL，不与其他
条件组合；相同条件在列区和 `VALUES` 区等不同位置的内容仍会一起输出。输出中会
标注本次降级，静态 SQL 片段仍会保留。

确实需要继续枚举更多组合时可使用：

```shell
java -Dmybatis2sql.maxVariants=50000 MybatisXmlToSql
```

如需参与改进，请先阅读[贡献指南](CONTRIBUTING.md)。

## 开源许可证

本项目采用 [MIT License](LICENSE) 开源。你可以自由使用、复制、修改和分发，但需
保留原始版权与许可声明。
