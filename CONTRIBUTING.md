# Contributing

Thank you for your interest in contributing to **Flash-Sale Order & Inventory Reservation API**.

## Development Setup

```bash
# Prerequisites: Java 21+, Maven, Docker (for integration tests)
git clone https://github.com/bachiep/flashsale-order-inventory-api.git
cd flashsale-order-inventory-api
cp .env.example .env              # configure database credentials
./mvnw -B verify                  # run full verification suite (55 tests)
```

## Workflow

1. **Open an Issue** describing the bug or feature.
2. **Create a branch** from `main`: `feat/issue-<n>-<slug>` or `fix/issue-<n>-<slug>`.
3. **Write tests first** for any new logic.
4. **Follow Conventional Commits**: `feat(inventory): add batch reservation (#12)`.
5. **Ensure CI passes**: `./mvnw -B verify` locally before pushing.
6. **Open a Pull Request** referencing the issue: `Closes #<n>`.

## Code Standards

- Java 21 with Virtual Threads — prefer `Executors.newVirtualThreadPerTaskExecutor()`.
- Spring Boot 3.4 conventions: constructor injection, `@Transactional` boundaries.
- JaCoCo coverage ≥ 85% on new code.
- Ruff/Checkstyle-clean: no warnings.

## Docker Smoke Tests

```bash
docker compose up -d --build
python3 benchmarks/run_load_benchmark.py --base-url http://127.0.0.1:8101
docker compose down -v
```