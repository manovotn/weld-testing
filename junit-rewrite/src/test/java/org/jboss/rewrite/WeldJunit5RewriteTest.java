package org.jboss.rewrite;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

public class WeldJunit5RewriteTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipeFromResources("org.jboss.weld.junit.JUnitRelocations")
                .parser(JavaParser.fromJavaVersion().logCompilationWarningsAndErrors(true))
                .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void testJUnit5ToJupiterRelocations() {
        //language=xml
        // TODO change expected version to 6.0.0.Final once released
        rewriteRun(pomXml("""
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>org.jboss.weld</groupId>
                    <artifactId>test-project</artifactId>
                    <version>1.0.0-SNAPSHOT</version>

                    <dependencies>
                        <dependency>
                            <groupId>org.jboss.weld</groupId>
                            <artifactId>weld-junit5</artifactId>
                            <version>5.0.3.Final</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                        <project>
                            <modelVersion>4.0.0</modelVersion>
                            <groupId>org.jboss.weld</groupId>
                            <artifactId>test-project</artifactId>
                            <version>1.0.0-SNAPSHOT</version>

                            <dependencies>
                                <dependency>
                                    <groupId>org.jboss.weld</groupId>
                                    <artifactId>weld-junit-jupiter</artifactId>
                                    <version>5.0.4-SNAPSHOT</version>
                                </dependency>
                            </dependencies>
                        </project>
                        """));
    }

    @Test
    void testImportChange() {
        // The code before applying the recipe
        rewriteRun(
                // Input Java class with the old import
                java(
                        """
                                package org.jboss.test;

                                import static org.junit.jupiter.api.Assertions.assertEquals;
                                import static org.junit.jupiter.api.Assertions.assertFalse;

                                import org.jboss.weld.junit5.WeldInitiator;
                                import org.jboss.weld.junit5.WeldJunit5Extension;
                                import org.jboss.weld.junit5.WeldSetup;
                                import org.jboss.weld.junit5.ofpackage.Alpha;
                                import org.junit.jupiter.api.Test;
                                import org.junit.jupiter.api.extension.ExtendWith;

                                @ExtendWith(WeldJunit5Extension.class)
                                 public class SimpleTest {

                                     @WeldSetup
                                     public WeldInitiator weld = WeldInitiator.of(Foo.class);

                                     @Test
                                     public void testFooA() {
                                         assertEquals("baz", weld.select(Foo.class).get().getBar());
                                         assertFalse(weld.select(Alpha.class).isResolvable());
                                     }
                                 }
                                """,
                        // Expected outcome after the change
                        """
                                package org.jboss.test;

                                import static org.junit.jupiter.api.Assertions.assertEquals;
                                import static org.junit.jupiter.api.Assertions.assertFalse;

                                import org.jboss.weld.junit.WeldInitiator;
                                import org.jboss.weld.junit.WeldJunit5Extension;
                                import org.jboss.weld.junit.WeldSetup;
                                import org.jboss.weld.junit.ofpackage.Alpha;
                                import org.junit.jupiter.api.Test;
                                import org.junit.jupiter.api.extension.ExtendWith;

                                @ExtendWith(WeldJunit5Extension.class)
                                 public class SimpleTest {

                                     @WeldSetup
                                     public WeldInitiator weld = WeldInitiator.of(Foo.class);

                                     @Test
                                     public void testFooA() {
                                         assertEquals("baz", weld.select(Foo.class).get().getBar());
                                         assertFalse(weld.select(Alpha.class).isResolvable());
                                     }
                                 }
                                """));
    }
}
