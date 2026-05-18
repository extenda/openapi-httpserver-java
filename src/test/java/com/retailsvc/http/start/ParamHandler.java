package com.retailsvc.http.start;

import static java.net.HttpURLConnection.HTTP_OK;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ParamHandler implements RequestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ParamHandler.class);

  @Override
  public Response handle(Request request) {
    LOG.debug("GET /params");
    return Response.status(HTTP_OK);
  }
}
