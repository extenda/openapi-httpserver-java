package com.retailsvc.http.openapi;

import com.retailsvc.http.openapi.exceptions.LoadSpecificationException;
import java.io.InputStream;

public class SpecificationLoader {

  public static byte[] load(String spec) {
    try (InputStream is = loadFile(spec)) {
      return is.readAllBytes();
    } catch (Exception e) {
      String message = "Specification %s could not be loaded".formatted(spec);
      throw new LoadSpecificationException(message, e);
    }
  }

  private static InputStream loadFile(String spec) {
    return SpecificationLoader.class.getClassLoader().getResourceAsStream(spec);
  }

  private SpecificationLoader() {}
}
