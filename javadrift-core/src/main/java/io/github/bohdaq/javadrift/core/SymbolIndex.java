package io.github.bohdaq.javadrift.core;
import java.util.*;
public final class SymbolIndex {
    public record Member(String name, List<String> parameters, boolean method, boolean varargs, boolean forRemoval) {
        public String signature() {return name+(method?"("+String.join(",",parameters)+")":"");}
    }
    public static final class Type {
        public final String name; public final List<Member> members=new ArrayList<>();
        public final List<String> parents=new ArrayList<>();
        public boolean generated, compiled, forRemoval, kotlinSource; public boolean project=true;
        public Type(String name) {this.name=name;}
        public String simpleName() {return name.substring(name.lastIndexOf('.')+1);}
    }
    public final Set<String> documentationTypes=new HashSet<>();
    public final Map<String,Type> types=new TreeMap<>();
    public Optional<Type> resolve(String name) {
        Type exact=types.get(name);if(exact!=null)return Optional.of(exact);
        List<Type> found=types.values().stream().filter(t->t.simpleName().equals(name)).toList();
        return found.size()==1?Optional.of(found.get(0)):Optional.empty();
    }
    public List<Member> members(Type type) {return members(type,new HashSet<>());}
    private List<Member> members(Type type, Set<String> seen) {
        if(!seen.add(type.name))return List.of();
        List<Member> result=new ArrayList<>(type.members);
        for(String parent:type.parents) resolve(parent).ifPresent(t->result.addAll(members(t,seen)));
        return result;
    }
    public boolean unresolvedParents(Type type) {return unresolvedParents(type,new HashSet<>());}
    private boolean unresolvedParents(Type type,Set<String> seen) {
        if(!seen.add(type.name))return false;
        for(String p:type.parents) {
            if(p.equals("Object")||p.equals("java.lang.Object"))continue;
            Optional<Type> parent=resolve(p);
            if(parent.isEmpty() || unresolvedParents(parent.get(),seen))return true;
        }
        return false;
    }
    public Set<String> packages() {
        Set<String> p=new TreeSet<>();
        for(String n:types.values().stream().filter(t->t.project).map(t->t.name).toList()) {int i=n.lastIndexOf('.');if(i>0)p.add(n.substring(0,i));}
        return p;
    }
}
