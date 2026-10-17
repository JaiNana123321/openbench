package edu.cwru.openbench;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.springframework.data.repository.Repository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dependency rule of design Section 2 (ADR-0005) and the code rules of Section 8. Fix the code
 * when a rule fails; never weaken a rule to make code pass.
 */
@AnalyzeClasses(packages = "edu.cwru.openbench", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "edu.cwru.openbench.";

    private static final DescribedPredicate<JavaAccess<?>> NO_ARGUMENT_NOW = describe(
            "a no-argument now() of java.time",
            access -> access.getTargetOwner().getPackageName().equals("java.time")
                    && access.getName().equals("now")
                    && access.getTarget() instanceof CodeUnitAccessTarget target
                    && target.getRawParameterTypes().isEmpty());

    private static final DescribedPredicate<JavaAnnotation<?>> TRANSACTIONAL_WITH_ISOLATION = describe(
            "@Transactional with an isolation attribute",
            annotation -> annotation.getRawType().isEquivalentTo(Transactional.class)
                    && annotation.get("isolation")
                            .map(value -> !((JavaEnumConstant) value).name().equals("DEFAULT"))
                            .orElse(false));

    /**
     * Layer order D-C, bottom to top: Inventory, Members, Settings, Notifications; Allocation, Ledger;
     * Requests, Dashboard, Jobs. Within a layer only the module map's one-way dependencies are
     * allowed (Inventory, Settings, and Notifications on Members; Settings on Inventory; Jobs on
     * Requests). {@code common} uses no module; {@code security} loads users from Members; only
     * {@code config} may wire everything.
     */
    @ArchTest
    static final ArchRule modulesDependOnlyDownward = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Common").definedBy(BASE + "common..")
            .layer("Config").definedBy(BASE + "config..")
            .layer("Security").definedBy(BASE + "security..")
            .layer("Members").definedBy(BASE + "members..")
            .layer("Inventory").definedBy(BASE + "inventory..")
            .layer("Settings").definedBy(BASE + "settings..")
            .layer("Notifications").definedBy(BASE + "notifications..")
            .layer("Allocation").definedBy(BASE + "allocation..")
            .layer("Ledger").definedBy(BASE + "ledger..")
            .layer("Requests").definedBy(BASE + "requests..")
            .layer("Dashboard").definedBy(BASE + "dashboard..")
            .layer("Jobs").definedBy(BASE + "jobs..")
            .whereLayer("Common").mayNotAccessAnyLayer()
            .whereLayer("Members").mayOnlyAccessLayers("Common")
            .whereLayer("Inventory").mayOnlyAccessLayers("Members", "Common")
            .whereLayer("Settings").mayOnlyAccessLayers("Inventory", "Members", "Common")
            .whereLayer("Notifications").mayOnlyAccessLayers("Members", "Common")
            .whereLayer("Allocation").mayOnlyAccessLayers(
                    "Inventory", "Members", "Settings", "Notifications", "Common")
            .whereLayer("Ledger").mayOnlyAccessLayers(
                    "Inventory", "Members", "Settings", "Notifications", "Common")
            .whereLayer("Requests").mayOnlyAccessLayers(
                    "Allocation", "Ledger", "Inventory", "Members", "Settings", "Notifications", "Common")
            .whereLayer("Dashboard").mayOnlyAccessLayers(
                    "Allocation", "Ledger", "Inventory", "Members", "Settings", "Notifications", "Common")
            .whereLayer("Jobs").mayOnlyAccessLayers(
                    "Requests", "Allocation", "Ledger", "Inventory", "Members", "Settings", "Notifications", "Common")
            .whereLayer("Security").mayOnlyAccessLayers("Members", "Common")
            .whereLayer("Security").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Config").mayNotBeAccessedByAnyLayer();

    @ArchTest
    static final ArchRule packagesHaveNoCycles = slices().matching(BASE + "(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule entitiesArePrivateToTheirModule = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().notBePublic()
            .because("a module's public surface is its services and DTOs (ADR-0005)");

    @ArchTest
    static final ArchRule repositoriesArePrivateToTheirModule = classes()
            .that().areAssignableTo(Repository.class)
            .should().notBePublic()
            .because("a module's public surface is its services and DTOs (ADR-0005)");

    @ArchTest
    static final ArchRule timeComesFromTheClock = noClasses()
            .should().accessTargetWhere(NO_ARGUMENT_NOW)
            .because("time is read only from the injected Clock (design Section 8)");

    @ArchTest
    static final ArchRule allocationClassesKeepTheDefaultIsolation = noClasses()
            .that().resideInAnyPackage(BASE + "allocation..", BASE + "requests..")
            .should().beAnnotatedWith(TRANSACTIONAL_WITH_ISOLATION)
            .because("the type lock is correct only at READ COMMITTED (ADR-0006)")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule allocationMethodsKeepTheDefaultIsolation = noMethods()
            .that().areDeclaredInClassesThat().resideInAnyPackage(BASE + "allocation..", BASE + "requests..")
            .should().beAnnotatedWith(TRANSACTIONAL_WITH_ISOLATION)
            .because("the type lock is correct only at READ COMMITTED (ADR-0006)")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersReachOnlyGuardedServiceMethods = classes()
            .that().areAnnotatedWith(RestController.class)
            .should(callOnlyServiceMethodsAnnotatedWithPreAuthorize())
            .because("roles are checked on the services, one path for every client (design Section 8)");

    /**
     * An application service is a {@code @Service} class (or an interface one implements). Every
     * method of one that a {@code @RestController} calls must carry {@code @PreAuthorize}, on the
     * method or on the type the controller calls.
     */
    private static ArchCondition<JavaClass> callOnlyServiceMethodsAnnotatedWithPreAuthorize() {
        return new ArchCondition<>("call only application-service methods annotated with @PreAuthorize") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                for (JavaMethodCall call : controller.getMethodCallsFromSelf()) {
                    JavaClass owner = call.getTargetOwner();
                    if (!isApplicationService(owner)) {
                        continue;
                    }
                    boolean guarded = owner.isAnnotatedWith(PreAuthorize.class)
                            || call.getTarget().resolveMember()
                                    .map(method -> method.isAnnotatedWith(PreAuthorize.class))
                                    .orElse(false);
                    if (!guarded) {
                        events.add(SimpleConditionEvent.violated(call,
                                call.getDescription() + ", which has no @PreAuthorize"));
                    }
                }
            }
        };
    }

    private static boolean isApplicationService(JavaClass type) {
        return type.isAnnotatedWith(Service.class)
                || type.isInterface() && type.getAllSubclasses().stream().anyMatch(sub -> sub.isAnnotatedWith(Service.class));
    }
}
