package com.retailsvc.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/**
 * An HTTP content coding (RFC 9110 §8.4.1) that the server decodes on requests and applies to
 * responses. The library ships {@code gzip}; anything else — {@code zstd}, {@code br}, {@code
 * deflate} — is supplied by the caller and registered on {@link
 * OpenApiServer.Builder#contentCoding(ContentCoding)}, which keeps this library free of any
 * compression dependency.
 *
 * <p>One instance serves every request, so implementations must be immutable and safe for
 * concurrent use.
 *
 * <p>Both methods wrap a stream rather than convert a whole body: return a stream that codes as it
 * is read or written. For {@link #decode} this is what bounds the work — the server reads at most
 * {@code maxDecompressedRequestBytes} from the stream you return, so a lazy decoder is protected
 * from decompression bombs without doing anything, while one that expands the whole body up front
 * has already spent what that limit exists to protect.
 */
public interface ContentCoding {

  /**
   * The {@code Content-Encoding} and {@code Accept-Encoding} token, such as {@code zstd}. Must be a
   * lower-case RFC 9110 token; {@code gzip}, {@code x-gzip}, {@code identity} and {@code *} are
   * reserved.
   */
  String token();

  /**
   * Further tokens that mean this same coding on the wire, such as a legacy {@code x-} name.
   * Recognised on input only; a coded response always announces {@link #token()}.
   */
  default Set<String> aliases() {
    return Set.of();
  }

  /**
   * Wraps a coded request body in a stream of the decoded bytes. The argument is always held in
   * memory, so a read failure means the body is malformed: throw {@link IOException} and the server
   * answers 400. Any other exception is treated as a fault in the coding.
   */
  InputStream decode(InputStream coded) throws IOException;

  /**
   * Wraps a response stream so that everything written to the returned stream reaches {@code sink}
   * coded. Closing the returned stream must finish the coded payload and close {@code sink}.
   */
  OutputStream encode(OutputStream sink) throws IOException;
}
