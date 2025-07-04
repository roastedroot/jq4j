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

TODO: usage docs, the API is minimal

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

* [Chicory](https://chicory.dev/) – a native JVM WebAssembly runtime
