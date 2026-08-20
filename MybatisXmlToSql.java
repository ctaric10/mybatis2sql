import org.w3c.dom.CDATASection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 从 MyBatis Mapper XML 中提取所有可能的结构化 SQL 分支。
 *
 * <p>程序只使用 JDK 标准库，无需引入 MyBatis 或其他第三方依赖。编译运行方式：</p>
 * <pre>
 *   javac MybatisXmlToSql.java
 *   java MybatisXmlToSql
 * </pre>
 */
public final class MybatisXmlToSql {
    /** 防止多个动态标签进行笛卡尔积时产生过多 SQL 变体。 */
    private static final int DEFAULT_MAX_VARIANTS = 10_000;

    /** 匹配最终仍未被 include property 替换的 #{...} 和 ${...} 占位符。 */
    private static final Pattern MYBATIS_PLACEHOLDER =
            Pattern.compile("[#$]\\{[^{}]*}");

    private MybatisXmlToSql() {
    }

    public static void main(String[] args) throws Exception {
        // 修改下面两个变量以指定输入文件/目录和输出文件/目录。
        String inputPath = "testdata/input";
        String outputPath = "testdata/output";

        convert(Paths.get(inputPath), Paths.get(outputPath));
    }

    /**
     * 将一个 Mapper XML 或 Mapper 目录转换到指定输出位置。
     *
     * <p>目录输入会先递归索引目录下所有 Mapper 的 {@code namespace.id}，因此
     * 引用方和片段定义方可以位于不同子目录，XML 文件名也不需要与 namespace
     * 的任何部分相同。</p>
     */
    public static void convert(Path inputPath, Path outputPath) throws Exception {
        if (inputPath == null || outputPath == null) {
            throw new IllegalArgumentException("Input and output paths must not be null");
        }

        Path input = inputPath.toAbsolutePath().normalize();
        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Input does not exist: " + input);
        }

        boolean singleFile = Files.isRegularFile(input);
        if (singleFile && !isXml(input)) {
            throw new IllegalArgumentException("Input file must end with .xml: " + input);
        }
        Path output = outputPath.toAbsolutePath().normalize();

        // 必须先加载输入范围内的全部 Mapper 并建立全局片段索引，
        // 否则当前 Mapper 无法解析指向其他 namespace 的 <include>。
        List<Path> xmlFiles = discoverXmlFiles(input, singleFile);
        List<MapperDocument> mappers = loadMappers(xmlFiles);
        FragmentRegistry fragments = new FragmentRegistry();
        for (MapperDocument mapper : mappers) {
            fragments.registerAll(mapper);
        }

        if (singleFile) {
            // 单文件模式仍会扫描父目录树来解析外部片段，但只输出用户指定的 Mapper。
            MapperDocument requested = findMapper(mappers, input);
            if (requested == null) {
                System.out.println("Skip non-Mapper XML: " + input);
                return;
            }
            Path outputFile = output.toString().toLowerCase(Locale.ROOT).endsWith(".sql")
                    ? output
                    : output.resolve(sqlFileName(input));
            extractOne(requested, outputFile, fragments);
            return;
        }

