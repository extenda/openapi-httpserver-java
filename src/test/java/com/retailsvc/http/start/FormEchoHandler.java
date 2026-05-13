package com.retailsvc.http.start;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Echoes the parsed form body to the response as Map#toString. */
public class FormEchoHandler implements RequestHandler {

  @Override
  public void handle(Request request) throws IOException {
    Object parsed = request.parsed();
    byte[] body = String.valueOf(parsed).getBytes(StandardCharsets.UTF_8);
    request.respond(200).contentType("text/plain; charset=utf-8").bytes(body);
  }
}
