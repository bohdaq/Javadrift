package io.github.bohdaq.javadrift.gradle;
import org.gradle.testkit.runner.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class PluginTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path f=root.resolve(path);Files.createDirectories(f.getParent());Files.writeString(f,text);}
    GradleRunner runner() {return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments("check","--stacktrace");}
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
}
