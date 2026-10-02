package io.github.bohdaq.javadrift.core;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
public final class Analyzer {
    private static final Pattern MEMBER=Pattern.compile("(?<![\\w.$])([A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*|[a-z][\\w$]*(?:\\.[\\w$]+)+)(#|::|\\.)([a-zA-Z_$][\\w$]*)(\\([^()]*\\))?");
    private static final Pattern QUALIFIED=Pattern.compile("(?<![\\w$])(?:[a-z][\\w$]*\\.)+[A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*");
    private static final Pattern PATH=Pattern.compile("(?<![\\w:/])(?:\\./)?(?:src|docs|config|gradle|\\.github)/[\\w./$@+-]+|(?<![\\w])(?:pom\\.xml|build\\.gradle(?:\\.kts)?|settings\\.gradle(?:\\.kts)?)");
    public record Result(List<Finding> findings,int documents,int types) {
        public boolean fails(Config config) {Severity level=Severity.parse(config.failOn);return findings.stream().anyMatch(f->f.severity().ordinal()>=level.ordinal());}
    }
    public Result analyze(Path root,Config config) throws IOException {
        root=root.toAbsolutePath().normalize();if(!Files.isDirectory(root))throw new IOException("Project directory does not exist: "+root);
        SymbolIndex index=new SourceIndexer().index(root);
        return scan(root,config,index);
    }
    public Result scan(Path root,Config config,SymbolIndex index) throws IOException {
        TreeSet<Finding> findings=new TreeSet<>();int count=0;
        Set<String> packages=config.sources.basePackages.isEmpty()?index.packages():new TreeSet<>(config.sources.basePackages);
        try(var files=Files.walk(root)) {
            for(Path file:files.filter(Files::isRegularFile).sorted().toList()) {
                String relative=relative(root,file);
                if(!config.includes(relative))continue;
                count++;DocReader.Document doc=new DocReader().read(file);
                for(DocReader.Fragment fragment:doc.fragments()) {
                    if(fragment.kind()==DocReader.Kind.LINK) {path(root,config,findings,doc,fragment,fragment.text(),0,true);continue;}
                    Matcher members=MEMBER.matcher(fragment.text());
                    while(members.find()) {
                        String typeName=members.group(1), name=members.group(3);
                        // A dotted member requires call syntax; fields use # or ::.
                        if(members.group(2).equals(".") && members.group(4)==null)continue;
                        Optional<SymbolIndex.Type> resolved=index.resolve(typeName);
                        if(resolved.isEmpty())continue;
                        SymbolIndex.Type type=resolved.get();
                        boolean known=index.members(type).stream().anyMatch(m->m.name().equals(name));
                        if(!known && !type.generated && !index.unresolvedParents(type) && !Set.of("toString","hashCode","equals","getClass","wait","notify","notifyAll","clone","finalize").contains(name))
                            add(root,config,findings,doc,fragment,members.start(),Check.JD002,members.group(),typeName+" has no member `"+name+"`",suggest(index,type,name));
                    }
                    Matcher qualified=QUALIFIED.matcher(fragment.text());
                    while(qualified.find()) {
                        String name=qualified.group();
                        if(packages.stream().anyMatch(p->name.startsWith(p+".")) && !index.types.containsKey(name))
                            add(root,config,findings,doc,fragment,qualified.start(),Check.JD001,name,"Unknown project type `"+name+"`",null);
                    }
                    Matcher paths=PATH.matcher(fragment.text());while(paths.find())path(root,config,findings,doc,fragment,paths.group(),paths.start(),false);
                }
            }
        }
        return new Result(List.copyOf(findings),count,index.types.size());
    }
    private String suggest(SymbolIndex index,SymbolIndex.Type type,String name) {
        return index.members(type).stream().map(SymbolIndex.Member::name).distinct().sorted().filter(n->distance(n,name)<=Math.max(2,name.length()/3)).findFirst().map(n->"Did you mean `"+n+"`?").orElse(null);
    }
    static int distance(String a,String b) {
        int[] prev=new int[b.length()+1];for(int j=0;j<=b.length();j++)prev[j]=j;
        for(int i=1;i<=a.length();i++){int[] next=new int[b.length()+1];next[0]=i;for(int j=1;j<=b.length();j++)next[j]=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));prev=next;}
        return prev[b.length()];
    }
    private void path(Path root,Config config,Set<Finding> out,DocReader.Document doc,DocReader.Fragment f,String value,int offset,boolean link) {
        if(value.startsWith("#")||value.contains(":")||value.startsWith("//")||value.contains("{")||value.contains("$")||value.contains("*")||value.contains(".."+"."))return;
        String path=value.split("[#?]",2)[0];if(path.isBlank())return;
        try {
            if(link)path=URI.create(path).getPath();
            Path target=link?(path.startsWith("/")?root.resolve(path.substring(1)):doc.path().getParent().resolve(path)):root.resolve(path);
            if(!Files.exists(target.normalize()))add(root,config,out,doc,f,offset,Check.JD007,value,"Missing repository path `"+path+"`",null);
        } catch(IllegalArgumentException e) { /* Not an unambiguous filesystem path. */ }
    }
    static void add(Path root,Config config,Set<Finding> out,DocReader.Document doc,DocReader.Fragment f,int offset,Check check,String ref,String message,String suggestion) {
        Severity severity=config.severity(check);if(severity==Severity.OFF)return;
        if(config.ignore.stream().anyMatch(g->Glob.matches(g,ref)))return;
        out.add(new Finding(relative(root,doc.path()),f.lineAt(offset),f.columnAt(offset),check.name(),severity,ref,message,suggestion));
    }
    static String relative(Path root,Path file) {return root.relativize(file).toString().replace('\\','/');}
}
