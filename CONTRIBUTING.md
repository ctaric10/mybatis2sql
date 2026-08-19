# 贡献指南

感谢你愿意改进 `mybatis2sql`。

## 开始之前

提交问题时，请尽量提供：

- JDK 版本和操作系统；
- 可复现问题的最小 Mapper XML；
- 期望 SQL 与实际 SQL；
- 完整错误信息，但请移除连接串、账号、密钥和业务敏感数据。

## 本地开发

项目保持零第三方依赖。修改后请在项目根目录执行：

```shell
javac -encoding UTF-8 MybatisXmlToSql.java MybatisXmlToSqlTest.java
java MybatisXmlToSqlTest
```

若修改输出行为，请同时更新：

1. `testdata/input` 或 `testdata/cross-input` 中的最小输入样例；
2. 对应输出目录中的预期 `.sql` 文件；
3. 必要的 README 或 `docs` 说明。

请勿提交 `.class`、IDE 配置、临时输出或包含真实业务数据的 Mapper。

## 变更原则

- 优先保持 JDK 标准库实现和单文件可编译特性。
- 新增动态标签行为时，应说明枚举策略以及组合数量的上界。
- 输出应保持确定性，相同输入重复运行应产生相同文件。
- 错误信息应包含足够的片段 ID 或文件位置，但不泄露输入内容中的敏感值。
