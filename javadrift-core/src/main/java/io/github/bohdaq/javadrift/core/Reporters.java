package io.github.bohdaq.javadrift.core;
public final class Reporters {
    private Reporters() {}
    public static String text(Analyzer.Result result) {
        StringBuilder out=new StringBuilder();
        for(Finding f:result.findings()) {
            out.append(f.file()).append(':').append(f.line()).append(':').append(f.column()).append("  ")
                .append(f.severity().name().toLowerCase(java.util.Locale.ROOT)).append("  ").append(f.checkId()).append("  ").append(f.message()).append('\n');
            if(f.suggestion()!=null)out.append("    ").append(f.suggestion()).append('\n');
        }
        out.append("Checked ").append(result.documents()).append(" documents, ").append(result.types()).append(" types; ").append(result.findings().size()).append(" findings.\n");
        return out.toString();
    }
}
