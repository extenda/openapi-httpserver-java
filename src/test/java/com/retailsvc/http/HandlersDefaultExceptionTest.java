package com.retailsvc.http;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.retailsvc.http.spec.HttpMethod;
import com.retailsvc.http.validate.ValidationError;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class HandlersDefaultExceptionTest {

  private static final TypeMapper JSON = new GsonTypeMapper();

  private Logger handlersLogger;
  private Level originalLevel;
  private ListAppender<ILoggingEvent> appender;

  @BeforeEach
  void attachAppender() {
    handlersLogger = (Logger) LoggerFactory.getLogger(Handlers.class);
    originalLevel = handlersLogger.getLevel();
    handlersLogger.setLevel(Level.DEBUG);
    appender = new ListAppender<>();
    appender.start();
    handlersLogger.addAppender(appender);
  }

  @AfterEach
  void detachAppender() {
    handlersLogger.detachAppender(appender);
    handlersLogger.setLevel(originalLevel);
  }

  @Test
  void validationExceptionRendersProblemJson() {
    Response resp =
        Handlers.defaultExceptionHandler()
            .handle(
                new ValidationException(
                    new ValidationError("/x", "type", "expected string", null)));

    assertThat(resp.status()).isEqualTo(400);
    assertThat(resp.contentType()).isEqualTo("application/problem+json");
    byte[] bytes = (byte[]) resp.body();
    String json = new String(bytes, StandardCharsets.UTF_8);
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed = (Map<String, Object>) JSON.readFrom(bytes, "application/json");
    assertThat(((Number) parsed.get("status")).intValue()).isEqualTo(400);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors = (List<Map<String, Object>>) parsed.get("errors");
    assertThat(errors)
        .singleElement()
        .satisfies(
            entry ->
                assertThat(entry).containsEntry("pointer", "#/x").containsEntry("keyword", "type"));
    assertThat(json).contains("expected string");
  }

  @Test
  void badRequestExceptionRendersProblemJsonWithCustomStatus() {
    Response resp =
        Handlers.defaultExceptionHandler()
            .handle(new BadRequestException(422, "email taken", "/email", "unique"));

    assertThat(resp.status()).isEqualTo(422);
    assertThat(resp.contentType()).isEqualTo("application/problem+json");
    @SuppressWarnings("unchecked")
    Map<String, Object> parsed =
        (Map<String, Object>) JSON.readFrom((byte[]) resp.body(), "application/json");
    assertThat(((Number) parsed.get("status")).intValue()).isEqualTo(422);
    assertThat(parsed)
        .containsEntry("title", "Unprocessable Content")
        .containsEntry("detail", "email taken");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors = (List<Map<String, Object>>) parsed.get("errors");
    assertThat(errors)
        .singleElement()
        .satisfies(
            entry ->
                assertThat(entry)
                    .containsEntry("pointer", "#/email")
                    .containsEntry("keyword", "unique"));
  }

  @Test
  void notFoundReturns404() {
    Response resp = Handlers.defaultExceptionHandler().handle(new NotFoundException("GET /x"));

    assertThat(resp.status()).isEqualTo(404);
    assertThat(resp.body()).isNull();
  }

  @Test
  void methodNotAllowedReturns405WithAllowHeader() {
    Response resp =
        Handlers.defaultExceptionHandler()
            .handle(new MethodNotAllowedException(Set.of(HttpMethod.GET, HttpMethod.POST)));

    assertThat(resp.status()).isEqualTo(405);
    assertThat(resp.headers()).containsKey("Allow");
    assertThat(resp.headers().get("Allow")).contains("GET").contains("POST");
  }

  @Test
  void badRequestCauseLoggedAtDebug() {
    Throwable cause = new IllegalStateException("root");

    Handlers.defaultExceptionHandler().handle(new BadRequestException("bad", cause));

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getThrowableProxy().getClassName())
                  .isEqualTo(IllegalStateException.class.getName());
            });
  }

  @Test
  void badRequestWithoutCauseDoesNotLog() {
    Handlers.defaultExceptionHandler().handle(new BadRequestException("bad"));

    assertThat(appender.list).isEmpty();
  }

  @Test
  void notFoundCauseLoggedAtDebug() {
    Throwable cause = new IllegalStateException("root");

    Handlers.defaultExceptionHandler().handle(new NotFoundException("missing", cause));

    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getThrowableProxy().getClassName())
                  .isEqualTo(IllegalStateException.class.getName());
            });
  }

  @Test
  void notFoundWithoutCauseDoesNotLog() {
    Handlers.defaultExceptionHandler().handle(new NotFoundException("missing"));

    assertThat(appender.list).isEmpty();
  }

  @Test
  void unknownExceptionReturns500() {
    Response resp = Handlers.defaultExceptionHandler().handle(new RuntimeException("kaboom"));

    assertThat(resp.status()).isEqualTo(500);
    assertThat(resp.body()).isNull();
  }
}
