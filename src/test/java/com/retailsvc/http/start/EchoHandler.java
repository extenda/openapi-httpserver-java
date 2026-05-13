package com.retailsvc.http.start;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Echoes back the request body as a response body */
public class EchoHandler implements RequestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Override
  public void handle(Request request) throws IOException {
    byte[] bytes = request.bytes();

    if (bytes.length == 0) {
      LOG.debug("No bytes available to read from the request body");
    } else {
      LOG.debug("Read {} bytes from the request body", bytes.length);
    }

    String requestBody = new String(bytes);
    LOG.debug("Request body: {}", requestBody);

    request.respond(200).contentType("application/json").bytes(requestBody.getBytes());
  }
}