        final Path outputRoot = output;
        int count = 0;
        for (MapperDocument mapper : mappers) {
            // 目录模式保留源文件相对于输入根目录的层级结构。
            Path relative = input.relativize(mapper.path);
            Path target = outputRoot.resolve(relative);
            target = target.resolveSibling(sqlFileName(target));
            if (extractOne(mapper, target, fragments)) {
                count++;
            }
        }
        System.out.println("Done. Generated " + count + " SQL file(s) under " + outputRoot);
    }

    /**
     * 确定本次解析需要索引的 XML 文件。
     * 单文件输入使用其父目录作为搜索根目录，目录输入则直接递归扫描该目录。
     */
    private static List<Path> discoverXmlFiles(Path input, boolean singleFile) throws Exception {
        Path searchRoot = singleFile ? input.getParent() : input;
        if (searchRoot == null) {
            searchRoot = input.toAbsolutePath().getParent();
        }
        List<Path> xmlFiles = new ArrayList<Path>();
        try (Stream<Path> paths = Files.walk(searchRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(MybatisXmlToSql::isXml)
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .forEach(xmlFiles::add);
        }
        return xmlFiles;
    }

    /** 解析 XML，并只保留根节点为 mapper 的文档。 */
    private static List<MapperDocument> loadMappers(List<Path> xmlFiles) throws Exception {
        List<MapperDocument> mappers = new ArrayList<MapperDocument>();
        for (Path xml : xmlFiles) {
            Document document = parseXml(xml);
            Element mapper = document.getDocumentElement();
            if (mapper != null && "mapper".equalsIgnoreCase(tagName(mapper))) {
                mappers.add(new MapperDocument(xml, mapper, mapper.getAttribute("namespace")));
            }
        }
        return mappers;
    }

    /** 从已加载的 Mapper 中找到单文件模式指定的那一个。 */
    private static MapperDocument findMapper(List<MapperDocument> mappers, Path path) {
        Path requested = path.toAbsolutePath().normalize();
        for (MapperDocument mapper : mappers) {
            if (mapper.path.equals(requested)) {
                return mapper;
            }
        }
        return null;
    }

    private static boolean isXml(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml");
    }

    private static String sqlFileName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + ".sql";
    }

    /**
     * 渲染一个 Mapper 中的全部增删改查语句，并写入对应 SQL 文件。
     * 返回 false 表示该 XML 只有片段定义，没有可输出的 SQL 语句。
     */
    private static boolean extractOne(MapperDocument mapperDocument, Path output,
                                      FragmentRegistry fragments) throws Exception {
        Path xml = mapperDocument.path;
        Element mapper = mapperDocument.mapper;
        Renderer renderer = new Renderer(fragments, mapperDocument.namespace);
        StringBuilder result = new StringBuilder();
        result.append("-- Generated from: ").append(xml.getFileName()).append('\n');
        result.append("-- foreach variants: one item, two items; empty output is omitted.\n\n");

        int statementCount = 0;
        NodeList children = mapper.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element) || !isStatement((Element) child)) {
                continue;
            }
            Element statement = (Element) child;
            List<Variant> rendered;
            Integer fallbackLimit = null;
            Map<String, String> noProperties = Collections.<String, String>emptyMap();
            try {
                // 组合时会同步相同 test 的状态，并在兼容结果真正超过上限时停止。
                rendered = renderer.renderChildren(statement, noProperties);
            } catch (VariantLimitExceededException exceeded) {
                rendered = renderer.renderChildrenIndividually(
                        statement, noProperties);
                fallbackLimit = exceeded.limit;
            }

            // 不同动态分支可能生成完全相同的 SQL，这里按规范化后的 SQL 去重，
            // LinkedHashMap 同时保证输出顺序稳定。
            LinkedHashMap<String, Variant> unique = new LinkedHashMap<String, Variant>();
            for (Variant variant : rendered) {
                String sql = normalizeSql(variant.text);
                if (!sql.isEmpty() && !unique.containsKey(sql)) {
                    unique.put(sql, new Variant(
                            sql, variant.decisions, variant.conditionStates));
                }
            }
            writeStatement(result, statement, unique, fallbackLimit);
            statementCount++;
        }

        if (statementCount == 0) {
            System.out.println("Skip XML without SQL statements: " + xml);
            return false;
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, result.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("Generated: " + output + " (" + statementCount + " statement(s))");
        return true;
    }

    /**
     * 使用关闭外部实体和外部 DTD 的 DOM 解析器读取 Mapper，避免网络访问和 XXE。
     */
    private static Document parseXml(Path xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException ignored) {
            // 某些旧版 JDK 不支持这些属性，上面设置的 SAX feature 仍会阻止外部资源加载。
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return builder.parse(xml.toFile());
    }

    /** 兼容不支持特定 XML feature 的 JDK 实现。 */
    private static void setFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // 忽略不支持的 feature，保证在不同 JDK XML 解析器上仍可运行。
        }
    }

    private static boolean isStatement(Element element) {
        String name = tagName(element).toLowerCase(Locale.ROOT);
        return "select".equals(name) || "insert".equals(name)
                || "update".equals(name) || "delete".equals(name);
    }

    private static String qualifiedFragmentId(String namespace, String id) {
        return namespace == null || namespace.isEmpty() ? id : namespace + "." + id;
    }

    /** 将一条语句的所有变体及其分支选择信息写成可直接查看的 SQL 文本。 */
    private static void writeStatement(StringBuilder output, Element statement,
                                       LinkedHashMap<String, Variant> variants,
                                       Integer fallbackLimit) {
        String type = tagName(statement).toUpperCase(Locale.ROOT);
        String id = statement.getAttribute("id");
        output.append("-- ============================================================\n");
        output.append("-- ").append(type).append(' ').append(id)
                .append(" (variants: ").append(variants.size()).append(")\n");
        output.append("-- ============================================================\n\n");
        if (fallbackLimit != null) {
            output.append("-- Enumeration skipped: variant limit ")
                    .append(fallbackLimit).append(" exceeded; generated one SQL per condition.\n\n");
        }
        int number = 1;
        for (Variant variant : variants.values()) {
            String outputSql = replaceMyBatisPlaceholders(variant.text);
            output.append("-- Variant ").append(number++).append('\n');
            if (!variant.decisions.isEmpty()) {
                output.append("-- Branches: ");
                for (int i = 0; i < variant.decisions.size(); i++) {
                    if (i > 0) {
                        output.append(" | ");
                    }
                    output.append(safeComment(variant.decisions.get(i)));
                }
                output.append('\n');
            }
            output.append(outputSql);
            if (!outputSql.endsWith(";")) {
                output.append(';');
            }
            output.append("\n\n");
        }
    }

    private static String safeComment(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replace("--", "-");
    }

    /**
     * 删除 SQL 文本中的 {@code --} 行注释，但保留结束该注释的换行。
     *
     * <p>必须在文本节点参与动态标签拼接前调用；否则拼接时移除换行后，行注释可能
     * 误吞后续节点。引号内的双减号以及块注释原样保留。</p>
     */
    private static String stripLineComments(String sql) {
        if (sql == null || sql.indexOf("--") < 0) {
            return sql == null ? "" : sql;
        }

        StringBuilder result = new StringBuilder(sql.length());
        char quoteEnd = 0;
        boolean blockComment = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);

            if (blockComment) {
                result.append(current);
                index++;
                if (current == '*' && index < sql.length() && sql.charAt(index) == '/') {
                    result.append('/');
                    index++;
                    blockComment = false;
                }
                continue;
            }

            if (quoteEnd != 0) {
                result.append(current);
                index++;
                if (current == '\\' && index < sql.length()) {
                    result.append(sql.charAt(index++));
                } else if (current == quoteEnd) {
                    if (index < sql.length() && sql.charAt(index) == quoteEnd) {
                        result.append(sql.charAt(index++));
                    } else {
                        quoteEnd = 0;
                    }
                }
                continue;
            }

            if (current == '/' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '*') {
                result.append("/*");
                index += 2;
                blockComment = true;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                result.append(current);
                quoteEnd = current;
                index++;
                continue;
            }
            if (current == '-' && index + 1 < sql.length()
                    && sql.charAt(index + 1) == '-') {
                index += 2;
                while (index < sql.length()) {
                    char commentCharacter = sql.charAt(index);
                    if (commentCharacter == '\r' || commentCharacter == '\n') {
                        break;
                    }
                    index++;
                }
                if (index < sql.length()) {
                    char lineBreak = sql.charAt(index++);
                    result.append(lineBreak);
                    if (lineBreak == '\r' && index < sql.length()
                            && sql.charAt(index) == '\n') {
                        result.append('\n');
                        index++;
                    }
                }
                continue;
            }

            result.append(current);
            index++;
        }
        return result.toString();
    }

    private static String normalizeSql(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }

    private static String replaceMyBatisPlaceholders(String sql) {
        return MYBATIS_PLACEHOLDER.matcher(sql).replaceAll("'?'");
    }

    private static int configuredMaxVariants() {
        return Integer.getInteger("mybatis2sql.maxVariants", DEFAULT_MAX_VARIANTS);
    }

    private static String tagName(Element element) {
        String name = element.getTagName();
        int colon = name.indexOf(':');
        return colon < 0 ? name : name.substring(colon + 1);
    }

    /**
     * 将 DOM 节点渲染成 SQL 变体列表。
     * 每个 Variant 同时保存 SQL 文本以及生成该文本时选择的动态分支。
     */
    private static final class Renderer {
        private final FragmentRegistry fragments;
        private final String rootNamespace;
        private final int maxVariants;

        /** 保存正在展开的全限定片段 ID，用于检测本地或跨文档循环引用。 */
        private final Deque<String> includeStack = new ArrayDeque<String>();

        /** 保存当前外部片段上下文，使嵌套的无前缀 refid 按片段所属 namespace 解析。 */
        private final Deque<Fragment> fragmentStack = new ArrayDeque<Fragment>();

        private Renderer(FragmentRegistry fragments, String namespace) {
            this.fragments = fragments;
            this.rootNamespace = namespace;
            this.maxVariants = configuredMaxVariants();
        }

        /**
         * 按 XML 中的先后顺序渲染所有子节点。
         * 每加入一个节点，都将已有结果与该节点的条件兼容结果做笛卡尔积组合。
         */
        private List<Variant> renderChildren(Node parent, Map<String, String> properties) {
            List<Variant> result = singleton("");
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                List<Variant> next = renderNode(children.item(i), properties);
                result = combine(result, next);
            }
            return result;
        }

        /**
         * 超过枚举上限后使用线性模式重新渲染整条语句。基线保存所有条件均不选时的
         * 静态 SQL，每个 alternative 只替换一个唯一条件分支；相同条件在不同区段的
         * alternative 会彼此合并，不同条件之间不再形成笛卡尔积。
         */
        private List<Variant> renderChildrenIndividually(
                Node parent, Map<String, String> properties) {
            IndividualVariants rendered = renderIndividualChildren(parent, properties);
            if (rendered.alternatives.isEmpty()) {
                return Collections.singletonList(rendered.baseline);
            }
            return rendered.alternatives;
        }

        private IndividualVariants renderIndividualChildren(
                Node parent, Map<String, String> properties) {
            IndividualVariants result = IndividualVariants.baseline(variant(""));
            NodeList children = parent.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                result = combineIndividually(
                        result, renderIndividualNode(children.item(i), properties));
            }
            return result;
        }

        private IndividualVariants renderIndividualNode(
                Node node, Map<String, String> properties) {
            if (node.getNodeType() == Node.TEXT_NODE || node instanceof CDATASection) {
                return IndividualVariants.baseline(
                        variant(substitute(stripLineComments(node.getNodeValue()), properties)));
            }
            if (!(node instanceof Element)) {
                return IndividualVariants.baseline(variant(""));
            }

            Element element = (Element) node;
            String name = tagName(element).toLowerCase(Locale.ROOT);
            if ("if".equals(name) || "when".equals(name)) {
                return renderIfIndividually(element, properties, name);
            }
            if ("choose".equals(name)) {
                return renderChooseIndividually(element, properties);
            }
            if ("where".equals(name)) {
                return wrapTrimIndividually(renderIndividualChildren(element, properties),
                        "WHERE", "", "AND|OR", "");
            }
            if ("set".equals(name)) {
                return wrapTrimIndividually(renderIndividualChildren(element, properties),
                        "SET", "", "", ",");
            }
            if ("trim".equals(name)) {
                return wrapTrimIndividually(renderIndividualChildren(element, properties),
                        attr(element, "prefix", properties),
                        attr(element, "suffix", properties),
                        attr(element, "prefixOverrides", properties),
                        attr(element, "suffixOverrides", properties));
            }
            if ("foreach".equals(name)) {
                return renderForeachIndividually(element, properties);
            }
            if ("include".equals(name)) {
                return renderIncludeIndividually(element, properties);
            }
            if ("bind".equals(name)) {
                return IndividualVariants.baseline(variant(""));
            }
            return renderIndividualChildren(element, properties);
        }

        /** 根据 MyBatis 动态标签类型分派到相应的渲染方法。 */
        private List<Variant> renderNode(Node node, Map<String, String> properties) {
            if (node.getNodeType() == Node.TEXT_NODE || node instanceof CDATASection) {
                return singleton(substitute(stripLineComments(node.getNodeValue()), properties));
            }
            if (!(node instanceof Element)) {
                return singleton("");
            }

            Element element = (Element) node;
            String name = tagName(element).toLowerCase(Locale.ROOT);
            if ("if".equals(name) || "when".equals(name)) {
                return renderIf(element, properties, name);
            }
            if ("choose".equals(name)) {
                return renderChoose(element, properties);
            }
            if ("where".equals(name)) {
                return wrapTrim(renderChildren(element, properties), "WHERE", "", "AND|OR", "");
            }
            if ("set".equals(name)) {
                return wrapTrim(renderChildren(element, properties), "SET", "", "", ",");
            }
            if ("trim".equals(name)) {
                return wrapTrim(renderChildren(element, properties),
                        attr(element, "prefix", properties), attr(element, "suffix", properties),
                        attr(element, "prefixOverrides", properties),
                        attr(element, "suffixOverrides", properties));
            }
            if ("foreach".equals(name)) {
                return renderForeach(element, properties);
            }
            if ("include".equals(name)) {
                return renderInclude(element, properties);
            }
            if ("bind".equals(name)) {
                return singleton("");
            }
            return renderChildren(element, properties);
        }

        /** `if`/`when` 同时生成“不包含内容”和“包含内容”两组结构分支。 */
        private List<Variant> renderIf(Element element, Map<String, String> properties, String label) {
            String test = attr(element, "test", properties);
            String conditionKey = conditionKey(test);
            List<Variant> result = new ArrayList<Variant>();
            result.add(new Variant("", Collections.singletonList(
                    label + "(" + test + ")=false")).withCondition(conditionKey, false));
            for (Variant included : renderChildren(element, properties)) {
                if (conditionCompatible(included, conditionKey, true)) {
                    result.add(included.withDecision(label + "(" + test + ")=true")
                            .withCondition(conditionKey, true));
                }
            }
            checkLimit(result.size());
            return result;
        }

        /** 空白差异不改变 OGNL test 的分组键；不尝试做表达式逻辑等价推导。 */
        private static String conditionKey(String test) {
            return normalizeSql(test);
        }

        private IndividualVariants renderIfIndividually(
                Element element, Map<String, String> properties, String label) {
            String test = attr(element, "test", properties);
            String conditionKey = conditionKey(test);
            IndividualVariants body = renderIndividualChildren(element, properties);
            Variant baseline = new Variant("",
                    Collections.singletonList(label + "(" + test + ")=false"))
                    .withCondition(conditionKey, false);
            List<Variant> alternatives = new ArrayList<Variant>();
            if (conditionCompatible(body.baseline, conditionKey, true)) {
                alternatives.add(body.baseline.withDecision(
                        label + "(" + test + ")=true")
                        .withCondition(conditionKey, true));
            }
            for (Variant alternative : body.alternatives) {
                if (conditionCompatible(alternative, conditionKey, true)) {
                    alternatives.add(alternative.withDecision(
                            label + "(" + test + ")=true")
                            .withCondition(conditionKey, true));
                }
            }
            return new IndividualVariants(baseline, alternatives);
        }

        private static boolean conditionCompatible(
                Variant variant, String conditionKey, boolean selected) {
            Boolean current = variant.conditionStates.get(conditionKey);
            return current == null || current.booleanValue() == selected;
        }

        /**
         * 为 choose 的每个 when 以及 otherwise 分别生成结果；
         * 没有 otherwise 时额外保留一个所有条件均不匹配的空分支。
         */
        private List<Variant> renderChoose(Element choose, Map<String, String> properties) {
            List<Variant> result = new ArrayList<Variant>();
            boolean hasOtherwise = false;
            NodeList children = choose.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element)) {
                    continue;
                }
                Element option = (Element) child;
                String optionName = tagName(option).toLowerCase(Locale.ROOT);
                if ("when".equals(optionName)) {
                    String test = attr(option, "test", properties);
                    for (Variant variant : renderChildren(option, properties)) {
                        result.add(variant.withDecision("choose when(" + test + ")"));
                    }
                } else if ("otherwise".equals(optionName)) {
                    hasOtherwise = true;
                    for (Variant variant : renderChildren(option, properties)) {
                        result.add(variant.withDecision("choose otherwise"));
                    }
                }
            }
            if (!hasOtherwise) {
                result.add(new Variant("", Collections.singletonList("choose no-match")));
            }
            checkLimit(result.size());
            return result;
        }

        private IndividualVariants renderChooseIndividually(
                Element choose, Map<String, String> properties) {
            List<Variant> alternatives = new ArrayList<Variant>();
            NodeList children = choose.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element)) {
                    continue;
                }
                Element option = (Element) child;
                String optionName = tagName(option).toLowerCase(Locale.ROOT);
                String decision;
                if ("when".equals(optionName)) {
                    decision = "choose when(" + attr(option, "test", properties) + ")";
                } else if ("otherwise".equals(optionName)) {
                    decision = "choose otherwise";
                } else {
                    continue;
                }

                IndividualVariants branch = renderIndividualChildren(option, properties);
                alternatives.add(branch.baseline.withDecision(decision));
                for (Variant alternative : branch.alternatives) {
                    alternatives.add(alternative.withDecision(decision));
                }
            }
            return new IndividualVariants(variant(""), alternatives);
        }

        /**
         * foreach 不尝试枚举任意集合长度，只生成一个元素和两个元素两种代表形式。
         * 两元素形式还会组合两次循环体内部可能出现的不同动态分支。
         */
        private List<Variant> renderForeach(Element foreach, Map<String, String> properties) {
            String collection = attr(foreach, "collection", properties);
            String item = attr(foreach, "item", properties);
            String index = attr(foreach, "index", properties);
            String open = attr(foreach, "open", properties);
            String close = attr(foreach, "close", properties);
            String separator = attr(foreach, "separator", properties);
            if (collection.isEmpty()) {
                collection = "collection";
            }
            if (item.isEmpty()) {
                item = "item";
            }
            if (index.isEmpty()) {
                index = "index";
            }

            List<Variant> result = new ArrayList<Variant>();
            List<Variant> bodies = renderChildren(foreach, properties);
            checkLimit(bodies.size() + (long) bodies.size() * bodies.size());
            String conditionScope = "foreach@"
                    + Integer.toHexString(System.identityHashCode(foreach)) + ":";

            // 单元素代表形式：item/index 分别指向 collection[0] 和 0。
            for (Variant body : bodies) {
                Variant scopedBody = body.withConditionScope(conditionScope + "item[0]:");
                String one = replaceLoopVariables(scopedBody.text, item, index,
                        collection + "[0]", "0");
                result.add(new Variant(join(open, join(one, close)), scopedBody.decisions,
                        scopedBody.conditionStates)
                        .withDecision("foreach(" + collection + ")=one"));
            }

            // 双元素代表形式：两个循环体独立选择动态分支，再用 separator 拼接。
            for (Variant firstBody : bodies) {
                for (Variant secondBody : bodies) {
                    Variant scopedFirst = firstBody.withConditionScope(
                            conditionScope + "item[0]:");
                    Variant scopedSecond = secondBody.withConditionScope(
                            conditionScope + "item[1]:");
                    String first = replaceLoopVariables(scopedFirst.text, item, index,
                            collection + "[0]", "0");
                    String second = replaceLoopVariables(scopedSecond.text, item, index,
                            collection + "[1]", "1");
                    List<String> decisions = new ArrayList<String>();
                    for (String decision : scopedFirst.decisions) {
                        decisions.add("item[0] " + decision);
                    }
                    for (String decision : scopedSecond.decisions) {
                        decisions.add("item[1] " + decision);
                    }
                    String two = joinWithSeparator(first, separator, second);
                    result.add(new Variant(join(open, join(two, close)), decisions,
                            mergeConditionStates(scopedFirst.conditionStates,
                                    scopedSecond.conditionStates))
                            .withDecision("foreach(" + collection + ")=two"));
                }
            }
            checkLimit(result.size());
            return result;
        }

        private IndividualVariants renderForeachIndividually(
                Element foreach, Map<String, String> properties) {
            String collection = attr(foreach, "collection", properties);
            String item = attr(foreach, "item", properties);
            String index = attr(foreach, "index", properties);
            String open = attr(foreach, "open", properties);
            String close = attr(foreach, "close", properties);
            String separator = attr(foreach, "separator", properties);
            if (collection.isEmpty()) {
                collection = "collection";
            }
            if (item.isEmpty()) {
                item = "item";
            }
            if (index.isEmpty()) {
                index = "index";
            }

            IndividualVariants bodies = renderIndividualChildren(foreach, properties);
            List<Variant> alternatives = new ArrayList<Variant>();
            String conditionScope = "foreach@"
                    + Integer.toHexString(System.identityHashCode(foreach)) + ":";
            addIndividualForeachVariants(alternatives, bodies.baseline,
                    collection, item, index, open, close, separator, conditionScope);
            for (Variant body : bodies.alternatives) {
                addIndividualForeachVariants(alternatives, body,
                        collection, item, index, open, close, separator, conditionScope);
            }
            return new IndividualVariants(variant(""), alternatives);
        }

        private static void addIndividualForeachVariants(
                List<Variant> result, Variant body, String collection,
                String item, String index, String open, String close, String separator,
                String conditionScope) {
            Variant scopedFirst = body.withConditionScope(conditionScope + "item[0]:");
            String first = replaceLoopVariables(scopedFirst.text, item, index,
                    collection + "[0]", "0");
            result.add(new Variant(join(open, join(first, close)), scopedFirst.decisions,
                    scopedFirst.conditionStates)
                    .withDecision("foreach(" + collection + ")=one"));

            Variant scopedSecond = body.withConditionScope(conditionScope + "item[1]:");
            String second = replaceLoopVariables(scopedSecond.text, item, index,
                    collection + "[1]", "1");
            List<String> decisions = new ArrayList<String>();
            for (String decision : scopedFirst.decisions) {
                decisions.add("item[0] " + decision);
            }
            for (String decision : scopedSecond.decisions) {
                decisions.add("item[1] " + decision);
            }
            String two = joinWithSeparator(first, separator, second);
            result.add(new Variant(join(open, join(two, close)), decisions,
                    mergeConditionStates(scopedFirst.conditionStates,
                            scopedSecond.conditionStates))
                    .withDecision("foreach(" + collection + ")=two"));
        }

        /**
         * 展开 include：先合并 property，再按当前片段所属 namespace 解析 refid。
         * 进入外部片段时将其压栈，从而让片段内部的无前缀 include 使用正确上下文。
         */
        private List<Variant> renderInclude(Element include, Map<String, String> properties) {
            Map<String, String> includeProperties = includeProperties(include, properties);
            String refid = attr(include, "refid", includeProperties);

            // 语句直接引用时使用当前 Mapper namespace；嵌套引用时使用外部片段 namespace。
            String activeNamespace = fragmentStack.isEmpty()
                    ? rootNamespace : fragmentStack.peek().namespace;
            Fragment fragment = fragments.resolve(refid, activeNamespace);
            if (fragment == null) {
                return singleton("/* unresolved include: " + safeComment(refid) + " */");
            }

            // 使用全限定 ID 检测，因此 A -> B -> A 这样的跨 Mapper 环也能被识别。
            if (includeStack.contains(fragment.qualifiedId)) {
                throw new IllegalArgumentException("Cyclic <include>: " + includeStack
                        + " -> " + fragment.qualifiedId);
            }
            includeStack.push(fragment.qualifiedId);
            fragmentStack.push(fragment);
            try {
                return renderChildren(fragment.element, includeProperties);
            } finally {
                fragmentStack.pop();
                includeStack.pop();
            }
        }

        private IndividualVariants renderIncludeIndividually(
                Element include, Map<String, String> properties) {
            Map<String, String> includeProperties = includeProperties(include, properties);
            String refid = attr(include, "refid", includeProperties);
            String activeNamespace = fragmentStack.isEmpty()
                    ? rootNamespace : fragmentStack.peek().namespace;
            Fragment fragment = fragments.resolve(refid, activeNamespace);
            if (fragment == null) {
                return IndividualVariants.baseline(
                        variant("/* unresolved include: " + safeComment(refid) + " */"));
            }
            if (includeStack.contains(fragment.qualifiedId)) {
                throw new IllegalArgumentException("Cyclic <include>: " + includeStack
                        + " -> " + fragment.qualifiedId);
            }
            includeStack.push(fragment.qualifiedId);
            fragmentStack.push(fragment);
            try {
                return renderIndividualChildren(fragment.element, includeProperties);
            } finally {
                fragmentStack.pop();
                includeStack.pop();
            }
        }

        private static Map<String, String> includeProperties(
                Element include, Map<String, String> properties) {
            Map<String, String> result = new LinkedHashMap<String, String>(properties);
            NodeList children = include.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element
                        && "property".equalsIgnoreCase(tagName((Element) child))) {
                    Element property = (Element) child;
                    result.put(property.getAttribute("name"),
                            substitute(property.getAttribute("value"), result));
                }
            }
            return result;
        }

        /** 实现 where、set 和 trim 共用的前后缀添加及覆盖词移除规则。 */
        private static List<Variant> wrapTrim(List<Variant> variants, String prefix, String suffix,
                                              String prefixOverrides, String suffixOverrides) {
            List<Variant> result = new ArrayList<Variant>();
            for (Variant variant : variants) {
                String text = normalizeSql(variant.text);
                text = removePrefix(text, prefixOverrides);
                text = removeSuffix(text, suffixOverrides);
                if (!text.isEmpty()) {
                    text = join(prefix, join(text, suffix));
                }
                result.add(new Variant(text, variant.decisions, variant.conditionStates));
            }
            return result;
        }

        private static IndividualVariants wrapTrimIndividually(
                IndividualVariants variants, String prefix, String suffix,
                String prefixOverrides, String suffixOverrides) {
            Variant baseline = wrapTrimVariant(
                    variants.baseline, prefix, suffix, prefixOverrides, suffixOverrides);
            List<Variant> alternatives = new ArrayList<Variant>();
            for (Variant alternative : variants.alternatives) {
                alternatives.add(wrapTrimVariant(
                        alternative, prefix, suffix, prefixOverrides, suffixOverrides));
            }
            return new IndividualVariants(baseline, alternatives);
        }

        private static Variant wrapTrimVariant(
                Variant variant, String prefix, String suffix,
                String prefixOverrides, String suffixOverrides) {
            String text = normalizeSql(variant.text);
            text = removePrefix(text, prefixOverrides);
            text = removeSuffix(text, suffixOverrides);
            if (!text.isEmpty()) {
                text = join(prefix, join(text, suffix));
            }
            return new Variant(text, variant.decisions, variant.conditionStates);
        }

        private static String removePrefix(String text, String overrides) {
            for (String token : splitOverrides(overrides)) {
                Pattern pattern = Pattern.compile("^(?i:" + Pattern.quote(token) + ")(?:\\s+|$)");
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    return text.substring(matcher.end()).trim();
                }
            }
            return text;
        }

        private static String removeSuffix(String text, String overrides) {
            for (String token : splitOverrides(overrides)) {
                Pattern pattern = Pattern.compile("(?:\\s+|^)(?i:" + Pattern.quote(token) + ")$");
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    return text.substring(0, matcher.start()).trim();
                }
                if (text.toLowerCase(Locale.ROOT).endsWith(token.toLowerCase(Locale.ROOT))) {
                    return text.substring(0, text.length() - token.length()).trim();
                }
            }
            return text;
        }

        private static List<String> splitOverrides(String value) {
            if (value == null || value.trim().isEmpty()) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<String>();
            for (String token : value.split("\\|")) {
                if (!token.trim().isEmpty()) {
                    result.add(token.trim());
                }
            }
            return result;
        }

        private static String replaceLoopVariables(String text, String item, String index,
                                                   String itemReplacement, String indexReplacement) {
            String result = replacePlaceholderVariable(text, item, itemReplacement);
            return replacePlaceholderVariable(result, index, indexReplacement);
        }

        private static String joinWithSeparator(String first, String separator, String second) {
            String left = normalizeSql(first);
            String right = normalizeSql(second);
            if (left.isEmpty()) {
                return right;
            }
            if (right.isEmpty()) {
                return left;
            }
            if (separator == null || separator.isEmpty()) {
                return left + " " + right;
            }
            return left + separator + " " + right;
        }

        private static String replacePlaceholderVariable(String text, String variable,
                                                         String replacement) {
            // 只替换占位符表达式开头的变量，避免把名称相似的普通文本误替换。
            Pattern pattern = Pattern.compile("([#$])\\{\\s*" + Pattern.quote(variable)
                    + "(?=\\.|\\s*[,}])");
            Matcher matcher = pattern.matcher(text);
            StringBuffer output = new StringBuffer();
            while (matcher.find()) {
                matcher.appendReplacement(output, Matcher.quoteReplacement(
                        matcher.group(1) + "{" + replacement));
            }
            matcher.appendTail(output);
            return output.toString();
        }

        private static String attr(Element element, String name, Map<String, String> properties) {
            return substitute(element.getAttribute(name), properties);
        }

        private static String substitute(String text, Map<String, String> properties) {
            String result = text == null ? "" : text;
            for (Map.Entry<String, String> property : properties.entrySet()) {
                result = result.replace("${" + property.getKey() + "}", property.getValue());
            }
            return result;
        }

        /**
         * 将相邻节点的变体做笛卡尔积，并合并两侧的 SQL 文本和分支说明。
         */
        private List<Variant> combine(List<Variant> left, List<Variant> right) {
            List<Variant> result = new ArrayList<Variant>();
            for (Variant first : left) {
                for (Variant second : right) {
                    if (!conditionsCompatible(first.conditionStates, second.conditionStates)) {
                        continue;
                    }
                    List<String> decisions = new ArrayList<String>(
                            first.decisions.size() + second.decisions.size());
                    decisions.addAll(first.decisions);
                    decisions.addAll(second.decisions);
                    result.add(new Variant(join(first.text, second.text), decisions,
                            mergeConditionStates(first.conditionStates,
                                    second.conditionStates)));
                    checkLimit(result.size());
                }
            }
            return result;
        }

        private static boolean conditionsCompatible(
                Map<String, Boolean> left, Map<String, Boolean> right) {
            for (Map.Entry<String, Boolean> entry : left.entrySet()) {
                Boolean other = right.get(entry.getKey());
                if (other != null && !other.equals(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }

        private static LinkedHashMap<String, Boolean> mergeConditionStates(
                Map<String, Boolean> left, Map<String, Boolean> right) {
            LinkedHashMap<String, Boolean> result =
                    new LinkedHashMap<String, Boolean>(left);
            result.putAll(right);
            return result;
        }

        private static IndividualVariants combineIndividually(
                IndividualVariants left, IndividualVariants right) {
            Variant baseline = combineVariants(left.baseline, right.baseline);
            List<Variant> alternatives = new ArrayList<Variant>(
                    left.alternatives.size() + right.alternatives.size());
            boolean[] matchedRight = new boolean[right.alternatives.size()];
            for (Variant leftAlternative : left.alternatives) {
                boolean matched = false;
                for (int index = 0; index < right.alternatives.size(); index++) {
                    Variant rightAlternative = right.alternatives.get(index);
                    if (sameSelectedConditions(leftAlternative, rightAlternative)) {
                        alternatives.add(combineVariants(
                                leftAlternative, rightAlternative));
                        matchedRight[index] = true;
                        matched = true;
                    }
                }
                if (!matched && conditionsCompatible(leftAlternative.conditionStates,
                        right.baseline.conditionStates)) {
                    alternatives.add(combineVariants(leftAlternative, right.baseline));
                }
            }
            for (int index = 0; index < right.alternatives.size(); index++) {
                Variant rightAlternative = right.alternatives.get(index);
                if (!matchedRight[index] && conditionsCompatible(
                        left.baseline.conditionStates, rightAlternative.conditionStates)) {
                    alternatives.add(combineVariants(left.baseline, rightAlternative));
                }
            }
            return new IndividualVariants(baseline, alternatives);
        }

        /** 线性降级中，选择了同一组 if 条件的两段 SQL 必须一起进入结果。 */
        private static boolean sameSelectedConditions(Variant left, Variant right) {
            List<String> leftSelected = selectedConditions(left);
            return !leftSelected.isEmpty()
                    && leftSelected.equals(selectedConditions(right));
        }

        private static List<String> selectedConditions(Variant variant) {
            List<String> result = new ArrayList<String>();
            for (Map.Entry<String, Boolean> entry : variant.conditionStates.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    result.add(entry.getKey());
                }
            }
            Collections.sort(result);
            return result;
        }

        private static Variant combineVariants(Variant first, Variant second) {
            if (!conditionsCompatible(first.conditionStates, second.conditionStates)) {
                throw new IllegalStateException(
                        "Cannot combine contradictory correlated condition states");
            }
            List<String> decisions = new ArrayList<String>(
                    first.decisions.size() + second.decisions.size());
            decisions.addAll(first.decisions);
            decisions.addAll(second.decisions);
            return new Variant(join(first.text, second.text), decisions,
                    mergeConditionStates(first.conditionStates, second.conditionStates));
        }

        private static Variant variant(String text) {
            return new Variant(text, Collections.<String>emptyList());
        }

        private static List<Variant> singleton(String text) {
            return Collections.singletonList(new Variant(text, Collections.<String>emptyList()));
        }

        /** 在真正分配集合前检查上限，避免动态分支爆炸耗尽内存。 */
        private void checkLimit(long size) {
            if (size > maxVariants) {
                throw new VariantLimitExceededException(maxVariants);
            }
        }

        private static String join(String left, String right) {
            String a = left == null ? "" : left.trim();
            String b = right == null ? "" : right.trim();
            if (a.isEmpty()) {
                return b;
            }
            if (b.isEmpty()) {
                return a;
            }
            return a + " " + b;
        }
    }

    /**
     * 全局 SQL 片段索引。键始终是 MyBatis 的全限定 ID（namespace.id），
     * 以便不同 Mapper 之间的 include 与片段内部的本地 include 使用同一套解析规则。
     */
    private static final class FragmentRegistry {
        private final Map<String, Fragment> fragments = new LinkedHashMap<String, Fragment>();

        private void registerAll(MapperDocument mapperDocument) {
            NodeList children = mapperDocument.mapper.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (!(child instanceof Element)
                        || !"sql".equalsIgnoreCase(tagName((Element) child))) {
                    continue;
                }

                Element element = (Element) child;
                String id = element.getAttribute("id").trim();
                if (id.isEmpty()) {
                    continue;
                }
                String qualifiedId = qualify(mapperDocument.namespace, id);
                Fragment fragment = new Fragment(qualifiedId, mapperDocument.namespace,
                        mapperDocument.path, element);
                Fragment previous = fragments.get(qualifiedId);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate SQL fragment '" + qualifiedId
                            + "' in " + previous.sourcePath + " and " + mapperDocument.path);
                }
                fragments.put(qualifiedId, fragment);
            }
        }

        private Fragment resolve(String refid, String activeNamespace) {
            String candidate = refid == null ? "" : refid.trim();
            if (candidate.isEmpty()) {
                return null;
            }

            Fragment local = fragments.get(qualify(activeNamespace, candidate));
            if (local != null) {
                return local;
            }
            return fragments.get(candidate);
        }

        private static String qualify(String namespace, String id) {
            String owner = namespace == null ? "" : namespace.trim();
            return owner.isEmpty() ? id : owner + "." + id;
        }
    }

    /** 一个 SQL 片段及其声明位置；namespace 用于解析片段内部的本地 include。 */
    private static final class Fragment {
        private final String qualifiedId;
        private final String namespace;
        private final Path sourcePath;
        private final Element element;

        private Fragment(String qualifiedId, String namespace, Path sourcePath, Element element) {
            this.qualifiedId = qualifiedId;
            this.namespace = namespace == null ? "" : namespace;
            this.sourcePath = sourcePath;
            this.element = element;
        }
    }

    /** 已解析的 Mapper 文档及其解析跨文档引用所需的元数据。 */
    private static final class MapperDocument {
        private final Path path;
        private final Element mapper;
        private final String namespace;

        private MapperDocument(Path path, Element mapper, String namespace) {
            this.path = path;
            this.mapper = mapper;
            this.namespace = namespace == null ? "" : namespace;
        }
    }

    /** 超限后线性渲染所需的基线 SQL 与单条件分支。 */
    private static final class IndividualVariants {
        private final Variant baseline;
        private final List<Variant> alternatives;

        private IndividualVariants(Variant baseline, List<Variant> alternatives) {
            this.baseline = baseline;
            this.alternatives = Collections.unmodifiableList(
                    new ArrayList<Variant>(alternatives));
        }

        private static IndividualVariants baseline(Variant baseline) {
            return new IndividualVariants(baseline, Collections.<Variant>emptyList());
        }
    }

    /** 只用于从普通枚举切换到单条件渲染，避免吞掉其他渲染错误。 */
    private static final class VariantLimitExceededException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final int limit;

        private VariantLimitExceededException(int limit) {
            super("Dynamic SQL produced more than " + limit + " variants");
            this.limit = limit;
        }
    }

    /** 一种 SQL 结构结果，以及生成它时经过的动态分支描述。 */
    private static final class Variant {
        private final String text;
        private final List<String> decisions;
        private final Map<String, Boolean> conditionStates;

        private Variant(String text, List<String> decisions) {
            this(text, decisions, Collections.<String, Boolean>emptyMap());
        }

        private Variant(String text, List<String> decisions,
                        Map<String, Boolean> conditionStates) {
            this.text = text == null ? "" : text;
            this.decisions = Collections.unmodifiableList(new ArrayList<String>(decisions));
            this.conditionStates = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Boolean>(conditionStates));
        }

        private Variant withDecision(String decision) {
            List<String> result = new ArrayList<String>(decisions);
            result.add(decision);
            return new Variant(text, result, conditionStates);
        }

        private Variant withCondition(String conditionKey, boolean selected) {
            LinkedHashMap<String, Boolean> result =
                    new LinkedHashMap<String, Boolean>(conditionStates);
            Boolean previous = result.put(conditionKey, selected);
            if (previous != null && previous.booleanValue() != selected) {
                throw new IllegalStateException(
                        "Contradictory state for correlated condition: " + conditionKey);
            }
            return new Variant(text, decisions, result);
        }

        private Variant withConditionScope(String scope) {
            if (conditionStates.isEmpty()) {
                return this;
            }
            LinkedHashMap<String, Boolean> result = new LinkedHashMap<String, Boolean>();
            for (Map.Entry<String, Boolean> entry : conditionStates.entrySet()) {
                result.put(scope + entry.getKey(), entry.getValue());
            }
            return new Variant(text, decisions, result);
        }
    }
}
