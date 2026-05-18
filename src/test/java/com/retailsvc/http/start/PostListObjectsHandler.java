package com.retailsvc.http.start;

import static java.net.HttpURLConnection.HTTP_OK;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostListObjectsHandler implements RequestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Override
  public Response handle(Request request) {
    LOG.debug("POST /list/objects");
    return Response.bytes(HTTP_OK, request.bytes(), "application/json");
  }
}
