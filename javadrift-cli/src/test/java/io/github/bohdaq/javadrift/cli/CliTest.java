package io.github.bohdaq.javadrift.cli;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;
class CliTest {
    @TempDir Path root;
    int run(String... args) {var cmd=Main.commandLine();cmd.setOut(new PrintWriter(new StringWriter()));cmd.setErr(new PrintWriter(new StringWriter()));return cmd.execute(args);}
    @Test void exitCodesDistinguishFindingsAndConfigurationErrors() throws Exception {
        Files.writeString(root.resolve("README.md"),"[missing](gone.md)");
        assertEquals(1,run("check","--root",root.toString()));
        assertEquals(0,run("check","--root",root.toString(),"--warn-only"));
        assertEquals(2,run("check","--root",root.toString(),"--config",root.resolve("missing.yml").toString()));
        assertEquals(2,run("explain","JD999"));assertEquals(0,run("explain","JD002"));
    }
    @Test void createsMissingReportDirectoriesForPathsWithSpaces() throws Exception {
        Path project=root.resolve("project files"),report=root.resolve("output files/nested reports/result.json");
        Files.createDirectories(project);Files.writeString(project.resolve("README.md"),"[missing](gone.md)");
        assertEquals(1,run("check","--root",project.toString(),"--format","json","--output",report.toString()));
        assertTrue(Files.readString(report).contains("JD007"));
        assertEquals(0,run("check","--root",project.toString(),"--format","json","--output",report.toString(),"--warn-only"));
    }
}
