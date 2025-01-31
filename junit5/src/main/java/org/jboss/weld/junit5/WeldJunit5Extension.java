/*
 * JBoss, Home of Professional Open Source
 * Copyright 2017, Red Hat, Inc., and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.junit5;

import static org.jboss.weld.junit5.ExtensionContextUtils.getAutoCloseableFromStore;
import static org.jboss.weld.junit5.ExtensionContextUtils.getContainerFromStore;
import static org.jboss.weld.junit5.ExtensionContextUtils.getEnrichersFromStore;
import static org.jboss.weld.junit5.ExtensionContextUtils.getExplicitInjectionInfoFromStore;
import static org.jboss.weld.junit5.ExtensionContextUtils.getInitiatorFromStore;
import static org.jboss.weld.junit5.ExtensionContextUtils.setAutoCloseableToStore;
import static org.jboss.weld.junit5.ExtensionContextUtils.setContainerToStore;
import static org.jboss.weld.junit5.ExtensionContextUtils.setEnrichersToStore;
import static org.jboss.weld.junit5.ExtensionContextUtils.setExplicitInjectionInfoToStore;
import static org.jboss.weld.junit5.ExtensionContextUtils.setInitiatorToStore;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.spi.BeanManager;

import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.jboss.weld.inject.WeldInstance;
import org.jboss.weld.util.collections.ImmutableList;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstanceFactory;
import org.junit.jupiter.api.extension.TestInstanceFactoryContext;
import org.junit.jupiter.api.extension.TestInstancePreConstructCallback;
import org.junit.jupiter.api.extension.TestInstancePreDestroyCallback;
import org.junit.jupiter.api.extension.TestInstantiationException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;

/**
 * JUnit 5 extension allowing to bootstrap Weld SE container for each @Test method (or once per test class
 * if running {@link org.junit.jupiter.api.TestInstance.Lifecycle#PER_CLASS}) and tear it down afterwards. Also allows
 * injecting CDI beans as parameters to @Test methods and resolves all @Inject fields in test class.
 *
 * <p>
 * If no {@link WeldInitiator} field annotated with {@link WeldSetup} is present on a test class, all service providers of
 * {@link WeldJunitEnricher} interface are used to enrich the default test environment.
 * </p>
 *
 * <pre>
 * &#64;ExtendWith(WeldJunit5Extension.class)
 * public class SimpleTest {
 *
 *     // Injected automatically
 *     &#64;Inject
 *     Foo foo;
 *
 *     &#64;Test
 *     public void testFoo() {
 *         // Weld container is started automatically
 *         assertEquals("baz", foo.getBaz());
 *     }
 * }
 * </pre>
 *
 * @author <a href="mailto:manovotn@redhat.com">Matej Novotny</a>
 * @see EnableWeld
 * @see WeldJunitEnricher
 */
