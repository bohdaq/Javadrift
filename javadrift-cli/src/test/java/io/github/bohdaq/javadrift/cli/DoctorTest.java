package io.github.bohdaq.javadrift.cli;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import javax.tools.ToolProvider;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

class DoctorTest {
    @TempDir Path root;
    record Run(int exit,JsonNode report) {}
    Run run(String... options) throws Exception {return run(new DoctorCommand(),options);}
    Run run(DoctorCommand doctor,String... options) throws Exception {
        StringWriter out=new StringWriter();
        CommandLine cli=new CommandLine(doctor).setOut(new PrintWriter(out)).setErr(new PrintWriter(new StringWriter()));
        List<String> args=new ArrayList<>(List.of("--format","json"));
        if(!List.of(options).contains("--root"))args.addAll(List.of("--root",root.toString()));
        args.addAll(List.of(options));
        int exit=cli.execute(args.toArray(String[]::new));
        JsonNode report=new ObjectMapper().readTree(out.toString());
        assertEquals(exit,report.path("exitCode").asInt());return new Run(exit,report);
    }
    void sourceProject() throws Exception {
        Path source=root.resolve("src/main/java/demo/Widget.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"package demo; public class Widget { public void present() {} }");
        Files.writeString(root.resolve("README.md"),"Use `demo.Widget#missing`.");
    }
    List<JsonNode> rows(Run run,String check) {
        List<JsonNode> rows=new ArrayList<>();run.report().path("diagnostics").forEach(r->{if(r.path("check").asText().equals(check))rows.add(r);});return rows;
    }
    Map<String,String> snapshot() throws Exception {
        Map<String,String> files=new TreeMap<>();
        try(var paths=Files.walk(root)) {
            for(Path path:paths.filter(Files::isRegularFile).toList())files.put(root.relativize(path).toString(),Base64.getEncoder().encodeToString(Files.readAllBytes(path))+Files.getLastModifiedTime(path));
        }
        return files;
    }
    @Test void readySetupDoesNotAnalyzeFindingsOrWriteFiles() throws Exception {
        sourceProject();Map<String,String> before=snapshot();
        Run run=run();assertEquals(0,run.exit());assertEquals(1,run.report().path("schemaVersion").asInt());
        assertTrue(rows(run,"documents").get(0).path("message").asText().contains("1 selected"));
        assertEquals(before,snapshot());
        CommandLine cli=Main.commandLine().setOut(new PrintWriter(new StringWriter())).setErr(new PrintWriter(new StringWriter()));
        assertEquals(1,cli.execute("check","--root",root.toString()));
        assertEquals(0,cli.execute("doctor","--root",root.toString()));
    }
    @Test void appliesConfiguredScopeAndRelativeAndAbsoluteInputs() throws Exception {
        sourceProject();Path classes=root.resolve("project classes");Files.createDirectories(classes);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),root.resolve("src/main/java/demo/Widget.java").toString()));
        Path jar=root.resolve("dependency jar.jar");
        try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("demo/Widget.class"));out.write(Files.readAllBytes(classes.resolve("demo/Widget.class")));out.closeEntry();
        }
        Files.createDirectories(root.resolve("module/src/main/kotlin/demo"));Files.writeString(root.resolve("module/src/main/kotlin/demo/Api.kt"),"package demo; class Api");
        Files.createDirectories(root.resolve("src/test/java/demo"));Files.writeString(root.resolve("src/test/java/demo/Test.java"),"test fixture");
        Files.createDirectories(root.resolve("guide"));Files.writeString(root.resolve("guide/current.adoc"),"Guide");Files.writeString(root.resolve("guide/archive.adoc"),"Archive");
        Files.writeString(root.resolve("javadrift.yml"),"docs:\n  include: ['guide/*.adoc']\n  exclude: ['guide/archive.adoc']\nclasses: ['project classes']\nclasspath: ['dependency jar.jar']\n");
        Map<String,String> before=snapshot();Run run=run("--class-dir",jar.toString(),"--classpath",jar+File.pathSeparator+classes);
        assertEquals(0,run.exit());assertEquals(2,rows(run,"class-dir").size());assertEquals(3,rows(run,"classpath").size());
        assertTrue(rows(run,"sources").get(0).path("message").asText().startsWith("2 production"));
        assertTrue(rows(run,"documents").get(0).path("message").asText().startsWith("1 selected"));assertEquals(before,snapshot());
    }
    @Test void reportsAllInvalidInputsTogether() throws Exception {
        sourceProject();Files.writeString(root.resolve("not-a-jar.txt"),"text");Files.writeString(root.resolve("broken.jar"),"not a ZIP");
        Run run=run("--class-dir","missing classes","--classpath",String.join(File.pathSeparator,"missing.jar","not-a-jar.txt","broken.jar", ""));
        assertEquals(2,run.exit());assertEquals(4,rows(run,"classpath").stream().filter(r->r.path("status").asText().equals("ERROR")).count());
        assertEquals("ERROR",rows(run,"class-dir").get(0).path("status").asText());
        assertTrue(rows(run,"classpath").stream().allMatch(r->!r.path("advice").asText().isBlank()));
    }
    @Test void warnsForEmptyScopeAndEmptyCompiledOutputs() throws Exception {
        Files.createDirectories(root.resolve("module/target/classes"));
        Run run=run();assertEquals(1,run.exit());assertEquals("WARN",rows(run,"documents").get(0).path("status").asText());
        assertEquals("WARN",rows(run,"sources").get(0).path("status").asText());
        assertEquals("WARN",rows(run,"compiled-classes").get(0).path("status").asText());
        StringWriter out=new StringWriter();CommandLine cli=Main.commandLine().setOut(new PrintWriter(out));
        assertEquals(1,cli.execute("doctor","--root",root.toString()));assertTrue(out.toString().contains("docs.include/docs.exclude"));
    }
    @Test void validatesCompilerOnlyWhenSnippetCompilationIsEnabled() throws Exception {
        sourceProject();assertEquals(0,run(new DoctorCommand(17,null)).exit());
        Files.writeString(root.resolve("javadrift.yml"),"checks:\n  snippet-compile: warning\nsnippets:\n  release: '17'\n");
        Run missing=run(new DoctorCommand(17,null));assertEquals(2,missing.exit());assertTrue(rows(missing,"compiler").get(0).path("advice").asText().contains("full JDK"));
        Map<String,String> before=snapshot();assertEquals(0,run().exit());assertEquals(before,snapshot());
        Files.writeString(root.resolve("javadrift.yml"),"checks:\n  JD008: error\nsnippets:\n  release: '999'\n");
        Run unsupported=run();assertEquals(2,unsupported.exit());assertTrue(rows(unsupported,"compiler").get(0).path("message").asText().contains("999"));
    }
    @Test void distinguishesInvalidRootConfigurationAndBaseline() throws Exception {
        assertEquals(2,run("--root",root.resolve("missing").toString()).exit());
        sourceProject();assertEquals(2,run("--config",root.resolve("absent.yml").toString()).exit());
        for(String yaml:List.of("checks: [", "checks: {unknown-check: error}", "docs: null")) {
            Files.writeString(root.resolve("javadrift.yml"),yaml);Run run=run();assertEquals(2,run.exit());assertEquals("ERROR",rows(run,"configuration").get(0).path("status").asText());
        }
        Files.delete(root.resolve("javadrift.yml"));Files.writeString(root.resolve("javadrift-baseline.json"),"{\"version\":2,\"fingerprints\":[]}");
        Map<String,String> before=snapshot();Run invalid=run();assertEquals(2,invalid.exit());assertEquals("ERROR",rows(invalid,"baseline").get(0).path("status").asText());assertEquals(before,snapshot());
        Files.writeString(root.resolve("javadrift-baseline.json"),"{\"version\":1,\"fingerprints\":[]}");assertEquals(0,run().exit());
    }
}
