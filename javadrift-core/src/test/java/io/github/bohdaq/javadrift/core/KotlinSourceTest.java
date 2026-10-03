package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.jgit.api.Git;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class KotlinSourceTest {
    @TempDir Path root;
    void write(String path,String source) throws Exception {
        Path file=root.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,source);
    }
    @Test void checksKotlinPublicApiWithoutBuildingSources() throws Exception {
        write("src/main/kotlin/demo/Client.kt", """
            package demo
            open class Parent { fun inherited() {} }
            data class Client(val name: String, var count: Int = 0) : Parent() {
                var isReady: Boolean = false
                fun send(text: String, times: Int = 1) {}
                fun tags(vararg names: String) {}
                private fun secret() {}
                internal fun internalCall() {}
                class Nested { fun nestedCall() {} }
                companion object { fun create() = Client("name") }
            }
            internal class Hidden
            // class Imaginary { fun ghost() {} }
            """);
        write("README.md", """
            `Client#name` `Client#getName` `Client#component1` `Client#copy` `Client#create`
            `Client#inherited` `Client.Nested#nestedCall` `Client#isReady` `Client#setReady`
            ```kotlin
            Client.send("hi")
            Client.send("hi", 2)
            Client.tags("a", "b")
            Client.send(true)
            ```
            `Client#secret` `Client#internalCall` `demo.Hidden` `demo.Imaginary`
            """);
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(4,result.findings().size(),result.findings().toString());
        assertEquals(1,result.findings().stream().filter(f->f.checkId().equals("JD003")).count());
        assertEquals(1,result.findings().stream().filter(f->f.checkId().equals("JD001")).count());
        assertEquals(2,result.findings().stream().filter(f->f.checkId().equals("JD002")).count());
    }
    @Test void indexesFileFacadesAliasesAndExcludesTestSources() throws Exception {
        write("src/main/kotlin/demo/Helpers.kt", """
            @file:JvmName("Helpers")
            package demo
            class Api { fun run() {} }
            typealias Alias = Api
            fun helper(text: String = "hi") {}
            """);
        write("src/test/kotlin/demo/TestOnly.kt","package demo; class TestOnly");
        write("README.md","`Alias#run` `Helpers#helper` `Helpers#missing` `demo.TestOnly`");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(1,result.findings().size(),result.findings().toString());
        assertEquals("Helpers#missing",result.findings().get(0).reference());
        assertEquals(3,result.types()); // Test helper names are hints, not public API types.
    }
    @Test void detectsKotlinMemberRenameInRealGitTrees() throws Exception {
        try(var git=Git.init().setDirectory(root.toFile()).call()) {
            write("src/main/kotlin/demo/Api.kt","package demo; class Api { fun oldCall() {} }");
            write("README.md","`Api#oldCall`");
            git.add().addFilepattern(".").call();
            String base=git.commit().setMessage("original").setAuthor("Fixture","fixture@example.com").call().name();
            write("src/main/kotlin/demo/Api.kt","package demo; class Api { fun newCall() {} }");
            git.add().addFilepattern(".").call();git.commit().setMessage("rename").setAuthor("Fixture","fixture@example.com").call();
            var result=new Analyzer().analyze(root,new Config(),base,true);
            assertTrue(result.findings().stream().anyMatch(f->f.checkId().equals("JD004") && f.suggestion().contains("newCall")),result.findings().toString());
            write("README.md","`Api#newCall`");
            assertTrue(new Analyzer().analyze(root,new Config(),base,true).findings().isEmpty());
        }
    }
    @Test void resolvesExtensionsDeclaredBeforeReceiverClasses() throws Exception {
        write("src/main/kotlin/demo/aExtensions.kt","package demo; fun Api.extra(text: String = \"hi\") {} ");
        write("src/main/kotlin/demo/zApi.kt","package demo; class Api");
        write("README.md","`Api#extra` `AExtensionsKt#extra` `Api#absent`");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(1,result.findings().size(),result.findings().toString());
        assertEquals("Api#absent",result.findings().get(0).reference());
    }
    @Test void compiledJvmSignaturesRetainKotlinPropertiesAndDefaults() throws Exception {
        write("src/main/kotlin/demo/Client.kt","package demo; class Client(val name: String) { fun send(text: String, times: Int = 1) {} }");
        Path classes=root.resolve("target/classes");Files.createDirectories(classes);
        Path java=root.resolve("Client.java");
        Files.writeString(java,"package demo; public class Client { public String getName(){return null;} public void send(String text,int times){} }");
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),java.toString()));
        Files.delete(java);
        write("README.md","`Client#name` `Client#getName` `Client.send(\"hi\")` `Client#absent`");
        var result=new Analyzer().analyze(root,new Config());
        assertEquals(1,result.findings().size(),result.findings().toString());
        assertEquals("Client#absent",result.findings().get(0).reference());
    }
    @Test void rejectsMalformedKotlinWithItsSourcePath() throws Exception {
        write("src/main/kotlin/demo/Broken.kt","class Broken { fun (");
        var failure=assertThrows(java.io.IOException.class,()->new Analyzer().analyze(root,new Config()));
        assertTrue(failure.getMessage().contains("Broken.kt"));
    }
}
