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
    @Test void skipsEllipsisArityButChecksLiteralEllipsisStrings() {
        var method=new SymbolIndex.Member("run",List.of("int","int"),true,false,false);
        assertTrue(CallArguments.matches(method,List.of("..."),new SymbolIndex()));
        assertFalse(CallArguments.matches(method,List.of("\"...\""),new SymbolIndex()));
    }
}
