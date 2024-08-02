# Spring Boot OpenTelemetry context initialization issue

This project demonstrates a problem with how Spring Boot initializes OpenTelemetries `ContextStorage` wrappers in 
combination with early usage of the OpenTelemetry `Context` (e.g. via its `Span.current()` method).

The problem is explained in the test class: `src/test/java/com/example/demo/TraceEventsTest.java`