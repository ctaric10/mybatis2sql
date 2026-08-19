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
