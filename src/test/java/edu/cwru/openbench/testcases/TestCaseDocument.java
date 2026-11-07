package edu.cwru.openbench.testcases;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The functional test case document read from its LaTeX sources, as {@code docs/test-cases/tools/gen_trace.py}
 * reads it: every {@code \begin{testcase}{key}{Title}{Req.Id, Req.Id}} of {@code sections/cases/*.tex} in file
 * order, numbered {@code TC-<area>-nn} within the area that the last {@code \tcarea} set, with the priority of its
 * {@code \tcfield{Priority}{...}} line; and every {@code \fr[Tag]{Id}} requirement of the SRS short edition,
 * grouped by its {@code \subsection}.
 */
public record TestCaseDocument(List<Area> areas, List<TestCase> cases, List<Requirement> requirements) {

    /** The documents, relative to the repository root (Surefire's working directory). */
    public static final Path CASES = Path.of("docs/test-cases/sections/cases");
    public static final Path SRS_FEATURES = Path.of("docs/srs/sections/short/04-system-features.tex");

    /** One feature's subsection of the document, such as {@code DRF}, "Draft and Submit a Request". */
    public record Area(String code, String title) {}

    /** One test case; {@code number} counts within its area from 1. */
    public record TestCase(String area, int number, String key, String title, List<String> requirements,
            String priority) {

        /** The printed number, such as {@code TC-DRF-05}. */
        public String id() {
            return "TC-%s-%02d".formatted(area, number);
        }
    }

    /** A functional requirement of the SRS short edition, in the feature {@code group}. */
    public record Requirement(String group, String id, boolean stretch) {}

    public TestCaseDocument {
        areas = List.copyOf(areas);
        cases = List.copyOf(cases);
        requirements = List.copyOf(requirements);
    }

    /** Reads the document under the repository root. */
    public static TestCaseDocument load(Path root) {
        Map<String, String> caseFiles = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(root.resolve(CASES))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".tex")).sorted().toList()) {
                caseFiles.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
            return parse(caseFiles.values().stream().toList(),
                    Files.readString(root.resolve(SRS_FEATURES), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the test case document under " + root.toAbsolutePath(), e);
        }
    }

    /** Reads the document from the repository root of this JVM's working directory. */
    public static TestCaseDocument load() {
        return load(Path.of(""));
    }

    private static final Pattern CASE_FILE_ITEM = Pattern.compile(
            "\\\\subsection\\*?\\{|\\\\tcarea\\{([^}]*)\\}|\\\\begin\\{testcase\\}");
    private static final Pattern PRIORITY = Pattern.compile("\\\\tcfield\\{Priority\\}\\{([^}]*)\\}");
    private static final Pattern SRS_ITEM = Pattern.compile(
            "\\\\subsection\\{([^}]*)\\}|\\\\fr(?:\\[([^\\]]*)\\])?\\{([^}]*)\\}");

    /** Parses the case files' contents, in file order, and the SRS short edition's Section 4. */
    public static TestCaseDocument parse(List<String> caseFiles, String srsFeatures) {
        List<Area> areas = new ArrayList<>();
        List<TestCase> cases = new ArrayList<>();
        String area = null;
        int number = 0;
        String subsection = "";
        for (String file : caseFiles) {
            String text = stripComments(file);
            Matcher m = CASE_FILE_ITEM.matcher(text);
            while (m.find()) {
                if (m.group().startsWith("\\subsection")) {
                    subsection = plain(braced(text, m.end() - 1).content());
                } else if (m.group(1) != null) {
                    area = m.group(1).trim();
                    number = 0;
                    areas.add(new Area(area, subsection));
                } else {
                    if (area == null) {
                        throw new IllegalArgumentException("A test case comes before the first \\tcarea");
                    }
                    Braced key = braced(text, m.end());
                    Braced title = braced(text, key.end());
                    Braced reqs = braced(text, title.end());
                    int end = text.indexOf("\\end{testcase}", reqs.end());
                    if (end < 0) {
                        throw new IllegalArgumentException("Test case " + key.content() + " has no \\end{testcase}");
                    }
                    Matcher priority = PRIORITY.matcher(text.substring(reqs.end(), end));
                    if (!priority.find()) {
                        throw new IllegalArgumentException("Test case " + key.content() + " has no Priority field");
                    }
                    cases.add(new TestCase(area, ++number, key.content().trim(), plain(title.content()),
                            splitList(reqs.content()), priority.group(1).trim()));
                    m.region(end, text.length());
                }
            }
        }
        List<Requirement> requirements = new ArrayList<>();
        String group = "";
        Matcher m = SRS_ITEM.matcher(stripComments(srsFeatures));
        while (m.find()) {
            if (m.group(1) != null) {
                group = plain(m.group(1));
            } else {
                requirements.add(new Requirement(group, m.group(3).trim(), "Stretch".equals(trim(m.group(2)))));
            }
        }
        return new TestCaseDocument(areas, cases, requirements);
    }

    /** Every test case key. */
    public Set<String> keys() {
        return cases.stream().map(TestCase::key).collect(Collectors.toUnmodifiableSet());
    }

    // ---------------------------------------------------------------- LaTeX

    private record Braced(String content, int end) {}

    /** The brace group opening at {@code text[i]} (after any whitespace), and the index after its closing brace. */
    private static Braced braced(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= text.length() || text.charAt(i) != '{') {
            throw new IllegalArgumentException("Expected '{' at: " + snippet(text, i));
        }
        int depth = 0;
        for (int j = i; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '\\') {
                j++;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return new Braced(text.substring(i + 1, j), j + 1);
            }
        }
        throw new IllegalArgumentException("Unbalanced braces at: " + snippet(text, i));
    }

    /** Removes {@code %} comments, keeping an escaped {@code \%}. */
    static String stripComments(String text) {
        return text.replaceAll("(?m)(?<!\\\\)%.*$", "");
    }

    private static final Pattern COMMAND_WITH_ARGUMENT = Pattern.compile("\\\\[a-zA-Z]+\\*?\\{([^{}]*)\\}");

    /** LaTeX inline text as plain text: commands give their argument, and the escapes their character. */
    static String plain(String latex) {
        String text = latex.replace("$\\times$", "×").replace("\\ldots", "…").replace("\\times", "×");
        String previous;
        do {
            previous = text;
            text = COMMAND_WITH_ARGUMENT.matcher(text).replaceAll(r -> Matcher.quoteReplacement(r.group(1)));
        } while (!text.equals(previous));
        text = text.replace("---", "—").replace("--", "–").replace("``", "\"").replace("''", "\"")
                .replace("\\%", "%").replace("\\_", "_").replace("\\&", "&").replace("\\#", "#").replace("\\$", "$")
                .replace("\\,", " ").replace("~", " ").replace("$", "").replace("{", "").replace("}", "")
                .replaceAll("\\\\[a-zA-Z]+\\*?", "");
        return text.replaceAll("\\s+", " ").trim();
    }

    private static List<String> splitList(String list) {
        return Stream.of(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String snippet(String text, int i) {
        return text.substring(Math.max(0, i), Math.min(text.length(), i + 40));
    }
}
