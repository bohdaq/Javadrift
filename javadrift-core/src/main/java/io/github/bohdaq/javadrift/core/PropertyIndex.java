package io.github.bohdaq.javadrift.core;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
public final class PropertyIndex {
    private final Set<String> keys=new TreeSet<>();
    public PropertyIndex(Path root) throws IOException {
        try(var files=Files.walk(root)) {
            for(Path file:files.filter(Files::isRegularFile).sorted().toList()) {
                String path=Analyzer.relative(root,file);
                if(SourceIndexer.productionJava(path)) {
                    String source=Files.readString(file);
                    Matcher declarations=Pattern.compile("(?:System\\.getProperty\\(\\s*\"|@(?:[\\w.]+\\.)?Value\\(\\s*\"\\$\\{)([a-zA-Z][a-zA-Z0-9_.-]+)").matcher(source);
                    while(declarations.find())keys.add(declarations.group(1));
                    continue;
                }
                if(!path.contains("src/main/resources/"))continue;
                String name=file.getFileName().toString();
                if(name.endsWith(".properties")) {
                    Properties properties=new Properties();try(var reader=Files.newBufferedReader(file)) {properties.load(reader);}keys.addAll(properties.stringPropertyNames());
                } else if(name.endsWith(".yml")||name.endsWith(".yaml")) {
                    try(var parser=new ObjectMapper(new YAMLFactory()).readerFor(JsonNode.class).readValues(file.toFile())) {
                        while(parser.hasNext())flatten("",(JsonNode)parser.next());
                    }
                } else if(name.equals("spring-configuration-metadata.json")) {
                    JsonNode props=new ObjectMapper().readTree(file.toFile()).path("properties");
                    for(JsonNode prop:props)if(prop.path("name").isTextual())keys.add(prop.path("name").asText());
                }
            }
        }
    }
    private void flatten(String prefix,JsonNode node) {
        if(node==null)return;
        if(node.isObject())node.fields().forEachRemaining(e->flatten(prefix.isEmpty()?e.getKey():prefix+"."+e.getKey(),e.getValue()));
        else if(!prefix.isEmpty())keys.add(prefix);
    }
    public void check(Path root,Config config,Set<Finding> out,DocReader.Document doc,DocReader.Fragment f) {
        if(f.kind()==DocReader.Kind.LINK)return;
        // Restrict opt-in checks to structured keys, not arbitrary dotted identifiers.
        Matcher matcher=Pattern.compile("(?m)(?:^|\\s)([a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)+)\\s*(?:=|:)").matcher(f.text());
        while(matcher.find()) {
            String key=matcher.group(1);
            if(!keys.contains(key))Analyzer.add(root,config,out,doc,f,matcher.start(1),Check.JD009,key,"Unknown configuration key `"+key+"`",null);
        }
        if(f.kind()==DocReader.Kind.CODE && f.text().matches("[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)+")) {
            String key=f.text();
            if(!keys.contains(key))Analyzer.add(root,config,out,doc,f,0,Check.JD009,key,"Unknown configuration key `"+key+"`",null);
        }
    }
}
