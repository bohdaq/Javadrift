package io.github.bohdaq.javadrift.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bohdaq.javadrift.core.*;
import picocli.CommandLine.*;
import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.jar.JarFile;

@Command(name="doctor",mixinStandardHelpOptions=true,description="Diagnose project setup without checking documentation findings")
public final class DoctorCommand implements Callable<Integer> {
    @Option(names={"--root","-r"},defaultValue=".",description="Project directory") Path root;
    @Option(names="--config",description="YAML configuration file") Path configPath;
    @Option(names="--class-dir",description="Compiled project classes (repeatable)") List<String> classes=new ArrayList<>();
    @Option(names="--classpath",description="Dependency classpath using the platform path separator") String classpath;
    @Option(names="--format",defaultValue="text",description="text or json") Format format;
    @Spec Model.CommandSpec spec;
    enum Format {text,json}
    record Diagnostic(String check,String status,String message,String advice) {}
    record Result(int schemaVersion,String root,int exitCode,List<Diagnostic> diagnostics) {}
    private final int javaFeature;
    private final java.util.function.Supplier<JavaCompiler> compilerProvider;

    public DoctorCommand() {this.javaFeature=Runtime.version().feature();this.compilerProvider=ToolProvider::getSystemJavaCompiler;}
    DoctorCommand(int javaFeature,JavaCompiler compiler) {this.javaFeature=javaFeature;this.compilerProvider=()->compiler;}

    public Integer call() throws IOException {
        root=root.toAbsolutePath().normalize();
        List<Diagnostic> rows=new ArrayList<>();
        inspect(rows);
        int exit=rows.stream().anyMatch(r->r.status().equals("ERROR"))?2:rows.stream().anyMatch(r->r.status().equals("WARN"))?1:0;
        Result result=new Result(1,root.toString(),exit,List.copyOf(rows));
        PrintWriter out=spec.commandLine().getOut();
        if(format==Format.json)out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        else {
            out.println("Javadrift setup: "+root);
            for(Diagnostic row:rows) {
                out.println("["+row.status()+"] "+row.check()+": "+row.message());
                if(row.advice()!=null)out.println("  "+row.advice());
            }
            out.println(exit==2?"Setup has invalid inputs (exit 2).":exit==1?"Setup has warnings (exit 1).":"Setup checks passed (exit 0). Run check to analyze documentation.");
        }
        out.flush();return exit;
    }

