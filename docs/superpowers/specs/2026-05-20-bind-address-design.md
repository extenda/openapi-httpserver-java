# Configurable bind address

Date: 2026-05-20
Branch: `fix/support-loopback`

## Problem

`OpenApiServer` binds via `new InetSocketAddress(port)` (`OpenApiServer.java:87`), which always listens on the wildcard address (all local interfaces). Callers that want to restrict the server to the loopback interface — common for local development, sidecar/companion processes, or tests — have no way to do so.

## Goal

Let callers choose the bind interface. Default behavior stays unchanged (wildcard).

Non-goals: dual-stack tuning, SO_REUSEADDR exposure, multiple bind addresses, hostname strings.

## API

Add one optional builder method:

```java
public Builder bindAddress(InetAddress bindAddress) {
  this.bindAddress = bindAddress;  // null allowed -> wildcard
  return this;
}
```

Typed `InetAddress` (not `String`) — no parsing, no ambiguity, and the standard library already provides the relevant factories:

```java
OpenApiServer.builder()
    .spec(spec)
    .handlers(handlers)
    .port(8080)
    .bindAddress(InetAddress.getLoopbackAddress())
    .build();
```

Unset (or explicitly `null`) preserves the current wildcard behavior — no source or behavioural change for existing callers.

## Implementation

`OpenApiServer.Builder` gains a private `InetAddress bindAddress` field, threaded through `build()` into the package-private constructor as a new parameter alongside `port` and `shutdownTimeoutSeconds`.

In the constructor, replace line 87:

```java
InetSocketAddress socketAddress = (bindAddress == null)
    ? new InetSocketAddress(port)
    : new InetSocketAddress(bindAddress, port);
this.httpServer = HttpServer.create(socketAddress, 0);
```

`bindAddress` is a network-binding concern; it stays out of `HandlerConfig` and sits directly on the constructor signature next to `port`.

### Startup log

Extend the existing line 119 log so the bound host is visible — helpful when verifying that a loopback restriction took effect:

```
Server started ({}:{}) in {}ms
```

Format using `httpServer.getAddress().getHostString()` and `.getPort()`. The existing `(port {})` form becomes `(host:port)` consistently for all callers.

## Testing

Add to the existing `OpenApiServerTest` (unit) suite:

1. **Loopback binding works** — build with `bindAddress(InetAddress.getLoopbackAddress())`, issue a request against `127.0.0.1:<listenPort>`, assert 2xx.
2. **Default is wildcard** — build without calling `bindAddress(...)`; assert `httpServer.getAddress().getAddress().isAnyLocalAddress()` is `true`. (Access via a small package-private accessor or by reading `listenPort()`-style getter on the bound address — pick whichever fits the existing test conventions; do not add public API just for tests.)
3. **Explicit null behaves as unset** — `bindAddress(null)` round-trips to wildcard.

No new integration tests; the change is a single line inside `HttpServer.create(...)` and is fully covered by unit tests.

## Documentation

README: add a short bullet under "Getting Started" / configuration showing the loopback example. One snippet, no extended discussion.

## Risk and rollback

Pure additive API. Default path is byte-identical to before (same `new InetSocketAddress(port)` call). Rollback is reverting the commit.
