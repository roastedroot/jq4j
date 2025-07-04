
.PHONY: clean
clean:
	rm -rf target

.PHONY: build
build:
	rm -f wasm/*
	docker build . -f buildtools/Dockerfile -t wasm-jq
	docker create --name dummy-jq-wasm wasm-jq
	docker cp dummy-jq-wasm:/workspace/jq.wasm wasm/jq.wasm
	docker rm -f dummy-jq-wasm
