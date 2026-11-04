package edu.cwru.openbench;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the functional test cases this test runs, by their keys in
 * {@code docs/test-cases/sections/cases/*.tex}, for example
 * {@code @Verifies("drf-balance-boundary")}. Set {@code partial} when the test
 * runs only some of a case's steps.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Verifies {
    String[] value();

    boolean partial() default false;
}
