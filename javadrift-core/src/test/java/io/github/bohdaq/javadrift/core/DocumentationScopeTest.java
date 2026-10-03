package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class DocumentationScopeTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);}
    @Test void historicalApiMentionsDoNotHideBrokenLinks() throws Exception {
        write("src/main/java/demo/Api.java","package demo; public class Api { public void current() {} }");
        write("docs/CHANGELOG.md","Renamed `Api#old` to `Api#current`. [missing](missing.md)");
        write("README.md","`Api#old`");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(2,result.findings().size());assertEquals(1,result.findings().stream().filter(f->f.checkId().equals("JD002")).count());
        Config config=new Config();config.docs.historical=List.of();
        assertEquals(3,new Analyzer().analyze(root,config).findings().size());
    }
    @Test void acceptsExistingTestHelperNamesWithoutPromotingThemToApi() throws Exception {
        write("src/main/java/demo/Api.java","package demo; public class Api {}");
        write("src/test/java/demo/Fixture.java","package demo; class Fixture { private class Nested {} }");
        write("src/test/kotlin/demo/Helper.kt","package demo; internal class Helper");
        write("src/test/java/demo/Broken.java","not valid java");
        write("README.md","`demo.Fixture` `demo.Fixture.Nested` `demo.Helper` `demo.Missing` `Api::class` `Api#missing`");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(1,result.types());assertEquals(2,result.findings().size(),result.findings().toString());
        assertEquals(List.of("demo.Missing","Api#missing"),result.findings().stream().map(Finding::reference).toList());
    }
    @Test void normalizesBinaryNamesAndRecognizesSiteExampleDeclarations() throws Exception {
        write("src/main/java/demo/Api.java","package demo; public class Api { public static class Nested {} private class Internal {} }");
        write("src/site/antora/modules/ROOT/examples/Demo.java","package demo; class SiteExample {}");
        write("README.md","`demo.Api$Nested` `demo.Api$Internal` `demo.SiteExample` `demo.Api$Gone`");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(List.of("demo.Api$Gone"),result.findings().stream().map(Finding::reference).toList());
        assertEquals(2,result.types());
    }
    @Test void infersOwnedPackagesConservativelyButAcceptsExplicitNamespaceRoots() throws Exception {
        write("src/main/java/lib/Api.java","package lib; public class Api {}");
        write("src/main/java/com/example/Demo.java","package com.example; public class Demo {}");
        write("README.md","`lib.Missing` `lib.optional.Foreign` `com.example.Customer`");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(List.of("lib.Missing"),result.findings().stream().map(Finding::reference).toList());
        Config config=new Config();config.sources.basePackages=List.of("lib","com.example");
        assertEquals(3,new Analyzer().analyze(root,config).findings().size());
    }
    @Test void keepsChecksForProjectsWhoseOwnNamespaceIsExample() throws Exception {
        write("src/main/java/com/example/Api.java","package com.example; public class Api {}");
        write("README.md","`com.example.Missing`");
        assertEquals(1,new Analyzer().analyze(root,new Config()).findings().size());
    }
}
