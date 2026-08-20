# 使用指南

## 环境要求

- JDK 8 或更高版本
- 不需要 Maven、Gradle、MyBatis 或数据库连接

可以通过 `java -version` 和 `javac -version` 确认本机环境。

## 编译与运行

在项目根目录执行：

```shell
javac -encoding UTF-8 MybatisXmlToSql.java
java MybatisXmlToSql
```

命令行入口当前不接收路径参数。运行前需要修改 `main` 方法中的两个变量：

```java
String inputPath = "testdata/input";
String outputPath = "testdata/output";
```

如果在其他 Java 程序中集成，可直接调用公共方法：

```java
MybatisXmlToSql.convert(inputPath, outputPath);
```

两个参数的类型均为 `java.nio.file.Path`。

## 路径规则

### 目录输入

输入目录下的 XML 会被递归扫描。工具先加载范围内的全部 Mapper 并建立 SQL
片段索引，再开始生成文件，因此引用方和片段定义方可以位于不同子目录。

输出目录保留相对层级。仅包含 `<sql>` 片段、没有 `select`、`insert`、`update`
或 `delete` 语句的 XML 会参与索引，但不会生成空的 SQL 文件。

### 单文件输入

输入为单个 XML 时，只为该文件生成 SQL。为了支持跨 Mapper 引用，工具仍会递归
索引该文件所在目录及其子目录。若被引用 Mapper 位于兄弟目录，应把两者共同的上级
目录作为输入。

输出参数以 `.sql` 结尾时会被视为目标文件；否则会被视为输出目录，并在其中生成
与输入 XML 同名的 `.sql` 文件。

## 跨 Mapper 引用

片段以 MyBatis 的全限定 `namespace.id` 注册。例如：

```xml
<!-- shared/ReusableSql.xml -->
<mapper namespace="com.example.shared">
    <sql id="userColumns">id, ${alias}.name</sql>
</mapper>
```

另一个 Mapper 可以通过全限定 ID 引用，并传递属性：

```xml
<include refid="com.example.shared.userColumns">
    <property name="alias" value="u"/>
</include>
```

解析只依据 `namespace.id`，不要求 namespace 与 XML 文件名或目录结构相同。外部
片段中的无前缀 `<include refid="otherFragment"/>` 会继续相对于该片段所属的
namespace 解析，属性也会继续传入嵌套引用。

## 动态标签行为

### 条件与选择

`if` 会生成排除和包含内容两种结构。`choose` 会为每个 `when` 及 `otherwise`
各生成一种结果；没有 `otherwise` 时还会保留一个无条件匹配的空结果。

工具不执行 OGNL，也不会推断两个条件是否矛盾。分支注释保留原始 `test` 文本，
方便人工判断。

### foreach

`foreach` 生成一个元素和两个元素两种代表形式，分支注释分别标记为
`foreach(collection)=one` 和 `foreach(collection)=two`。两个元素之间使用 XML
声明的 `separator`，循环体中的 item 和 index 占位符会分别映射到对应位置。

空集合形式不会生成，以免如下静态文本产生残缺 SQL：

```xml
id IN
<foreach collection="ids" open="(" close=")" separator=",">#{id}</foreach>
```

### where、set 与 trim

这些标签会先渲染内部变体，再应用 MyBatis 风格的 prefix、suffix、
prefixOverrides 和 suffixOverrides 规则。内部结果为空时不会添加前后缀。

## 参数与输出

片段属性替换完成后，剩余的 `#{...}` 与 `${...}` 会统一输出为 `'?'`，便于阅读，
同时避免把样例结果误当成真实参数值。

同一语句中生成的重复 SQL 会被去重，但保留首次出现的顺序。每个结果前会记录语句
ID、语句类型、变体序号和动态分支选择。

## 错误与保护机制

- 输入不存在：抛出 `IllegalArgumentException`。
- 单文件输入不是 `.xml`：抛出 `IllegalArgumentException`。
- 出现重复的全限定片段 ID：报出 ID 和两个来源文件。
- 出现本地或跨 Mapper 循环引用：报出引用链。
- 找不到 include：在 SQL 中输出 `/* unresolved include: ... */`，便于定位缺失文件。
- 单条语句超过 10,000 个中间组合：停止枚举并从头按逐条件模式生成；每个条件单独
  生成一条 SQL，不与其他条件排列组合。

提高组合上限的示例：

```shell
java -Dmybatis2sql.maxVariants=50000 MybatisXmlToSql
```

提高上限会增加内存和处理时间。保持默认值时，发生降级的语句会在输出中显示
`Enumeration skipped` 说明；未超限语句仍按原有方式完整枚举。
