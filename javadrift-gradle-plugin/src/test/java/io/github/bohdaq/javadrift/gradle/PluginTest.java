package io.github.bohdaq.javadrift.gradle;
import org.gradle.testkit.runner.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PluginTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path f=root.resolve(path);Files.createDirectories(f.getParent());Files.writeString(f,text);}
    GradleRunner runner() throws Exception {
        String engine=System.getProperty("javadrift.test.engine").replace("\\","\\\\").replace("'","\\'");
        write("engine.gradle","dependencies { javadriftRuntime files('"+engine+"'.split(java.io.File.pathSeparator)) }\n");
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments("check","--stacktrace","--init-script",root.resolve("init.gradle").toString());
    }
    @BeforeEach void configureEngine() throws Exception {
        write("init.gradle","allprojects { p -> p.afterEvaluate { if (p.plugins.hasPlugin('io.github.bohdaq.javadrift')) p.apply from: p.rootProject.file('engine.gradle') } }\n");
    }
    @Test void checkTracksDocsAndIsUpToDateUntilAnInputChanges() throws Exception {
        write("settings.gradle","rootProject.name='fixture'\n");
        write("build.gradle","plugins { id 'java'; id 'io.github.bohdaq.javadrift' }\n");
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order { public void place(){} }");
        write("README.md","`Order#place`\n");
        assertEquals(TaskOutcome.SUCCESS,runner().build().task(":javadriftCheck").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE,runner().build().task(":javadriftCheck").getOutcome());
        write("README.md","`Order#submit`\n");assertTrue(runner().buildAndFail().getOutput().contains("JD002"));
        write("README.md","[missing](asset.txt)\n");write("asset.txt","asset");runner().build();
        Files.delete(root.resolve("asset.txt"));assertTrue(runner().buildAndFail().getOutput().contains("JD007"));
    }
    @Test void rootPluginCollectsSubprojectClasses() throws Exception {
        write("settings.gradle","rootProject.name='fixture'\ninclude 'api'\n");
        write("build.gradle","plugins { id 'io.github.bohdaq.javadrift' }\n");
        write("api/build.gradle","plugins { id 'java' }\n");
        write("api/src/main/java/com/acme/Order.java","package com.acme; public class Order { public void place(){} }");
        write("README.md","`Order#place`\n");
        BuildResult result=runner().build();assertTrue(result.getOutput().contains("0 findings."));assertNotNull(result.task(":api:compileJava"));
    }
    @Test void kotlinParserRunsWithoutBeingVisibleToTheBuild() throws Exception {
        write("settings.gradle","rootProject.name='fixture'\n");
        write("build.gradle","""
            plugins { id 'io.github.bohdaq.javadrift' }
            ['io.github.bohdaq.javadrift.core.Analyzer', 'org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment'].each { name ->
                try { plugins.getPlugin('io.github.bohdaq.javadrift').class.classLoader.loadClass(name)
                    throw new GradleException('Engine leaked into build classpath: ' + name)
                } catch (ClassNotFoundException expected) { }
            }
            """);
        write("src/main/kotlin/com/acme/Welcome.kt","package com.acme\nclass Welcome { fun greet(name: String = \"world\"): String = name }");
        write("README.md","`com.acme.Welcome#greet`\n");
        assertTrue(runner().build().getOutput().contains("0 findings."));
        write("README.md","`com.acme.Welcome#missing`\n");
        assertTrue(runner().buildAndFail().getOutput().contains("JD002"));
    }
    @Test void changedGitRefsInvalidateAnOtherwiseUnchangedScan() throws Exception {
        write("settings.gradle","rootProject.name='fixture'\n");
        write("build.gradle","plugins { id 'java'; id 'io.github.bohdaq.javadrift' }\n");
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order {}");
        write("README.md","`com.acme.Order`\n");
        try(var git=org.eclipse.jgit.api.Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").setAuthor("Test","test@example.com").setCommitter("Test","test@example.com").call();
            runner().build();
            assertEquals(TaskOutcome.UP_TO_DATE,runner().build().task(":javadriftCheck").getOutcome());
            git.tag().setName("new-release").setAnnotated(false).call();
            assertEquals(TaskOutcome.SUCCESS,runner().build().task(":javadriftCheck").getOutcome());
        }
    }
}
