import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/** 不依赖测试框架的回归测试；通过 main 方法运行。 */
public final class MybatisXmlToSqlTest {
    private MybatisXmlToSqlTest() {
    }

    public static void main(String[] args) throws Exception {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        Path temporaryOutput = Files.createTempDirectory("mybatis2sql-test-");
        try {
            assertFixture(projectRoot, temporaryOutput, "input", "output", "UserMapper.sql");
            assertFixture(projectRoot, temporaryOutput, "cross-input", "cross-output",
                    "business/order/UserMapper.sql");

            Path crossResult = temporaryOutput.resolve("cross-input")
                    .resolve("business/order/UserMapper.sql");
            String sql = new String(Files.readAllBytes(crossResult), StandardCharsets.UTF_8);
            assertTrue(sql.contains("SELECT id, u.name , u.status"),
                    "qualified cross-directory include was not expanded");
            assertTrue(!sql.contains("unresolved include"),
                    "cross-directory result contains an unresolved include");
            assertDuplicateFragmentRejected(temporaryOutput);
            assertCyclicIncludeRejected(temporaryOutput);
            assertVariantLimitFallsBackToIndividualConditions(temporaryOutput);
            assertRepeatedIfTestsShareOneDecision(temporaryOutput);
            assertRepeatedIfTestsStayPairedDuringFallback(temporaryOutput);
            assertSqlLineCommentsRemoved(temporaryOutput);

            System.out.println("All MybatisXmlToSql regression tests passed.");
        } finally {
            deleteTree(temporaryOutput);
        }
    }

    private static void assertFixture(Path projectRoot, Path temporaryOutput,
                                      String inputName, String expectedName,
                                      String relativeSqlPath) throws Exception {
        Path input = projectRoot.resolve("testdata").resolve(inputName);
        Path actualRoot = temporaryOutput.resolve(inputName);
        MybatisXmlToSql.convert(input, actualRoot);

        Path expected = projectRoot.resolve("testdata").resolve(expectedName)
                .resolve(relativeSqlPath);
        Path actual = actualRoot.resolve(relativeSqlPath);
        assertTrue(Files.exists(actual), "missing generated SQL: " + actual);

        String expectedText = normalizeNewlines(Files.readAllBytes(expected));
        String actualText = normalizeNewlines(Files.readAllBytes(actual));
        assertTrue(expectedText.equals(actualText),
                "generated SQL differs from fixture: " + relativeSqlPath);
    }

    private static String normalizeNewlines(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static void assertDuplicateFragmentRejected(Path temporaryOutput) throws Exception {
        Path input = temporaryOutput.resolve("duplicate-input");
        Files.createDirectories(input);
        writeUtf8(input.resolve("FirstMapper.xml"),
                "<mapper namespace=\"test.shared\"><sql id=\"columns\">id</sql></mapper>");
        writeUtf8(input.resolve("SecondMapper.xml"),
                "<mapper namespace=\"test.shared\"><sql id=\"columns\">name</sql></mapper>");

        try {
            MybatisXmlToSql.convert(input, temporaryOutput.resolve("duplicate-output"));
            throw new AssertionError("duplicate qualified fragment ID was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate SQL fragment 'test.shared.columns'"),
                    "duplicate fragment error did not identify the qualified ID");
        }
    }

