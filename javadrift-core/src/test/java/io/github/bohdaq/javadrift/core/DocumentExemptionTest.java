package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class DocumentExemptionTest {
    @TempDir Path root;
    void write(String path,String text) throws Exception {Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,text);}
    @Test void scopesExemptionsToFileCheckReferenceAndFragmentKind() throws Exception {
        write("src/main/java/demo/Api.java","package demo; public class Api {}");
        write("README.md","`Api#old` [gone](optional.txt)\n");
        write("docs/migration.md","`Api#old` `Api#typo`\n```java\n// Api#old\nApi.old();\n```\n[optional](optional.txt) [gone](gone.txt)\n");
        write("javadrift.yml","docs:\n  exemptions:\n    - file: docs/migration.md\n      check: JD002\n      reference: 'Api#old'\n      kind: CODE\n      reason: Historical inline API name\n    - file: docs/migration.md\n      check: JD007\n      reference: optional.txt\n      kind: LINK\n      reason: Optional consumer configuration\n");
        var result=new Analyzer().analyze(root,Config.load(root,null));
        assertEquals(6,result.findings().size(),result.findings().toString());
        assertTrue(result.findings().stream().anyMatch(f->f.file().equals("README.md") && f.reference().equals("Api#old")));
        assertTrue(result.findings().stream().anyMatch(f->f.reference().equals("Api.old")));
        assertEquals(2,result.findings().stream().filter(f->f.reference().equals("Api#old")).count());
        assertTrue(result.findings().stream().anyMatch(f->f.reference().equals("Api#typo")));
        assertTrue(result.findings().stream().anyMatch(f->f.reference().equals("gone.txt")));
    }
    @Test void exemptingMissingMemberKeepsOtherChecksForThatReference() throws Exception {
        write("src/main/java/demo/Api.java","package demo; @Deprecated(forRemoval=true) public class Api {}");
        write("README.md","`Api#old`");
        write("javadrift.yml","docs:\n  exemptions:\n    - file: README.md\n      check: JD002\n      reference: 'Api#old'\n      reason: Historical member\n");
        assertEquals(List.of("JD005"),new Analyzer().analyze(root,Config.load(root,null)).findings().stream().map(Finding::checkId).toList());
    }
    @Test void validatesExemptionReasonsAndCheckIds() throws Exception {
        for(String text:List.of("docs: {exemptions: [null]}","docs: {exemptions: [{file: README.md, check: JD001, reference: demo.Missing}]}",
            "docs: {exemptions: [{file: README.md, check: JD999, reference: demo.Missing, reason: historical}]}")) {
            write("javadrift.yml",text);assertThrows(IllegalArgumentException.class,()->Config.load(root,null));
        }
    }
}
