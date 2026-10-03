package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ReferenceContextTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);}
    @Test void diagramLabelsAreNotJavaCallsButJavaBlocksStillAre() throws Exception {
        write("src/main/java/demo/Api.java","package demo; public class Api { public void call(String value) {} }");
        write("README.adoc","[plantuml]\n....\n:Api#call();\n:demo.Gone;\n....\n[source,java]\n----\nApi.call();\n----\n");
        write("docs/diagram.md","```mermaid\nApi.call()\ndemo.Gone\n```\n");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(List.of("JD003"),result.findings().stream().map(Finding::checkId).toList());
        assertEquals(8,result.findings().get(0).line());
    }
    @Test void javadocLabelOwnerOverridesTheLocalShortName() throws Exception {
        write("src/main/java/demo/Clock.java","package demo; public class Clock { public void local() {} }");
        write("README.adoc","See https://docs.oracle.com/docs/api/java/time/Clock.html#zone[`Clock#systemDefaultZone()`].\n`Clock#missing`\n");
        write("docs/api.md","[`Clock#systemDefaultZone()`](https://docs.oracle.com/docs/api/java/time/Clock.html)\n[`Clock#missing`](https://example.org/apidocs/demo/Clock.html)\n");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(List.of("Clock#missing","Clock#missing"),result.findings().stream().map(Finding::reference).toList());
    }
    @Test void catchesMisplacedKnownTypesWithoutClaimingUnknownDependencyNamespaces() throws Exception {
        write("src/main/java/com/acme/lib/Api.java","package com.acme.lib; public class Api {}");
        write("src/test/java/com/acme/lib/cache/CacheSubject.java","package com.acme.lib.cache; class CacheSubject {}");
        write("README.md","`com.acme.lib.testing.CacheSubject` `com.acme.optional.Foreign` `com.example.CacheSubject`\n");
        assertEquals(List.of("com.acme.lib.testing.CacheSubject"),new Analyzer().analyze(root,new Config()).findings().stream().map(Finding::reference).toList());
    }
    @Test void moduleDirectoriesAndGithubHeadLinksRequireExistingTargets() throws Exception {
        write("module/src/main/java/demo/Api.java","package demo; public class Api {}");
        write("README.md","`src/main` [missing](src/main)\n");
        write("docs/advice.md","[root](\n../tree/HEAD/README.md) [gone](../blob/HEAD/Gone.md)\n");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(List.of("src/main","../blob/HEAD/Gone.md"),result.findings().stream().map(Finding::reference).toList());
    }
}
