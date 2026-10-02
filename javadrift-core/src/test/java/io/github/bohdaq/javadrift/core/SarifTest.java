package io.github.bohdaq.javadrift.core;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class SarifTest {
    @Test void sarifContainsRulesLocationsAndStableFingerprints() throws Exception {
        Finding f=new Finding("docs/a b.md",42,15,"JD002",Severity.ERROR,"Order#submit","missing",null);
        String output=Reporters.render(new Analyzer.Result(List.of(f),1,1),"sarif");
        var tree=new ObjectMapper().readTree(output);assertEquals("2.1.0",tree.path("version").asText());
        var run=tree.path("runs").get(0);assertEquals(9,run.path("tool").path("driver").path("rules").size());
        var finding=run.path("results").get(0);assertEquals("JD002",finding.path("ruleId").asText());
        var location=finding.path("locations").get(0).path("physicalLocation");
        assertEquals("docs/a%20b.md",location.path("artifactLocation").path("uri").asText());
        assertEquals(42,location.path("region").path("startLine").asInt());
        assertEquals(Baseline.fingerprint(f),finding.path("partialFingerprints").path("javadrift/v1").asText());
        assertEquals(output,Reporters.render(new Analyzer.Result(List.of(f),1,1),"sarif"));
    }
}
