package com.retailsvc.http.start;

import static java.net.HttpURLConnection.HTTP_OK;

import com.retailsvc.http.Request;
import com.retailsvc.http.RequestHandler;
import com.retailsvc.http.Response;

/** Echoes the parsed text/plain body back to the response. */
public class TextEchoHandler implements RequestHandler {

  @Override
  public Response handle(Request request) {
    return Response.text(HTTP_OK, (String) request.parsed());
  }
}
