package com.retailsvc.http.start;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GetDataHandler implements RequestHandler {

  private static final Logger LOG = LoggerFactory.getLogger(GetDataHandler.class);

  @Override
  public void handle(Request request) throws IOException {
    LOG.debug("GET /data");

    byte[] bytes =
        """
        {
          "id": "some-id"
        }\
        """
            .getBytes();
    request.respond(200).contentType("application/json").bytes(bytes);
  }
}
