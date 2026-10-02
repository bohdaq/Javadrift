package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FullScanTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);}
    @Test void reportsOnlyUnambiguousReferencesWithPositions() throws Exception {
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order { public void place() {} private void secret() {} }");
        write("README.md","# Docs\n\n`com.acme.Missing`\n`Order#submit`\n`Order#place`\n`Order` and `submit()`\nProse com.acme.Missing Order#submit\n`ObjectMapper#readValue`\n[missing](docs/gone.md)\n`src/main/java/com/acme/Gone.java`\n");
        var r=new Analyzer().analyze(root,new Config());
        assertEquals(List.of("JD001","JD002","JD007","JD007"),r.findings().stream().map(Finding::checkId).toList());
        assertEquals(List.of(3,4,9,10),r.findings().stream().map(Finding::line).toList());
        assertEquals(2,r.findings().get(0).column());
    }
    @Test void handlesInheritanceRecordsEnumsAndAmbiguity() throws Exception {
        write("src/main/java/com/acme/Base.java","package com.acme; public class Base { protected void inherited(){} public static class Factory {} }");
        write("src/main/java/com/acme/Child.java","package com.acme; public class Child extends Base {}");
        write("src/main/java/com/acme/Item.java","package com.acme; public record Item(String name) {}");
        write("src/main/java/com/acme/State.java","package com.acme; public enum State { READY }");
        write("src/main/java/other/Child.java","package other; public class Child {}");
        write("README.md","`com.acme.Child#inherited` `Base#Factory` `Item#name` `State#READY` `State.values()` `Child#missing`");
        assertTrue(new Analyzer().analyze(root,new Config()).findings().isEmpty());
    }
    @Test void skipsGeneratedAndUnresolvedInheritedMembers() throws Exception {
        write("src/main/java/com/acme/Order.java","package com.acme; @Data public class Order {} ");
        write("src/main/java/com/acme/Child.java","package com.acme; public class Child extends ThirdParty {} ");
        write("README.md","`Order#getId` `Child#inherited` `com.external.Missing`");
        assertTrue(new Analyzer().analyze(root,new Config()).findings().isEmpty());
    }
    @Test void readsAsciiDocAndRootRelativeCodePaths() throws Exception {
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order {} ");
        write("docs/guide.adoc","= Guide\n`Order#missing`\n[source,java]\n----\ncom.acme.Gone x;\n----\nlink:absent.adoc[Missing]\n`src/main/java/com/acme/Order.java`\n");
        var findings=new Analyzer().analyze(root,new Config()).findings();
        assertEquals(List.of("JD002","JD001","JD007"),findings.stream().map(Finding::checkId).toList());
        assertEquals(List.of(2,5,7),findings.stream().map(Finding::line).toList());
    }
    @Test void configurationControlsDiscoveryAndSeverity() throws Exception {
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order {}");
        write("docs/guide.md","`Order#gone`");write("docs/archive/old.md","`Order#gone`");
        write("javadrift.yml","docs:\n  exclude: ['docs/archive/**']\nchecks:\n  unknown-member: warning\nfailOn: warning\n");
        Config config=Config.load(root,null);var result=new Analyzer().analyze(root,config);
        assertEquals(1,result.documents());assertEquals(Severity.WARNING,result.findings().get(0).severity());assertTrue(result.fails(config));
        write("javadrift.yml","checks:\n  invented-check: error");assertThrows(IllegalArgumentException.class,()->Config.load(root,null));
        assertTrue(Glob.matches("docs/**/*.md","docs/guide.md"));
    }
    @Test void parseFailuresAreErrorsAndTestsAreExcluded() throws Exception {
        write("src/test/java/Broken.java","this is not java");
        assertDoesNotThrow(()->new Analyzer().analyze(root,new Config()));
        write("src/main/java/Broken.java","this is not java");
        assertThrows(java.io.IOException.class,()->new Analyzer().analyze(root,new Config()));
    }
}
