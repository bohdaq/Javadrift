package io.github.bohdaq.javadrift.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

class BaselineTest {
    @TempDir Path root;
    String stdout;
    int baseline(String... options) {
        List<String> args=new ArrayList<>(List.of("baseline","--root",root.toString()));args.addAll(List.of(options));
        return run(args.toArray(String[]::new));
    }
    int run(String... args) {
        var command=Main.commandLine();var out=new StringWriter();
        command.setOut(new PrintWriter(out));command.setErr(new PrintWriter(new StringWriter()));
        int code=command.execute(args);stdout=out.toString();return code;
    }
    Path file() {return root.resolve("javadrift-baseline.json");}
    Set<String> fingerprints() throws Exception {
        var json=new ObjectMapper().readTree(file().toFile());Set<String> values=new TreeSet<>();
        json.path("fingerprints").forEach(n->values.add(n.asText()));return values;
    }
    @Test void auditAndPruneKeepExistingAcceptanceWithoutAcceptingNewFindings() throws Exception {
        Files.writeString(root.resolve("README.md"),"[old](old.txt)\n[keep](keep.txt)\n");
        assertEquals(0,baseline());Set<String> original=fingerprints();assertEquals(2,original.size());
        Files.writeString(root.resolve("README.md"),"[keep](keep.txt)\n[new](new.txt)\n");
        byte[] before=Files.readAllBytes(file());
        assertEquals(1,baseline("--check"));assertTrue(stdout.contains("1 unused"));
        assertTrue(stdout.contains("1 current fingerprints remain unaccepted"));
        assertArrayEquals(before,Files.readAllBytes(file()));
        assertEquals(0,baseline("--prune"));assertEquals(1,fingerprints().size());assertTrue(original.containsAll(fingerprints()));
        Path report=root.resolve("report.json");
        assertEquals(1,run("check","--root",root.toString(),"--format","json","--output",report.toString()));
        var findings=new ObjectMapper().readTree(report.toFile()).path("findings");
        assertEquals(1,findings.size());assertEquals("new.txt",findings.get(0).path("reference").asText());
        assertEquals(0,baseline("--check"));before=Files.readAllBytes(file());
        assertEquals(0,baseline("--prune"));assertArrayEquals(before,Files.readAllBytes(file()));
        Files.writeString(root.resolve("README.md"),"Clean documentation.\n");
        assertEquals(0,baseline("--prune"));assertTrue(fingerprints().isEmpty());
    }
    @Test void maintenanceRejectsMissingInvalidAndConflictingInputsWithoutWriting() throws Exception {
        Files.writeString(root.resolve("README.md"),"[missing](gone.txt)\n");
        assertEquals(2,baseline("--check"));assertEquals(2,baseline("--prune"));assertFalse(Files.exists(file()));
        for(String invalid:List.of("not json","{\"version\":2,\"fingerprints\":[]}","{\"version\":1,\"fingerprints\":[null]}")) {
            Files.writeString(file(),invalid);
            assertEquals(2,baseline("--check"));assertEquals(2,baseline("--prune"));assertEquals(invalid,Files.readString(file()));
        }
        assertEquals(0,baseline());byte[] before=Files.readAllBytes(file());
        assertEquals(2,baseline("--check","--prune"));
        assertEquals(2,baseline("--prune","--classpath",root.resolve("missing.jar").toString()));
        assertEquals(2,baseline("--prune","--config",root.resolve("missing.yml").toString()));
        assertArrayEquals(before,Files.readAllBytes(file()));
    }
    @Test void explicitTargetAndConfigurationSelectTheBaseline() throws Exception {
        Files.writeString(root.resolve("README.md"),"[missing](gone.txt)\n");
        Files.writeString(root.resolve("javadrift.yml"),"baseline: 'accepted files/default.json'\n");
        assertEquals(0,baseline());Path configured=root.resolve("accepted files/default.json");
        Path explicit=root.resolve("other files/explicit.json");
        assertEquals(0,baseline("--output",explicit.toString()));
        Files.writeString(root.resolve("README.md"),"Clean.\n");byte[] before=Files.readAllBytes(configured);
        assertEquals(1,baseline("--check","--output",explicit.toString()));
        assertEquals(0,baseline("--prune","--output",explicit.toString()));
        assertEquals(0,new ObjectMapper().readTree(explicit.toFile()).path("fingerprints").size());
        assertArrayEquals(before,Files.readAllBytes(configured));assertFalse(Files.exists(file()));
    }
    @Test void snippetMaintenanceUsesExplicitClassesAndNativeDependencyClasspath() throws Exception {
        Path external=Files.createTempDirectory(root.getParent(),"baseline dependency files ");
        try {
            Path dependency=external.resolve("Dependency.java"),classes=external.resolve("compiled classes");Files.createDirectories(classes);
            Files.writeString(dependency,"package demo; public class Dependency { public static String title() { return \"Daily\"; } }");
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),dependency.toString()));
            Files.writeString(root.resolve("javadrift.yml"),"checks:\n  JD008: error\nsnippets:\n  imports: ['demo.Dependency']\n");
            Files.writeString(root.resolve("README.md"),"```java\nint title = Dependency.title();\n```\n");
            String[] paths={"--class-dir",classes.toString(),"--class-dir",classes.toString(),
                "--classpath",classes+File.pathSeparator+external};
            assertEquals(0,baseline(paths));assertEquals(1,fingerprints().size());
            List<String> audit=new ArrayList<>(List.of(paths));audit.add("--check");assertEquals(0,baseline(audit.toArray(String[]::new)));
            Files.writeString(root.resolve("README.md"),"```java\nString title = Dependency.title();\n```\n");
            List<String> prune=new ArrayList<>(List.of(paths));prune.add("--prune");assertEquals(0,baseline(prune.toArray(String[]::new)));
            assertTrue(fingerprints().isEmpty());
        } finally {
            try(var files=Files.walk(external)) {for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
    }
}
