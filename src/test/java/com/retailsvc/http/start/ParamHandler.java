package com.retailsvc.http.start;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ParamHandler implements RequestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ParamHandler.class);

  @Override
  public void handle(Request request) throws IOException {
    LOG.debug("GET /params");

    request.respond(200).empty();
  }
}
