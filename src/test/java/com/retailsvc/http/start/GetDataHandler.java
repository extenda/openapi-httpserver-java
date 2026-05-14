package com.retailsvc.http.start;

import static java.net.HttpURLConnection.HTTP_OK;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetDataHandler implements RequestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GetDataHandler.class);

  @Override
  public Response handle(Request request) {
    LOG.debug("GET /data");
    return Response.of(HTTP_OK, Map.of("id", "some-id"));
  }
}
