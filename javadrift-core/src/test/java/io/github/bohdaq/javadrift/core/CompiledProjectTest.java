package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.api.Git;
import java.nio.file.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class CompiledProjectTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path f=root.resolve(path);Files.createDirectories(f.getParent());Files.writeString(f,text);}
    @Test void compiledApiOverridesLombokSkipAndNeverRunsInitializers() throws Exception {
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order extends Thread { static { if(true) throw new RuntimeException(\"must not execute\"); } public String getOrderId(){return \"id\";} }");
        Path classes=root.resolve("target/classes");Files.createDirectories(classes);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),root.resolve("src/main/java/com/acme/Order.java").toString()));
        // Simulate an annotation processor's generated getter, absent in source.
        write("src/main/java/com/acme/Order.java","package com.acme; @Data public class Order extends Thread {}");
        write("README.md","`Order#getOrderId` `Order#start` `Order#absent`");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(1,result.types());assertEquals(1,result.findings().size());assertEquals("JD002",result.findings().get(0).checkId());
    }
    @Test void versionCheckUsesOwnCoordinatesOnlyAndNumericTags() throws Exception {
        write("pom.xml","<project><groupId>com.acme</groupId><artifactId>orders</artifactId><version>2.0-SNAPSHOT</version></project>");
        write("README.md","```xml\n<dependency><groupId>com.acme</groupId><artifactId>orders</artifactId><version>1.0</version></dependency>\n```\n```gradle\nimplementation 'com.acme:orders:1.0'\nimplementation 'other:orders:1.0'\n```\n");
        try(Git git=Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();git.commit().setMessage("fixture").setAuthor("Test","test@example.com").call();
            git.tag().setName("v1.9.0").setAnnotated(false).call();git.tag().setName("v1.10.0").setAnnotated(false).call();
            var result=new Analyzer().analyze(root,new Config());assertEquals(2,result.findings().size());
            assertTrue(result.findings().stream().allMatch(f->f.checkId().equals("JD006")&&f.message().contains("1.10.0")));
        }
    }
}
