package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;
class AdvancedChecksTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path f=root.resolve(path);Files.createDirectories(f.getParent());Files.writeString(f,text);}
    @Test void matchesOverloadsLiteralTypesVarargsAndNestedArgumentsConservatively() throws Exception {
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order { public void place(String s){} public void count(long n){} public void tags(String... tags){} public void map(java.util.Map<String,Integer> m){} }");
        write("README.md","`Order.place(1)`\n`Order.place(unknown)`\n`Order.place(\"a,b\")`\n`Order.count(1)`\n`Order.tags(\"a\", \"b\")`\n`Order.map(new java.util.HashMap<String, Integer>())`\n`Order.place()`\n`Order.gone(new Thing())`\n");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(3,result.findings().size());
        assertEquals(java.util.List.of("JD003","JD003","JD002"),result.findings().stream().map(Finding::checkId).toList());
        assertEquals(java.util.List.of(1,7,8),result.findings().stream().map(Finding::line).toList());
    }
    @Test void reportsOnlyDeprecationForRemoval() throws Exception {
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order { @Deprecated(forRemoval=true) public void old(){} @Deprecated public void legacy(){} }");
        write("README.md","`Order#old` `Order#legacy`");
        var result=new Analyzer().analyze(root,new Config());assertEquals(1,result.findings().size());assertEquals("JD005",result.findings().get(0).checkId());
    }
    @Test void snippetCompilerAcceptsAllThreeFormsAndSkipsEllipses() throws Exception {
        write("README.md","```java\npublic class Example { static { if(true) throw new RuntimeException(); } }\n```\n```java\npublic void hello() {}\n```\n```java\nint answer = 42;\n```\n```java\n...\n```\n```java\n// javadrift:skip\nthis is not java\n```\n");
        Config config=new Config();config.checks.put("snippet-compile","error");
        assertTrue(new Analyzer().analyze(root,config).findings().isEmpty());
        assertFalse(Files.exists(root.resolve("Example.class")));assertFalse(Files.exists(root.resolve("JavadriftExample.class")));
    }
    @Test void snippetCompilationUsesProjectClasspathAndFindsBrokenExamples() throws Exception {
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order { public void place() {} }");
        Path classes=root.resolve("target/classes");Files.createDirectories(classes);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),root.resolve("src/main/java/com/acme/Order.java").toString()));
        write("README.md","```java\nnew Order().place();\n```\n```java\nnew Order().gone();\n```\n");
        Config config=new Config();config.snippets.imports.add("com.acme.Order");config.checks.put("JD008","error");
        var result=new Analyzer().analyze(root,config);assertEquals(1,result.findings().size());assertEquals("JD008",result.findings().get(0).checkId());
        assertEquals(5,result.findings().get(0).line());assertTrue(result.findings().get(0).message().contains("gone"));
    }
    @Test void unknownPropertyReadsYamlAndPropertiesAndStaysOptIn() throws Exception {
        write("src/main/resources/application.properties","app.port=8080\n");
        write("src/main/resources/application.yml","app:\n  host: localhost\n");
        write("README.md","`app.port` `app.host` `app.missing`\n```properties\napp.other=42\n```\n");
        assertTrue(new Analyzer().analyze(root,new Config()).findings().isEmpty());
        write("src/main/java/com/acme/Props.java","package com.acme; public class Props { String p=System.getProperty(\"app.custom\"); }");
        Files.writeString(root.resolve("README.md"),"`app.custom` "+Files.readString(root.resolve("README.md")));
        Config config=new Config();config.checks.put("JD009","warning");
        var result=new Analyzer().analyze(root,config);assertEquals(2,result.findings().size());assertTrue(result.findings().stream().allMatch(f->f.checkId().equals("JD009")));
    }
}
