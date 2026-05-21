# Convenience targets for the Java-21 OpenRewrite fitness loop.

IMAGE      ?= j21-fitness:latest
DATASET    ?= java21-migration-dataset.json
POOL       ?= recipes/pool.yml
SEED       ?= recipes/seed.yml
RESULTS    ?= results
PARALLEL   ?= 4
MAX_ITER   ?= 20

.PHONY: help image dry-run loop best smoke clean

help:
	@echo "Targets:"
	@echo "  image     - build the multi-JDK runner Docker image"
	@echo "  smoke     - python-level syntax + dry-run loop (no Docker)"
	@echo "  dry-run   - run the orchestrator with a synthetic fitness function"
	@echo "  loop      - full ralph loop against the dataset (requires Docker)"
	@echo "  best      - pretty-print results/best.json"
	@echo "  clean     - wipe results/ but keep recipes/ and code"

image:
	docker build -t $(IMAGE) .

smoke:
	python3 -m py_compile orchestrator/*.py
	python3 -m unittest discover -v tests/

dry-run:
	python3 -m orchestrator.orchestrator \
		--dataset $(DATASET) \
		--pool $(POOL) \
		--seed $(SEED) \
		--results $(RESULTS) \
		--max-iter 8 \
		--proposals 4 \
		--dry-run

loop:
	python3 -m orchestrator.orchestrator \
		--dataset $(DATASET) \
		--pool $(POOL) \
		--seed $(SEED) \
		--results $(RESULTS) \
		--parallel $(PARALLEL) \
		--max-iter $(MAX_ITER)

best:
	@jq '.best_score, .iterations_run, (.best | join("\n  "))' $(RESULTS)/best.json

clean:
	rm -rf $(RESULTS)