public class WeldJunit5Extension implements BeforeAllCallback, ParameterResolver, TestInstanceFactory,
        TestInstancePreDestroyCallback, TestInstancePreConstructCallback {

    // global system property
    public static final String GLOBAL_EXPLICIT_PARAM_INJECTION = "org.jboss.weld.junit5.explicitParamInjection";

    private static void storeExplicitParamResolutionInformation(ExtensionContext ec) {
        // check system property which may have set the global explicit param injection
        boolean globalSettings = Boolean.parseBoolean(System.getProperty(GLOBAL_EXPLICIT_PARAM_INJECTION, "false"));
        if (globalSettings) {
            setExplicitInjectionInfoToStore(ec, true);
            return;
        }
        // check class-level annotation
        Class<?> inspectedTestClass = ec.getRequiredTestClass();
        ExplicitParamInjection explicitParamInjection = inspectedTestClass.getAnnotation(ExplicitParamInjection.class);
        if (explicitParamInjection != null) {
            setExplicitInjectionInfoToStore(ec, explicitParamInjection.value());
        } else {
            // if not found, it can still be a nested class
            // inspect enclosing classes until first annotation is found or until we hit top-level class
            inspectedTestClass = inspectedTestClass.getEnclosingClass();
            while (inspectedTestClass != null && explicitParamInjection == null) {
                explicitParamInjection = inspectedTestClass.getAnnotation(ExplicitParamInjection.class);
                if (explicitParamInjection != null) {
                    setExplicitInjectionInfoToStore(ec, explicitParamInjection.value());
                }
                inspectedTestClass = inspectedTestClass.getEnclosingClass();
            }
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) {
        // store information about explicit param injection
        storeExplicitParamResolutionInformation(context);

        // find and load all Weld Enrichers
        // we are storing them into root context, hence only needs to be done once per test suite
        if (getEnrichersFromStore(context) == null) {
            ImmutableList.Builder<WeldJunitEnricher> enrichers = ImmutableList.builder();
            ServiceLoader.load(WeldJunitEnricher.class).forEach(enrichers::add);
            setEnrichersToStore(context, enrichers.build());
        }
    }

    @Override
    public void preConstructTestInstance(TestInstanceFactoryContext factoryContext, ExtensionContext context) throws Exception {
        if (!factoryContext.getOuterInstance().isEmpty()) {
            // this is a nested test; we attempt to reuse parent ExtensionContext and take container from there
            if (context.getParent().isEmpty()) {
                throw new IllegalStateException("Nested test " + factoryContext.getTestClass() + " with an ExtensionContext that has no parent context is illegal.");
            }
            ExtensionContext parentContext = context.getParent().get();
            WeldContainer containerFromStore = getContainerFromStore(parentContext);
            WeldInitiator initiatorFromStore = getInitiatorFromStore(parentContext);
            if (containerFromStore == null || initiatorFromStore == null) {
                // this is a nested test but its parent class is not a JUnit test class - we need to boot fresh container
                startWeldContainer(context);
            } else {
                setContainerToStore(context, containerFromStore);;
                setInitiatorToStore(context, initiatorFromStore);
            }
        } else {
            startWeldContainer(context);
        }
    }

    @Override
    public void preDestroyTestInstance(ExtensionContext context) throws Exception {
        // NOTE: unlike its pre construct counterpart, this method is called only once per ExtensionContext!
        // This means that for a combination of enclosing and nested class, we still get only a single invocation
        // The TestInstancePreDestroyCallback#preDestroyTestInstances is an intended way of handling
        // all instances properly if need be
        AutoCloseable autoCloseable = getAutoCloseableFromStore(context);
        if (autoCloseable != null) {
            autoCloseable.close();
        }
        WeldInitiator initiator = getInitiatorFromStore(context);
        if (initiator != null) {
            initiator.shutdownWeld();
        }
    }

    protected void weldInit(ExtensionContext context, Weld weld, WeldInitiator.Builder weldInitiatorBuilder) {
        weld.addPackage(false, context.getRequiredTestClass());
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        // we did our checks in supportsParameter() method, now we can do simple resolution
        if (getContainerFromStore(extensionContext) != null) {
            List<Annotation> qualifiers = resolveQualifiers(parameterContext,
                    getContainerFromStore(extensionContext).getBeanManager());
            return getContainerFromStore(extensionContext)
                    .select(parameterContext.getParameter().getParameterizedType(),
                            qualifiers.toArray(new Annotation[qualifiers.size()]))
                    .get();
        }
        return null;
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        // do not attempt to resolve JUnit 5 built-in parameters
        if (isJUnitResolvedParameter(parameterContext)) {
            return false;
        }
        // if weld container isn't up yet or if it's not Method, we don't resolve it
        if (getContainerFromStore(extensionContext) == null
                || (!(parameterContext.getDeclaringExecutable() instanceof Method))) {
            return false;
        }
        List<Annotation> qualifiers = resolveQualifiers(parameterContext,
                getContainerFromStore(extensionContext).getBeanManager());
        // if we require explicit parameter injection (via global settings or annotation) and there are no qualifiers we don't resolve it
        // if the method is annotated @ParameterizedTest, we treat it as explicit param injection and require qualifiers
        if ((getExplicitInjectionInfoFromStore(extensionContext)
                || methodRequiresExplicitParamInjection(parameterContext)
                || methodIsParameterizedTest(parameterContext))
                && qualifiers.isEmpty()) {
            return false;
        } else {
            // attempt to resolve the bean; at this point we know it should be a CDI bean since it has CDI qualifiers
            // if resolution fails, throw an exception
            WeldInstance<?> select = getContainerFromStore(extensionContext).select(
                    parameterContext.getParameter().getParameterizedType(),
                    qualifiers.toArray(new Annotation[qualifiers.size()]));
            if (!select.isResolvable()) {
                throw new ParameterResolutionException(String.format(
                        "Weld has failed to resolve test parameter [%s] in method [%s].%n" +
                                "%s dependency has type %s and qualifiers %s.",
                        parameterContext.getParameter(), parameterContext.getDeclaringExecutable().toGenericString(),
                        select.isAmbiguous() ? "Ambiguous" : "Unsatisfied",
                        parameterContext.getParameter().getType().getName(), qualifiers));
            }
            return true;
        }
    }

    /**
     * @see {@code org.junit.jupiter.engine.extension.TestInfoParameterResolver.supportsParameter}
     * @see {@code org.junit.jupiter.engine.extension.RepetitionExtension.supportsParameter}
     * @see {@code org.junit.jupiter.engine.extension.TestReporterParameterResolver.supportsParameter}
     * @see {@code org.junit.jupiter.engine.extension.TempDirectory.supportsParameter}
     */
    private boolean isJUnitResolvedParameter(ParameterContext parameterContext) {
        Class<?> type = parameterContext.getParameter().getType();
        if (type == TestInfo.class || type == RepetitionInfo.class || type == TestReporter.class) {
            return true;
        }
        if (parameterContext.isAnnotated(TempDir.class)) {
            return true;
        }
        return false;
    }

    private List<Annotation> resolveQualifiers(ParameterContext pc, BeanManager bm) {
        List<Annotation> qualifiers = new ArrayList<>();
        if (pc.getParameter().getAnnotations().length == 0) {
            return Collections.emptyList();
        } else {
            for (Annotation annotation : pc.getParameter().getAnnotations()) {
                // use BeanManager.isQualifier to be able to detect custom qualifiers which don't need to have @Qualifier
                if (bm.isQualifier(annotation.annotationType())) {
                    qualifiers.add(annotation);
                }
            }
        }
        return qualifiers;
    }

    private boolean methodRequiresExplicitParamInjection(ParameterContext pc) {
        ExplicitParamInjection ann = pc.getDeclaringExecutable().getAnnotation(ExplicitParamInjection.class);
        if (ann != null) {
            return ann.value();
        }
        return false;
    }

    private boolean methodIsParameterizedTest(ParameterContext pc) {
        return pc.getDeclaringExecutable().getAnnotation(ParameterizedTest.class) != null ? true : false;
    }

    private void startWeldContainer(ExtensionContext context) {
        Class<?> testClass = context.getRequiredTestClass();

        // store info about explicit param injection, either from global settings or from annotation on the test class
        storeExplicitParamResolutionInformation(context);

        // iterate through the test class hierarchy, the enclosing instance (in case of nested tests),
        // the enclosing instance of the enclosing instance (in cases of twice nested tests) and so on
        // until we find a WeldInitiator
        final List<Class<?>> allTestClasses = new ArrayList<>();
        allTestClasses.add(testClass);
        Class<?> enclosingClass = testClass.getEnclosingClass();
        while (enclosingClass != null) {
            allTestClasses.add(enclosingClass);
            enclosingClass = enclosingClass.getEnclosingClass();
        }
        Collections.reverse(allTestClasses); // so that we can iterate from inner-most to outer-most
        WeldInitiator initiator = allTestClasses.stream()
                .map(this::findInitiatorInInstance)
                .filter(Objects::nonNull)
                .findFirst()
                .orElseGet(() -> getDefaultInitiator(context, testClass));
        setInitiatorToStore(context, initiator);

        // and finally, init Weld
        setContainerToStore(context, initiator.initWeld(testClass));
    }

    private WeldInitiator findInitiatorInInstance(Class<?> testClass) {
        // all found fields which are WeldInitiator and have @WeldSetup annotation
        List<Field> foundInitiatorFields = new ArrayList<>();
        WeldInitiator initiator = null;
        // We will go through class hierarchy in search of @WeldSetup field (even private)
        for (Class<?> clazz = testClass; clazz != null; clazz = clazz.getSuperclass()) {
            // Find @WeldSetup field using getDeclaredFields() - this allows even for private fields
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(WeldSetup.class)) {
                    if (!Modifier.isStatic(field.getModifiers())) {
                        // we cannot support non-static fields if we want to be able to provide test instances
                        throw new IllegalStateException("Fields annotated with @WeldSetup must be declared static!");
                    }
                    Object fieldInstance;
                    try {
                        // we can use null as argument because we know it is a static field and the arg is ignored
                        fieldInstance = field.get(null);
                    } catch (IllegalAccessException e) {
                        // inaccessible, retry while forcibly making accessible
                        field.setAccessible(true);
                        try {
                            fieldInstance = field.get(null);
                        } catch (IllegalAccessException e2) {
                            // we should never get to this point, because setAccessible would have thrown earlier if access could
                            // not be granted.
                            throw new AssertionError();
                        }
                    }
                    if (fieldInstance instanceof WeldInitiator) {
                        initiator = (WeldInitiator) fieldInstance;
                        foundInitiatorFields.add(field);
                    } else {
                        // Field with other type than WeldInitiator was annotated with @WeldSetup
                        throw new IllegalStateException("@WeldSetup annotation should only be used on a field with a "
                                + "WeldInitiator type but was found on field " + field.getName() + " with a "
                                + field.getType() + " type, declared in class " + field.getDeclaringClass());
                    }
                }
            }
        }
        if (foundInitiatorFields.isEmpty()) {
            return null;
        }
        validateInitiator(foundInitiatorFields);
        // Multiple occurrences of @WeldSetup in the hierarchy will lead to an exception
        if (foundInitiatorFields.size() > 1) {
            final String msg = foundInitiatorFields.stream()
                    .map(f -> "Field '" + f.getName() + "' with type " + f.getType() + " which is declared in "
                            + f.getDeclaringClass())
                    .collect(Collectors.joining("\n", "Multiple @WeldSetup annotated fields found, "
                            + "only one is allowed! Fields found:\n", ""));
            throw new IllegalStateException(msg);
        }

        return initiator;
    }

    private WeldInitiator getDefaultInitiator(ExtensionContext context, Class<?> testClass) {

        Weld weld = WeldInitiator.createWeld();
        WeldInitiator.Builder builder = WeldInitiator.from(weld);

        weldInit(context, weld, builder);

        // Apply discovered enrichers
        for (WeldJunitEnricher enricher : getEnrichersFromStore(context)) {
            String property = System.getProperty(enricher.getClass().getName());
            if (property == null || Boolean.parseBoolean(property)) {
                enricher.enrich(testClass, context, weld, builder);
            }
        }

        return builder.build();
    }

    protected void validateInitiator(List<Field> foundInitiatorFields) {
        // a found initiator is always good for this variant
    }

    @Override
    public Object createTestInstance(TestInstanceFactoryContext factoryContext, ExtensionContext extensionContext) throws TestInstantiationException {
        WeldContainer containerFromStore = getContainerFromStore(extensionContext);
        if (containerFromStore == null) {
            // Should be detected earlier but we still check it here
            throw new IllegalStateException("Cannot create test class instance, WeldContainer was not yet started!");
        }

        Optional<Object> outerInstance = factoryContext.getOuterInstance();
        if (!outerInstance.isEmpty()) {
            // CDI cannot handle nested classes as beans by definition
            // we fall back to primitive object creation and expect a single no-arg ctor (no-arg for nested class will have outer class as its param)
            Constructor<?>[] declaredConstructors = factoryContext.getTestClass().getDeclaredConstructors();
            if (declaredConstructors.length != 1 || declaredConstructors[0].getParameterCount() != 1) {
                throw new IllegalStateException("A nested test class " + factoryContext.getTestClass() + " needs to a single no-args constructor.");
            }
            try {
                Constructor<?> declaredConstructor = declaredConstructors[0];
                declaredConstructor.setAccessible(true);
                Object testInstance = declaredConstructor.newInstance(outerInstance.get());
                // The container is already running, but we need to inject into this new instance
                // The reference to closable needs to be passed to the Store to be closed at a later time
                try {
                    // injecting into non-contextual can yield some bean resolution exception - catch, shutdown container, rethrow
                    setAutoCloseableToStore(extensionContext, getInitiatorFromStore(extensionContext).injectNonContextual(testInstance));
                } catch (Exception e) {
                    getInitiatorFromStore(extensionContext).shutdownWeld();
                    throw new TestInstantiationException("Weld was unable to inject into non contextual instance (nested test class instance) - " + factoryContext.getTestClass(), e);
                }
                return testInstance;
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new TestInstantiationException("Unable to instantiate nested class " + factoryContext.getTestClass(), e);
            }
        } else {
            try {
                return containerFromStore.select(factoryContext.getTestClass()).get();
            } catch (UnsatisfiedResolutionException | AmbiguousResolutionException e) {
                // shutdown container
                getInitiatorFromStore(extensionContext).shutdownWeld();
                // rethrow exception
                throw new TestInstantiationException("Weld container was unable to retrieve bean for test class " + factoryContext.getTestClass(), e);
            }
        }
    }
}
