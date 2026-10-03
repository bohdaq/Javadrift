# JSON reports

The project and Jackson dependency classes must be available when compiling these examples.

## Statements with configured imports

```java
String title = ReportService.title("{\"title\":\"Daily\"}");
```

## Jackson tree API

```java
ObjectMapper mapper = new ObjectMapper();
String title = mapper.readTree("{\"title\":\"Daily\"}").path("title").asText();
```

## Class members

```java
public String title() throws Exception {
    return ReportService.title("{\"title\":\"Daily\"}");
}
```

## Complete compilation unit

```java
package demo.docs;
import demo.ReportService;
public class CompleteExample {
    public static String title() throws Exception {
        return ReportService.title("{\"title\":\"Daily\"}");
    }
}
```

## Explicit partial example

```java
// javadrift:skip
Install your report adapter here
```

## Compilation does not execute the example

```java
public class CompileOnlyExample {
    static {
        if (true) throw new AssertionError("Documentation examples must not execute");
    }
}
```
