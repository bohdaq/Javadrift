package io.github.bohdaq.javadrift.core;
import java.util.regex.Pattern;
public final class Glob {
    private Glob() {}
    public static boolean matches(String pattern, String value) {
        StringBuilder r=new StringBuilder("^");
        for(int i=0;i<pattern.length();i++) {
            char c=pattern.charAt(i);
            if(c=='*') {
                if(i+1<pattern.length() && pattern.charAt(i+1)=='*') {
                    i++;
                    if(i+1<pattern.length() && pattern.charAt(i+1)=='/') {i++;r.append("(?:.*/)?");}
                    else r.append(".*");
                } else r.append("[^/]*");
            } else if(c=='?') r.append("[^/]");
            else r.append(Pattern.quote(String.valueOf(c)));
        }
        return value.matches(r.append('$').toString());
    }
}
