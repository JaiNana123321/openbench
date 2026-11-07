package edu.cwru.openbench;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import edu.cwru.openbench.testcases.TestCaseDocument;
import java.util.Set;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/** Rules for the test code itself. */
@AnalyzeClasses(packages = "edu.cwru.openbench", importOptions = ImportOption.OnlyIncludeTests.class)
class TestConventionsTest {

    @ArchTest
    static final ArchRule testsSendTheCsrfTokenLikeTheClient = noClasses()
            .should().callMethod(SecurityMockMvcRequestPostProcessors.class, "csrf")
            .because("csrf() swaps the CSRF filter's token store in the shared application context, after which "
                    + "no response sets the XSRF-TOKEN cookie; use PostgresTest.xsrf()");

    @ArchTest
    static final ArchRule verifiesNamesOnlyDocumentedTestCases = classes()
            .should(nameOnlyDocumentedTestCases())
            .because("a @Verifies key must be a test case key in docs/test-cases/sections/cases/*.tex, or the run "
                    + "report would credit a case that does not exist");

    private static ArchCondition<JavaClass> nameOnlyDocumentedTestCases() {
        Set<String> keys = TestCaseDocument.load().keys();
        return new ArchCondition<>("name only documented test cases in @Verifies") {
            @Override
            public void check(JavaClass type, ConditionEvents events) {
                type.tryGetAnnotationOfType(Verifies.class).ifPresent(v -> checkKeys(type.getName(), v, events));
                for (JavaMethod method : type.getMethods()) {
                    method.tryGetAnnotationOfType(Verifies.class).ifPresent(v -> checkKeys(method.getFullName(), v, events));
                }
            }

            private void checkKeys(String where, Verifies verifies, ConditionEvents events) {
                if (verifies.value().length == 0) {
                    events.add(SimpleConditionEvent.violated(where, where + " has @Verifies without a key"));
                }
                for (String key : verifies.value()) {
                    if (!keys.contains(key)) {
                        events.add(SimpleConditionEvent.violated(where,
                                where + " names test case '" + key + "', which docs/test-cases does not have"));
                    }
                }
            }
        };
    }
}
