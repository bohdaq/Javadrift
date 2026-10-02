package io.github.bohdaq.javadrift.core;
public final class Reporters {
    private Reporters() {}
    public static String render(Analyzer.Result result,String format) throws java.io.IOException {
        return switch(format) {
            case "text" -> text(result);
            case "json" -> new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result)+"\n";
            case "github" -> github(result);
            case "sarif" -> sarif(result);
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
    public static String sarif(Analyzer.Result result) throws java.io.IOException {
        java.util.List<java.util.Map<String,Object>> rules=new java.util.ArrayList<>(),findings=new java.util.ArrayList<>();
        for(Check c:Check.values())rules.add(java.util.Map.of("id",c.name(),"name",c.key,"shortDescription",java.util.Map.of("text",c.explanation),
            "defaultConfiguration",java.util.Map.of("level",c.defaultSeverity==Severity.ERROR?"error":c.defaultSeverity==Severity.WARNING?"warning":"none")));
        for(Finding f:result.findings()) {
            String uri;
            try {uri=new java.net.URI(null,null,f.file(),null).toASCIIString();}catch(java.net.URISyntaxException e){throw new java.io.IOException(e);}
            findings.add(java.util.Map.of("ruleId",f.checkId(),"ruleIndex",Check.from(f.checkId()).ordinal(),
                "level",f.severity()==Severity.ERROR?"error":"warning",
                "message",java.util.Map.of("text",f.message()+(f.suggestion()==null?"":" "+f.suggestion())),
                "partialFingerprints",java.util.Map.of("javadrift/v1",Baseline.fingerprint(f)),
                "locations",java.util.List.of(java.util.Map.of("physicalLocation",java.util.Map.of(
                    "artifactLocation",java.util.Map.of("uri",uri,"uriBaseId","%SRCROOT%"),
                    "region",java.util.Map.of("startLine",f.line(),"startColumn",f.column()))))));
        }
        java.util.Map<String,Object> document=java.util.Map.of("version","2.1.0","$schema","https://json.schemastore.org/sarif-2.1.0.json",
            "runs",java.util.List.of(java.util.Map.of("tool",java.util.Map.of("driver",java.util.Map.of("name","Javadrift","informationUri","https://github.com/bohdaq/Javadrift","rules",rules)),"results",findings)));
        return new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true)
            .writerWithDefaultPrettyPrinter().writeValueAsString(document)+"\n";
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
