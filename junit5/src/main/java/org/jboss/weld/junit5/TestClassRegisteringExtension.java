package org.jboss.weld.junit5;

import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.literal.SingletonLiteral;
import jakarta.enterprise.inject.spi.Extension;
import jakarta.enterprise.inject.spi.ProcessAnnotatedType;

/**
 * Tests would normally be registered as dependent beans as they have no other scope.
 * This extension changes that to a singleton.
 *
 * @param <T> type of the test class
 */
public class TestClassRegisteringExtension<T> implements Extension {

    private Class<T> testClass;

    public TestClassRegisteringExtension(Class<T> testClass){
        this.testClass = testClass;
    }

    void changeTestClassScope(@Observes ProcessAnnotatedType<T> pat) {
        if (pat.getAnnotatedType().getJavaClass().equals(testClass)) {
            pat.configureAnnotatedType().add(SingletonLiteral.INSTANCE);
        }
    }
}
