package io.github.bohdaq.javadrift.core;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
public final class Config {
    public static final class Docs {
        public List<String> include=new ArrayList<>(List.of("README.md","README.adoc","docs/**/*.md","docs/**/*.adoc"));
        public List<String> exclude=new ArrayList<>();
        public List<Exemption> exemptions=new ArrayList<>();
        public List<String> historical=new ArrayList<>(List.of("**/CHANGELOG.*","**/CHANGES.*","**/HISTORY.*","**/changelog.*","**/changes.*","**/history.*","**/release-notes/**","**/release-notes.*"));
    }
    public static final class Exemption {
        public String file,check,reference,reason;
        public DocReader.Kind kind;
    }
    public static final class Sources { public List<String> basePackages=new ArrayList<>(); }
    public static final class Snippets { public List<String> imports=new ArrayList<>(); public String release="17"; }
    public Snippets snippets=new Snippets();
    public static final class Project { public String groupId,artifactId,version; }
    public Project project=new Project();
    public List<String> classes=new ArrayList<>(),classpath=new ArrayList<>();
    public Docs docs=new Docs(); public Sources sources=new Sources();
    public Map<String,String> checks=new LinkedHashMap<>();
    public String failOn="error"; public String baseline="javadrift-baseline.json";
    public List<String> ignore=new ArrayList<>();
    public static Config load(Path root, Path override) throws IOException {
        Path file=override==null?root.resolve("javadrift.yml"):override;
        if(!Files.exists(file)) {
            if(override!=null) throw new IOException("Configuration file does not exist: "+file);
            return new Config();
        }
        Config c=new ObjectMapper(new YAMLFactory()).readValue(file.toFile(),Config.class);
        if(c==null || c.snippets==null || c.snippets.imports==null || c.snippets.release==null || c.project==null || c.classes==null || c.classpath==null || c.docs==null || c.sources==null || c.checks==null || c.ignore==null || c.baseline==null
           || c.docs.include==null || c.docs.exclude==null || c.docs.historical==null || c.docs.exemptions==null || c.sources.basePackages==null)
            throw new IllegalArgumentException("Configuration sections cannot be null");
        Severity failure=Severity.parse(c.failOn);
        if(failure==Severity.OFF) throw new IllegalArgumentException("failOn must be warning or error");
        c.checks.forEach((k,v)->{Check.from(k);Severity.parse(v);});
        if(!c.snippets.release.matches("[0-9]+"))throw new IllegalArgumentException("snippets.release must be a Java release number");
        for(String name:c.snippets.imports)if(name==null || !name.matches("(?:static )?[a-zA-Z_$][\\w$]*(?:\\.[\\w$*]+)+"))throw new IllegalArgumentException("Invalid snippet import: "+name);
        for(List<String> list:List.of(c.docs.include,c.docs.exclude,c.docs.historical,c.sources.basePackages,c.ignore,c.classes,c.classpath))
            if(list.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Patterns cannot be null");
        for(Exemption exemption:c.docs.exemptions) {
            if(exemption==null || exemption.file==null || exemption.file.isBlank() || exemption.check==null
                || exemption.reference==null || exemption.reference.isBlank() || exemption.reason==null || exemption.reason.isBlank())
                throw new IllegalArgumentException("Document exemptions require file, check, reference and reason");
            Check.from(exemption.check);
        }
        return c;
    }
    public Severity severity(Check c) {return Severity.parse(checks.getOrDefault(c.name(),checks.getOrDefault(c.key,c.defaultSeverity.name())));}
    public boolean exempted(String path,Check check,String reference,DocReader.Kind kind) {
        return docs.exemptions.stream().anyMatch(e->Glob.matches(e.file,path) && Check.from(e.check)==check
            && Glob.matches(e.reference,reference) && (e.kind==null || e.kind==kind));
    }
    public boolean historical(String path) {return docs.historical.stream().anyMatch(g->Glob.matches(g,path));}
    public boolean includes(String path) {return docs.include.stream().anyMatch(g->Glob.matches(g,path)) && docs.exclude.stream().noneMatch(g->Glob.matches(g,path));}
}
