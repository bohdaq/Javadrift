package io.github.bohdaq.javadrift.core;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
public final class Analyzer {
    private static final Pattern MEMBER=Pattern.compile("(?<![\\w.$])([A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*|[a-z][\\w$]*(?:\\.[\\w$]+)+)(#|::|\\.)([a-zA-Z_$][\\w$]*)");
    private static final Pattern QUALIFIED=Pattern.compile("(?<![\\w$])(?:[a-z][\\w$]*\\.)+[A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*");
    private static final Pattern PATH=Pattern.compile("(?<![\\w:/.~])(?:\\./)?(?:src|docs|config|gradle|\\.github)/[\\w./$@+-]+|(?<![\\w])(?:pom\\.xml|build\\.gradle(?:\\.kts)?|settings\\.gradle(?:\\.kts)?)");
    public record Result(List<Finding> findings,int documents,int types) {
        public boolean fails(Config config) {Severity level=Severity.parse(config.failOn);return findings.stream().anyMatch(f->f.severity().ordinal()>=level.ordinal());}
    }
    public Result analyze(Path root,Config config) throws IOException {return analyze(root,config,null,true);}
    public Result analyze(Path root,Config config,String since,boolean useBaseline) throws IOException {
        root=root.toAbsolutePath().normalize();if(!Files.isDirectory(root))throw new IOException("Project directory does not exist: "+root);
        SymbolIndex index=new SourceIndexer().index(root);
        BytecodeIndexer bytecode=new BytecodeIndexer();
        try(var paths=Files.walk(root)) {
            for(Path dir:paths.filter(Files::isDirectory).sorted().toList()) {
                String path=relative(root,dir);
                if(BytecodeIndexer.automaticClasses(path))bytecode.add(index,dir,true);
            }
        }
        for(String dir:config.classes)bytecode.add(index,root.resolve(dir),true);
        for(String entry:config.classpath) {
            Path path=root.resolve(entry);if(!Files.exists(path))throw new IOException("Classpath entry does not exist: "+path);
            bytecode.add(index,path,false);
        }
        bytecode.addJdkParents(index);
        Result full=scan(root,config,index);
        TreeSet<Finding> findings=new TreeSet<>(full.findings());
        if(since!=null) {
            List<GitHistory.Removed> removed=new GitHistory().removed(root,since);
            try(var files=Files.walk(root)) {
                for(Path file:files.filter(Files::isRegularFile).sorted().toList()) {
                    if(!config.includes(relative(root,file)) || config.historical(relative(root,file)))continue;
                    DocReader.Document doc=new DocReader().read(file);
                    for(DocReader.Fragment fragment:doc.fragments()) {
                        if(!DocReader.apiFragment(fragment))continue;
                        for(GitHistory.Removed symbol:removed) {
                            Matcher m=symbol.pattern().matcher(fragment.text());
                            while(m.find()) {
                                int line=fragment.lineAt(m.start()),column=fragment.columnAt(m.start());
                                String fileName=relative(root,doc.path());
                                boolean covered=findings.stream().anyMatch(f->f.checkId().equals("JD004") && f.file().equals(fileName)
                                    && f.line()==line && f.column()<=column && f.column()+f.reference().length()>column);
                                if(!covered)add(root,config,findings,doc,fragment,m.start(),Check.JD004,m.group(),"Removed symbol `"+symbol.token()+"` is still documented",symbol.suggestion());
                            }
                        }
                    }
                }
            }
            // Prefer a history-backed finding to a generic missing symbol at the same location.
            Set<String> historyLocations=new HashSet<>();
            findings.stream().filter(f->f.checkId().equals("JD004")).forEach(f->historyLocations.add(f.file()+":"+f.line()+":"+f.column()));
            findings.removeIf(f->Set.of("JD001","JD002").contains(f.checkId()) && historyLocations.contains(f.file()+":"+f.line()+":"+f.column()));
        }
        List<Finding> filtered=useBaseline?Baseline.filter(root.resolve(config.baseline),List.copyOf(findings)):List.copyOf(findings);
        return new Result(filtered,full.documents(),full.types());
    }
    public Result scan(Path root,Config config,SymbolIndex index) throws IOException {
        TreeSet<Finding> findings=new TreeSet<>();int count=0;
        PropertyIndex properties=config.severity(Check.JD009)==Severity.OFF?null:new PropertyIndex(root);
        List<Path> compilationClasspath=new ArrayList<>();
        for(String entry:config.classes)compilationClasspath.add(root.resolve(entry));
        for(String entry:config.classpath)compilationClasspath.add(root.resolve(entry));
        try(var dirs=Files.walk(root)) {
            for(Path dir:dirs.filter(Files::isDirectory).toList()) {
                String path=relative(root,dir);
                if(BytecodeIndexer.automaticClasses(path))compilationClasspath.add(dir);
            }
        }
        List<ProjectVersions.Coordinates> coordinates=new ProjectVersions().discover(root,config);
        Set<String> packages=config.sources.basePackages.isEmpty()?index.packages():new TreeSet<>(config.sources.basePackages);
        if(config.sources.basePackages.isEmpty() && packages.stream().anyMatch(p->!examplePackage(p))) {
            packages=new TreeSet<>(packages);packages.removeIf(Analyzer::examplePackage);
        }
        try(var files=Files.walk(root)) {
            for(Path file:files.filter(Files::isRegularFile).sorted().toList()) {
                String relative=relative(root,file);
                if(!config.includes(relative))continue;
                count++;DocReader.Document doc=new DocReader().read(file);
                for(DocReader.Fragment fragment:doc.fragments()) {
                    if(fragment.kind()==DocReader.Kind.LINK) {path(root,config,findings,doc,fragment,fragment.text(),0,true);continue;}
                    if(!config.historical(relative) && DocReader.apiFragment(fragment)) {
                    Matcher members=MEMBER.matcher(fragment.text());
                    while(members.find()) {
                        String typeName=members.group(1), name=members.group(3);
                        if(members.group(2).equals("::") && name.equals("class"))continue; // Kotlin class literal.
                        // A dotted member requires call syntax; fields use # or ::.
                        Optional<CallArguments.Call> call=CallArguments.parse(fragment.text(),members.end());
                        if(members.group(2).equals(".") && call.isEmpty())continue;
                        String owner=fragment.language().startsWith("owner:")?fragment.language().substring(6):typeName;
                        Optional<SymbolIndex.Type> resolved=fragment.language().startsWith("owner:")
                            ? index.types.values().stream().filter(t->owner.equals(t.name)||owner.endsWith("."+t.name)).max(Comparator.comparingInt(t->t.name.length()))
                            : index.resolve(owner);
                        if(resolved.isEmpty())continue;
                        SymbolIndex.Type type=resolved.get();
                        if(!type.project)continue;
                        List<SymbolIndex.Member> matching=index.members(type).stream().filter(m->m.name().equals(name)).toList();
                        boolean known=!matching.isEmpty();
                        if(known && call.isPresent() && (fragment.kind()==DocReader.Kind.BLOCK || !call.get().arguments().isEmpty()) && matching.stream().anyMatch(SymbolIndex.Member::method) && matching.stream().noneMatch(m->CallArguments.matches(m,call.get().arguments(),index)))
                            add(root,config,findings,doc,fragment,members.start(),Check.JD003,fragment.text().substring(members.start(),call.get().end()),
                                "No overload of `"+typeName+"#"+name+"` matches the documented arguments",null);
                        if(type.forRemoval || (!matching.isEmpty() && matching.stream().allMatch(SymbolIndex.Member::forRemoval)))
                            add(root,config,findings,doc,fragment,members.start(),Check.JD005,members.group(),"API `"+members.group()+"` is deprecated for removal",null);
                        if(!known && !type.generated && !index.unresolvedParents(type) && !Set.of("toString","hashCode","equals","getClass","wait","notify","notifyAll","clone","finalize").contains(name))
                            add(root,config,findings,doc,fragment,members.start(),Check.JD002,members.group(),typeName+" has no member `"+name+"`",suggest(index,type,name));
                    }
                    Matcher qualified=QUALIFIED.matcher(fragment.text());
                    while(qualified.find()) {
                        String reference=qualified.group(),name=reference.replace('$','.');
                        SymbolIndex.Type mentioned=index.types.get(name);
                        if(mentioned!=null && mentioned.project && mentioned.forRemoval && findings.stream().noneMatch(f->f.checkId().equals("JD005") && f.file().equals(relative(root,doc.path())) && f.line()==fragment.lineAt(qualified.start()) && f.column()==fragment.columnAt(qualified.start())))
                            add(root,config,findings,doc,fragment,qualified.start(),Check.JD005,name,"Type `"+name+"` is deprecated for removal",null);
                        String parentName=name.contains(".")?name.substring(0,name.lastIndexOf('.')):"";
                        boolean accessibleField=index.resolve(parentName).map(t->index.members(t).stream().anyMatch(m->m.name().equals(name.substring(name.lastIndexOf('.')+1)))).orElse(false);
                        boolean owned=config.sources.basePackages.isEmpty()
                            ? packages.contains(parentName) || index.types.containsKey(parentName) || misplacedKnownType(index,name)
                            : config.sources.basePackages.stream().anyMatch(p->name.startsWith(p+"."));
                        if(owned && !index.types.containsKey(name) && !index.documentationTypes.contains(name) && !accessibleField)
                            add(root,config,findings,doc,fragment,qualified.start(),Check.JD001,reference,"Unknown project type `"+reference+"`",null);
                    }
                    new ProjectVersions().check(root,config,findings,doc,fragment,coordinates);
                    new SnippetCompiler().check(root,config,findings,doc,fragment,compilationClasspath);
                    if(properties!=null)properties.check(root,config,findings,doc,fragment);
                    }
                    Matcher paths=PATH.matcher(fragment.text());while(paths.find()) {
                        if(paths.end()<fragment.text().length() && "*{?".indexOf(fragment.text().charAt(paths.end()))>=0)continue;
                        path(root,config,findings,doc,fragment,paths.group(),paths.start(),false);
                    }
                }
            }
        }
        return new Result(List.copyOf(findings),count,(int)index.types.values().stream().filter(t->t.project).count());
    }
    private static boolean misplacedKnownType(SymbolIndex index,String name) {
        if(examplePackage(name))return false;
        String[] parts=name.split("\\.");if(parts.length<4)return false;
        return java.util.stream.Stream.concat(index.types.keySet().stream(),index.documentationTypes.stream()).anyMatch(known->{
            if(!known.endsWith("."+parts[parts.length-1]))return false;
            String[] candidate=known.split("\\.");int shared=0;
            while(shared<Math.min(parts.length-1,candidate.length-1) && parts[shared].equals(candidate[shared]))shared++;
            return shared>=3;
        });
    }
    private static boolean examplePackage(String value) {
        return Set.of("example","com.example","org.example").stream().anyMatch(p->value.equals(p)||value.startsWith(p+"."));
    }
    private String suggest(SymbolIndex index,SymbolIndex.Type type,String name) {
        return index.members(type).stream().map(SymbolIndex.Member::name).distinct().sorted(Comparator.comparingInt((String n)->distance(n,name)).thenComparing(n->n)).filter(n->distance(n,name)<=Math.max(2,name.length()/3)).findFirst().map(n->"Did you mean `"+n+"`?").orElse(null);
    }
    static int distance(String a,String b) {
        int[] prev=new int[b.length()+1];for(int j=0;j<=b.length();j++)prev[j]=j;
        for(int i=1;i<=a.length();i++){int[] next=new int[b.length()+1];next[0]=i;for(int j=1;j<=b.length();j++)next[j]=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));prev=next;}
        return prev[b.length()];
    }
    private void path(Path root,Config config,Set<Finding> out,DocReader.Document doc,DocReader.Fragment f,String value,int offset,boolean link) throws IOException {
        if(value.startsWith("#")||value.contains(":")||value.startsWith("//")||value.contains("{")||value.contains("$")||value.contains("*")||value.contains(".."+"."))return;
        String path=value.split("[#?]",2)[0];if(path.isBlank())return;
        try {
            if(link)path=URI.create(path).getPath();
            Path origin=RepositoryPaths.origin(root,doc.path());
            Path target=link?(path.startsWith("/")?root.resolve(path.substring(1)):origin.getParent().resolve(path)):root.resolve(path);
            if(link && f.language().equals("xref") && !path.contains("/") && !path.contains("."))return; // Same-document anchor.
            if(link && f.language().equals("xref")) {
                // Antora page IDs are rooted at their module's pages family.
                for(Path module=origin.getParent();module!=null && module.startsWith(root);module=module.getParent()) {
                    if(module.getParent()!=null && module.getParent().getFileName().toString().equals("modules")) {
                        target=path.startsWith("./")||path.startsWith("../")
                            ?origin.getParent().resolve(path):module.resolve("pages").resolve(path);
                        break;
                    }
                }
            }
            // Escaping links include GitHub sibling-repo and wiki navigation.
            // They are outside the repository-file check's scope.
            if(link && (path.startsWith("/") || !target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())))return;
            if(!RepositoryPaths.exists(root,origin,target,path,link) && (link || !RepositoryPaths.ambiguousInline(root,doc.path(),f,path)))add(root,config,out,doc,f,offset,Check.JD007,value,"Missing repository path `"+path+"`",null);
        } catch(IllegalArgumentException e) { /* Not an unambiguous filesystem path. */ }
    }
    static void add(Path root,Config config,Set<Finding> out,DocReader.Document doc,DocReader.Fragment f,int offset,Check check,String ref,String message,String suggestion) {
        Severity severity=config.severity(check);if(severity==Severity.OFF)return;
        if(config.ignore.stream().anyMatch(g->Glob.matches(g,ref)) || config.exempted(relative(root,doc.path()),check,ref,f.kind()))return;
        String[] lines=doc.text().split("\\n",-1);
        int at=f.lineAt(offset)-1;
        if(ignored(lines,at) || (f.kind()==DocReader.Kind.BLOCK && ignored(lines,f.line()-2)))return;
        int column=f.columnAt(offset);
        if(at>=0 && at<lines.length) {
            int localLine=f.lineAt(offset)-f.line();String[] fragmentLines=f.text().split("\\n",-1);
            if(localLine<fragmentLines.length) {
                int start=lines[at].indexOf(fragmentLines[localLine],localLine==0?Math.max(0,f.column()-1):0);
                if(start>=0)column=start+(localLine==0?offset:column-1)+1;
            }
        }
        out.add(new Finding(relative(root,doc.path()),f.lineAt(offset),column,check.name(),severity,ref,message,suggestion));
    }
    private static boolean ignored(String[] lines,int line) {
        if(line<=0 || line>lines.length)return false;
        String previous=lines[line-1].trim();
        return previous.equals("<!-- javadrift:ignore-next -->") || previous.equals("// javadrift:ignore-next");
    }
    static String relative(Path root,Path file) {return root.relativize(file).toString().replace('\\','/');}
}
