package io.github.bohdaq.javadrift.core;
public final class Reporters {
    private Reporters() {}
    public static String render(Analyzer.Result result,String format) throws java.io.IOException {
        return switch(format) {
            case "text" -> text(result);
            case "json" -> new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result)+"\n";
            case "github" -> github(result);
            default -> throw new IllegalArgumentException("Unknown output format: "+format);
        };
    }
    private static String data(String value) {return value.replace("%","%25").replace("\r","%0D").replace("\n","%0A");}
    private static String property(String value) {return data(value).replace(":","%3A").replace(",","%2C");}
    public static String github(Analyzer.Result result) {
        StringBuilder out=new StringBuilder();
        for(Finding f:result.findings())out.append("::").append(f.severity()==Severity.ERROR?"error":"warning")
            .append(" file=").append(property(f.file())).append(",line=").append(f.line()).append(",col=").append(f.column())
            .append(",title=").append(f.checkId()).append("::").append(data(f.message()+(f.suggestion()==null?"":" "+f.suggestion()))).append('\n');
        return out.toString();
    }
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
