package io.github.bohdaq.javadrift.core;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
public final class Baseline {
    public record Data(int version,List<String> fingerprints) {}
    private static final ObjectMapper JSON=new ObjectMapper();
    private Baseline() {}
    public static String fingerprint(Finding f) {
        try {
            byte[] data=(f.file()+"\0"+f.checkId()+"\0"+f.reference()).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    public static List<Finding> filter(Path file,List<Finding> findings) throws IOException {
        if(!Files.exists(file))return findings;
        Data data=JSON.readValue(file.toFile(),Data.class);
        if(data==null || data.version()!=1 || data.fingerprints()==null || data.fingerprints().stream().anyMatch(s->s==null||!s.matches("[a-f0-9]{64}")))
            throw new IOException("Invalid baseline file: "+file);
        Set<String> accepted=new HashSet<>(data.fingerprints());
        return findings.stream().filter(f->!accepted.contains(fingerprint(f))).toList();
    }
    public static void write(Path file,List<Finding> findings) throws IOException {
        Path parent=file.toAbsolutePath().getParent();Files.createDirectories(parent);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(),new Data(1,findings.stream().map(Baseline::fingerprint).distinct().sorted().toList()));
    }
}
