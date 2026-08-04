package io.euhedral_execution.testing.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;

/**
 * JUnit 5 tag for integration tests that should only run during deployment
 * or when merging into main.
 * 
 * <p>Integration tests are long-running, resource-intensive tests that validate
 * end-to-end system behavior. They are excluded from the regular {@code test}
 * task and only execute when running {@code integrationTest}.
 * 
 * <p>Usage:
 * <pre>{@code
 * @Test
 * @IntegrationTest
 * void myIntegrationTest() {
 *     // Test implementation
 * }
 * }</pre>
 * 
 * @see org.junit.jupiter.api.Tag
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("integration")
public @interface IntegrationTest {
}
