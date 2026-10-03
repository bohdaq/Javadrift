package io.github.bohdaq.javadrift.core;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
public final class Baseline {
    public record Data(int version,List<String> fingerprints) {}
    public record Audit(List<String> retained,List<String> unused,List<String> unaccepted) {}
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
        Set<String> accepted=new HashSet<>(read(file).fingerprints());
        return findings.stream().filter(f->!accepted.contains(fingerprint(f))).toList();
    }
    private static Data read(Path file) throws IOException {
        Data data=JSON.readValue(file.toFile(),Data.class);
        if(data==null || data.version()!=1 || data.fingerprints()==null || data.fingerprints().stream().anyMatch(s->s==null||!s.matches("[a-f0-9]{64}")))
            throw new IOException("Invalid baseline file: "+file);
        return data;
    }
    public static Audit audit(Path file,List<Finding> findings) throws IOException {
        Set<String> accepted=new TreeSet<>(read(file).fingerprints());
        Set<String> current=new TreeSet<>();findings.forEach(f->current.add(fingerprint(f)));
        return new Audit(accepted.stream().filter(current::contains).toList(),
            accepted.stream().filter(s->!current.contains(s)).toList(),
            current.stream().filter(s->!accepted.contains(s)).toList());
    }
    public static void prune(Path file,Audit audit) throws IOException {
        if(!audit.unused().isEmpty())writeData(file,new Data(1,audit.retained()));
    }
    public static void write(Path file,List<Finding> findings) throws IOException {
        writeData(file,new Data(1,findings.stream().map(Baseline::fingerprint).distinct().sorted().toList()));
    }
    private static void writeData(Path file,Data data) throws IOException {
        Path target=file.toAbsolutePath(),parent=target.getParent();Files.createDirectories(parent);
        Path temporary=Files.createTempFile(parent,".javadrift-baseline-",".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(),data);
            try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e) {Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
}
