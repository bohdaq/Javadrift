package io.github.bohdaq.javadrift.core;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class HistoryTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path p=root.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,text);}
    void commit(Git git) throws Exception {git.add().addFilepattern(".").call();git.commit().setMessage("fixture").setAuthor("Fixture","fixture@example.com").call();}
    @ParameterizedTest
    @CsvSource({"Order#submit", "Order.submit()", "Order::submit", "submit()", "com.acme.Order#submit", ".submit()", "builder.submit(new Value())"})
    void replaysMemberRenameWithoutBuildingOldSources(String reference) throws Exception {
        try(Git git=Git.init().setDirectory(root.toFile()).call()) {
            write("src/main/java/com/acme/Order.java","package com.acme; public class Order { public void submit(){} }");
            write("README.md","`"+reference+"`\nProse submit()\n`submission()`\n");commit(git);
            String base=git.getRepository().resolve("HEAD").name();
            write("src/main/java/com/acme/Order.java","package com.acme; public class Order { public void place(){} }");commit(git);
            var result=new Analyzer().analyze(root,new Config(),base,true);
            assertEquals(1,result.findings().size());var f=result.findings().get(0);
            assertEquals("JD004",f.checkId());assertEquals(1,f.line());assertEquals(reference.startsWith(".")||reference.startsWith("builder.")?reference.indexOf("submit")+2:2,f.column());
            assertTrue(f.suggestion().contains("place()"));
            assertThrows(java.io.IOException.class,()->new Analyzer().analyze(root,new Config(),"invalid-ref",true));
        }
    }
    @Test void removedBareTypeIsCheckedButSurvivingOverloadIsNot() throws Exception {
        try(Git git=Git.init().setDirectory(root.toFile()).call()) {
            write("src/main/java/com/acme/Order.java","package com.acme; public class Order {}");
            write("README.md","`Order` `Orderly`\n");commit(git);String base=git.getRepository().resolve("HEAD").name();
            Files.delete(root.resolve("src/main/java/com/acme/Order.java"));git.rm().addFilepattern("src/main/java/com/acme/Order.java").call();commit(git);
            assertEquals(1,new Analyzer().analyze(root,new Config(),base,true).findings().size());
        }
    }
    @Test void commentsGlobsAndBaselinesSuppressPrecisely() throws Exception {
        write("src/main/java/com/acme/Order.java","package com.acme; public class Order {}");
        write("README.md","<!-- javadrift:ignore-next -->\n`Order#one`\n<!-- javadrift:ignore-next -->\n```java\nOrder.two();\n```\n`Order#three`\n`docs/archive/**`\n");
        Config c=new Config();var first=new Analyzer().analyze(root,c);assertEquals(1,first.findings().size());
        Path baseline=root.resolve(c.baseline);Baseline.write(baseline,first.findings());
        assertTrue(new Analyzer().analyze(root,c).findings().isEmpty());
        Files.writeString(root.resolve("README.md"),"New heading\n"+Files.readString(root.resolve("README.md")));
        assertTrue(new Analyzer().analyze(root,c).findings().isEmpty());
        assertEquals(1,new Analyzer().analyze(root,c,null,false).findings().size());
        Files.writeString(baseline,"{\"version\":2,\"fingerprints\":[]}");
        assertThrows(java.io.IOException.class,()->new Analyzer().analyze(root,c));
    }
    @Test void outputIsDeterministicAndGithubEscapesControlCharacters() throws Exception {
        Finding f=new Finding("docs/a,b.md",3,2,"JD002",Severity.ERROR,"Order#gone","missing\n%thing",null);
        var result=new Analyzer.Result(List.of(f),1,1);
        assertEquals(Reporters.render(result,"json"),Reporters.render(result,"json"));
        String github=Reporters.render(result,"github");assertTrue(github.contains("a%2Cb.md"));assertTrue(github.contains("missing%0A%25thing"));
    }
}
