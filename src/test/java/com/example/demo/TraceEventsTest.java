package com.example.demo;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.EventListener;
import io.micrometer.tracing.otel.bridge.EventPublishingContextWrapper;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.context.ContextStorage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * This test class shows that if the OpenTelemetry tracing (e.g. retrieving the current span) is used
 * before Spring Boot was able to fully initialize the system, that the eventing system is broken. This results in the
 * logging {@link MDC} not being filled with the trace ID, span ID and baggage.
 * <p>
 * This test class contains two nested test classes which execute the same test method. This test method checks if events
 * are sent to event listeners and if the logging MDC is filled with tracing information. The difference between the
 * two nested classes is that in the {@link TraceContextCreatedEarly} class an {@link ApplicationContextInitializer}
 * is used to simulate that an OpenTelemetry Span was accessed before the Spring Boot application is fully started.
 * <p>
 * In real life this happens with the Google Logging library, as of version 3.19.0.
 * As of that version this library now supports OpenTelemetry, meaning it tries to add tracing information,
 * retrieved from OpenTelemetry, to every log event.
 * This code can be seen here:
 * https://github.com/googleapis/java-logging/blob/884bbb2f2af3eec25b1c362c6a058074c57731b5/google-cloud-logging/src/main/java/com/google/cloud/logging/LoggingImpl.java#L842
 * Because the logging library is initialized and log events are created before the application is fully started,
 * this causes issues with spring Boot initialization of OpenTelemetry.
 * <p>
 * In {@link org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryAutoConfiguration#otelCurrentTraceContext(OtelTracer.EventPublisher)}
 * it injects the {@link EventPublishingContextWrapper} into OpenTelemetry`s {@link ContextStorage}.
 * However, if the ContextStorage has already been accessed earlier to get the context of a trace, then it is already initialized,
 * and it doesn't allow any wrappers to be added anymore. So, the EventPublishingContextWrapper instance is simply ignored.
 * Without the EventPublishingContextWrapper, Micrometer won't receive any events, meaning logging MDC is never provided
 * with tracing entries.
 * <p>
 * IMPORTANT: Because OpenTelemetry relies on static state, that can't be reset, the two nested test classes need
 * to be executed in separate JVM runs.
 * Do this directly via the IDE, or via Maven with the following commands:
 * <p>
 * Succeeds: ./mvnw test -Dtest=TraceEventsTest\$TraceContextNotCreatedEarly
 * Fails   : ./mvnw test -Dtest=TraceEventsTest\$TraceContextCreatedEarly
 */
class TraceEventsTest {

	// This test should succeed (only run this test by itself!)
	@Nested
	class TraceContextNotCreatedEarly extends AbstractTest {
	}

	// This test should fail
	@Nested
	@ContextConfiguration(initializers = {com.example.demo.TraceEventsTest.EarlyUseOfTracingSimulator.class})
	class TraceContextCreatedEarly extends AbstractTest {
	}

	@SpringBootTest(classes = TestApplication.class)
	@AutoConfigureObservability
	static abstract class AbstractTest {

		@Autowired
		Tracer tracer;

		@Autowired
		StoringEventListener storingEventListener;

		@Test
		void tracing_event_listener_should_get_events_when_tracing_occurs() {

			Span newSpan = tracer.nextSpan().name("test");

			try (Tracer.SpanInScope ws = this.tracer.withSpan(newSpan.start())) {

				assert !storingEventListener.events.isEmpty() : "Expected events in the event listener";
				assert MDC.get("traceId") != null : "Expected a trace ID within the logging MDC";
				assert MDC.get("spanId") != null : "Expected a trace ID within the logging MDC";

			} finally {
				newSpan.end();
			}

		}

	}

	@SpringBootApplication
	static class TestApplication {
		@Bean
		StoringEventListener traceEventListener() {
			return new StoringEventListener();
		}
	}

	// This class only exists to simulate code that uses a OpenTelemetry Span very early in the application lifecycle,
	// before the beans of the OpenTelemetryAutoConfiguration are loaded.
	static class EarlyUseOfTracingSimulator implements ApplicationContextInitializer<ConfigurableApplicationContext> {
		@Override
		public void initialize(ConfigurableApplicationContext applicationContext) {
			io.opentelemetry.api.trace.Span.current();
		}
	}

	static class StoringEventListener implements EventListener {

		final List<Object> events = new ArrayList<>();

		@Override
		public void onEvent(Object event) {
			events.add(event);
		}
	}

}
