package com.retailsvc.http.validate;

import com.retailsvc.http.ValidationException;
import com.retailsvc.http.spec.schema.AdditionalProperties;
import com.retailsvc.http.spec.schema.AllOfSchema;
import com.retailsvc.http.spec.schema.AlwaysSchema;
import com.retailsvc.http.spec.schema.AnyOfSchema;
import com.retailsvc.http.spec.schema.ArraySchema;
import com.retailsvc.http.spec.schema.BooleanSchema;
import com.retailsvc.http.spec.schema.ConstSchema;
import com.retailsvc.http.spec.schema.EnumSchema;
import com.retailsvc.http.spec.schema.IntegerSchema;
import com.retailsvc.http.spec.schema.NeverSchema;
import com.retailsvc.http.spec.schema.NotSchema;
import com.retailsvc.http.spec.schema.NullSchema;
import com.retailsvc.http.spec.schema.NumberSchema;
import com.retailsvc.http.spec.schema.ObjectSchema;
import com.retailsvc.http.spec.schema.OneOfSchema;
import com.retailsvc.http.spec.schema.RefSchema;
import com.retailsvc.http.spec.schema.Schema;
import com.retailsvc.http.spec.schema.StringSchema;
import com.retailsvc.http.spec.schema.TypeName;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class DefaultValidator implements Validator {

  private static final String FORMAT_KEYWORD = "format";

  private record FormatCheck(Predicate<String> isValid, String message) {}

  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]++@[^\\s@.]++\\.[^\\s@]++$");

  private static final Pattern HOSTNAME =
      Pattern.compile(
          "^(?=.{1,253}$)[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"
              + "(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*+$");

  private static final Map<String, FormatCheck> FORMAT_CHECKS =
      Map.ofEntries(
          Map.entry("uuid", new FormatCheck(DefaultValidator::isUuid, "not a valid uuid")),
          Map.entry("date", new FormatCheck(DefaultValidator::isDate, "not a valid date")),
          Map.entry(
              "date-time", new FormatCheck(DefaultValidator::isDateTime, "not a valid date-time")),
          Map.entry("email", new FormatCheck(s -> EMAIL.matcher(s).matches(), "not a valid email")),
          Map.entry("uri", new FormatCheck(DefaultValidator::isUri, "not a valid uri")),
          Map.entry(
              "uri-reference",
              new FormatCheck(DefaultValidator::isUriReference, "not a valid uri-reference")),
          Map.entry(
              "hostname",
              new FormatCheck(s -> HOSTNAME.matcher(s).matches(), "not a valid hostname")),
          Map.entry("ipv4", new FormatCheck(DefaultValidator::isIpv4, "not a valid ipv4")),
          Map.entry("ipv6", new FormatCheck(DefaultValidator::isIpv6, "not a valid ipv6")),
          Map.entry("regex", new FormatCheck(DefaultValidator::isRegex, "not a valid regex")),
          Map.entry("byte", new FormatCheck(DefaultValidator::isByte, "not valid base64")),
          Map.entry("binary", new FormatCheck(s -> true, "not valid binary")),
          Map.entry("password", new FormatCheck(s -> true, "not valid password")));

  private final Function<String, Schema> refResolver;
  private final ConcurrentMap<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

  public DefaultValidator(Function<String, Schema> refResolver) {
    this.refResolver = refResolver;
  }

  @Override
  public void validate(Object value, Schema schema, String pointer) {
    if (value == null && schema.types().contains(TypeName.NULL)) {
      return;
    }

    switch (schema) {
      case RefSchema(String ref) -> validate(value, refResolver.apply(ref), pointer);
      case BooleanSchema _ -> validateBoolean(value, pointer);
      case NullSchema _ -> require(value == null, pointer, "type", "expected null");
      case StringSchema s -> validateString(value, s, pointer);
      case IntegerSchema i -> validateInteger(value, i, pointer);
      case NumberSchema n -> validateNumber(value, n, pointer);
      case ObjectSchema o -> validateObject(value, o, pointer);
      case ArraySchema a -> validateArray(value, a, pointer);
      case EnumSchema(List<Object> values) ->
          require(values.contains(value), pointer, "enum", "value not in enum");
      case ConstSchema(Object expected) ->
          require(Objects.equals(expected, value), pointer, "const", "value does not equal const");
      case AllOfSchema(List<Schema> parts) -> {
        for (Schema p : parts) {
          validate(value, p, pointer);
        }
      }
      case AnyOfSchema(List<Schema> options) -> validateAnyOf(value, options, pointer);
      case OneOfSchema(List<Schema> options) -> validateOneOf(value, options, pointer);
      case NotSchema(Schema inner) -> validateNot(value, inner, pointer);
      case AlwaysSchema _ -> {
        /* accepts any value, including null */
      }
      case NeverSchema _ -> fail(pointer, "false", "schema rejects all values", value);
    }
  }

  private void validateBoolean(Object value, String pointer) {
    require(value instanceof Boolean, pointer, "type", "expected boolean");
  }

  private void validateString(Object value, StringSchema s, String pointer) {
    require(value instanceof String, pointer, "type", "expected string");
    String str = (String) value;
    if (s.minLength() != null && str.length() < s.minLength()) {
      fail(pointer, "minLength", "string shorter than " + s.minLength(), str);
    }
    if (s.maxLength() != null && str.length() > s.maxLength()) {
      fail(pointer, "maxLength", "string longer than " + s.maxLength(), str);
    }
    if (s.pattern() != null
        && !compiledPatterns
            .computeIfAbsent(s.pattern(), Pattern::compile)
            .matcher(str)
            .matches()) {
      fail(pointer, "pattern", "does not match pattern " + s.pattern(), str);
    }
    if (s.enumValues() != null && !s.enumValues().contains(str)) {
      fail(pointer, "enum", "value not in enum", str);
    }
    if (s.format() != null) {
      validateStringFormat(str, s.format(), pointer);
    }
  }

  private void validateStringFormat(String str, String format, String pointer) {
    FormatCheck check = FORMAT_CHECKS.get(format);
    if (check == null) {
      return;
    }
    if (!check.isValid().test(str)) {
      fail(pointer, FORMAT_KEYWORD, check.message(), str);
    }
  }

  private static boolean isUuid(String s) {
    try {
      UUID.fromString(s);
      return true;
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  private static boolean isDate(String s) {
    try {
      LocalDate.parse(s);
      return true;
    } catch (DateTimeParseException _) {
      return false;
    }
  }

  private static boolean isDateTime(String s) {
    try {
      OffsetDateTime.parse(s);
      return true;
    } catch (DateTimeParseException _) {
      return false;
    }
  }

  private static boolean isUri(String s) {
    try {
      return new URI(s).isAbsolute();
    } catch (URISyntaxException _) {
      return false;
    }
  }

  private static boolean isRegex(String s) {
    try {
      Pattern.compile(s);
      return true;
    } catch (PatternSyntaxException _) {
      return false;
    }
  }

  private static boolean isByte(String s) {
    try {
      Base64.getDecoder().decode(s);
      return true;
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  private static boolean isUriReference(String s) {
    try {
      new URI(s);
      return true;
    } catch (URISyntaxException _) {
      return false;
    }
  }

  private static final int IPV4_OCTET_COUNT = 4;
  private static final int IPV4_OCTET_MAX_DIGITS = 3;
  private static final int IPV4_OCTET_MAX_VALUE = 255;
  private static final int DECIMAL_RADIX = 10;
  private static final int IPV6_HEXTET_COUNT = 8;
  private static final int IPV6_HEXTET_MAX_DIGITS = 4;

  private static boolean isIpv4(String s) {
    String[] parts = s.split("\\.", -1);
    if (parts.length != IPV4_OCTET_COUNT) {
      return false;
    }
    for (String part : parts) {
      if (!isIpv4Octet(part)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isIpv4Octet(String part) {
    int len = part.length();
    if (len == 0 || len > IPV4_OCTET_MAX_DIGITS) {
      return false;
    }
    if (len > 1 && part.charAt(0) == '0') {
      return false;
    }
    int n = 0;
    for (int i = 0; i < len; i++) {
      char c = part.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
      n = n * DECIMAL_RADIX + (c - '0');
    }
    return n <= IPV4_OCTET_MAX_VALUE;
  }

  private static boolean isIpv6(String s) {
    int doubleColon = s.indexOf("::");
    if (doubleColon != s.lastIndexOf("::")) {
      return false;
    }
    boolean compressed = doubleColon >= 0;
    String[] left;
    String[] right;
    if (compressed) {
      String l = s.substring(0, doubleColon);
      String r = s.substring(doubleColon + 2);
      left = l.isEmpty() ? new String[0] : l.split(":", -1);
      right = r.isEmpty() ? new String[0] : r.split(":", -1);
    } else {
      left = s.split(":", -1);
      right = new String[0];
    }
    int total = left.length + right.length;
    if (compressed ? total > IPV6_HEXTET_COUNT - 1 : total != IPV6_HEXTET_COUNT) {
      return false;
    }
    return allHextets(left) && allHextets(right);
  }

  private static boolean allHextets(String[] parts) {
    for (String hextet : parts) {
      if (!isHextet(hextet)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isHextet(String hextet) {
    int len = hextet.length();
    if (len == 0 || len > IPV6_HEXTET_MAX_DIGITS) {
      return false;
    }
    for (int i = 0; i < len; i++) {
      char c = hextet.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) {
        return false;
      }
    }
    return true;
  }

  private void validateInteger(Object value, IntegerSchema s, String pointer) {
    long n;
    switch (value) {
      case Number num -> n = num.longValue();
      case String str -> {
        try {
          n = Long.parseLong(str);
        } catch (NumberFormatException _) {
          fail(pointer, "type", "expected integer", value);
          return;
        }
      }
      case null, default -> {
        fail(pointer, "type", "expected integer", value);
        return;
      }
    }

    if (s.minimum() != null && n < s.minimum()) {
      fail(pointer, "minimum", "integer below minimum " + s.minimum(), n);
    }
    if (s.maximum() != null && n > s.maximum()) {
      fail(pointer, "maximum", "integer above maximum " + s.maximum(), n);
    }
    if (s.exclusiveMinimum() != null && n <= s.exclusiveMinimum()) {
      fail(pointer, "exclusiveMinimum", "integer not greater than " + s.exclusiveMinimum(), n);
    }
    if (s.exclusiveMaximum() != null && n >= s.exclusiveMaximum()) {
      fail(pointer, "exclusiveMaximum", "integer not less than " + s.exclusiveMaximum(), n);
    }
    if (s.multipleOf() != null && n % s.multipleOf() != 0) {
      fail(pointer, "multipleOf", "not a multiple of " + s.multipleOf(), n);
    }
  }

  private void validateNumber(Object value, NumberSchema s, String pointer) {
    double n;
    switch (value) {
      case Number num -> n = num.doubleValue();
      case String str -> {
        try {
          n = Double.parseDouble(str);
        } catch (NumberFormatException _) {
          fail(pointer, "type", "expected number", value);
          return;
        }
      }
      case null, default -> {
        fail(pointer, "type", "expected number", value);
        return;
      }
    }

    if (s.minimum() != null && n < s.minimum().doubleValue()) {
      fail(pointer, "minimum", "number below minimum " + s.minimum(), n);
    }
    if (s.maximum() != null && n > s.maximum().doubleValue()) {
      fail(pointer, "maximum", "number above maximum " + s.maximum(), n);
    }
    if (s.exclusiveMinimum() != null && n <= s.exclusiveMinimum().doubleValue()) {
      fail(pointer, "exclusiveMinimum", "number not greater than " + s.exclusiveMinimum(), n);
    }
    if (s.exclusiveMaximum() != null && n >= s.exclusiveMaximum().doubleValue()) {
      fail(pointer, "exclusiveMaximum", "number not less than " + s.exclusiveMaximum(), n);
    }
    if (s.multipleOf() != null && !isMultipleOf(n, s.multipleOf().doubleValue())) {
      fail(pointer, "multipleOf", "not a multiple of " + s.multipleOf(), n);
    }
  }

  /**
   * Returns whether {@code value} is an exact multiple of {@code divisor}, using {@link BigDecimal}
   * to avoid floating-point rounding artifacts that {@code (value / divisor) % 1 == 0} would
   * produce (e.g., {@code 0.3 / 0.1} is not exactly {@code 3.0} as a double).
   */
  private static boolean isMultipleOf(double value, double divisor) {
    BigDecimal v = BigDecimal.valueOf(value);
    BigDecimal d = BigDecimal.valueOf(divisor);
    return v.remainder(d).compareTo(BigDecimal.ZERO) == 0;
  }

  @SuppressWarnings("unchecked")
  private void validateObject(Object value, ObjectSchema s, String pointer) {
    require(value instanceof Map, pointer, "type", "expected object");
    Map<String, Object> map = (Map<String, Object>) value;

    for (String required : s.required()) {
      require(
          map.containsKey(required),
          pointer + "/" + required,
          "required",
          "required property missing");
    }

    if (s.minProperties() != null && map.size() < s.minProperties()) {
      fail(pointer, "minProperties", "fewer than " + s.minProperties() + " properties", map.size());
    }
    if (s.maxProperties() != null && map.size() > s.maxProperties()) {
      fail(pointer, "maxProperties", "more than " + s.maxProperties() + " properties", map.size());
    }

    for (var entry : map.entrySet()) {
      String childPointer = pointer + "/" + entry.getKey();
      Schema propSchema = s.properties().get(entry.getKey());
      if (propSchema != null) {
        validate(entry.getValue(), propSchema, childPointer);
      } else {
        switch (s.additionalProperties()) {
          case AdditionalProperties.Allowed _ -> {
            /* no-op: additional properties are permitted by default */
          }
          case AdditionalProperties.Forbidden _ ->
              fail(
                  childPointer,
                  "additionalProperties",
                  "additional property not allowed",
                  entry.getKey());
          case AdditionalProperties.SchemaConstraint(Schema constraint) ->
              validate(entry.getValue(), constraint, childPointer);
        }
      }
    }
  }

  private void validateArray(Object value, ArraySchema s, String pointer) {
    require(value instanceof Iterable, pointer, "type", "expected array");
    Iterable<?> it = (Iterable<?>) value;
    List<Object> elements = new ArrayList<>();
    for (Object o : it) {
      elements.add(o);
    }

    if (s.minItems() != null && elements.size() < s.minItems()) {
      fail(pointer, "minItems", "fewer than " + s.minItems() + " items", elements.size());
    }
    if (s.maxItems() != null && elements.size() > s.maxItems()) {
      fail(pointer, "maxItems", "more than " + s.maxItems() + " items", elements.size());
    }

    if (s.uniqueItems()) {
      Set<Object> seen = new HashSet<>();
      for (Object e : elements) {
        if (!seen.add(e)) {
          fail(pointer, "uniqueItems", "duplicate item", e);
        }
      }
    }

    for (int i = 0; i < elements.size(); i++) {
      validate(elements.get(i), s.items(), pointer + "/" + i);
    }
  }

  private static void fail(String pointer, String keyword, String message, Object rejectedValue) {
    throw new ValidationException(new ValidationError(pointer, keyword, message, rejectedValue));
  }

  static void require(boolean condition, String pointer, String keyword, String message) {
    if (!condition) {
      throw new ValidationException(new ValidationError(pointer, keyword, message, null));
    }
  }

  private void validateAnyOf(Object value, List<Schema> options, String pointer) {
    for (Schema o : options) {
      try {
        validate(value, o, pointer);
        return;
      } catch (ValidationException ignored) {
        // try next branch
      }
    }
    fail(pointer, "anyOf", "did not match any anyOf branch", value);
  }

  private void validateOneOf(Object value, List<Schema> options, String pointer) {
    int matched = 0;
    for (Schema o : options) {
      try {
        validate(value, o, pointer);
        matched++;
      } catch (ValidationException ignored) {
        // branch did not match; continue
      }
    }
    if (matched != 1) {
      fail(
          pointer,
          "oneOf",
          "matched " + matched + " of " + options.size() + " oneOf branches",
          value);
    }
  }

  private void validateNot(Object value, Schema inner, String pointer) {
    try {
      validate(value, inner, pointer);
    } catch (ValidationException expected) {
      return;
    }
    fail(pointer, "not", "value matched 'not' schema", value);
  }
}
