package demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ReportService {
    private ReportService() {}

    public static String title(String json) throws JsonProcessingException {
        return new ObjectMapper().readTree(json).path("title").asText();
    }

    public static String outputDirectory() {
        return System.getProperty("report.output.dir", "reports");
    }
}
