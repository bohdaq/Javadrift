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
    }
    public static final class Sources { public List<String> basePackages=new ArrayList<>(); }
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
        if(c==null || c.docs==null || c.sources==null || c.checks==null || c.ignore==null || c.baseline==null
           || c.docs.include==null || c.docs.exclude==null || c.sources.basePackages==null)
            throw new IllegalArgumentException("Configuration sections cannot be null");
        Severity failure=Severity.parse(c.failOn);
        if(failure==Severity.OFF) throw new IllegalArgumentException("failOn must be warning or error");
        c.checks.forEach((k,v)->{Check.from(k);Severity.parse(v);});
        for(List<String> list:List.of(c.docs.include,c.docs.exclude,c.sources.basePackages,c.ignore))
            if(list.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Patterns cannot be null");
        return c;
    }
    public Severity severity(Check c) {return Severity.parse(checks.getOrDefault(c.name(),checks.getOrDefault(c.key,c.defaultSeverity.name())));}
    public boolean includes(String path) {return docs.include.stream().anyMatch(g->Glob.matches(g,path)) && docs.exclude.stream().noneMatch(g->Glob.matches(g,path));}
}
