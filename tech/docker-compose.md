# Docker Compose (`docker-compose.yml`)

## What it is

Docker Compose runs one or more containers from a description in a YAML file (`yaml.md`). Instead of typing a long `docker run ...` command with ports, variables and checks, you write them once in `docker-compose.yml` and run `docker compose up`. A **service** is one container definition in the file. Compose also creates a private network and names the containers `<project>-<service>-1`. (Docker itself: `docker.md`.)

## Why this repository uses it

The PostgreSQL adapter needs a real database. `make up` starts one with the right user, database name, port and health check, in one command, and `make down` removes it with its data. The file is the exact, repeatable description of that database.

## Where it appears

| Path | What it does with Compose |
| --- | --- |
| `docker-compose.yml` | Defines the single service `db` (PostgreSQL 18). |
| `Makefile` (`up`, `down`, `db`, `queries`, `psql`) | Run `docker compose up -d --wait`, `docker compose down -v`, `docker compose exec ...`. |
| `.env` (optional, not committed) | Can set `DATABASE_PASSWORD` and `POSTGRES_PORT`; Compose reads it for `${...}` values (`environment-variables.md`). |

## How it works underneath

`docker compose up -d --wait` reads the file, creates the network and the container, starts it in the background (`-d`, detached) and then **waits** (`--wait`) until the service's health check reports `healthy` (or fails). A **health check** is a command Compose runs inside the container every few seconds; exit code 0 means healthy.

`docker compose exec -T db psql ...` runs a command inside the *running* container named by the service. `docker compose down -v` stops and removes the containers and the network, and `-v` also removes the volumes (stored data). In this file no volume is declared, so the database lives only as long as the container.

Compose substitutes `${NAME:-default}` with the environment variable `NAME` or the default, **before** the container sees the file.

## Walkthrough of this repository's use

```yaml
# PostgreSQL for the JDBC adapter. Data lives only as long as the container: `make down` deletes it.
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_USER: seatholds
      POSTGRES_DB: seatholds
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD:-seatholds-local-only}
    ports:
      - "127.0.0.1:${POSTGRES_PORT:-55468}:5432"
    healthcheck:
      # TCP, not the socket: during first start the image runs a temporary server that listens only on
      # the socket, so a socket check can pass before the database exists.
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U seatholds -d seatholds"]
      interval: 2s
      timeout: 3s
      retries: 30
```

- `services:` begins the list of services. `db:` is this service's name (used by `docker compose exec db`).
- `image: postgres:18`: start from the official PostgreSQL image, server version 18.
- `environment:` sets variables **inside** the container. The image reads `POSTGRES_USER` and `POSTGRES_DB` to create the user and database `seatholds` on first start. `POSTGRES_PASSWORD: ${DATABASE_PASSWORD:-seatholds-local-only}` uses your `DATABASE_PASSWORD` if set, else a placeholder meant only for a throwaway local container.
- `ports: - "127.0.0.1:${POSTGRES_PORT:-55468}:5432"`: publish the container's port 5432 on your computer's **loopback** address only (`127.0.0.1`, reachable only from your own machine, not from the network) at port 55468 (or `POSTGRES_PORT`). The format is `host-address:host-port:container-port`.
- `healthcheck:` defines the check. The comment in the file explains the choice. `test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U seatholds -d seatholds"]` runs that command through a shell inside the container. `interval: 2s` runs it every 2 seconds, `timeout: 3s` gives each run 3 seconds, `retries: 30` allows 30 failures before the service is called unhealthy (about a minute). `-h 127.0.0.1` forces a TCP check, because the first-start temporary server listens only on a socket (`postgresql.md`).

Port 55468 is deliberately unusual so it does not clash with a Postgres you may already run on 5432 or with other projects.

## Try it yourself

1. `make up` starts it and loads the SQL files.
2. `docker compose ps` shows service `db`, status `Up ... (healthy)`.
3. `docker compose config` prints the file with all `${...}` filled in; run it as `DATABASE_PASSWORD=other docker compose config | grep PASSWORD` to see the substitution.
4. `docker compose logs db | tail -5` shows the server log.
5. `make down` removes the container; `docker compose ps -a` is then empty.

## Mistakes people make

- **A health check that passes too early.** Here it checks TCP (see above); a socket check let `make up` finish before the database existed.
- **Publishing the database on all interfaces** (`"55468:5432"`), which exposes it to the network. Here it is bound to `127.0.0.1`.
- **Real passwords in the file.** The default is a placeholder; real values come from the environment.
- **Leaving stacks running.** `make down` removes the container and data, and the shared-machine rule is to bring every stack down after a run.
- **Port clashes.** An unusual port and the `POSTGRES_PORT` variable avoid them.

## Check yourself

1. *What is a service in Compose?* One container definition in the file.
2. *What does `--wait` do?* Waits until the service's health check passes.
3. *What does `127.0.0.1:55468:5432` mean?* Publish container port 5432 on host port 55468, reachable only from this machine.
4. *What does `${DATABASE_PASSWORD:-seatholds-local-only}` mean?* The variable's value, or that placeholder if it is unset or empty.
5. *What does `docker compose down -v` remove?* The containers, the network and the volumes (the data).

## Further reading

- Compose file reference: <https://docs.docker.com/reference/compose-file/> (services, `healthcheck`, `ports`)
- `docker compose up`, `exec`, `down`: <https://docs.docker.com/reference/cli/docker/compose/>
