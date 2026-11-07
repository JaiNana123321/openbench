package edu.cwru.openbench.testcases;

import edu.cwru.openbench.Verifies;
import edu.cwru.openbench.testcases.TestCaseReport.Outcome;
import edu.cwru.openbench.testcases.TestCaseReport.TestRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Writes the run report ({@link TestCaseReport}) at the end of a test run. Registered for every JUnit Platform
 * launch through {@code META-INF/services}, so it sees each test's {@link Verifies} tags and its result in one
 * place, with no second pass over Surefire's XML. It writes only when the system property
 * {@value #REPORT_PROPERTY} names the file, as {@code pom.xml} sets it for Surefire; a run from an IDE writes
 * nothing.
 *
 * <p>A test method counts once per case key, however many invocations it has: failed if any failed, disabled if
 * all were, passed otherwise. A tag on the class covers every test of the class in one entry. A class that fails
 * before its tests run (a broken {@code @BeforeAll}, say) fails every case it or its methods are tagged with.
 */
public class TestCaseReportListener implements TestExecutionListener {

    public static final String REPORT_PROPERTY = "openbench.test-cases.report";

    /** Results by case key, then by test (class and method, or class alone for a class tag). */
    private final Map<String, Map<String, Tally>> tallies = new LinkedHashMap<>();

    @Override
    public void executionSkipped(TestIdentifier test, String reason) {
        TestSource source = test.getSource().orElse(null);
        if (source instanceof MethodSource method) {
            record(method.getJavaClass(), method.getJavaMethod(), Outcome.SKIPPED, reason);
        } else if (source instanceof ClassSource type) {
            recordWholeClass(type.getJavaClass(), Outcome.SKIPPED, reason);
        }
    }

    @Override
    public void executionFinished(TestIdentifier test, TestExecutionResult result) {
        TestSource source = test.getSource().orElse(null);
        if (test.isTest() && source instanceof MethodSource method) {
            Outcome outcome = switch (result.getStatus()) {
                case SUCCESSFUL -> Outcome.PASSED;
                case FAILED -> Outcome.FAILED;
                case ABORTED -> Outcome.SKIPPED;
            };
            record(method.getJavaClass(), method.getJavaMethod(), outcome,
                    result.getThrowable().map(t -> firstLine(t.getMessage())).orElse(null));
        } else if (source instanceof ClassSource type && result.getStatus() == TestExecutionResult.Status.FAILED) {
            recordWholeClass(type.getJavaClass(), Outcome.FAILED,
                    result.getThrowable().map(t -> firstLine(t.getMessage())).orElse(null));
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan plan) {
        String target = System.getProperty(REPORT_PROPERTY);
        if (target == null || target.isBlank()) {
            return;
        }
        Map<String, List<TestRun>> runs = new LinkedHashMap<>();
        tallies.forEach((key, tests) -> runs.put(key, tests.values().stream().map(Tally::run).toList()));
        String report = TestCaseReport.render(TestCaseDocument.load(), runs, commit());
        try {
            Path file = Path.of(target);
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, report, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the test case report to " + target, e);
        }
    }

    private void record(Class<?> type, Method method, Outcome outcome, String note) {
        Verifies onMethod = method.getAnnotation(Verifies.class);
        if (onMethod != null) {
            for (String key : onMethod.value()) {
                tally(key, type.getName() + "#" + method.getName(), type, name(method), onMethod.partial()).add(outcome, note);
            }
        }
        // A tag on a class covers its @Nested classes' tests too.
        for (Class<?> c = type; c != null; c = c.getEnclosingClass()) {
            Verifies onClass = c.getAnnotation(Verifies.class);
            if (onClass != null) {
                for (String key : onClass.value()) {
                    tally(key, c.getName(), c, null, onClass.partial()).add(outcome, note);
                }
            }
        }
    }

    /** A class skipped or failed as a whole: its tagged methods never report, so record them here. */
    private void recordWholeClass(Class<?> type, Outcome outcome, String note) {
        for (Method method : type.getDeclaredMethods()) {
            Verifies onMethod = method.getAnnotation(Verifies.class);
            if (onMethod != null) {
                for (String key : onMethod.value()) {
                    Tally tally = tally(key, type.getName() + "#" + method.getName(), type, name(method), onMethod.partial());
                    if (tally.tests == 0) {
                        tally.add(outcome, note);
                    }
                }
            }
        }
        Verifies onClass = type.getAnnotation(Verifies.class);
        if (onClass != null) {
            for (String key : onClass.value()) {
                Tally tally = tally(key, type.getName(), type, null, onClass.partial());
                if (outcome == Outcome.FAILED || tally.tests == 0) {
                    tally.add(outcome, note);
                }
            }
        }
    }

    private Tally tally(String key, String id, Class<?> type, String method, boolean partial) {
        return tallies.computeIfAbsent(key, k -> new LinkedHashMap<>())
                .computeIfAbsent(id, i -> new Tally(simpleName(type), method, partial));
    }

    /** {@code PricingTest.Refunds} for a nested class. */
    private static String simpleName(Class<?> type) {
        return type.getEnclosingClass() == null ? type.getSimpleName()
                : simpleName(type.getEnclosingClass()) + "." + type.getSimpleName();
    }

    private static String name(Method method) {
        DisplayName displayName = method.getAnnotation(DisplayName.class);
        return displayName != null ? displayName.value() : method.getName() + "()";
    }

    private static String firstLine(String message) {
        if (message == null) {
            return null;
        }
        String line = message.strip().lines().findFirst().orElse("");
        return line.length() > 160 ? line.substring(0, 157) + "..." : line;
    }

    /** The commit under test, for the manual run record; null outside a git checkout. */
    private static String commit() {
        try {
            Process git = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!git.waitFor(10, TimeUnit.SECONDS) || git.exitValue() != 0) {
                return null;
            }
            Process status = new ProcessBuilder("git", "status", "--porcelain", "--untracked-files=no")
                    .redirectErrorStream(true).start();
            boolean dirty = !new String(status.getInputStream().readAllBytes(), StandardCharsets.UTF_8).isBlank();
            status.waitFor(10, TimeUnit.SECONDS);
            return "`" + out + "`" + (dirty ? " with uncommitted changes" : "");
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static final class Tally {
        private final String testClass;
        private final String method;
        private final boolean partial;
        private int tests;
        private int failed;
        private int skipped;
        private final List<String> notes = new ArrayList<>();

        Tally(String testClass, String method, boolean partial) {
            this.testClass = testClass;
            this.method = method;
            this.partial = partial;
        }

        void add(Outcome outcome, String note) {
            tests++;
            if (outcome == Outcome.FAILED) {
                failed++;
            } else if (outcome == Outcome.SKIPPED) {
                skipped++;
            }
            if (outcome != Outcome.PASSED && note != null && !notes.contains(note)) {
                notes.add(note);
            }
        }

        TestRun run() {
            Outcome outcome = failed > 0 ? Outcome.FAILED : skipped == tests ? Outcome.SKIPPED : Outcome.PASSED;
            return new TestRun(testClass, method, partial, outcome, tests,
                    outcome == Outcome.PASSED ? null : String.join("; ", notes));
        }
    }
}