    private static void assertCyclicIncludeRejected(Path temporaryOutput) throws Exception {
        Path input = temporaryOutput.resolve("cycle-input");
        Files.createDirectories(input);
        writeUtf8(input.resolve("CycleMapper.xml"),
                "<mapper namespace=\"test.cycle\">"
                        + "<sql id=\"a\"><include refid=\"b\"/></sql>"
                        + "<sql id=\"b\"><include refid=\"a\"/></sql>"
                        + "<select id=\"selectCycle\"><include refid=\"a\"/></select>"
                        + "</mapper>");

        try {
            MybatisXmlToSql.convert(input, temporaryOutput.resolve("cycle-output"));
            throw new AssertionError("cyclic include was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Cyclic <include>"),
                    "cycle error did not identify the include cycle");
        }
    }

    private static void assertVariantLimitFallsBackToIndividualConditions(
            Path temporaryOutput) throws Exception {
        Path input = temporaryOutput.resolve("variant-limit-input");
        Path output = temporaryOutput.resolve("variant-limit-output");
        Files.createDirectories(input);
        writeUtf8(input.resolve("LimitMapper.xml"),
                "<mapper namespace=\"test.limit\">"
                        + "<select id=\"findUsers\">SELECT * FROM users <where>"
                        + "<if test=\"name != null\">AND name = #{name}</if>"
                        + "<if test=\"status != null\">AND status = #{status}</if>"
                        + "<if test=\"type != null\">AND type = #{type}</if>"
                        + "</where></select>"
                        + "</mapper>");

        String previousLimit = System.getProperty("mybatis2sql.maxVariants");
        System.setProperty("mybatis2sql.maxVariants", "4");
        try {
            MybatisXmlToSql.convert(input, output);
        } finally {
            if (previousLimit == null) {
                System.clearProperty("mybatis2sql.maxVariants");
            } else {
                System.setProperty("mybatis2sql.maxVariants", previousLimit);
            }
        }

        String sql = normalizeNewlines(Files.readAllBytes(output.resolve("LimitMapper.sql")));
        assertTrue(sql.contains("findUsers (variants: 3)"),
                "over-limit statement did not produce one variant per condition");
        assertTrue(sql.contains("Enumeration skipped: variant limit 4 exceeded"),
                "over-limit statement did not report the individual-condition fallback");
        assertTrue(countOccurrences(sql, "WHERE name = '?'") == 1,
                "name condition was not emitted exactly once");
        assertTrue(countOccurrences(sql, "WHERE status = '?'") == 1,
                "status condition was not emitted exactly once");
        assertTrue(countOccurrences(sql, "WHERE type = '?'") == 1,
                "type condition was not emitted exactly once");
        assertTrue(!sql.contains("name = '?' AND")
                        && !sql.contains("status = '?' AND")
                        && !sql.contains("type = '?' AND"),
                "individual conditions were still combined");
    }

    private static void assertRepeatedIfTestsShareOneDecision(
            Path temporaryOutput) throws Exception {
        Path input = temporaryOutput.resolve("correlated-input");
        Path output = temporaryOutput.resolve("correlated-output");
        Files.createDirectories(input);
        writeUtf8(input.resolve("AuthorityMapper.xml"),
                "<mapper namespace=\"test.authority\">"
                        + "<insert id=\"createAuthority\">INSERT INTO authority "
                        + "<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">"
                        + "<if test=\"participantID != null\">participant_id,</if>"
                        + "<if test=\"agencyID != null\">agency_id,</if>"
                        + "</trim>"
                        + "<trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\">"
                        + "<if test=\"  participantID   !=   null  \">#{participantID},</if>"
                        + "<if test=\"agencyID != null\">#{agencyID},</if>"
                        + "</trim>"
                        + "</insert>"
                        + "</mapper>");

        MybatisXmlToSql.convert(input, output);

        String sql = normalizeNewlines(Files.readAllBytes(output.resolve("AuthorityMapper.sql")));
        assertTrue(sql.contains("createAuthority (variants: 4)"),
                "repeated tests were enumerated as independent conditions");
        assertTrue(sql.contains("( participant_id ) VALUES ( '?' )"),
                "participant column and value were not selected together");
        assertTrue(sql.contains("( agency_id ) VALUES ( '?' )"),
                "agency column and value were not selected together");
        assertTrue(sql.contains("( participant_id, agency_id ) VALUES ( '?', '?' )"),
                "combined columns and values were not selected together");
        assertTrue(!sql.contains("( participant_id ) VALUES ( '?', '?' )")
                        && !sql.contains("( agency_id ) VALUES ( '?', '?' )")
                        && !sql.contains("( participant_id, agency_id ) VALUES ( '?' )"),
                "an impossible column/value combination was generated");
    }

    private static void assertRepeatedIfTestsStayPairedDuringFallback(
            Path temporaryOutput) throws Exception {
        Path input = temporaryOutput.resolve("correlated-limit-input");
        Path output = temporaryOutput.resolve("correlated-limit-output");
        Files.createDirectories(input);
        writeUtf8(input.resolve("AuthorityMapper.xml"),
                "<mapper namespace=\"test.authority.limit\">"
                        + "<insert id=\"createAuthority\">INSERT INTO authority "
                        + "<trim prefix=\"(\" suffix=\")\" suffixOverrides=\",\">"
                        + "<if test=\"participantID != null\">participant_id,</if>"
                        + "<if test=\"agencyID != null\">agency_id,</if>"
                        + "<if test=\"marketID != null\">market_id,</if>"
                        + "</trim>"
                        + "<trim prefix=\"VALUES (\" suffix=\")\" suffixOverrides=\",\">"
                        + "<if test=\"participantID != null\">#{participantID},</if>"
                        + "<if test=\"agencyID != null\">#{agencyID},</if>"
                        + "<if test=\"marketID != null\">#{marketID},</if>"
                        + "</trim>"
                        + "</insert>"
                        + "</mapper>");

        String previousLimit = System.getProperty("mybatis2sql.maxVariants");
        System.setProperty("mybatis2sql.maxVariants", "4");
        try {
            MybatisXmlToSql.convert(input, output);
        } finally {
            if (previousLimit == null) {
                System.clearProperty("mybatis2sql.maxVariants");
            } else {
                System.setProperty("mybatis2sql.maxVariants", previousLimit);
            }
        }

        String sql = normalizeNewlines(Files.readAllBytes(output.resolve("AuthorityMapper.sql")));
        assertTrue(sql.contains("createAuthority (variants: 3)"),
                "fallback did not generate one variant per unique condition");
        assertTrue(sql.contains("Enumeration skipped: variant limit 4 exceeded"),
                "correlated INSERT did not exercise the fallback renderer");
        assertTrue(countOccurrences(sql, "( participant_id ) VALUES ( '?' )") == 1,
                "participant fallback column and value were not paired");
        assertTrue(countOccurrences(sql, "( agency_id ) VALUES ( '?' )") == 1,
                "agency fallback column and value were not paired");
        assertTrue(countOccurrences(sql, "( market_id ) VALUES ( '?' )") == 1,
                "market fallback column and value were not paired");
    }

    private static void assertSqlLineCommentsRemoved(Path temporaryOutput) throws Exception {
        Path input = temporaryOutput.resolve("line-comment-input");
        Path output = temporaryOutput.resolve("line-comment-output");
        Files.createDirectories(input);
        writeUtf8(input.resolve("CommentMapper.xml"),
                "<mapper namespace=\"test.comments\">"
                        + "<select id=\"findUsers\">\n"
                        + "SELECT id\n"
                        + "-- remove before dynamic node\n"
                        + "<if test=\"includeName\">, name</if>\n"
                        + "-- remove after dynamic node\n"
                        + "FROM users\n"
                        + "WHERE note = '-- literal' -- remove trailing comment\n"
                        + "AND quoted = \"value--part\"\n"
                        + "AND ticked = `value--part`\n"
                        + "/* keep -- block comment */\n"
                        + "</select>"
                        + "</mapper>");

        MybatisXmlToSql.convert(input, output);

        String sql = normalizeNewlines(Files.readAllBytes(output.resolve("CommentMapper.sql")));
        assertTrue(sql.contains("SELECT id FROM users"),
                "line comment removed SQL from a later line");
        assertTrue(sql.contains("SELECT id , name FROM users"),
                "line comment removed a later dynamic node");
        assertTrue(sql.contains("'-- literal'"),
                "double dash inside a single-quoted value was removed");
        assertTrue(sql.contains("\"value--part\""),
                "double dash inside a double-quoted value was removed");
        assertTrue(sql.contains("`value--part`"),
                "double dash inside a backtick-quoted identifier was removed");
        assertTrue(sql.contains("/* keep -- block comment */"),
                "block comment was changed while removing line comments");
        assertTrue(!sql.contains("remove before dynamic node")
                        && !sql.contains("remove after dynamic node")
                        && !sql.contains("remove trailing comment"),
                "source SQL line comment remains in generated output");
    }

    private static int countOccurrences(String text, String value) {
        int count = 0;
        int start = 0;
        while ((start = text.indexOf(value, start)) >= 0) {
            count++;
            start += value.length();
        }
        return count;
    }

    private static void writeUtf8(Path path, String text) throws IOException {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new DeleteFailedException(exception);
                }
            });
        } catch (DeleteFailedException exception) {
            throw (IOException) exception.getCause();
        }
    }

    private static final class DeleteFailedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private DeleteFailedException(IOException cause) {
            super(cause);
        }
    }
}
