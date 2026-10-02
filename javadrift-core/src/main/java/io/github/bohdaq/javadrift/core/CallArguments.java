package io.github.bohdaq.javadrift.core;
import java.util.*;
public final class CallArguments {
    private CallArguments() {}
    public record Call(List<String> arguments,int end) {}
    public static Optional<Call> parse(String text,int start) {
        if(start>=text.length() || text.charAt(start)!='(')return Optional.empty();
        List<String> args=new ArrayList<>();int depth=1,part=start+1;char quote=0;boolean escape=false;
        for(int i=start+1;i<text.length();i++) {
            char c=text.charAt(i);
            if(quote!=0) {if(escape)escape=false;else if(c==92)escape=true;else if(c==quote)quote=0;continue;}
            if(c==34 || c==39) {quote=c;continue;}
            if(c=='('||c=='['||c=='{')depth++;
            if(c==')'||c==']'||c=='}') {
                depth--;
                if(depth==0) {
                    String last=text.substring(part,i).trim();if(!last.isEmpty())args.add(last);
                    var parsed=new com.github.javaparser.JavaParser().parseExpression("javadrift("+text.substring(start+1,i)+")");
                    if(parsed.isSuccessful() && parsed.getResult().orElse(null) instanceof com.github.javaparser.ast.expr.MethodCallExpr expression)
                        args=new ArrayList<>(expression.getArguments().stream().map(Object::toString).toList());
                    return Optional.of(new Call(List.copyOf(args),i+1));
                }
            }
            if(c==',' && depth==1) {args.add(text.substring(part,i).trim());part=i+1;}
        }
        return Optional.empty();
    }
    public static boolean matches(SymbolIndex.Member member,List<String> args,SymbolIndex index) {
        int count=member.parameters().size();
        if(!member.method() || (!member.varargs() && args.size()!=count) || (member.varargs() && args.size()<count-1))return false;
        for(int i=0;i<args.size();i++) {
            String param=member.parameters().get(Math.min(i,count-1));
            if(member.varargs() && i>=count-1)param=param.replaceFirst("\\[\\]$","");
            String inferred=infer(args.get(i));
            if(inferred!=null && !compatible(inferred,param,index))return false;
        }
        return true;
    }
    private static String infer(String arg) {
        if(arg.matches("\"(?:[^\"\\\\]|\\\\.)*\""))return "String";
        if(arg.matches("'(?:[^'\\\\]|\\\\.)'"))return "char";
        if(arg.equals("true")||arg.equals("false"))return "boolean";
        if(arg.equals("null"))return "null";
        if(arg.matches("[-+]?\\d+[lL]"))return "long";
        if(arg.matches("[-+]?\\d+"))return "int";
        if(arg.matches("[-+]?\\d+(?:\\.\\d*)?[fF]"))return "float";
        if(arg.matches("[-+]?\\d+\\.\\d*(?:[dD])?"))return "double";
        var created=java.util.regex.Pattern.compile("new\\s+([\\w.]+)\\s*\\(").matcher(arg);
        if(created.find())return created.group(1);
        if(arg.matches("(?:[a-z]\\w*\\.)*[A-Z]\\w*|boolean|byte|short|int|long|float|double|char"))return arg;
        return null;
    }
    private static boolean compatible(String inferred,String parameter,SymbolIndex index) {
        String p=parameter.replaceAll("<.*>","").trim();String simple=p.substring(p.lastIndexOf('.')+1);
        String actual=inferred.substring(inferred.lastIndexOf('.')+1);
        if(actual.equals("null"))return !Set.of("boolean","byte","short","int","long","float","double","char").contains(simple);
        if(actual.equals(simple) || simple.equals("Object") || simple.matches("[A-Z]"))return true;
        if(actual.equals("String") && Set.of("CharSequence","Serializable","Comparable").contains(simple))return true;
        Map<String,String> boxed=Map.of("int","Integer","long","Long","float","Float","double","Double","char","Character","boolean","Boolean");
        if(simple.equals(boxed.get(actual)))return true;
        if(actual.equals("int") && Set.of("long","float","double","Number").contains(simple))return true;
        if(actual.equals("long") && Set.of("float","double","Number").contains(simple))return true;
        if(actual.equals("float") && Set.of("double","Number").contains(simple))return true;
        // Unknown reference hierarchy is not evidence of a type mismatch.
        Optional<SymbolIndex.Type> type=index.resolve(inferred);
        if(type.isEmpty())return !Set.of("boolean","byte","short","int","long","float","double","char","String").contains(simple);
        if(index.unresolvedParents(type.get()))return true;
        return inherits(index,type.get(),p,new HashSet<>());
    }
    private static boolean inherits(SymbolIndex index,SymbolIndex.Type type,String parent,Set<String> seen) {
        if(!seen.add(type.name))return false;
        if(type.name.equals(parent)||type.simpleName().equals(parent))return true;
        for(String p:type.parents)if(index.resolve(p).map(t->inherits(index,t,parent,seen)).orElse(false))return true;
        return false;
    }
}
