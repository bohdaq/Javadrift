package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ExpandedDocsTest {
    @TempDir Path root;
    void write(String name,String value) throws Exception {
        Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.writeString(p,value);
    }
    @Test void resolvesAntoraPageIdsFromNavigationAndNestedPages() throws Exception {
        write("docs/modules/ROOT/nav.adoc","* xref:guide/start.adoc[Start]\n* xref:missing.adoc[Missing]\n");
        write("docs/modules/ROOT/pages/guide/start.adoc","xref:guide/next.adoc[Next]\nxref:../index.adoc[Index]\n");
        write("docs/modules/ROOT/pages/guide/next.adoc","= Next");
        write("docs/modules/ROOT/pages/index.adoc","= Index");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(1,result.findings().size());
        assertEquals("missing.adoc",result.findings().get(0).reference());
        assertEquals("JD007",result.findings().get(0).checkId());
    }
    @Test void keepsOrdinaryAsciiDocLinksRelativeToTheDocument() throws Exception {
        write("docs/modules/ROOT/pages/guide/start.adoc","link:next.adoc[Next]\nlink:missing.adoc[Missing]\n");
        write("docs/modules/ROOT/pages/guide/next.adoc","= Next");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(List.of("missing.adoc"),result.findings().stream().map(Finding::reference).toList());
    }
    @Test void resolvesSiteOutputsWithoutHidingMissingSources() throws Exception {
        write("pom.xml","<project/>");write("api/pom.xml","<project/>");
        write("api/src/main/java/example/Api.java","package example; public class Api { public static class Nested {} }");
        write("src/main/java/example/Local.java","package example; public class Local {}");
        write("src/site/markdown/guide.md","[next](next.html) [api](javadoc/api/example/Api.Nested.html) [missing](javadoc/api/example/Missing.html) [local](apidocs/example/Local.html)");
        write("src/site/markdown/next.md","Next");
        Config config=new Config();config.docs.include=List.of("**/*.md");
        var result=new Analyzer().analyze(root,config);
        assertEquals(List.of("javadoc/api/example/Missing.html"),result.findings().stream().map(Finding::reference).toList());
        write("docs/guide.md","[ordinary HTML](next.html)");
        assertEquals(2,new Analyzer().analyze(root,config).findings().size());
    }
    @Test void resolvesModulePathsButPreservesMissingFilesAndLinks() throws Exception {
        write("module/src/main/java/example/Api.java","package example; public class Api {}");
        write("module/README.md","`src/main/java/example/Api.java` `src/main/java/example/Missing.java` [bad](absent.md)");
        write("docs/tutorial.adoc","`pom.xml` `src/main/resources` xref:section[Section]");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(0,result.findings().size()); // Default scope excludes module README.
        Config config=new Config();config.docs.include=List.of("**/*.md","**/*.adoc");
        result=new Analyzer().analyze(root,config);
        assertEquals(List.of("absent.md","src/main/java/example/Missing.java"),result.findings().stream().map(Finding::reference).sorted().toList());
    }
    @Test void checksGitHubTemplateLinksInTheirRepositoryContext() throws Exception {
        write("CONTRIBUTING.md","Contribute");
        write(".github/pull_request_template.md","[contribute](CONTRIBUTING.md) [bad](missing.md)");
        write("docs/guide.md","[bad relative link](CONTRIBUTING.md)");
        Config config=new Config();config.docs.include=List.of("**/*.md");
        assertEquals(2,new Analyzer().analyze(root,config).findings().size());
    }
    @Test void resolvesLinksFromTheSymlinkedDocumentsActualDirectory() throws Exception {
        write(".claude/CLAUDE.md","[skill](skills/tool.md) [missing](skills/absent.md)");
        write(".claude/skills/tool.md","Tool");
        Files.createDirectories(root.resolve(".github"));
        Files.createSymbolicLink(root.resolve(".github/copilot-instructions.md"),Path.of("../.claude/CLAUDE.md"));
        Config config=new Config();config.docs.include=List.of(".github/*.md");
        assertEquals(List.of("skills/absent.md"),new Analyzer().analyze(root,config).findings().stream().map(Finding::reference).toList());
    }
    @Test void skipsEllipsisArityButChecksLiteralEllipsisStrings() {
        var method=new SymbolIndex.Member("run",List.of("int","int"),true,false,false);
        assertTrue(CallArguments.matches(method,List.of("..."),new SymbolIndex()));
        assertFalse(CallArguments.matches(method,List.of("\"...\""),new SymbolIndex()));
    }
}
