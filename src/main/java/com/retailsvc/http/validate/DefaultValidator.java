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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.DoublePredicate;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class DefaultValidator implements Validator {

  private static final String FORMAT_KEYWORD = "format";
  private static final Optional<ValidationError> OK = Optional.empty();

  private record FormatCheck(Predicate<String> isValid, String message) {}

  private record IntegerFormatCheck(LongPredicate isValid, String message) {}

  private record NumberFormatCheck(DoublePredicate isValid, String message) {}

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

  private static final Map<String, IntegerFormatCheck> INTEGER_FORMAT_CHECKS =
      Map.of(
          "int32",
          new IntegerFormatCheck(
              n -> n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE, "value does not fit in int32"),
          "int64",
          new IntegerFormatCheck(n -> true, "value does not fit in int64"));

  private static final Map<String, NumberFormatCheck> NUMBER_FORMAT_CHECKS =
      Map.of(
          "float",
          new NumberFormatCheck(
              n -> !Double.isNaN(n) && !Double.isInfinite(n) && Math.abs(n) <= Float.MAX_VALUE,
              "value does not fit in float"),
          "double",
          new NumberFormatCheck(n -> true, "value does not fit in double"));

  private final Function<String, Schema> refResolver;
  private final ConcurrentMap<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

  public DefaultValidator(Function<String, Schema> refResolver) {
    this.refResolver = refResolver;
  }

  @Override
  public void validate(Object value, Schema schema, String pointer) {
    Optional<ValidationError> result = check(value, schema, pointer);
    if (result.isPresent()) {
      throw new ValidationException(result.get());
    }
  }

  /**
   * Internal validation entry point. Returns the first {@link ValidationError} encountered, or
   * {@link Optional#empty()} on success. This is what {@code anyOf} / {@code oneOf} / {@code not}
   * branch-select against, so it must never throw {@link ValidationException} on a failed branch —
   * exceptions would re-introduce the hot-path control-flow cost this refactor exists to remove.
   */
  Optional<ValidationError> check(Object value, Schema schema, String pointer) {
    if (value == null && schema.types().contains(TypeName.NULL)) {
      return OK;
    }

    return switch (schema) {
      case RefSchema(String ref, _) -> check(value, refResolver.apply(ref), pointer);
      case BooleanSchema _ -> checkBoolean(value, pointer);
      case NullSchema _ -> value == null ? OK : err(pointer, "type", "expected null", value);
      case StringSchema s -> checkString(value, s, pointer);
      case IntegerSchema i -> checkInteger(value, i, pointer);
      case NumberSchema n -> checkNumber(value, n, pointer);
      case ObjectSchema o -> checkObject(value, o, pointer);
      case ArraySchema a -> checkArray(value, a, pointer);
      case EnumSchema(List<Object> values, _) ->
          values.contains(value) ? OK : err(pointer, "enum", "value not in enum", value);
      case ConstSchema(Object expected, _) ->
          Objects.equals(expected, value)
              ? OK
              : err(pointer, "const", "value does not equal const", value);
      case AllOfSchema(List<Schema> parts, _) -> checkAllOf(value, parts, pointer);
      case AnyOfSchema(List<Schema> options, _) -> checkAnyOf(value, options, pointer);
      case OneOfSchema(List<Schema> options, _) -> checkOneOf(value, options, pointer);
      case NotSchema(Schema inner, _) -> checkNot(value, inner, pointer);
      case AlwaysSchema _ -> OK;
      case NeverSchema _ -> err(pointer, "false", "schema rejects all values", value);
    };
  }

  private static Optional<ValidationError> err(
      String pointer, String keyword, String message, Object rejectedValue) {
    return Optional.of(new ValidationError(pointer, keyword, message, rejectedValue));
  }

  private static Optional<ValidationError> err(String pointer, String keyword, String message) {
    return Optional.of(new ValidationError(pointer, keyword, message, null));
  }

  private static Optional<ValidationError> checkBoolean(Object value, String pointer) {
    return value instanceof Boolean ? OK : err(pointer, "type", "expected boolean", value);
  }

  private Optional<ValidationError> checkString(Object value, StringSchema s, String pointer) {
    if (!(value instanceof String str)) {
      return err(pointer, "type", "expected string", value);
    }
    if (s.minLength() != null && str.length() < s.minLength()) {
      return err(pointer, "minLength", "string shorter than " + s.minLength(), str);
    }
    if (s.maxLength() != null && str.length() > s.maxLength()) {
      return err(pointer, "maxLength", "string longer than " + s.maxLength(), str);
    }
    if (s.pattern() != null
        && !compiledPatterns
            .computeIfAbsent(s.pattern(), Pattern::compile)
            .matcher(str)
            .matches()) {
      return err(pointer, "pattern", "does not match pattern " + s.pattern(), str);
    }
    if (s.enumValues() != null && !s.enumValues().contains(str)) {
      return err(pointer, "enum", "value not in enum", str);
    }
    if (s.format() != null) {
      return checkStringFormat(str, s.format(), pointer);
    }
    return OK;
  }

  private static Optional<ValidationError> checkStringFormat(
      String str, String format, String pointer) {
    FormatCheck check = FORMAT_CHECKS.get(format);
    if (check == null) {
      return OK;
    }
    return check.isValid().test(str) ? OK : err(pointer, FORMAT_KEYWORD, check.message(), str);
  }

  private static Optional<ValidationError> checkIntegerFormat(
      long n, String format, String pointer) {
    IntegerFormatCheck check = INTEGER_FORMAT_CHECKS.get(format);
    if (check == null) {
      return OK;
    }
    return check.isValid().test(n) ? OK : err(pointer, FORMAT_KEYWORD, check.message(), n);
  }

  private static Optional<ValidationError> checkNumberFormat(
      double n, String format, String pointer) {
    NumberFormatCheck check = NUMBER_FORMAT_CHECKS.get(format);
    if (check == null) {
      return OK;
    }
    return check.isValid().test(n) ? OK : err(pointer, FORMAT_KEYWORD, check.message(), n);
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

  private static Optional<ValidationError> checkInteger(
      Object value, IntegerSchema s, String pointer) {
    if (!(value instanceof Number num)) {
      return err(pointer, "type", "expected integer", value);
    }
    long n = num.longValue();

    if (s.minimum() != null && n < s.minimum()) {
      return err(pointer, "minimum", "integer below minimum " + s.minimum(), n);
    }
    if (s.maximum() != null && n > s.maximum()) {
      return err(pointer, "maximum", "integer above maximum " + s.maximum(), n);
    }
    if (s.exclusiveMinimum() != null && n <= s.exclusiveMinimum()) {
      return err(
          pointer, "exclusiveMinimum", "integer not greater than " + s.exclusiveMinimum(), n);
    }
    if (s.exclusiveMaximum() != null && n >= s.exclusiveMaximum()) {
      return err(pointer, "exclusiveMaximum", "integer not less than " + s.exclusiveMaximum(), n);
    }
    if (s.multipleOf() != null && n % s.multipleOf() != 0) {
      return err(pointer, "multipleOf", "not a multiple of " + s.multipleOf(), n);
    }
    if (s.format() != null) {
      return checkIntegerFormat(n, s.format(), pointer);
    }
    return OK;
  }

  private static Optional<ValidationError> checkNumber(
      Object value, NumberSchema s, String pointer) {
    if (!(value instanceof Number num)) {
      return err(pointer, "type", "expected number", value);
    }
    double n = num.doubleValue();

    if (s.minimum() != null && n < s.minimum().doubleValue()) {
      return err(pointer, "minimum", "number below minimum " + s.minimum(), n);
    }
    if (s.maximum() != null && n > s.maximum().doubleValue()) {
      return err(pointer, "maximum", "number above maximum " + s.maximum(), n);
    }
    if (s.exclusiveMinimum() != null && n <= s.exclusiveMinimum().doubleValue()) {
      return err(pointer, "exclusiveMinimum", "number not greater than " + s.exclusiveMinimum(), n);
    }
    if (s.exclusiveMaximum() != null && n >= s.exclusiveMaximum().doubleValue()) {
      return err(pointer, "exclusiveMaximum", "number not less than " + s.exclusiveMaximum(), n);
    }
    if (s.multipleOf() != null && !isMultipleOf(n, s.multipleOf().doubleValue())) {
      return err(pointer, "multipleOf", "not a multiple of " + s.multipleOf(), n);
    }
    if (s.format() != null) {
      return checkNumberFormat(n, s.format(), pointer);
    }
    return OK;
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
  private Optional<ValidationError> checkObject(Object value, ObjectSchema s, String pointer) {
    if (!(value instanceof Map)) {
      return err(pointer, "type", "expected object", value);
    }
    Map<String, Object> map = (Map<String, Object>) value;

    for (String required : s.required()) {
      if (!map.containsKey(required)) {
        return err(pointer + "/" + required, "required", "required property missing");
      }
    }

    if (s.minProperties() != null && map.size() < s.minProperties()) {
      return err(
          pointer, "minProperties", "fewer than " + s.minProperties() + " properties", map.size());
    }
    if (s.maxProperties() != null && map.size() > s.maxProperties()) {
      return err(
          pointer, "maxProperties", "more than " + s.maxProperties() + " properties", map.size());
    }

    for (var entry : map.entrySet()) {
      String childPointer = pointer + "/" + entry.getKey();
      Schema propSchema = s.properties().get(entry.getKey());
      Optional<ValidationError> result;
      if (propSchema != null) {
        result = check(entry.getValue(), propSchema, childPointer);
      } else {
        result =
            switch (s.additionalProperties()) {
              case AdditionalProperties.Allowed _ -> OK;
              case AdditionalProperties.Forbidden _ ->
                  err(
                      childPointer,
                      "additionalProperties",
                      "additional property not allowed",
                      entry.getKey());
              case AdditionalProperties.SchemaConstraint(Schema constraint) ->
                  check(entry.getValue(), constraint, childPointer);
            };
      }
      if (result.isPresent()) {
        return result;
      }
    }
    return OK;
  }

  private Optional<ValidationError> checkArray(Object value, ArraySchema s, String pointer) {
    if (!(value instanceof Iterable<?> it)) {
      return err(pointer, "type", "expected array", value);
    }
    List<Object> elements = new ArrayList<>();
    for (Object o : it) {
      elements.add(o);
    }

    if (s.minItems() != null && elements.size() < s.minItems()) {
      return err(pointer, "minItems", "fewer than " + s.minItems() + " items", elements.size());
    }
    if (s.maxItems() != null && elements.size() > s.maxItems()) {
      return err(pointer, "maxItems", "more than " + s.maxItems() + " items", elements.size());
    }

    if (s.uniqueItems()) {
      Set<Object> seen = new HashSet<>();
      for (Object e : elements) {
        if (!seen.add(e)) {
          return err(pointer, "uniqueItems", "duplicate item", e);
        }
      }
    }

    for (int i = 0; i < elements.size(); i++) {
      Optional<ValidationError> result = check(elements.get(i), s.items(), pointer + "/" + i);
      if (result.isPresent()) {
        return result;
      }
    }
    return OK;
  }

  private Optional<ValidationError> checkAllOf(Object value, List<Schema> parts, String pointer) {
    for (Schema p : parts) {
      Optional<ValidationError> result = check(value, p, pointer);
      if (result.isPresent()) {
        return result;
      }
    }
    return OK;
  }

  private Optional<ValidationError> checkAnyOf(Object value, List<Schema> options, String pointer) {
    List<ValidationError> failures = null;
    for (Schema o : options) {
      Optional<ValidationError> result = check(value, o, pointer);
      if (result.isEmpty()) {
        return OK;
      }
      if (failures == null) {
        failures = new ArrayList<>(options.size() - 1);
      }
      failures.add(result.get());
    }
    List<ValidationError> branches = failures != null ? failures : List.of();
    return Optional.of(
        new ValidationError(pointer, "anyOf", "did not match any anyOf branch", value, branches));
  }

  private Optional<ValidationError> checkOneOf(Object value, List<Schema> options, String pointer) {
    int matched = 0;
    List<ValidationError> failures = new ArrayList<>();
    for (Schema o : options) {
      Optional<ValidationError> result = check(value, o, pointer);
      if (result.isEmpty()) {
        matched++;
      } else {
        failures.add(result.get());
      }
    }
    if (matched == 1) {
      return OK;
    }
    return Optional.of(
        new ValidationError(
            pointer,
            "oneOf",
            "matched " + matched + " of " + options.size() + " oneOf branches",
            value,
            // Ambiguous match (matched > 1): the non-matching branches' errors are noise — omit
            // them.
            matched == 0 ? failures : List.of()));
  }

  private Optional<ValidationError> checkNot(Object value, Schema inner, String pointer) {
    return check(value, inner, pointer).isPresent()
        ? OK
        : err(pointer, "not", "value matched 'not' schema", value);
  }
}
