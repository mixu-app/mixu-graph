# mixu-graph quality gates. Run via `just check`, never bare tool invocations.
set shell := ["bash", "-euo", "pipefail", "-c"]

check: lint-md gradle-check

lint-md:
    markdownlint-cli2 --config .markdownlint-cli2.jsonc

gradle-check:
    ./gradlew --no-daemon check
