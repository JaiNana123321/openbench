package edu.cwru.openbench;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

/** Rules for the test code itself. */
@AnalyzeClasses(packages = "edu.cwru.openbench", importOptions = ImportOption.OnlyIncludeTests.class)
class TestConventionsTest {

    @ArchTest
    static final ArchRule testsSendTheCsrfTokenLikeTheClient = noClasses()
            .should().callMethod(SecurityMockMvcRequestPostProcessors.class, "csrf")
            .because("csrf() swaps the CSRF filter's token store in the shared application context, after which "
                    + "no response sets the XSRF-TOKEN cookie; use PostgresTest.xsrf()");
}
