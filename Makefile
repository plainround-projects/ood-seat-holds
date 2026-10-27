# make up | run | run-jdbc | race | test | test-integration | lint | audit | check | down      (make help lists everything)
MVN := ./mvnw -B -q
MAIN := io.plainround.seatholds.interfaces.cli.Main
CLASSPATH := target/classes:target/lib/*
POSTGRES_PORT ?= 55468
PSQL := docker compose exec -T db psql -v ON_ERROR_STOP=1 -U seatholds seatholds

-include .env
export

.PHONY: help build up db queries psql run run-jdbc race serve health lint format test test-integration audit check docker-build down clean

help: ## list the targets
	@grep -E '^[a-z-]+:.*## ' $(MAKEFILE_LIST) | sed -E 's/:.*## /\t/' | sort

build:
	$(MVN) -DskipTests package

up: ## start PostgreSQL in a container and apply sql/001_schema.sql and sql/002_seed.sql
	docker compose up -d --wait
	$(MAKE) --no-print-directory db

db: ## apply the numbered SQL scripts in order (DDL, then the seed rows)
	$(PSQL) < sql/001_schema.sql
	$(PSQL) < sql/002_seed.sql

queries: ## run sql/003_queries.sql: seats per state, who holds what, expired holds
	$(PSQL) < sql/003_queries.sql

psql: ## open a psql session on the database
	docker compose exec db psql -U seatholds seatholds

run: build ## the lesson's scenarios on the in-memory store, and the failure they guard against
	java -cp "$(CLASSPATH)" $(MAIN) scenario

JDBC_ENV = STORE=jdbc DATABASE_URL=$${DATABASE_URL:-jdbc:postgresql://localhost:$(POSTGRES_PORT)/seatholds} DATABASE_USER=$${DATABASE_USER:-seatholds} DATABASE_PASSWORD=$${DATABASE_PASSWORD:-seatholds-local-only}

run-jdbc: build ## the same scenarios on PostgreSQL (make up first)
	env $(JDBC_ENV) java -cp "$(CLASSPATH)" $(MAIN) scenario

RUNS ?= 20
race: build ## race 1000 customers for 10 seats RUNS times (default 20); add STORE=jdbc after make up
	env $(if $(filter jdbc,$(STORE)),$(JDBC_ENV)) java -cp "$(CLASSPATH)" $(MAIN) race $(RUNS) 1000

serve: build ## sweep expired holds every SWEEP_INTERVAL until Ctrl-C, then stop cleanly
	java -cp "$(CLASSPATH)" $(MAIN) serve

health: build ## exit 0 when the configuration and the store are usable
	java -cp "$(CLASSPATH)" $(MAIN) health

lint: ## format check, compiler lint (-Xlint:all -Werror), checkstyle
	$(MVN) spotless:check
	$(MVN) -DskipTests compile checkstyle:check

format: ## apply google-java-format
	$(MVN) spotless:apply

test: ## unit, architecture and end-to-end tests with the domain and application coverage gate (no Docker needed)
	./mvnw -B verify

test-integration: ## unit tests plus the contract and race tests against PostgreSQL in a container (Docker needed)
	TESTCONTAINERS_RYUK_DISABLED=$${TESTCONTAINERS_RYUK_DISABLED:-true} ./mvnw -B -Pintegration verify

audit: ## check the resolved dependencies for known vulnerabilities (OSV database)
	$(MVN) dependency:list -DoutputFile=target/dependencies.txt -DappendOutput=false
	python3 scripts/audit.py

check: lint test ## everything that needs no Docker, except the audit

docker-build: ## build the container image
	docker build -t ood-seat-holds:dev .

down: ## stop PostgreSQL and delete its data
	docker compose down -v

clean: down
	$(MVN) clean
