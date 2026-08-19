# 架构说明

## 目标

项目以单个 Java 源文件实现一个无第三方依赖的静态转换器：读取 MyBatis Mapper
XML，枚举动态 SQL 的代表性结构，并输出稳定、可审查的 SQL 文本。

## 处理流程

```text
发现 XML 文件
    ↓
安全解析 DOM（不加载外部 DTD）
    ↓
建立 namespace.id 全局片段索引
    ↓
递归渲染每条增删改查语句
    ↓
组合、限制并去重 SQL 变体
    ↓
规范化空白和参数占位符
    ↓
按相对目录写入 .sql 文件
```

## 核心组件

所有组件均为 `MybatisXmlToSql` 的内部类型，便于直接用 `javac` 编译。

- `MapperDocument`：保存 XML 路径、Mapper 根节点和 namespace。
- `FragmentRegistry`：以全限定 `namespace.id` 注册和查找 `<sql>` 片段。
- `Fragment`：保存片段节点、声明 namespace 和来源路径。
- `Renderer`：递归渲染 DOM 节点，并组合动态分支。
- `Variant`：同时保存 SQL 文本和产生该文本的分支说明。

## 变体生成

顺序节点通过笛卡尔积组合。`if` 贡献包含与不包含两条路径，`choose` 贡献每个候选
分支，`foreach` 贡献一个元素及两个元素的代表形式。`where`、`set` 和 `trim`
在子节点完成组合后应用包装规则。

每次可能扩大结果集时都会检查 `mybatis2sql.maxVariants`，避免在真正分配大型集合
前耗尽内存。语句渲染完成后使用 `LinkedHashMap` 按规范化 SQL 去重，因此输出顺序
可复现。

## include 解析

目录范围内的 Mapper 会先被全部解析，再统一注册片段。片段记录自己的声明
namespace；渲染外部片段时，嵌套的无前缀 refid 使用该 namespace，而不是最初
语句的 namespace。

引用栈保存全限定片段 ID，可检测跨文档的 `A -> B -> A` 循环。注册阶段也会拒绝
重复的全限定 ID，防止输出依赖文件扫描顺序。

## XML 安全

解析器关闭外部实体和外部 DTD 加载，不会为了处理 Mapper 的 DOCTYPE 而访问网络
或本地外部资源。项目只读取输入 XML，并将结果写入调用方指定的输出位置。

## 测试策略

`MybatisXmlToSqlTest` 使用临时输出目录运行转换，并把结果与 `testdata` 中提交的预期
SQL 逐字节比较（换行符除外）。测试同时覆盖普通动态标签和跨目录 Mapper 引用。
GitHub Actions 会在受支持的 JDK 环境中重复执行同一组测试。
