.DEFAULT_GOAL := build

MVN        ?= mvn
SKIP_TESTS := -DskipTests
SIM_PL     := -pl simulator,examples/pus -am

# Test runs: keep going past failing tests/modules, then list failures at the end.
TEST_OPTS     := -fae -Dmaven.test.failure.ignore=true
REPORT_FILES  := find . -path '*/target/surefire-reports/*.txt' -not -path '*/node_modules/*'
CLEAN_REPORTS := find . -type d -path '*/target/surefire-reports' -not -path '*/node_modules/*' -prune -exec rm -rf {} +
# $(call RUN_TESTS,<mvn goals>) -> exit non-zero if mvn failed or any test failed
RUN_TESTS      = $(CLEAN_REPORTS); $(MVN) $(TEST_OPTS) $(1); rc=$$?; $(MAKE) --no-print-directory test-report || rc=1; exit $$rc

.PHONY: help
help: ## List available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# --- Clean -------------------------------------------------------------------

.PHONY: clean
clean: ## mvn clean (all modules)
	@$(MVN) clean

# --- Build (incremental) -----------------------------------------------------

.PHONY: build
build: ## Install all modules, skip tests
	@$(MVN) install $(SKIP_TESTS)

.PHONY: build-test
build-test: ## Install all modules, run all tests, list failures at end
	@$(call RUN_TESTS,install)

# --- Rebuild (clean + build) -------------------------------------------------

.PHONY: rebuild
rebuild: ## Clean + install all modules, skip tests
	@$(MVN) clean install $(SKIP_TESTS)

.PHONY: rebuild-test
rebuild-test: ## Clean + install all modules, run all tests, list failures at end
	@$(call RUN_TESTS,clean install)

# --- Test --------------------------------------------------------------------

.PHONY: test
test: ## Run all tests + javadoc (no install), list failures at end
	@$(call RUN_TESTS,test javadoc:javadoc)

.PHONY: test-report
test-report: ## Summarise test results from the last run
	@$(REPORT_FILES) -exec cat {} + 2>/dev/null | awk ' \
		/^Tests run:/ { gsub(/,/, ""); run += $$3; fail += $$5; err += $$7; skip += $$9; next } \
		/<<< (FAILURE|ERROR)!/ { tag = ($$0 ~ /ERROR!/) ? "ERROR  " : "FAILURE"; sub(/ -- Time elapsed.*/, ""); bad[++n] = tag "  " $$0 } \
		END { \
			printf "\n=== Test summary: %d run, %d failed, %d errors, %d skipped ===\n", run, fail, err, skip; \
			for (i = 1; i <= n; i++) print "  " bad[i]; \
			if (n) { print "Details: <module>/target/surefire-reports/<TestClass>.txt"; exit 1 } \
			print "  All tests passed." }'

# --- Web ---------------------------------------------------------------------

.PHONY: build-web
build-web: ## npm build webapp, then install yamcs-web, skip tests
	cd yamcs-web/src/main/webapp && npm install && npm run build
	@$(MVN) install $(SKIP_TESTS) -pl yamcs-web -am

# --- Simulator (examples/pus) ------------------------------------------------

.PHONY: build-sim
build-sim: ## Install simulator + examples/pus, skip tests
	@$(MVN) $(SIM_PL) install $(SKIP_TESTS)

.PHONY: rebuild-sim
rebuild-sim: ## Clean + install simulator + examples/pus, skip tests
	@$(MVN) $(SIM_PL) clean install $(SKIP_TESTS)

.PHONY: run-sim
run-sim: ## Run examples/pus (UI: http://localhost:8090, instance "pus")
	@$(MVN) -pl examples/pus yamcs:run
