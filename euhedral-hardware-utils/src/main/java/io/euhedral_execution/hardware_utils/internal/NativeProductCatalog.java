package io.euhedral_execution.hardware_utils.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

final class NativeProductCatalog {

    static final String RESOURCE = "/META-INF/euhedral/native-products.tsv";
    private static final int MAX_CATALOG_BYTES = 65_536;

    static NativeProductCatalog load() throws IOException {
        InputStream catalog = JNIClassLoader.class.getResourceAsStream(RESOURCE);
        if (catalog == null) {
            throw failure("missing runtime catalog " + RESOURCE);
        }
        return parse(catalog, path -> {
            try (InputStream resource = JNIClassLoader.class.getResourceAsStream(path)) {
                return resource != null;
            }
        });
    }

    static NativeProductCatalog parse(String catalog, ResourceProbe resourceProbe)
            throws IOException {
        return parse(new ByteArrayInputStream(catalog.getBytes(StandardCharsets.UTF_8)),
                resourceProbe);
    }

    static NativeProductCatalog parse(InputStream input, ResourceProbe resourceProbe)
            throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(resourceProbe, "resourceProbe");
        byte[] bytes;
        try (input) {
            bytes = input.readNBytes(MAX_CATALOG_BYTES + 1);
        }
        if (bytes.length > MAX_CATALOG_BYTES) {
            throw failure("runtime catalog exceeds " + MAX_CATALOG_BYTES + " bytes");
        }
        if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
            throw failure("runtime catalog must be non-empty and end with LF");
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb
                && bytes[2] == (byte) 0xbf) {
            throw failure("runtime catalog must not contain a BOM");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw failure("runtime catalog is not valid UTF-8", e);
        }
        if (text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0) {
            throw failure("runtime catalog contains a forbidden character");
        }

        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !"schema\t1".equals(lines[0])) {
            throw failure("runtime catalog must start with schema 1");
        }
        List<OsRule> osRules = new ArrayList<>();
        Map<String, String> architectures = new LinkedHashMap<>();
        List<NativeProduct> products = new ArrayList<>();
        Set<String> rowKeys = new HashSet<>();
        Set<String> productIds = new HashSet<>();
        Set<String> resourcePaths = new HashSet<>();
        Set<String> candidateOrders = new HashSet<>();
        int section = 1;

        for (int lineNumber = 1; lineNumber < lines.length - 1; lineNumber++) {
            String line = lines[lineNumber];
            if (line.isEmpty()) {
                throw failure("blank row at line " + (lineNumber + 1));
            }
            String[] fields = line.split("\t", -1);
            validateFields(fields, lineNumber + 1);
            switch (fields[0]) {
                case "os" -> {
                    if (section > 1) {
                        throw failure(
                                "OS row appears after a later section at line " + (lineNumber + 1));
                    }
                    requireFieldCount(fields, 4, lineNumber);
                    Match match = switch (fields[1]) {
                        case "exact" -> Match.EXACT;
                        case "prefix" -> Match.PREFIX;
                        default -> throw failure("unknown OS match at line " + (lineNumber + 1));
                    };
                    String key = "os\t" + fields[1] + '\t' + fields[2];
                    if (!rowKeys.add(key)) {
                        throw failure("duplicate OS rule at line " + (lineNumber + 1));
                    }
                    osRules.add(new OsRule(match, fields[2], fields[3]));
                }
                case "arch" -> {
                    if (section > 2) {
                        throw failure(
                                "architecture row appears after products at line " + (lineNumber
                                        + 1));
                    }
                    section = 2;
                    requireFieldCount(fields, 3, lineNumber);
                    String previous = architectures.putIfAbsent(fields[1], fields[2]);
                    if (previous != null) {
                        throw failure("duplicate architecture alias at line " + (lineNumber + 1));
                    }
                }
                case "product" -> {
                    section = 3;
                    requireFieldCount(fields, 7, lineNumber);
                    int order;
                    try {
                        order = Integer.parseInt(fields[5]);
                    } catch (NumberFormatException e) {
                        throw failure("invalid load order at line " + (lineNumber + 1), e);
                    }
                    NativeProduct product;
                    try {
                        product = new NativeProduct(
                                fields[1], fields[2], fields[3], fields[4], order, fields[6]);
                    } catch (IllegalArgumentException e) {
                        throw failure(e.getMessage(), e);
                    }
                    if (!productIds.add(product.id())) {
                        throw failure("duplicate product id " + product.id());
                    }
                    if (!resourcePaths.add(product.resourcePath())) {
                        throw failure("duplicate product resource " + product.resourcePath());
                    }
                    String candidateOrder =
                            product.operatingSystem() + '\t' + product.architecture()
                                    + '\t' + product.loadOrder();
                    if (!candidateOrders.add(candidateOrder)) {
                        throw failure(
                                "duplicate candidate load order for " + product.operatingSystem()
                                        + '/' + product.architecture() + ": "
                                        + product.loadOrder());
                    }
                    products.add(product);
                }
                default -> throw failure("unknown row type at line " + (lineNumber + 1));
            }
        }
        if (osRules.isEmpty() || architectures.isEmpty() || products.isEmpty()) {
            throw failure("runtime catalog has an empty required section");
        }
        validateOsRules(osRules);
        for (NativeProduct product : products) {
            if (osRules.stream()
                    .noneMatch(rule -> rule.canonical().equals(product.operatingSystem()))) {
                throw failure("product " + product.id() + " references an unknown OS");
            }
            if (!architectures.containsValue(product.architecture())) {
                throw failure("product " + product.id() + " references an unknown architecture");
            }
            if (!resourceProbe.exists(product.resourcePath())) {
                throw failure("missing product resource " + product.resourcePath());
            }
        }
        return new NativeProductCatalog(osRules, architectures, products);
    }

    private static void validateOsRules(List<OsRule> rules) throws IOException {
        Map<String, String> exact = new HashMap<>();
        for (OsRule rule : rules) {
            if (rule.match() == Match.EXACT
                    && exact.putIfAbsent(rule.value(), rule.canonical()) != null) {
                throw failure("ambiguous exact OS rule " + rule.value());
            }
        }
        List<OsRule> prefixes = rules.stream().filter(rule -> rule.match() == Match.PREFIX)
                .toList();
        for (int left = 0; left < prefixes.size(); left++) {
            for (int right = left + 1; right < prefixes.size(); right++) {
                String a = prefixes.get(left).value();
                String b = prefixes.get(right).value();
                if (a.startsWith(b) || b.startsWith(a)) {
                    throw failure("ambiguous prefix OS rules " + a + " and " + b);
                }
            }
        }
    }

    private static void validateFields(String[] fields, int lineNumber) throws IOException {
        for (String field : fields) {
            if (field.isEmpty() || !field.equals(field.strip())) {
                throw failure("empty or padded field at line " + lineNumber);
            }
        }
    }

    private static void requireFieldCount(String[] fields, int expected, int zeroBasedLine)
            throws IOException {
        if (fields.length != expected) {
            throw failure("wrong field count at line " + (zeroBasedLine + 1));
        }
    }

    private static String normalizeOs(String value) {
        String normalized = normalizeProperty(value);
        StringBuilder result = new StringBuilder(normalized.length());
        boolean whitespace = false;
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (character == ' ' || character == '\t' || character == '\n' || character == '\r'
                    || character == '\f') {
                whitespace = result.length() > 0;
            } else {
                if (whitespace) {
                    result.append(' ');
                    whitespace = false;
                }
                result.append(character);
            }
        }
        return result.toString();
    }

    private static String normalizeProperty(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && isAsciiWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isAsciiWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end).toLowerCase(Locale.ROOT);
    }

    private static boolean isAsciiWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r' || value == '\f';
    }

    private static IOException failure(String message) {
        return new IOException("native-loader: " + message);
    }

    private static IOException failure(String message, Throwable cause) {
        return new IOException(
                message.startsWith("native-loader:") ? message : "native-loader: " + message,
                cause);
    }

    private final List<OsRule> osRules;
    private final Map<String, String> architectureAliases;
    private final List<NativeProduct> products;

    private NativeProductCatalog(
            List<OsRule> osRules,
            Map<String, String> architectureAliases,
            List<NativeProduct> products) {
        this.osRules = List.copyOf(osRules);
        this.architectureAliases = Map.copyOf(architectureAliases);
        this.products = List.copyOf(products);
    }

    List<NativeProduct> select(String osName, String osArch) {
        String normalizedOs = normalizeOs(osName);
        String canonicalOs = resolveOs(osName, normalizedOs);
        String normalizedArch = normalizeProperty(osArch);
        String canonicalArch = architectureAliases.get(normalizedArch);
        if (canonicalArch == null) {
            throw new IllegalArgumentException("native-loader: unsupported architecture '" + osArch
                    + "'; supported aliases: " + new TreeSet<>(architectureAliases.keySet()));
        }
        List<NativeProduct> selected = products.stream()
                .filter(product -> product.operatingSystem().equals(canonicalOs))
                .filter(product -> product.architecture().equals(canonicalArch))
                .sorted(Comparator.comparingInt(NativeProduct::loadOrder)
                        .thenComparing(NativeProduct::id))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "native-loader: no product for " + canonicalOs + '/' + canonicalArch);
        }
        return selected;
    }

    String canonicalOs(String osName) {
        return resolveOs(osName, normalizeOs(osName));
    }

    String canonicalArchitecture(String osArch) {
        String canonical = architectureAliases.get(normalizeProperty(osArch));
        if (canonical == null) {
            throw new IllegalArgumentException("native-loader: unsupported architecture '" + osArch
                    + "'; supported aliases: " + new TreeSet<>(architectureAliases.keySet()));
        }
        return canonical;
    }

    private String resolveOs(String original, String normalized) {
        List<OsRule> exact = osRules.stream()
                .filter(rule -> rule.match() == Match.EXACT && rule.value().equals(normalized))
                .toList();
        List<OsRule> prefix = osRules.stream()
                .filter(rule -> rule.match() == Match.PREFIX && normalized.startsWith(rule.value()))
                .toList();
        if (exact.size() > 1 || prefix.size() > 1) {
            throw new IllegalArgumentException(
                    "native-loader: ambiguous operating system '" + original + "'");
        }
        if (!exact.isEmpty()) {
            if (!prefix.isEmpty() && !exact.get(0).canonical().equals(prefix.get(0).canonical())) {
                throw new IllegalArgumentException(
                        "native-loader: ambiguous operating system '" + original + "'");
            }
            return exact.get(0).canonical();
        }
        if (!prefix.isEmpty()) {
            return prefix.get(0).canonical();
        }
        TreeSet<String> aliases = new TreeSet<>();
        osRules.forEach(rule -> aliases.add(rule.value()));
        throw new IllegalArgumentException(
                "native-loader: unsupported operating system '" + original
                        + "'; supported aliases: " + aliases);
    }

    private enum Match {
        EXACT,
        PREFIX
    }

    @FunctionalInterface
    interface ResourceProbe {

        boolean exists(String path) throws IOException;
    }

    private record OsRule(Match match, String value, String canonical) {

    }
}
