package com.retailsvc.http.start;

import static java.net.HttpURLConnection.HTTP_OK;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Echoes back the request body as a response body. */
public class EchoHandler implements RequestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Override
  public Response handle(Request request) {
    byte[] bytes = request.bytes();
    if (bytes.length == 0) {
      LOG.debug("No bytes available to read from the request body");
    } else {
      LOG.debug("Read {} bytes from the request body", bytes.length);
    }
    return Response.bytes(HTTP_OK, bytes, "application/json");
  }
}
