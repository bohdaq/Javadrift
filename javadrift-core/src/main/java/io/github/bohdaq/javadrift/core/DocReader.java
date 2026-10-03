package io.github.bohdaq.javadrift.core;
import org.commonmark.node.*;
import org.commonmark.parser.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
public final class DocReader {
    public enum Kind { CODE, BLOCK, LINK }
    public record Fragment(String text,int line,int column,Kind kind,String language) {
        public int lineAt(int offset) {return line+(int)text.substring(0,offset).chars().filter(c->c=='\n').count();}
        public int columnAt(int offset) {int last=text.lastIndexOf('\n',Math.max(0,offset-1));return last<0?column+offset:offset-last;}
    }
    public record Document(Path path, String text, List<Fragment> fragments) {}
    private final Parser parser=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();
    public Document read(Path path) throws IOException {
        String text=Files.readString(path);
        return new Document(path,text,path.toString().endsWith(".adoc")?asciidoc(text):markdown(text));
    }
    private List<Fragment> markdown(String text) {
        List<Fragment> result=new ArrayList<>();
        parser.parse(text).accept(new AbstractVisitor() {
            private SourceSpan span(Node n) {return n.getSourceSpans().isEmpty()?SourceSpan.of(0,0,0,0):n.getSourceSpans().get(0);}
            public void visit(Code code) {
                SourceSpan s=span(code);int start=s.getInputIndex();int delimiters=0;
                while(start+delimiters<text.length() && text.charAt(start+delimiters)=='`')delimiters++;
                result.add(new Fragment(code.getLiteral(),s.getLineIndex()+1,s.getColumnIndex()+delimiters+1,Kind.CODE,code.getParent() instanceof Link link?linkedOwner(link.getDestination()):""));
            }
            public void visit(FencedCodeBlock code) {
                SourceSpan s=span(code);
                result.add(new Fragment(code.getLiteral(),s.getLineIndex()+2,s.getColumnIndex()+1,Kind.BLOCK,code.getInfo().split("\\s+")[0]));
            }
            public void visit(IndentedCodeBlock code) {
                SourceSpan s=span(code);result.add(new Fragment(code.getLiteral(),s.getLineIndex()+1,s.getColumnIndex()+1,Kind.BLOCK,""));
            }
            public void visit(Link link) {addLink(link,link.getDestination());visitChildren(link);}
            public void visit(Image link) {addLink(link,link.getDestination());visitChildren(link);}
            private void addLink(Node n,String dest) {
                SourceSpan s=span(n);int lineEnd=text.indexOf('\n',s.getInputIndex());if(lineEnd<0)lineEnd=text.length();
                int pos=text.substring(s.getInputIndex(),lineEnd).indexOf(dest);
                result.add(new Fragment(dest,s.getLineIndex()+1,s.getColumnIndex()+Math.max(0,pos)+1,Kind.LINK,""));
            }
        });
        return result;
    }
    private static String linkedOwner(String destination) {
        if(!destination.startsWith("https://") && !destination.startsWith("http://"))return "";
        Matcher owner=Pattern.compile("/((?:[a-z][\\w]*?/)+[A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*)\\.html(?:[#?].*)?$").matcher(destination);
        return owner.find()?"owner:"+owner.group(1).replace('/','.'):"";
    }
    private static String labelOwner(String line,int offset) {
        Matcher link=Pattern.compile("(?:https?://[^\\s\\[]+|link:[^\\s\\[]+)\\[").matcher(line);
        while(link.find()) {
            int end=line.indexOf(']',link.end());
            if(link.end()<=offset && end>=offset)return linkedOwner(link.group().replaceFirst("^link:","").replaceFirst("\\[$",""));
        }
        return "";
    }
    static boolean apiFragment(Fragment fragment) {
        return fragment.kind()!=Kind.LINK && !(fragment.kind()==Kind.BLOCK && Set.of("plantuml","mermaid","dot","graphviz").contains(fragment.language().toLowerCase(Locale.ROOT)));
    }
    private List<Fragment> asciidoc(String text) {
        List<Fragment> result=new ArrayList<>();String[] lines=text.split("\n",-1);
        String language="";boolean fenced=false;String fence="";StringBuilder block=new StringBuilder();int start=0;
        Pattern inline=Pattern.compile("`([^`]+)`|(?:link|xref):([^\\s\\[]+)\\[");
        for(int i=0;i<lines.length;i++) {
            String line=lines[i];String trim=line.trim();
            if(!fenced && trim.equals("[plantuml]")) {language="plantuml";continue;}
            if(!fenced && trim.startsWith("[source")) {Matcher m=Pattern.compile("\\[source,([^,\\]]+)").matcher(trim);language=m.find()?m.group(1):"";continue;}
            if(trim.equals("----")||trim.equals("....")) {
                if(fenced && trim.equals(fence)) {result.add(new Fragment(block.toString(),start+1,1,Kind.BLOCK,language));block.setLength(0);fenced=false;language="";}
                else if(!fenced) {fenced=true;fence=trim;start=i+1;}
                continue;
            }
            if(fenced) {block.append(line).append('\n');continue;}
            if(trim.startsWith("//"))continue;
            Matcher m=inline.matcher(line);while(m.find())result.add(new Fragment(m.group(1)!=null?m.group(1):m.group(2),i+1,m.start()+1+(m.group(1)!=null?1:5),m.group(1)!=null?Kind.CODE:Kind.LINK,m.group(1)==null && m.group().startsWith("xref:")?"xref":m.group(1)!=null?labelOwner(line,m.start()):""));
        }
        if(fenced)result.add(new Fragment(block.toString(),start+1,1,Kind.BLOCK,language));
        return result;
    }
}
