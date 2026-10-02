package io.github.bohdaq.javadrift.core;
import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
public final class SnippetCompiler {
    private record Attempt(List<Diagnostic<? extends JavaFileObject>> errors,int prefix) {}
    public void check(Path root,Config config,Set<Finding> out,DocReader.Document doc,DocReader.Fragment fragment,List<Path> classpath) throws IOException {
        if(config.severity(Check.JD008)==Severity.OFF || fragment.kind()!=DocReader.Kind.BLOCK || !fragment.language().equalsIgnoreCase("java"))return;
        String source=fragment.text();
        if(source.lines().anyMatch(l->Set.of("...","// ...","// javadrift:skip").contains(l.trim())))return;
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IOException("JD008 requires a JDK with javac");
        String imports=String.join("\n",config.snippets.imports.stream().map(i->"import "+i+";").toList());
        if(!imports.isEmpty())imports+="\n";
        List<Attempt> attempts=new ArrayList<>();
        String full=source.matches("(?s).*\\bpackage\\s+.*")?source:imports+source;
        int importLines=(int)imports.chars().filter(c->c=='\n').count();
        attempts.add(compile(compiler,full,full.equals(source)?0:importLines,config,classpath));
        if(attempts.get(0).errors().isEmpty())return;
        if(!source.matches("(?s).*\\b(?:package|import)\\s+.*")) {
            attempts.add(compile(compiler,imports+"class JavadriftExample {\n"+source+"\n}\n",importLines+1,config,classpath));
            if(attempts.get(1).errors().isEmpty())return;
            attempts.add(compile(compiler,imports+"class JavadriftExample { void example() throws Exception {\n"+source+"\n}}\n",importLines+1,config,classpath));
            if(attempts.get(2).errors().isEmpty())return;
        }
        Attempt best=attempts.stream().reduce((a,b)->a.errors().size()<b.errors().size()?a:b).orElseThrow();
        Diagnostic<? extends JavaFileObject> error=best.errors().get(0);
        int localLine=(int)Math.max(1,error.getLineNumber()-best.prefix());
        String[] lines=source.split("\n",-1);int offset=0;
        for(int i=0;i<Math.min(localLine-1,lines.length);i++)offset+=lines[i].length()+1;
        int column=(int)Math.max(1,error.getColumnNumber());
        if(localLine<=lines.length)offset+=Math.min(column-1,lines[localLine-1].length());
        offset=Math.min(offset,source.length());
        Analyzer.add(root,config,out,doc,fragment,offset,Check.JD008,source.strip(),"Java snippet does not compile: "+error.getMessage(Locale.ROOT).replace('\n',' '),null);
    }
    private Attempt compile(JavaCompiler compiler,String source,int prefix,Config config,List<Path> classpath) throws IOException {
        Matcher name=Pattern.compile("\\bpublic\\s+(?:(?:abstract|final)\\s+)*(?:class|interface|enum|record)\\s+(\\w+)").matcher(source);
        String file=name.find()?name.group(1):"JavadriftExample";
        JavaFileObject input=new SimpleJavaFileObject(URI.create("string:///"+file+".java"),JavaFileObject.Kind.SOURCE) {
            public CharSequence getCharContent(boolean ignore) {return source;}
        };
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager standard=compiler.getStandardFileManager(diagnostics,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8);
            JavaFileManager memory=new ForwardingJavaFileManager<StandardJavaFileManager>(standard) {
                public JavaFileObject getJavaFileForOutput(Location location,String name,JavaFileObject.Kind kind,FileObject sibling) {
                    return new SimpleJavaFileObject(URI.create("mem:///"+name.replace('.','/')+kind.extension),kind) {
                        public OutputStream openOutputStream() {return new ByteArrayOutputStream();}
                    };
                }
            }) {
            List<String> options=new ArrayList<>(List.of("-proc:none","-encoding","UTF-8","--release",config.snippets.release));
            // An explicit empty classpath prevents accidental access to Javadrift's own libraries.
            options.addAll(List.of("-classpath",classpath.isEmpty()?"":String.join(File.pathSeparator,classpath.stream().map(Path::toString).toList())));
            compiler.getTask(new StringWriter(),memory,diagnostics,options,null,List.of(input)).call();
            return new Attempt(diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).toList(),prefix);
        }
    }
}
