package com.retailsvc.http.start;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Echoes the parsed text/plain body back to the response. */
public class TextEchoHandler implements RequestHandler {

  @Override
  public void handle(Request request) throws IOException {
    String parsed = (String) request.parsed();
    byte[] body = parsed.getBytes(StandardCharsets.UTF_8);
    request.respond(200).contentType("text/plain; charset=utf-8").bytes(body);
  }
}
