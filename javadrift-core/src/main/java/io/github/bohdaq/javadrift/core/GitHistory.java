package io.github.bohdaq.javadrift.core;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.revwalk.*;
import org.eclipse.jgit.treewalk.TreeWalk;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
public final class GitHistory {
    public record Removed(String token,Pattern pattern,String suggestion) {}
    public static String state(Path root) throws IOException {
        FileRepositoryBuilder builder=new FileRepositoryBuilder().findGitDir(root.toFile());
        if(builder.getGitDir()==null)return "no-git";
        try(Repository repo=builder.build()) {
            List<String> values=new ArrayList<>();
            ObjectId head=repo.resolve("HEAD");values.add(head==null?"unborn":head.name());
            repo.getRefDatabase().getRefsByPrefix("refs/").forEach(r->{if(r.getObjectId()!=null)values.add(r.getName()+":"+r.getObjectId().name());});
            Collections.sort(values);return String.join("\n",values);
        }
    }
    public List<Removed> removed(Path root,String since) throws IOException {
        FileRepositoryBuilder builder=new FileRepositoryBuilder().findGitDir(root.toFile());
        if(builder.getGitDir()==null)throw new IOException("Diff mode requires a Git repository");
        try(Repository repo=builder.build()) {
            SymbolIndex before=index(repo,since),after=index(repo,"HEAD");
            List<Removed> result=new ArrayList<>();
            for(SymbolIndex.Type old:before.types.values()) {
                SymbolIndex.Type current=after.types.get(old.name);
                if(current==null) {
                    result.add(new Removed(old.name,exact(old.name),null));
                    if(before.resolve(old.simpleName()).isPresent() && after.resolve(old.simpleName()).isEmpty())
                        result.add(new Removed(old.simpleName(),exact(old.simpleName()),null));
                    continue;
                }
                Set<String> visited=new HashSet<>();
                for(SymbolIndex.Member m:before.members(old)) {
                    if(!visited.add(m.name()) || after.members(current).stream().anyMatch(n->n.name().equals(m.name())))continue;
                    String suggestion=after.members(current).stream()
                        .filter(n->n.method()==m.method() && n.parameters().equals(m.parameters()))
                        .filter(n->before.members(old).stream().noneMatch(o->o.name().equals(n.name())))
                        .sorted(Comparator.comparing(SymbolIndex.Member::signature)).map(n->"Did you mean `"+n.signature()+"`? Added since "+since+".").findFirst().orElse(null);
                    for(String name:List.of(old.name,old.simpleName())) {
                        if(!name.equals(old.name) && before.resolve(name).isEmpty())continue;
                        String regex="(?<![\\w.$])"+Pattern.quote(name)+"(?:#|::|\\.)"+Pattern.quote(m.name())+"(?![\\w$])(?:\\([^()]*\\))?";
                        result.add(new Removed(name+"#"+m.name(),Pattern.compile(regex),suggestion));
                    }
                    if(m.method() && after.types.values().stream().flatMap(t->after.members(t).stream()).noneMatch(n->n.name().equals(m.name())))
                        result.add(new Removed(m.name(),Pattern.compile("(?<![\\w$#])"+Pattern.quote(m.name())+"(?=\\s*\\()"),suggestion));
                }
            }
            return result;
        }
    }
    private Pattern exact(String value) {return Pattern.compile("(?<![\\w.$])"+Pattern.quote(value)+"(?![\\w$])");}
    private SymbolIndex index(Repository repo,String ref) throws IOException {
        ObjectId id=repo.resolve(ref+"^{commit}");if(id==null)throw new IOException("Unknown Git ref: "+ref+" (fetch history first)");
        SymbolIndex index=new SymbolIndex();SourceIndexer parser=new SourceIndexer();
        try(RevWalk walk=new RevWalk(repo);TreeWalk tree=new TreeWalk(repo)) {
            tree.addTree(walk.parseCommit(id).getTree());tree.setRecursive(true);
            while(tree.next()) {
                String path=tree.getPathString();if(!SourceIndexer.productionJava(path))continue;
                if(!tree.getFileMode(0).equals(FileMode.REGULAR_FILE) && !tree.getFileMode(0).equals(FileMode.EXECUTABLE_FILE))continue;
                parser.add(index,new String(repo.open(tree.getObjectId(0)).getBytes(),StandardCharsets.UTF_8),ref+":"+path);
            }
        }
        return index;
    }
}
