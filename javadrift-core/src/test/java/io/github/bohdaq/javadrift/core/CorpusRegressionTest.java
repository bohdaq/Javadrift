package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class CorpusRegressionTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path f=root.resolve(path);Files.createDirectories(f.getParent());Files.writeString(f,text);}
    @Test void excludesGithubNavigationButChecksWithinCheckoutLinks() throws Exception {
        write("README.md","[sibling](../../../jackson) [wiki](../../wiki) [web](/org/repo/issues) [missing](docs/missing.md)");
        write("docs/guide.md","[local](../missing.md)");
        var findings=new Analyzer().analyze(root,new Config()).findings();
        assertEquals(2,findings.size());assertTrue(findings.stream().allMatch(f->f.checkId().equals("JD007")));
    }
    @Test void methodNameMentionsAreNotZeroArgumentExamples() throws Exception {
        write("src/main/java/example/JavaFile.java","package example; public class JavaFile { public void writeTo(java.nio.file.Path p) {} }");
        write("README.md","Use `JavaFile.writeTo()`.\n```java\nJavaFile.writeTo();\n```\n");
        var findings=new Analyzer().analyze(root,new Config()).findings();
        assertEquals(1,findings.size());assertEquals("JD003",findings.get(0).checkId());assertEquals(3,findings.get(0).line());
    }
    @Test void acceptsSourceVariantsAndSkipsJavaTemplateFiles() throws Exception {
        write("api/src/main/java/example/Thing.java","package example; public class Thing { public void desktop() {} }");
        write("android/api/src/main/java/example/Thing.java","package example; public class Thing { public void mobile() {} }");
        write("migrator/templates/content.java","a Java fragment = without a compilation unit");
        write("README.md","`Thing#desktop` `Thing#mobile` `Thing#gone`");
        var result=new Analyzer().analyze(root,new Config());assertEquals(1,result.types());
        assertEquals(1,result.findings().size());assertTrue(result.findings().get(0).reference().contains("gone"));
    }
}
