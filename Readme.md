# Jq4J

**Jq4J** is [`jq`](https://github.com/jqlang/jq) running as pure Java bytecode.

## Why?

`jq` is a very handy tool we all use everyday, sadly it's syntax doesn't follow a spec that can be easily ported across languages.
By compiling `jq` to Wasm and Wasm to Java bytecode thanks to [Chicory](https://chicory.dev) we don't need to port the original source code and we have 1:1 functionality out-of-the-box.

## Quick Start

Add Jq4J as a standard Maven dependency:

```xml
<dependency>
    <groupId>io.roastedroot</groupId>
    <artifactId>jq4j</artifactId>
</dependency>
```

The usage matches 1:1 the usage through the `jq` CLI, hence you should:
 - define the command line arguments to be used
 - pass the input over on stdin
 - extract the output as stdout

In case of any error you can always inspect stdout/stderr produced by `jq` to identify the issue.
Example:

```java
import io.roastedroot.jq4j.Jq;

var result = Jq.builder()
    .withStdin("{\"foo\": 0}".getBytes(UTF_8))
    .withArgs("-M", "--compact-output", ".")
    .run();

if (result.success()) {
    var resultStr = new String(result.stdout(), UTF_8); // {"foo":0}\n
    ...
} else {
    System.out.println(result.stdout());
    System.err.println(result.stderr());
}
```

## Reactor Mode

The default `Jq.builder()` creates a fresh WASM instance for every invocation.
**Reactor mode** keeps a single instance alive and reuses it across calls,
avoiding the per-call initialization overhead:

```java
import io.roastedroot.jq4j.JqReactor;

var jq = JqReactor.build();

byte[] result = jq
    .withInput("{\"foo\": 0}")
    .withFilter(".")
    .withCompactOutput()
    .run();
// result: {"foo":0}\n

// The same instance can be reused immediately
byte[] fruits = jq
    .withInput(fruitsJson)
    .withFilter(".[].name")
    .run();

jq.close();
```

Available options: `withSlurp()`, `withNullInput()`, `withCompactOutput()`, `withSortKeys()`.

> **Note:** A single `JqReactor` instance is **not thread-safe**.
> Use one per thread, or use the pool described below.

## Reactor Pool

`JqReactorPool` manages a bounded set of reactor instances for concurrent use.
It uses lock-free data structures internally, so it is safe to use with
virtual threads (no carrier-thread pinning).

```java
import io.roastedroot.jq4j.JqReactorPool;

var pool = JqReactorPool.create(4); // at most 4 concurrent reactors

try (var loan = pool.borrow()) {    // blocks if all 4 are in use
    byte[] result = loan.jq()
        .withInput("{\"a\": 1}")
        .withFilter(".a")
        .withCompactOutput()
        .run();
}
// reactor is automatically returned to the pool

pool.close();
```

If a processing error may have left the reactor in a bad state, call
`loan.discard()` instead of letting `close()` return it:

```java
try (var loan = pool.borrow()) {
    try {
        result = loan.jq().withInput(input).withFilter(filter).run();
    } catch (RuntimeException ex) {
        loan.discard(); // destroys this reactor; pool will create a fresh one
        // handle error ...
    }
}
```

## Building the Project

To build this project, you'll need:

* Docker
* JDK 11 or newer
* Maven

Steps:

```bash
make build
mvn clean install
```

## Acknowledgements

This project stands on the shoulders of giants:

* [go-jq](https://github.com/wasilibs/go-jq) - go-jq is a distribution of jq, that can be built with Go
* [Chicory](https://chicory.dev/) – a native JVM WebAssembly runtime
