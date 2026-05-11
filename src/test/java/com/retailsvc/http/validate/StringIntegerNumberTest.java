package com.retailsvc.http.validate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.NumberSchema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StringIntegerNumberTest {
  private final Validator v =
      new DefaultValidator(
          name -> {
            throw new AssertionError();
          });

  @Test
  void stringMinLength() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, 3, null, null, null);
    assertThatCode(() -> v.validate("abc", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("ab", s, "/v"))
        .isInstanceOf(ValidationException.class)
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("minLength");
  }

  @Test
  void stringMaxLength() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, 5, null, null);
    assertThatThrownBy(() -> v.validate("abcdef", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("maxLength");
  }

  @Test
  void stringPattern() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), "^[a-z]+$", null, null, null, null);
    assertThatCode(() -> v.validate("abc", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("ABC", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("pattern");
  }

  @Test
  void stringEnum() {
    StringSchema s =
        new StringSchema(Set.of(TypeName.STRING), null, null, null, null, List.of("a", "b"));
    assertThatCode(() -> v.validate("a", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("c", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("enum");
  }

  @Test
  void stringFormatUuid() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "uuid", null);
    assertThatCode(() -> v.validate(UUID.randomUUID().toString(), s, "/v"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("not-a-uuid", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }

  @Test
  void stringFormatEmail() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "email", null);
    assertThatCode(() -> v.validate("user@example.com", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("not-an-email", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
    assertThatThrownBy(() -> v.validate("missing@dot", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }

  @Test
  void stringFormatUri() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "uri", null);
    assertThatCode(() -> v.validate("https://example.com/path", s, "/v"))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("/relative/path", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
    assertThatThrownBy(() -> v.validate("not a uri at all", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }

  @Test
  void stringFormatUriReference() {
    StringSchema s =
        new StringSchema(Set.of(TypeName.STRING), null, null, null, "uri-reference", null);
    assertThatCode(() -> v.validate("https://example.com", s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate("/relative/path", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("ht tp://broken", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }

  @Test
  void stringFormatHostname() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "hostname", null);
    assertThatCode(() -> v.validate("example.com", s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate("a.b.c.example", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("-leading-hyphen.com", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
    assertThatThrownBy(() -> v.validate("invalid host name", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }

  @Test
  void stringFormatIpv4() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "ipv4", null);
    assertThatCode(() -> v.validate("192.168.0.1", s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate("0.0.0.0", s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate("255.255.255.255", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("256.0.0.1", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
    assertThatThrownBy(() -> v.validate("1.2.3", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
    assertThatThrownBy(() -> v.validate("01.02.03.04", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }

  @Test
  void stringFormatIpv6() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "ipv6", null);
    assertThatCode(() -> v.validate("2001:0db8:85a3:0000:0000:8a2e:0370:7334", s, "/v"))
        .doesNotThrowAnyException();
    assertThatCode(() -> v.validate("2001:db8::1", s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate("::1", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("not:an:ipv6", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
    assertThatThrownBy(() -> v.validate("12345::1", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }

  @Test
  void integerWithMinMax() {
    IntegerSchema s =
        new IntegerSchema(Set.of(TypeName.INTEGER), 0L, 10L, null, null, null, "int32");
    assertThatCode(() -> v.validate(5, s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate(-1, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("minimum");
    assertThatThrownBy(() -> v.validate(11, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("maximum");
  }

  @Test
  void integerExclusiveBoundsBugFixedFromMaster() {
    // Master's Schema defaulted minimum to Double.MIN_VALUE (~4.9e-324) and silently rejected
    // negative numbers. New model uses null = no constraint.
    IntegerSchema s =
        new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, "int32");
    assertThatCode(() -> v.validate(-1_000_000, s, "/v")).doesNotThrowAnyException();
  }

  @Test
  void integerMultipleOf() {
    IntegerSchema s =
        new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, 5L, "int32");
    assertThatCode(() -> v.validate(15, s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate(7, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("multipleOf");
  }

  @Test
  void numberAcceptsDoublesAndIntegers() {
    NumberSchema s = new NumberSchema(Set.of(TypeName.NUMBER), 0, 1, null, null, null, "double");
    assertThatCode(() -> v.validate(0.5, s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate(1, s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate(2.0, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("maximum");
  }

  @Test
  void stringFormatRegex() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "regex", null);
    assertThatCode(() -> v.validate("^[a-z]+$", s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate("\\d{3}-\\d{4}", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("[invalid", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }

  @Test
  void stringFormatByte() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "byte", null);
    assertThatCode(() -> v.validate("aGVsbG8=", s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate("", s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate("not base64!!", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
    assertThatThrownBy(() -> v.validate("a===", s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }

  @Test
  void stringFormatBinaryAcceptsAnyString() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "binary", null);
    assertThatCode(() -> v.validate("anything goes", s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate(" ", s, "/v")).doesNotThrowAnyException();
  }

  @Test
  void stringFormatPasswordAcceptsAnyString() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, "password", null);
    assertThatCode(() -> v.validate("anything goes", s, "/v")).doesNotThrowAnyException();
  }

  @Test
  void stringRejectsNonString() {
    StringSchema s = new StringSchema(Set.of(TypeName.STRING), null, null, null, null, null);
    assertThatThrownBy(() -> v.validate(42, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("type");
  }

  @Test
  void stringFormatUnknownIsIgnored() {
    StringSchema s =
        new StringSchema(
            Set.of(TypeName.STRING), null, null, null, "definitely-not-a-format", null);
    assertThatCode(() -> v.validate("anything", s, "/v")).doesNotThrowAnyException();
  }

  @Test
  void integerFormatInt64AcceptsAnyLong() {
    IntegerSchema s =
        new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, "int64");
    assertThatCode(() -> v.validate(Long.MAX_VALUE, s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate(Long.MIN_VALUE, s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate(0L, s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate(123L, s, "/v")).doesNotThrowAnyException();
  }

  @Test
  void integerFormatInt32() {
    IntegerSchema s =
        new IntegerSchema(Set.of(TypeName.INTEGER), null, null, null, null, null, "int32");
    assertThatCode(() -> v.validate(Integer.MAX_VALUE, s, "/v")).doesNotThrowAnyException();
    assertThatCode(() -> v.validate(Integer.MIN_VALUE, s, "/v")).doesNotThrowAnyException();
    assertThatThrownBy(() -> v.validate(Integer.MAX_VALUE + 1L, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
    assertThatThrownBy(() -> v.validate(Integer.MIN_VALUE - 1L, s, "/v"))
        .extracting(t -> ((ValidationException) t).error().keyword())
        .isEqualTo("format");
  }
}
