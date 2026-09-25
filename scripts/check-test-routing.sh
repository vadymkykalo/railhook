#!/usr/bin/env bash
set -euo pipefail

# CI splits backend jobs by class-name suffix, so a Testcontainers test named plain FooTest passes
# locally and fails in the no-Docker unit job.

cd "$(git rev-parse --show-toplevel)"

INTEGRATION_SUFFIXES='(IntegrationTest|IT|RepositoryTest|ConcurrencyTest|RbacTest|IsolationTest)\.java$'

NEEDS_DOCKER='@Testcontainers|@SpringBootTest|AbstractIntegrationTest|GenericContainer|PostgreSQLContainer|KafkaContainer'

misrouted=()
while IFS= read -r file; do
    if [[ "$file" =~ $INTEGRATION_SUFFIXES ]]; then
        continue
    fi
    if grep -qE "$NEEDS_DOCKER" "$file"; then
        misrouted+=("$file")
    fi
done < <(find . -path ./node_modules -prune -o -path '*/src/test/java/*' -name '*Test.java' -print)

if [ ${#misrouted[@]} -gt 0 ]; then
    echo "::error::Test classes need Docker but are named to run in the unit-test job."
    echo ""
    echo "CI routes backend tests by class-name suffix alone. These files use"
    echo "Testcontainers or @SpringBootTest, so they belong in the integration job,"
    echo "but their names put them in the unit job — where there is no Docker:"
    echo ""
    for f in "${misrouted[@]}"; do
        echo "  $f"
    done
    echo ""
    echo "Rename each to end in one of: IntegrationTest, IT, RepositoryTest,"
    echo "ConcurrencyTest, RbacTest, IsolationTest."
    exit 1
fi

echo "Test routing OK: no Docker-dependent test is named into the unit-test job."
