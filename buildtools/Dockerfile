FROM ghcr.io/webassembly/wasi-sdk:wasi-sdk-22

RUN apt-get update && apt-get install -y curl binaryen

WORKDIR /workspace
ADD buildtools/version.txt version.txt
RUN curl -L https://github.com/jqlang/jq/releases/download/$(cat version.txt | awk '{$1=$1};1')/$(cat version.txt | awk '{$1=$1};1').tar.gz | tar -xz --strip-components 1 -C /workspace

ENV CFLAGS --target=wasm32-wasi-threads --sysroot=/wasi-sysroot/ -pthread -O3 -D_WASI_EMULATED_SIGNAL
ENV LDFLAGS ${CFLAGS} -Wl,--global-base=1024  -Wl,--max-memory=4294967296 -lwasi-emulated-signal
ENV NM llvm-nm-${LLVM_VERSION}

# host is required by configure but not used so set it arbitrarily
RUN ./configure --with-oniguruma=builtin --host=i686-pc-linux-gnu
RUN make

RUN wasm-opt -o jq.wasm --low-memory-unused --flatten --rereloop --converge -O3 ./jq

CMD ["cat", "jq.wasm"]