    private void inspect(List<Diagnostic> rows) {
        add(rows,"java",javaFeature>=17?"PASS":"ERROR","Java "+javaFeature+" at "+System.getProperty("java.home"),javaFeature>=17?null:"Run Javadrift with Java 17 or newer.");
        if(!Files.isDirectory(root) || !Files.isReadable(root)) {
            add(rows,"root","ERROR","Project directory is missing, unreadable or not a directory: "+root,"Choose an existing readable project with --root.");return;
        }
        add(rows,"root","PASS","Readable project directory",null);
        Config config;
        Path selected=configPath==null?root.resolve("javadrift.yml"):configPath.toAbsolutePath().normalize();
        try {
            config=Config.load(root,configPath);
            add(rows,"configuration",Files.exists(selected)?"PASS":"INFO",Files.exists(selected)?"Loaded "+selected:"No javadrift.yml; using default configuration",null);
        } catch(IOException | IllegalArgumentException e) {
            add(rows,"configuration","ERROR",selected+": "+e.getMessage(),"Fix the YAML, check names and values, or choose an existing file with --config. Relative --config paths use the working directory.");return;
        }
        config.classes.addAll(classes);
        if(classpath!=null)config.classpath.addAll(Arrays.asList(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator),-1)));
        long sourceCount=0,projectClasses=0;
        try(var paths=Files.walk(root)) {
            List<Path> inputs=paths.sorted().toList();
            List<Path> docs=inputs.stream().filter(Files::isRegularFile).filter(p->config.includes(relative(p))).toList();
            add(rows,"documents",docs.isEmpty()?"WARN":"PASS",docs.size()+" selected documents; include="+config.docs.include+", exclude="+config.docs.exclude,
                docs.isEmpty()?"Choose the project root containing your docs, or adjust docs.include/docs.exclude in javadrift.yml.":null);
            for(Path doc:docs)if(!Files.isReadable(doc))add(rows,"documents","ERROR","Unreadable document: "+doc,"Make the selected document readable or exclude it.");
            List<Path> sources=inputs.stream().filter(Files::isRegularFile).filter(p->SourceIndexer.productionSource(relative(p))).toList();
            sourceCount=sources.size();
            for(Path source:sources)if(!Files.isReadable(source))add(rows,"sources","ERROR","Unreadable source: "+source,"Make production source files readable.");
            List<Path> automatic=inputs.stream().filter(Files::isDirectory).filter(p->BytecodeIndexer.automaticClasses(relative(p))).toList();
            if(automatic.isEmpty())add(rows,"compiled-classes","INFO","No automatic Maven/Gradle class directories; source indexing is available",
                "Build the project for generated/inherited APIs and Java snippets, or provide custom outputs with --class-dir.");
            for(Path dir:automatic)projectClasses+=inspectEntry(rows,"compiled-classes",dir,true);
        } catch(IOException | UncheckedIOException | SecurityException e) {
            add(rows,"discovery","ERROR","Cannot discover project inputs: "+e.getMessage(),"Make the project tree readable and choose the correct --root.");
        }
        for(String entry:config.classes)projectClasses+=inspectEntry(rows,"class-dir",entry,true);
        for(String entry:config.classpath)inspectEntry(rows,"classpath",entry,false);
        if(config.classpath.isEmpty())add(rows,"classpath","INFO","No explicit dependencies",
            "When using external APIs or JD008, provide dependency jars/directories with --classpath and the platform path separator.");
        add(rows,"sources",sourceCount>0?"PASS":projectClasses>0?"INFO":"WARN",sourceCount+" production Java/Kotlin source files",
            sourceCount>0?null:projectClasses>0?"Compiled project inputs are available; source-based Git history checks still require source files.":"Use --root containing production src trees or flat root sources, or supply compiled project inputs with --class-dir. Without these, API checks have no project symbols.");
        inspectCompiler(rows,config);
        try {
            Path baseline=root.resolve(config.baseline);
            Baseline.filter(baseline,List.of());
            add(rows,"baseline",Files.exists(baseline)?"PASS":"INFO",Files.exists(baseline)?"Valid baseline: "+baseline:"No baseline; findings will be unsuppressed",null);
        } catch(IOException | IllegalArgumentException e) {
            add(rows,"baseline","ERROR","Cannot load baseline: "+e.getMessage(),"Fix the configured baseline's version and fingerprints; review accepted findings before regenerating it.");
        }
    }

    private long inspectEntry(List<Diagnostic> rows,String check,String entry,boolean project) {
        if(entry.isBlank()) {
            add(rows,check,"ERROR","Empty classpath entry","Remove empty entries and use "+File.pathSeparator+" between dependency paths.");return 0;
        }
        try {return inspectEntry(rows,check,root.resolve(entry),project);}
        catch(InvalidPathException e) {add(rows,check,"ERROR","Invalid path: "+entry,"Correct the input path.");return 0;}
    }

    private long inspectEntry(List<Diagnostic> rows,String check,Path path,boolean project) {
        try {
            if(!Files.exists(path) || !Files.isReadable(path))throw new IOException("Missing or unreadable: "+path);
            long count;
            if(Files.isDirectory(path)) {
                try(var files=Files.walk(path)) {count=files.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".class")).count();}
            } else if(Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
                try(JarFile jar=new JarFile(path.toFile())) {count=jar.stream().filter(e->!e.isDirectory() && e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")).count();}
            } else throw new IOException("Expected a directory or .jar file: "+path);
            add(rows,check,project && count==0?"WARN":"PASS",path+": "+count+" class files",
                project && count==0?"Compile production classes before checking, or choose the correct output directory/jar.":null);
            return count;
        } catch(IOException | UncheckedIOException | SecurityException e) {
            add(rows,check,"ERROR",e.getMessage(),"Build the project or resolve its dependencies, then correct the configured or CLI input path.");return 0;
        }
    }

    private void inspectCompiler(List<Diagnostic> rows,Config config) {
        JavaCompiler compiler=compilerProvider.get();
        if(config.severity(Check.JD008)==Severity.OFF) {
            add(rows,"compiler","INFO",compiler==null?"javac is unavailable; JD008 is disabled":"javac is available; JD008 is disabled",null);return;
        }
        if(compiler==null) {
            add(rows,"compiler","ERROR","JD008 is enabled but javac is unavailable","Run with a full JDK 17 or newer, or disable snippet-compile.");return;
        }
        JavaFileObject probe=new SimpleJavaFileObject(URI.create("string:///JavadriftDoctorProbe.java"),JavaFileObject.Kind.SOURCE) {
            public CharSequence getCharContent(boolean ignore) {return "class JavadriftDoctorProbe {}";}
        };
        try(JavaFileManager memory=new ForwardingJavaFileManager<StandardJavaFileManager>(compiler.getStandardFileManager(null,Locale.ROOT,StandardCharsets.UTF_8)) {
            public JavaFileObject getJavaFileForOutput(Location location,String name,JavaFileObject.Kind kind,FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("mem:///"+name+kind.extension),kind) {
                    public OutputStream openOutputStream() {return new ByteArrayOutputStream();}
                };
            }
        }) {
            boolean supported=compiler.getTask(new StringWriter(),memory,null,List.of("-proc:none","-classpath","","--release",config.snippets.release),null,List.of(probe)).call();
            add(rows,"compiler",supported?"PASS":"ERROR","JD008 compiler probe for Java release "+config.snippets.release+(supported?" passed":" failed"),
                supported?null:"Use a JDK supporting snippets.release, or change the configured release.");
        } catch(IOException | IllegalArgumentException e) {
            add(rows,"compiler","ERROR","JD008 compiler cannot use Java release "+config.snippets.release+": "+e.getMessage(),"Use a JDK supporting snippets.release, or change the configured release.");
        }
    }

    private String relative(Path path) {return root.relativize(path).toString().replace('\\','/');}
    private static void add(List<Diagnostic> rows,String check,String status,String message,String advice) {rows.add(new Diagnostic(check,status,message,advice));}
}
