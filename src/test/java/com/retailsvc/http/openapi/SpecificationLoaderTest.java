package com.retailsvc.http.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.openapi.exceptions.LoadSpecificationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class SpecificationLoaderTest {

  @Test
  void shouldLoadSpecificationCorrectly() throws IOException {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream("openapi.json")) {
      if (Objects.nonNull(is)) {
        byte[] expectedBytes = is.readAllBytes();
        byte[] actualBytes = SpecificationLoader.load("openapi.json");
        assertThat(actualBytes).isEqualTo(expectedBytes);
      }
    }
  }

  @Test
  void shouldThrowRuntimeExceptionWhenFileNotFound() {
    assertThatThrownBy(() -> SpecificationLoader.load("non_existent_file.json"))
        .isInstanceOf(LoadSpecificationException.class);
  }
}
