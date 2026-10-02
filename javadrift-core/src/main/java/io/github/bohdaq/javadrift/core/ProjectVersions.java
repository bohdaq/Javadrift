package io.github.bohdaq.javadrift.core;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.lib.Repository;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
public final class ProjectVersions {
    public record Coordinates(String groupId,String artifactId,String version) {}
    public List<Coordinates> discover(Path root,Config config) throws IOException {
        List<Coordinates> result=new ArrayList<>();
        if(config.project.groupId!=null && config.project.artifactId!=null && config.project.version!=null)
            result.add(new Coordinates(config.project.groupId,config.project.artifactId,latest(root,config.project.version)));
        try(var files=Files.walk(root)) {
            for(Path p:files.filter(f->f.getFileName().toString().equals("pom.xml")).filter(f->!root.relativize(f).toString().matches("(?:.*?/)?(?:target|build|\\.git|node_modules)/.*")).sorted().toList()) {
                try {
                    DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
                    Element project=factory.newDocumentBuilder().parse(p.toFile()).getDocumentElement();
                    Element parent=child(project,"parent");
                    String group=first(value(project,"groupId"),parent==null?null:value(parent,"groupId"));
                    String version=first(value(project,"version"),parent==null?null:value(parent,"version"));
                    String artifact=value(project,"artifactId");
                    Element props=child(project,"properties");
                    if(props!=null) {group=expand(group,props);version=expand(version,props);}
                    if(group!=null && artifact!=null && version!=null && !group.contains("${") && !version.contains("${"))
                        result.add(new Coordinates(group,artifact,latest(root,version)));
                } catch(Exception e) {throw new IOException("Cannot read project coordinates from "+p,e);}
            }
        }
        return result.stream().distinct().toList();
    }
    private String expand(String text,Element props) {
        if(text==null)return null;
        Matcher m=Pattern.compile("\\$\\{([^}]+)}").matcher(text);StringBuffer b=new StringBuffer();
        while(m.find())m.appendReplacement(b,Matcher.quoteReplacement(Optional.ofNullable(value(props,m.group(1))).orElse(m.group())));
        m.appendTail(b);return b.toString();
    }
    private String first(String a,String b) {return a==null?b:a;}
    private Element child(Element e,String key) {for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element c && c.getTagName().equals(key))return c;return null;}
    private String value(Element e,String key) {Element c=child(e,key);return c==null?null:c.getTextContent().trim();}
    private String latest(Path root,String current) throws IOException {
        if(!current.endsWith("-SNAPSHOT"))return current;
        FileRepositoryBuilder builder=new FileRepositoryBuilder().findGitDir(root.toFile());if(builder.getGitDir()==null)return null;
        try(Repository repo=builder.build()) {
            return repo.getRefDatabase().getRefsByPrefix("refs/tags/").stream().map(r->r.getName().substring("refs/tags/".length()).replaceFirst("^v",""))
                .filter(s->s.matches("\\d+(?:\\.\\d+){1,3}"))
                .max(this::compareVersions).orElse(null);
        }
    }
    private int compareVersions(String a,String b) {
        String[] x=a.split("\\."),y=b.split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++) {
            var v=new java.math.BigInteger(i<x.length?x[i]:"0");var w=new java.math.BigInteger(i<y.length?y[i]:"0");int c=v.compareTo(w);if(c!=0)return c;
        }
        return a.compareTo(b);
    }
    public void check(Path root,Config config,Set<Finding> out,DocReader.Document doc,DocReader.Fragment fragment,List<Coordinates> coordinates) {
        String text=fragment.text();
        for(Coordinates c:coordinates) {
            if(c.version()==null)continue;
            Matcher dependency=Pattern.compile("(?s)<(?:dependency|plugin)>.*?</(?:dependency|plugin)>").matcher(text);
            while(dependency.find()) {
                String xml=dependency.group();
                if(!xml.contains("<groupId>"+c.groupId()+"</groupId>") || !xml.contains("<artifactId>"+c.artifactId()+"</artifactId>"))continue;
                Matcher version=Pattern.compile("<version>\\s*([^<]+?)\\s*</version>").matcher(xml);
                if(version.find())report(root,config,out,doc,fragment,dependency.start()+version.start(1),c,version.group(1));
            }
            Matcher gradle=Pattern.compile("[\"']"+Pattern.quote(c.groupId()+":"+c.artifactId()+":")+"([^\"']+)[\"']").matcher(text);
            while(gradle.find())report(root,config,out,doc,fragment,gradle.start(1),c,gradle.group(1));
        }
    }
    private void report(Path root,Config c,Set<Finding> out,DocReader.Document doc,DocReader.Fragment f,int offset,Coordinates project,String version) {
        if(version.contains("$") || version.equals(project.version()))return;
        Analyzer.add(root,c,out,doc,f,offset,Check.JD006,project.groupId()+":"+project.artifactId()+":"+version,
            "Snippet shows version "+version+"; latest local release is "+project.version(),"Use version "+project.version());
    }
}
