# RGP Game Load Test

**English** · [Tiếng Việt](#hướng-dẫn-tiếng-việt)

A load-test harness for RGP slot-game backends, built on Gatling. It simulates hundreds to thousands of players using a game at the same time, measures response times, errors, CPU and memory, and writes a **PASS / FAIL verdict** that gates production deploys.

**One command does everything.** Run `./loadtest.sh` and answer a few questions, or call `./scripts/run-variant.sh` with flags. Either way the harness:

1. checks that the game server is up, and offers to start it if it isn't,
2. records the server's CPU / memory and health while the test runs,
3. runs the Gatling test,
4. checks the results against the pass/fail limits,
5. writes a one-page `summary.html` you open in a browser.

Direct `./gradlew :games:…` runs also exist, but they give no verdict. See [Advanced runs](#advanced-runs-without-the-wrapper).

New here? Go to [Quick start](#quick-start). A first smoke test takes a few minutes.

## Who reads what

| You are…                                   | Read these sections                                                                                                                                                                                                  |
|--------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **QC / PM / DevOps (non-technical)**       | [Setup](#setup) → [Quick start](#quick-start) → [Understanding outputs](#understanding-outputs) → [Troubleshooting](#troubleshooting). When a server has to be started: [Starting the game server automatically](#starting-the-game-server-automatically). |
| **Running tests by hand or from CI**       | Everything above, plus [Running tests](#running-tests) and [Advanced runs](#advanced-runs-without-the-wrapper).                                                                                                      |
| **Developer adding or changing a game**    | Everything above, plus [Configuration](#configuration), [Project layout](#project-layout), [gRPC runtimes](#grpc-runtimes) and [Add a new game](#add-a-new-game).                                                    |

More reading: [`HUONG-DAN.md`](HUONG-DAN.md) (Vietnamese beginner guide), [`naga777-load-test-guide.md`](naga777-load-test-guide.md) (Naga's Fortune 777: run it and read the results), and [Where to learn more](#where-to-learn-more).

Two words used everywhere below:

- **VU (virtual user)**: one simulated player.
- **SUT (system under test)**: the game server being tested.

## Games today

This table is the source of truth for the values every command below uses. The game servers ("backends") live in **separate repositories** next to this one. This repo does not build them.

| Game                | `GAME`        | Backend source folder (example)                  | Container                  | HTTP port | gRPC port | Health probe path                   | Simulations                        | Auto-start |
|---------------------|---------------|--------------------------------------------------|----------------------------|-----------|-----------|-------------------------------------|------------------------------------|------------|
| Silk Road Caravans  | `silkroad`    | `../be-silk-road-caravans`                       | `game-silk-road-caravans`  | `3000`    | —         | `/actuator/health`                  | `Soak`, `Stress`, `Spike`, `Basic` | Yes        |
| Golden Boat Bonanza | `bonanza`     | `../be-golden-boat-bonanza`                      | `game-golden-boat-bonanza` | `3005`    | `9091`    | `/golden/api/configs/bet-levels`    | `Soak`, `Basic`, `Grpc`            | **No**     |
| Naga's Fortune 777  | `naga777`     | `../Stable_NAGAS_777/stable-be-naga-fortune-777` | `stable-naga_fortune_777`  | `3000`    | `9096`    | `/health`                           | `Grpc`                             | Yes        |
| Mutant Merge        | `mutantmerge` | `../Stable_Mutant_Merge/be-mutant-merge`         | `stable-game-mutant-merge` | `3000`    | `9104`    | `/health`                           | `Grpc`                             | Yes        |
| Zero Day            | `zeroday`     | `../be-zero-day`                                 | `game-zero-day`            | `3000`    | `9103`    | `/api/game/zeroday/actuator/health` | `Grpc`                             | Yes        |

What the columns mean:

- **Backend source folder**: the folder that holds the game's `Dockerfile`. The paths are only examples; use wherever the repo sits on your machine.
- **Container**: the Docker container name the harness watches for CPU / memory. The auto-start compose files use exactly these names. If you start a server another way, check the real name with `docker ps`.
- **HTTP port**: the default when you don't pass `--port`. The health probe URL is `http://localhost:<HTTP port><health probe path>`.
- **gRPC port**: where the `Grpc` simulation connects (always on `localhost` when you use the wrapper). Silk Road has no gRPC test.
- **Auto-start**: "Yes" means `games/<game>/docker-compose.loadtest.yml` exists, so the harness can start the server for you. See [Starting the game server automatically](#starting-the-game-server-automatically). Bonanza has no such file, so you start it yourself.

`settings.gradle` also has four more games stubbed out (commented).

## Setup

**Requirements**

| Tool                                   | Why                                                                                                                  |
|----------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| **JDK 17**                             | Every simulation and the verifier. The build pins a Java 17 toolchain and no auto-download is set up, so JDK 17 must be installed locally. |
| **Docker** with **Compose v2 or newer** | Runs the game server. The scripts call `docker compose` (the plugin), not the old `docker-compose`.                  |
| **Python 3.9+**                        | Builds `summary.html` and the cross-run report.                                                                      |
| **git**                                | Only for the "download the source code from git" start-up option.                                                    |
| **curl**                               | Health probes. Pre-installed on macOS and most Linux systems.                                                        |
| **macOS or Linux**                     | Windows is untested. `loadtest.sh` is written for the stock macOS bash 3.2, so no newer bash is needed.              |

You don't need to install Gradle. `./gradlew` bundles Gradle 9.2.1 and downloads it on first use.

**Verify**

```bash
java -version            # 17.x
./gradlew --version      # Gradle 9.2.1
docker --version
docker compose version   # v2 or newer
python3 --version        # 3.9 or newer
git --version
curl --version
```

The first run also downloads the Gradle dependencies, so it takes longer than later runs.

## Quick start

### Easiest way the interactive menu

```bash
./loadtest.sh
```

The menu asks a few questions, shows you the exact command it is about to run, runs it, and offers to open the report. You never need to remember a flag. Most answers are a number, and yes/no questions take `y` or `n`. Enter picks the default shown in `[brackets]`. A wrong number asks again. Ctrl+D at a prompt quits.

**Screen 1: which game.** Each game shows ● if its container is running right now and ○ if it isn't. A server that runs outside Docker shows ○, but the test still works as long as its health probe answers.

**Screen 2: what kind of test.** Only the games with more than one test type ask this. Naga's Fortune 777, Mutant Merge and Zero Day have only `gRPC`, so the menu prints it and moves on.

| Menu text                                                               | Simulation |
|-------------------------------------------------------------------------|------------|
| `Soak    steady load for a while (finds slowdowns and memory leaks)`   | `Soak`     |
| `Stress  keep adding players until the server struggles`               | `Stress`   |
| `Spike   normal load with sudden rushes of players`                     | `Spike`    |
| `Basic   call one API many times`                                       | `Basic`    |
| `gRPC    steady load over gRPC, the way the real game client connects` | `Grpc`     |

**Screen 3: how big.** For every test type except `Basic`:

| Choice          | Players                  | Test length    | Warm-up                        | Pass limit                                  |
|-----------------|--------------------------|----------------|--------------------------------|---------------------------------------------|
| **Smoke**       | 10                       | 1 min          | none                           | `target`: CPU under 70 %, memory under 80 % |
| **Quick check** | 200                      | 5 min          | 1 min                          | `baseline`: CPU under 50 %, memory under 60 % |
| **Full test**   | 1000                     | 60 min         | 5 min (gRPC: 2 min)            | `target`: CPU under 70 %, memory under 80 % |
| **Custom**      | you choose               | you choose     | you choose                     | you choose                                  |

"Warm-up" means the players join gradually over that time. The **Full test** is the release check.

**Custom** asks, in this order:

1. `Number of players (virtual users) [1000]`
2. `Test length in minutes (not counting warm-up) [60]`
3. `Warm-up minutes (players join gradually) [5]`. For gRPC the default is `2`. `0` is allowed.
4. Soak only: `Let all players join at once instead of gradually? [y/N]`. Yes adds `--parallel`. It only has an effect for Silk Road (see [Wrapper flags](#wrapper-flags)).
5. `When does the test pass? The server's CPU and memory must stay:`, then a choice of four limits: `baseline` (CPU under 50 %, memory under 60 %), `target` (70 % / 80 %, the release standard), `stress` (85 % / 90 %) or `critical` (95 % / 95 %).
6. `Game server HTTP port [Enter = usual port for this game]`. Type a port only if the server runs on a non-default port.

**Basic** asks for something else instead of a size:

1. `Which API`: the endpoint to call (list in [Test one endpoint at a time](#test-one-endpoint-at-a-time)).
2. `Number of players (virtual users) [500]`
3. `Total number of requests [5000]`

A Basic run always uses the `target` limit. It ends when all requests are sent. The menu passes 1 minute and no warm-up, which only sets how long the CPU / health monitors may run.

> Stress and Spike follow their own built-in load shape. The size you pick doesn't fully apply to them. See [The simulations](#the-simulations).

**Screen 4: check before starting.** The menu shows what it will run, plus the equivalent `run-variant.sh` command after `Same as`. Copy that line if you want to repeat the test later without the menu, or run it in CI.

```text
== Check before starting ==
  Game         Silk Road Caravans (server container: game-silk-road-caravans)
  Test type    Soak
  Size         10 players for 1 min, no warm-up
  Pass limit   CPU under 70%, memory under 80%  (target: release standard)
  Same as      ./scripts/run-variant.sh --game silkroad --variant target --simulation Soak --users 10 --duration-minutes 1 --ramp-minutes 0 --container game-silk-road-caravans
Start now? [Y/n]:
```

Press Enter (or `y`) to start. Any other answer, for example `n`, prints `Cancelled — nothing was run.` and exits.

**While it runs.** If the game server isn't up, you are asked how to start it. See [Starting the game server automatically](#starting-the-game-server-automatically). Then the test runs, and the console shows Gatling's live counters ([Reading the console](#reading-the-console)).

**At the end.** If the run produced a new `summary.html`, the menu asks `Open the result report (summary.html) in your browser? [Y/n]`. It opens with `open` on macOS or `xdg-open` on Linux.

**A complete example**, a Silk Road smoke test:

```text
$ ./loadtest.sh
== Which game do you want to test? ==  (● server running  ○ server not running)
  1) ○ Silk Road Caravans
  2) ○ Golden Boat Bonanza
  3) ○ Naga's Fortune 777
  4) ○ Mutant Merge
  5) ● Zero Day
Game [1-5]: 1

== What kind of test? ==
  1) Soak    steady load for a while (finds slowdowns and memory leaks)
  2) Stress  keep adding players until the server struggles
  3) Spike   normal load with sudden rushes of players
  4) Basic   call one API many times
Test type [1-4]: 1

== How big should the test be? ==
  1) Smoke         10 players for 1 minute (just checks that it works)
  2) Quick check   200 players for 5 minutes
  3) Full test     1000 players for 60 minutes (the release check)
  4) Custom        choose the numbers yourself
Size [1-4]: 1
...
```

**Exit code.** `loadtest.sh` ends with the same exit code as `run-variant.sh`, which is the exit code of the Gatling run: `0` means every Gatling assertion passed. The release verdict (PASS / FAIL) is in `summary.html` and `verdict.json`, and the two can disagree (see [The simulations](#the-simulations)). If the server couldn't be started, the exit code is `1`.

**No terminal (CI, pipes).** The menu refuses to run and prints `loadtest.sh needs a terminal to ask questions. In CI, run scripts/run-variant.sh directly.` (exit code `1`).

### Manual way four steps

Pick your game's row in [Games today](#games-today) and export its values. Silk Road as an example:

```bash
export GAME=silkroad
export BACKEND_DIR=../be-silk-road-caravans
export CONTAINER=game-silk-road-caravans
export PORT=3000
```

**1. Start the game server.** You can skip this step for every game except Bonanza: if the server isn't up, step 3 offers to start it ([Starting the game server automatically](#starting-the-game-server-automatically)). To start it by hand with the load-test compose file:

```bash
cp games/$GAME/docker-compose.loadtest.yml "$BACKEND_DIR"/
(cd "$BACKEND_DIR" && HTTP_PORT=$PORT docker compose -f docker-compose.loadtest.yml up -d --build)
```

The first build can take a few minutes. For Bonanza, use the backend repo's own Docker set-up and make sure it answers on port `3005` (and `9091` for gRPC).

**2. Check that the server answers.** Use the health probe for your game. Any `2xx` code (normally `200`) means it's up. Anything else: wait a little and retry, or go to [Troubleshooting](#troubleshooting).

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/actuator/health                  # silkroad
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3005/golden/api/configs/bet-levels     # bonanza
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/health                            # naga777, mutantmerge
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/api/game/zeroday/actuator/health  # zeroday
```

**3. Run a 1-minute smoke test.** Use `--simulation Grpc` for naga777, mutantmerge and zeroday.

```bash
./scripts/run-variant.sh \
  --game "$GAME" \
  --variant target --simulation Soak \
  --users 10 --duration-minutes 1 --ramp-minutes 0 \
  --container "$CONTAINER" --port "$PORT"
```

**4. Open the summary.**

```bash
open "$(ls -td target/variants/$GAME/target-* | head -1)/summary.html"      # macOS (Linux: xdg-open)
```

A green **PASS** banner means the test cleared every limit.

## Starting the game server automatically

`run-variant.sh` (and so the menu) calls `scripts/ensure-sut.sh` before every test. When the game server is down, it can start it from the game's source code with `games/<game>/docker-compose.loadtest.yml`. That compose file runs the game plus its own Mongo and Redis (and RabbitMQ for Zero Day), with the real wallet switched off and no outside services.

**When it steps in.** Only when **both** of these are true:

- no **running** container has the game's container name, **and**
- the health probe URL doesn't answer with a `2xx` code.

A server that runs outside Docker is accepted as long as its health probe answers. A running container is accepted even if it is still booting. In that case the harness does not wait, so give a server you just started time to answer first.

**What you see.**

```text
[ensure-sut] The silkroad game server isn't running (no running container 'game-silk-road-caravans', and http://localhost:3000/actuator/health doesn't answer).
How do you want to start it?
  1) The game's source code is on this machine (you paste the folder path)
  2) Download the source code from git, branch main (you paste the git URL)
  q) Cancel
Choice [1/2/q]: 1
Folder with the game's source code: ~/Projects/be-silk-road-caravans
[ensure-sut] Starting the game server (the first time can take a few minutes): HTTP_PORT=3000 docker compose -f docker-compose.loadtest.yml up -d --build
[ensure-sut] Waiting for the server to answer http://localhost:3000/actuator/health (up to 300s)
[ensure-sut] Game server is up.
```

**Option 1: a folder on this machine.** Paste the path of the backend folder that contains the `Dockerfile` (see [Games today](#games-today)).

- You can drag the folder from Finder into the terminal. Escaped spaces (`\ `) are handled.
- Single and double quotes are removed, so a quoted path works.
- A leading `~` means your home folder.
- An absolute path is safest. A relative path is resolved from the folder you ran the command in.

**Option 2: download from git.** Paste the repository URL, for example `https://git.example.com/<group>/<backend-repo>.git`. The script clones branch **`main`** only, into a new temporary folder (`$TMPDIR/<game>-sut.XXXXXX`, or `/tmp/…` when `TMPDIR` isn't set). git uses your normal credentials (SSH key or credential helper). Each run of option 2 makes a fresh clone. The folder is not deleted afterwards, because the running stack is built from it. The repository's top folder must contain the `Dockerfile`. If the backend sits in a sub-folder of a bigger repository, use option 1 instead.

Any other answer (for example `q`) prints `Cancelled.` and stops the run.

**What happens next.**

1. The script checks that the folder exists and contains a `Dockerfile`.
2. It copies `games/<game>/docker-compose.loadtest.yml` into that folder. If a **different** file with that name is already there, the old one is first renamed to `docker-compose.loadtest.yml.bak`, and the script says so. An identical file is simply replaced.
3. It runs `HTTP_PORT=<port> docker compose -f docker-compose.loadtest.yml up -d --build` in that folder. `HTTP_PORT` is the wrapper's `--port` (default from [Games today](#games-today)), which becomes the game's port on your machine. The gRPC port is fixed per game.
4. It checks the health probe every 3 seconds for up to **300 seconds**. When it answers, you see `Game server is up.` and the test starts. If it doesn't answer in time, the script prints the container's last 50 log lines and stops with `the server didn't come up within 300s`.

The server keeps running after the test. Later tests reuse it and skip all of this.

**When it can't start the server.** The run stops with an `[ensure-sut] ERROR:` line in these cases (details in [Troubleshooting](#troubleshooting)):

| Situation                                      | What happens                                                                                                                              |
|------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| **Bonanza**                                    | It has no `docker-compose.loadtest.yml`, so the script tells you to start the server yourself and run again.                                   |
| **No terminal** (CI, pipes)                    | It can't ask you anything, so it prints the manual command and stops: `cp <repo>/games/<game>/docker-compose.loadtest.yml <source-dir>/ && cd <source-dir> && HTTP_PORT=<port> docker compose -f docker-compose.loadtest.yml up -d --build` |
| **Docker isn't running**                       | `Docker isn't running. Open Docker Desktop, wait until it's ready, then run again.`                                                        |
| **A port is already in use**                   | `docker compose` fails. Another game's stack most likely holds port 3000. Stop it (below), or run with `--port 3010` (menu: **Custom**, last question). |

**Stopping the server afterwards.** Each compose file gives its stack a fixed project name, so these commands work from any folder:

| Game          | Compose project name   |
|---------------|------------------------|
| `silkroad`    | `silkroad-loadtest`    |
| `naga777`     | `naga777-loadtest`     |
| `mutantmerge` | `mutantmerge-loadtest` |
| `zeroday`     | `zeroday-loadtest`     |

```bash
docker compose ls                               # which stacks are running
docker compose -p zeroday-loadtest stop         # pause it; `start` brings it back
docker compose -p zeroday-loadtest down         # remove its containers and network (images stay)
docker compose -p zeroday-loadtest down -v      # also delete its database volumes
```

All four stacks put the game on host port `3000` by default, so only one runs at a time unless you give the others a different `--port`.

## Running tests

`./scripts/run-variant.sh` is the one entry point for any run that needs a verdict. In order, it:

1. checks the game server and starts it if needed ([previous section](#starting-the-game-server-automatically)),
2. creates the output folder `target/variants/<game>/<variant>-<YYYYMMDD-HHMMSS>/`,
3. starts `monitor-resources.sh` (CPU / memory every 5 s) and `health-poll.sh` (health probe every 2 s) in the background, for the ramp plus the duration plus 2 minutes,
4. runs the Gradle task `:games:<game>:<simulation>` (the simulation name in lower case) and saves its output to `gatling.log`,
5. copies the newest Gatling HTML report into the bundle (if this run made no report, for example because Gatling failed to start, that is an older run's report),
6. runs `./gradlew verifyVariant`, which writes `verdict.json`,
7. runs `scripts/generate-summary-html.py`, which writes `summary.html`,
8. prints the file paths and exits with the Gatling run's exit code.

Ctrl+C stops the background monitors, prints the path of the partial results and exits with `130`. No verdict is written.

### Wrapper flags

```bash
./scripts/run-variant.sh \
  --game <silkroad|bonanza|naga777|mutantmerge|zeroday> \
  --variant <baseline|target|stress|critical> \
  --simulation <Soak|Stress|Spike|Basic|Grpc> \
  --container <name> \
  [--users N] [--duration-minutes N] [--ramp-minutes N] \
  [--port N] [--parallel] [--requests N] [--scenario <name>]
```

| Flag                 | Default                                     | Required | Notes                                                                                                                                                                                  |
|----------------------|---------------------------------------------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `--game`             | none                                        | Yes      | One of the five `GAME` ids. It can also come from the `GAME` environment variable, and the flag wins if both are set. There is no default: leaving it out stops with an error. A name with no `games/<name>/` folder stops with `ERROR: unknown game '<name>'`. |
| `--variant`          | none                                        | Yes      | Sets the CPU / memory limit: `baseline` 50 / 60 %, `target` 70 / 80 % (release gate), `stress` 85 / 90 %, `critical` 95 / 95 %. See [Configuration](#configuration). Only the verifier checks the name, **after** the test, so check the spelling. |
| `--simulation`       | none                                        | Yes      | Upper or lower case both work. It must be one the game supports ([Games today](#games-today)). If not, Gradle cannot find the task and the run fails.                                    |
| `--container`        | none                                        | Yes      | Used to detect a running server and to read `docker stats`. If no such container exists, CPU / memory come from the process listening on `--port`. If there is none either, the values are `N/A`. |
| `--users`            | `1000`                                      |          | Number of players (VUs).                                                                                                                                                               |
| `--duration-minutes` | `60`                                        |          | How long the full load is held, after the ramp.                                                                                                                                         |
| `--ramp-minutes`     | `5`                                         |          | Warm-up: time for players to join. The wrapper always sends this value, so the `Grpc` and Bonanza `Soak` simulations never use their own default of 2. The menu sends 2 for `Grpc` and 5 for every other test. To give Bonanza `Soak` its own ramp of 2, pass `--ramp-minutes 2`. |
| `--port`             | `3005` for `bonanza`, `3000` for all others |          | Game HTTP port. Used for the health probe URL, as the CPU / memory fallback, as `HTTP_PORT` when auto-starting, and passed to the REST simulations as `-Dport`. The `Grpc` simulations always use their own gRPC port. |
| `--parallel`         | off                                         |          | Soak only: all players join at once instead of ramping. It only works for **Silk Road**. Bonanza's `Soak` ignores it.                                                                    |
| `--requests`         | the simulation's default, `10000`           |          | Basic only: total number of requests across all VUs.                                                                                                                                   |
| `--scenario`         | the first endpoint (`spin` / `BetLevels`)   |          | Basic only: which endpoint or mode. See [Test one endpoint at a time](#test-one-endpoint-at-a-time).                                                                                     |

An unknown flag stops the run with `Unknown arg: <flag>`. A missing required flag stops it with `--variant required`, `--simulation required`, `--container required`, or `ERROR: --game <…> required (or set GAME env var)`.

The wrapper has no flag for the gRPC host / port, the pace, the `requestRate` / `eventCount` floors, the Stress / Spike shape or the per-game bet settings. Use [Advanced runs](#advanced-runs-without-the-wrapper) for those. The wrapper passes `-DgameName=<game>` to the verifier, so each game's own limits apply automatically (for example Silk Road's 300 ms mean and Bonanza's 0.99 % error rate).

### The simulations

Two layers score every run, and they can disagree:

- **Gatling assertions** are checked inside the test. They decide Gatling's exit code, which is also the exit code of `run-variant.sh` and `loadtest.sh`. A failed assertion prints `BUILD FAILED`.
- **The verdict** is the release gate. It is written after the test to `verdict.json` and `summary.html`, and covers the six rows in [The six threshold rows](#the-six-threshold-rows).

The verdict uses the same six rows for every simulation. The last column below lists Gatling assertions only. The extra ones (for example the 50 req/s and 100 000-request floors) change only the exit code, never the verdict.

| Simulation | Games                                   | What it does                                                                                                                                                                                                                                            | When to use                                            | Gatling assertions (exit code only)                                                                                                                         |
|------------|-----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Soak`     | silkroad, bonanza                       | Silk Road: players join over `--ramp-minutes` (or all at once with `--parallel`). Each one then plays for `--duration-minutes`. Bonanza: the number of players rises to `--users` during the ramp and stays there for the duration. Each Bonanza player creates a session, then loops through the game with a 5 s pace. | Release gate. Finds slowdowns and memory leaks.        | Mean response ≤ PR-4 limit, error % ≤ PR-5 limit. Bonanza also needs: ≥ 50 req/s overall, > 100 000 successful requests, `Spin` p95 ≤ 800 ms, `Spin` and `CreateSession` errors ≤ 0.5 %. |
| `Stress`   | silkroad                                | New players arrive at a rate rising from 500 to 2500 per minute across `--duration-minutes`. Each one then plays for `--duration-minutes`, so the run lasts up to 5 minutes longer. `--users` and `--ramp-minutes` don't change it.                    | Finding the breaking point.                            | None. Measurement only.                                                                                                                                  |
| `Spike`    | silkroad                                | For 25 minutes, 200 new players arrive per minute, plus 5 bursts of 1500 players at once (about every 4.5 minutes). Each player plays for 25 minutes, and the run stops at its 30-minute cap. `--users`, `--duration-minutes` and `--ramp-minutes` don't change it. | Checking recovery from sudden rushes.                  | None. Measurement only.                                                                                                                                  |
| `Basic`    | silkroad, bonanza                       | `--users` VUs start at once and send `--requests` requests in total to one endpoint (or a mode).                                                                                                                                                        | Smoke-testing a single endpoint.                       | Zero failed requests.                                                                                                                                       |
| `Grpc`     | bonanza, naga777, mutantmerge, zeroday  | The number of players rises to `--users` during the ramp and stays there for the duration. Each player joins over gRPC (`ConnectAndCall`), then spins (`Call`) once per pace interval. This is the path the real game client uses.                      | Release gate for gRPC games.                           | Mean response ≤ PR-4 limit, error % ≤ PR-5 limit, > 50 req/s overall, > 100 000 successful requests, `Spin` p95 ≤ 800 ms, `Spin` and `Join` errors ≤ 0.5 %. |

> **Small runs of `Grpc` and Bonanza `Soak` always end with `BUILD FAILED` and a non-zero exit code**, even on a perfect server. They must reach 50 req/s and more than 100 000 successful requests, which only a full-size run can do. A 10-player smoke test produces about 2 requests per second. Read `verdict.json` / `summary.html` in that case: these floors are not verdict rows, so the verdict can still be PASS. To lower these limits, use [Advanced runs](#advanced-runs-without-the-wrapper) (`-DrequestRate=… -DeventCount=…`).
>
> **Stress and Spike run longer than the size you pick**: Stress takes up to `--duration-minutes` + 5 minutes, and Spike about 30 minutes. The CPU / health monitors stop after the ramp plus the duration plus 2 minutes, so they can miss the end. For Spike, pass `--duration-minutes 30 --ramp-minutes 0`. For Stress, pass `--ramp-minutes 4`. Spike ignores both values and Stress ignores the ramp, so they only make the monitors cover the whole run, with a minute to spare for Gradle start-up. In the menu, enter them under **Custom**. To change the Stress or Spike shape, use [Advanced runs](#advanced-runs-without-the-wrapper).
>
> **`Grpc` runs on a different Gatling version** (3.9.5 plus a community gRPC plugin) than the REST tests (3.15). See [gRPC runtimes](#grpc-runtimes).

**`Grpc` defaults per game.** These are the simulation's own defaults. Through the wrapper, `--users`, `--duration-minutes` and `--ramp-minutes` always replace the first three.

| Game          | `users` | `durationMinutes` | `rampMinutes` | `paceSec` | `requestRate` (req/s floor) | `eventCount` (success floor) | `grpcHost` | `grpcPort` | Game-only settings (Gradle only)           |
|---------------|---------|-------------------|---------------|-----------|-----------------------------|------------------------------|------------|------------|--------------------------------------------|
| `bonanza`     | 1000    | 60                | 2             | 5         | 50                          | 100000                       | localhost  | 9091       | none                                       |
| `naga777`     | 1000    | 60                | 2             | 5         | 50                          | 100000                       | localhost  | 9096       | `coinValue=5`, `coinPerLine=3`             |
| `mutantmerge` | 1000    | 60                | 2             | 5         | 50                          | 100000                       | localhost  | 9104       | `betLevelId=3`, `superBet=false`           |
| `zeroday`     | 1000    | 60                | 2             | 5         | 50                          | 100000                       | localhost  | 9103       | `bet=1.00`                                 |

Bonanza's REST `Soak` uses the same `rampMinutes=2`, `paceSec=5`, `requestRate=50` and `eventCount=100000` defaults.

### Standard examples

These are the same commands the menu builds. **Smoke** is 10 players for 1 minute. The **production gate** is 1000 players for 60 minutes.

```bash
# Silk Road — smoke / production gate
./scripts/run-variant.sh --game silkroad --variant target --simulation Soak \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container game-silk-road-caravans
./scripts/run-variant.sh --game silkroad --variant target --simulation Soak \
  --users 1000 --duration-minutes 60 --ramp-minutes 5 --container game-silk-road-caravans

# Golden Boat Bonanza — smoke / REST production gate / gRPC production gate (port 3005 is automatic)
./scripts/run-variant.sh --game bonanza --variant target --simulation Soak \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container game-golden-boat-bonanza
./scripts/run-variant.sh --game bonanza --variant target --simulation Soak \
  --users 1000 --duration-minutes 60 --ramp-minutes 5 --container game-golden-boat-bonanza
./scripts/run-variant.sh --game bonanza --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 --container game-golden-boat-bonanza

# Naga's Fortune 777 — smoke / production gate
./scripts/run-variant.sh --game naga777 --variant target --simulation Grpc \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container stable-naga_fortune_777
./scripts/run-variant.sh --game naga777 --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 --container stable-naga_fortune_777

# Mutant Merge — smoke / production gate
./scripts/run-variant.sh --game mutantmerge --variant target --simulation Grpc \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container stable-game-mutant-merge
./scripts/run-variant.sh --game mutantmerge --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 --container stable-game-mutant-merge

# Zero Day — smoke / production gate
./scripts/run-variant.sh --game zeroday --variant target --simulation Grpc \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container game-zero-day
./scripts/run-variant.sh --game zeroday --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 --container game-zero-day
```

For the menu's **Quick check**, use `--variant baseline --users 200 --duration-minutes 5 --ramp-minutes 1`.

### Test one endpoint at a time

Use `--simulation Basic --scenario <name>` to hit a single endpoint instead of the full player journey. Only Silk Road and Bonanza have REST endpoints to test.

| Game       | Endpoints (`--scenario`)                                                               | Modes                   |
|------------|----------------------------------------------------------------------------------------|-------------------------|
| `silkroad` | `spin`, `last-spin`, `history-summary`                                                 | `all`, `chain`, `burst` |
| `bonanza`  | `BetLevels`, `ReelStrips`, `CreateSession`, `Spin`, `JackpotPools`, `HistorySessions`  | `all`, `chain`, `burst` |

- **One endpoint**: `--users` VUs start at once, and each sends `--requests ÷ --users` requests (at least 1).
- **`all`**: the same, for every endpoint at the same time.
- **`chain`**: each VU calls every endpoint in order, repeated until the request budget is used.
- **`burst`**: `--users` VUs per endpoint, all at once, one request each.

The names are case-sensitive. A wrong name fails with `Unknown scenario: '<name>'. Valid: …`. The menu lists Silk Road's endpoints and modes, and Bonanza's endpoints. Bonanza's modes work through the wrapper too.

Bonanza's stateful endpoints (`BonusStart`, `BonusReveal`, `HistoryRounds`, `RoundDetail`) can't be tested alone, because they need state from an earlier session or spin. They run inside `Soak`.

**Template.** It assumes the Quick start exports. Otherwise pass literal values.

```bash
./scripts/run-variant.sh \
  --game "$GAME" \
  --variant target --simulation Basic --scenario <NAME> \
  --users <USERS> --requests <REQUESTS> \
  --duration-minutes 1 --ramp-minutes 0 \
  --container "$CONTAINER" --port "$PORT"
```

**Suggested load per endpoint**

| Game       | `--scenario`      | `--users` | `--requests` | Notes                                       |
|------------|-------------------|-----------|--------------|---------------------------------------------|
| `silkroad` | `spin`            | 500       | 5 000        | Main game action.                           |
| `silkroad` | `last-spin`       | 500       | 5 000        | Read-only.                                  |
| `silkroad` | `history-summary` | 500       | 5 000        | Read-only.                                  |
| `bonanza`  | `BetLevels`       | 1 000     | 10 000       | Static config, cheap.                       |
| `bonanza`  | `ReelStrips`      | 1 000     | 10 000       | Static config, cheap.                       |
| `bonanza`  | `CreateSession`   | 500       | 2 000        | Heavier: one session is created per request. |
| `bonanza`  | `Spin`            | 500       | 5 000        | Returns HTTP 201, not 200.                  |
| `bonanza`  | `JackpotPools`    | 1 000     | 10 000       | Read-only.                                  |
| `bonanza`  | `HistorySessions` | 500       | 5 000        | Read-only.                                  |

### Game-specific checks

**Silk Road.** A REST-only game. The load-test compose file turns off the real wallet and cheats, and replaces the ZMQ publisher with a mock, so no outside services are needed.

**Golden Boat Bonanza.** You must start it yourself (no auto-start). REST calls go to port `3005` under `/golden`, and `Spin` returns **201**. The `Grpc` test needs gRPC port `9091` reachable on `localhost`.

**Naga's Fortune 777.** gRPC only: over HTTP the backend offers nothing but its health check. The spin time measured is the gRPC acknowledgement, because the full spin result is pushed over ZMQ. The server derives the bet as `coinValue × coinPerLine × 5`, which is 75 by default. More detail in [`naga777-load-test-guide.md`](naga777-load-test-guide.md).

**Mutant Merge.** gRPC only. The test reads each spin reply and counts a non-zero business code as a failed request, so business errors show up as `KO` in Gatling.

**Zero Day.** gRPC only. A spin returns an **empty** reply: the result and any business error go out over ZMQ, and Gatling only sees transport failures. After every run, check the backend log. This must print `0`:

```bash
docker logs game-zero-day 2>&1 | grep -E "\[gRPC\] (ConnectAndCall|Call) (business )?error" | grep -vc "c=1362"
```

`c=1362` (jackpot pending) rejections are expected now and then, so the command leaves them out. `docker logs` covers everything since the container started, so run the check on a fresh container or compare the count before and after. The load-test compose file already sets `LUIGI_WALLET_ENABLED=false`, `CHEAT_ENABLED=false` and the `json-file` logging driver, which `docker logs` needs. If you start Zero Day another way, set these too.

**Bet settings are Gradle-only.** The wrapper can't change them. Use [Advanced runs](#advanced-runs-without-the-wrapper) with:

- naga777: `-DcoinValue=` (one of 1, 5, 20, 50, 100, 200, 500) and `-DcoinPerLine=` (1–10),
- mutantmerge: `-DbetLevelId=` (1-based step on the bet ladder, `3` = $1.00) and `-DsuperBet=true`,
- zeroday: `-Dbet=` (a value on the ladder from `0.20` to `100.00`; any other value stops the test at start).

## Understanding outputs

### Where the results go

| Run via                         | Output                                                                                                         |
|---------------------------------|----------------------------------------------------------------------------------------------------------------|
| `./loadtest.sh` or `./scripts/run-variant.sh` | `target/variants/<game>/<variant>-<YYYYMMDD-HHMMSS>/`: the full bundle below.                    |
| `./gradlew :games:…` directly   | `games/<game>/build/reports/gatling/<simulation class, lower case>-<timestamp>/`: Gatling HTML only, no verdict. |

The `target/` folder is not committed to git.

| File in the bundle          | What it is                                                                 | Open it when                                   |
|-----------------------------|----------------------------------------------------------------------------|------------------------------------------------|
| `summary.html`              | One-page verdict, limits, CPU / memory charts and the embedded Gatling report. | **Always start here.**                     |
| `verdict.json`              | The same numbers as `summary.html`, machine-readable.                      | Automating decisions (CI, dashboards).         |
| `gatling-report/index.html` | Gatling's full HTML report (per-request percentiles, errors).              | Drilling into one endpoint or error.           |
| `resource.csv`              | CPU / memory samples, one row every 5 s.                                   | Plotting CPU / memory over time.               |
| `health.csv`                | Health probe samples, one row every 2 s.                                   | Finding downtime windows.                      |
| `gatling.log`               | Everything Gradle and Gatling printed.                                     | Debugging a failed or odd run.                 |

### The summary page

From top to bottom:

1. **Verdict banner**: a big green `PASS` or red `FAIL`, with game, variant and timestamp. On the right: players, duration in seconds (how long the Gatling step took, including Gradle start-up) and the host's CPU core count.
2. **Run Info**: the same facts as a table.
3. **Threshold Check**: the six rows below. A note under the table names the YAML file the limits came from.
4. **HTTP Requests Summary (from Gatling)**: total, OK and KO request counts, plus the Gatling exit code. For gRPC games these count gRPC calls.
5. **CPU % over time**: a line chart with average and maximum. CPU is divided by the host's core count. The red dashed line is the limit.
6. **Memory % over time**: the same, for memory.
7. **Gatling HTTP Report (Embedded)**: the full Gatling report. **Fullscreen** enlarges it, and Esc or the round × button closes it.

### The six threshold rows

**The verdict is PASS only when the first five rows all pass.** The sixth is for information only.

| Row in `summary.html`           | `verdict.json` fields                                                                              | Passes when                                                                                                                                  | Default limit                            |
|---------------------------------|----------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------|
| **PR-4: Mean response**         | `mean_response_ms`, `mean_ms_ceil`, `pr4_pass`                                                     | Gatling's overall mean response time ≤ the limit. If Gatling printed no mean (very short runs), the row passes and the Gatling assertion decides. | 500 ms (silkroad: 300 ms)               |
| **PR-5: Error rate (KO%)**      | `http_ko_percent`, `http_ko_ceil`, `pr5_pass` (`http_pass` is an old alias)                        | Failed requests % ≤ the limit. If the log has no request counts, it falls back to "Gatling exit code is 0".                                  | 1.0 % (bonanza: 0.99 %)                  |
| **CPU p95**                     | `cpu_p95`, `cpu_ceil`, `cpu_pass`                                                                  | 95th percentile of CPU % (divided by host cores) ≤ the variant limit. **No samples means FAIL.**                                             | 70 % at `target`                         |
| **Mem p95**                     | `mem_p95`, `mem_ceil`, `mem_pass`                                                                  | 95th percentile of memory % ≤ the variant limit. **No samples means FAIL.**                                                                  | 80 % at `target`                         |
| **PR-3: Health probe failures** | `health_failures`, `max_consecutive_failures`, `crash_max_consecutive_ceil`, `crash_pass`          | The longest run of failed probes in a row is below the limit. 3 failures in a row (about 6 s down) is a crash.                               | fewer than 3 in a row                    |
| Response time p95               | `p95_response_ms`                                                                                  | Information only. Not a gate.                                                                                                                | —                                        |

The CPU / memory limits per variant are `baseline` 50 / 60, `target` 70 / 80, `stress` 85 / 90 and `critical` 95 / 95. To change any limit without touching code, see [Configuration](#configuration).

Other `verdict.json` fields: `variant`, `game_name`, `users`, `duration_sec`, `http_total`, `http_ok`, `http_ko`, `gatling_exit_code`, `host_cores`, `sla_config_source` (which YAML supplied the limits) and `verdict` (`PASS` / `FAIL`).

> **CPU normalisation.** `docker stats` adds CPU up across cores (600 % on a 12-core machine means 6 cores busy). The verifier divides by the host's core count first: 600 % / 12 = 50 %.

### When a row fails

| Failing row             | First file to open          | Look for                                                                                    |
|-------------------------|-----------------------------|---------------------------------------------------------------------------------------------|
| `pr4_pass` (slow)       | `gatling-report/index.html` | The statistics table: which request has a high mean or p95.                                 |
| `pr5_pass` (errors)     | `gatling-report/index.html` | The errors table: the error message and the request it came from.                           |
| `cpu_pass` / `mem_pass` | `resource.csv`              | Plot `cpu_pct` / `mem_pct` against `timestamp`. Spikes usually line up with the slow window. All `N/A` means the container wasn't found. |
| `crash_pass`            | `health.csv`                | Rows with `http_status` `000` or not `2xx`, or `total_seconds` ≥ 5. Three in a row is a crash. Then check `docker logs <container>`. |

### CSV columns

`resource.csv` has the header `timestamp,cpu_pct,mem_pct,mem_used,net_io,block_io` and one row every 5 s. Timestamps are UTC.

- `cpu_pct`: container CPU added up across cores. Divide by the host core count to compare with the limit.
- `mem_pct`: memory as a % of the container's memory limit. The load-test compose files cap each game at 768 MB. A container with no limit is measured against all the memory Docker can use. `mem_used` is human-readable, for example `448.3MiB / 768MiB`.
- `net_io`, `block_io`: network and disk I/O from `docker stats`.
- `N/A` rows: the container wasn't found at that moment. When the container doesn't exist, the monitor falls back to the process listening on `--port`. In that mode `mem_pct` is a % of host memory, and `net_io` / `block_io` are `N/A`.

`health.csv` has the header `timestamp,http_status,total_seconds` and one row every 2 s. Timestamps are UTC.

- `http_status` `000` means no answer (network error or time-out). The probe gives up after 5 s.
- A row counts as a failure if `http_status` isn't `2xx` **or** `total_seconds` is 5.0 or more.
- After 3 failures in a row, the prober prints `CRASH DETECTED` and stops, so the file ends there.

### The Gatling report

Open `gatling-report/index.html` directly, or use the embedded copy in `summary.html`.

| Part                                         | Use it for                                                                            |
|----------------------------------------------|---------------------------------------------------------------------------------------|
| **Global** tab                               | The whole run.                                                                        |
| Assertions                                   | Each Gatling assertion and whether it passed. Explains a non-zero exit code.         |
| Stats                                        | One row per request type: count, OK / KO, min / mean / percentiles / max response time. |
| Errors (only shown when something failed)    | Each error message with its count. The fastest way to diagnose `KO`.                  |
| Response Time Ranges                         | How many requests were fast, slower or failed.                                        |
| Active Users along the Simulation            | Confirms the load shape was what you expected.                                        |
| Response Time Percentiles over Time (OK)     | A steady climb suggests a leak. Short spikes suggest pauses such as GC.               |
| Number of requests / responses per second    | Throughput over time.                                                                 |
| **Details** tab                              | The same charts for one request type (for example `Spin`).                            |

### Reading the console

About every 5 seconds while Gatling runs, it prints counters. This is a gRPC run:

```text
> Global                                                   (OK=30     KO=0     )
> Join                                                     (OK=10     KO=0     )
> Spin                                                     (OK=20     KO=0     )
```

`OK` means the request succeeded. `KO` means it failed (time-out, error status or a failed check). **`KO=0`** is what you want.

At the end, Gatling prints a summary block (`> request count …`, `> mean response time …`, `> response time 95th percentile …`) followed by one line per assertion that ends in `true` or `false`. The REST tests (Gatling 3.15) print that block as a table with `|` columns, and the gRPC tests (Gatling 3.9.5) as `> request count  140 (OK=140  KO=0 )`. The verifier reads the request count, the mean and the 95th percentile from either form. After that come `[run-variant] Verdict:` and the `verdict.json` content, then the list of result files.

### Cross-run compliance report

`scripts/generate-final-report.py` combines several runs of one game into a Markdown compliance report. The wrapper does not run it. Run it yourself once the runs you need exist:

```bash
python3 scripts/generate-final-report.py \
  --variants-dir target/variants/<game> \
  --report-out target/variants/<game>/final-report.md \
  [--host localhost] [--port 3000]
```

- For each variant (`baseline`, `target`, `stress`, `critical`) it takes the **newest** run folder, whatever simulation that run was. A variant never run shows as `N/A`.
- It fills a PR-1 to PR-7 table from the `target` run: at least 1000 players, at least 60 minutes, no health gap of 5 s or more, mean ≤ 500 ms, errors ≤ 1 %, and the CPU / memory results from `verdict.json`. The final verdict counts every row except PR-2 (duration). These limits are fixed in the script; the per-game YAML limits don't apply.
- It adds a CPU / memory table for all four variants, plus latency percentiles and a resource summary for `target`. The CPU % in that resource summary is the raw `docker stats` value, not divided by the core count.
- The latency and error rows need `gatling-report/js/stats.json`, and only the gRPC tests (Gatling 3.9.5) write that file. For REST runs (Silk Road, Bonanza `Soak` / `Basic`) those rows are `N/A`, so the final verdict reads FAIL. Use `summary.html` for those games.
- `--host` / `--port` only fill the "SUT" line of the report.

## Troubleshooting

| Symptom                                                                                          | Likely cause and fix                                                                                                                                                                                                                                            |
|--------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `loadtest.sh needs a terminal to ask questions. In CI, run scripts/run-variant.sh directly.`     | The menu was run without a terminal (CI, a pipe, a script). Use the `Same as` command from a manual menu run, or build one from [Standard examples](#standard-examples).                                                                                        |
| Menu shows ○ although the server is up                                                           | The server runs outside Docker, or under a different container name. The test still runs if the health probe answers, but CPU / memory may be `N/A`. Pass the real name with `--container` (manual run).                                                        |
| `[ensure-sut] ERROR: Docker isn't running. …`                                                    | Open Docker Desktop (or start the Docker service), wait until it is ready, and run again.                                                                                                                                                                       |
| `[ensure-sut] ERROR: bonanza can't be started automatically …`                                   | Bonanza has no load-test compose file. Start it from its own repo so that it answers on `3005`, then run again.                                                                                                                                                  |
| `[ensure-sut] ERROR: can't ask where the source code is (no terminal). …`                        | Auto-start needs a terminal. Start the server with the command printed under the error (see [Manual way four steps](#manual-way-four-steps)), then run again.                                                                                                   |
| `[ensure-sut] ERROR: folder not found: …` or `no Dockerfile in …`                                | Wrong folder. Paste the backend's top folder, the one with the `Dockerfile` (see [Games today](#games-today)).                                                                                                                                                  |
| `[ensure-sut] ERROR: couldn't download the source code (see the git error above).`               | Check the URL, your git access (SSH key or credentials), and that the repo has a `main` branch.                                                                                                                                                                 |
| `[ensure-sut] ERROR: couldn't start the game server …` with `port is already allocated`          | Another stack holds the port, usually another game on `3000`. Find it with `docker ps --format '{{.Names}}\t{{.Ports}}'`, stop it with `docker compose -p <project> stop` ([project names](#starting-the-game-server-automatically)), or use another `--port`. |
| `[ensure-sut] ERROR: couldn't start the game server …` with `container name … is already in use` | A container with that name exists from another set-up, for example the backend's own compose. Stop and remove that container, then run again.                                                                                                                  |
| `[ensure-sut] ERROR: couldn't start the game server …` with `env file … .env.staging not found`  | The Silk Road, Naga's Fortune 777 and Zero Day compose files read `.env.staging` from the backend folder. Make sure that file is there.                                                                                                                         |
| `[ensure-sut] ERROR: the server didn't come up within 300s. …`                                   | Read the 50 log lines printed above the error. Often Mongo / Redis are still starting, or the app failed at boot. Fix it, then run again. The next run reuses the running container.                                                                            |
| `Connection refused`, or the curl health check fails                                             | The server isn't up. Run `docker ps --filter name=<container>` and `docker logs <container> 2>&1 \| tail -50`. Right after start-up, wait and retry.                                                                                                            |
| `health.csv` shows only `000` or 5-second rows                                                   | The probe URL doesn't answer `2xx`. The wrapper uses `http://localhost:<port>` plus: silkroad `/actuator/health`, bonanza `/golden/api/configs/bet-levels`, naga777 and mutantmerge `/health`, zeroday `/api/game/zeroday/actuator/health`. Check that the server is on that port and path, or pass `--port`. |
| `resource.csv` is all `N/A`, and CPU / Mem rows FAIL                                              | No container has the name passed to `--container`, and nothing listens on `--port`. Check with `docker ps`. Without samples, the CPU and memory rows fail.                                                                                                      |
| `BUILD FAILED` and a non-zero exit code, but `verdict.json` says PASS                            | Gatling assertions and the verdict are separate. For small `Grpc` or Bonanza `Soak` runs, the 50 req/s and 100 000-request floors can't be reached. See [The simulations](#the-simulations).                                                                  |
| Gradle can't find task `:games:<game>:<simulation>`                                              | That game doesn't have that simulation. Check [Games today](#games-today).                                                                                                                                                                                    |
| `Unknown variant: …`, then an empty `verdict.json` and no `summary.html`                         | `--variant` must be `baseline`, `target`, `stress` or `critical`. The verifier checks it only after the test, so the test itself did run.                                                                                                                      |
| `Unknown arg: …`, or `… required`                                                                | A mistyped flag, or a missing `--game` / `--variant` / `--simulation` / `--container`. See [Wrapper flags](#wrapper-flags).                                                                                                                                    |
| `Unknown scenario: '…'. Valid: …`                                                                | Wrong `--scenario` for `Basic`. Names are case-sensitive. See [Test one endpoint at a time](#test-one-endpoint-at-a-time).                                                                                                                                    |
| A Spike "smoke" ran for about 30 minutes, or a Stress run took up to 5 minutes longer          | These two follow their own built-in shape, whatever size you pick. See [The simulations](#the-simulations).                                                                                                                                                      |
| `summary.html` missing after a run                                                               | Python 3 isn't installed, `generate-summary-html.py` failed, or `verdict.json` is empty (for example an unknown variant). When `verdict.json` has content, it is still the authoritative result.                                                                |
| Test runs, but about half the requests return HTTP 400                                           | A body template uses `${userId}` instead of Gatling EL `#{userId}`, so every VU sends the same literal value. Find them with `grep -r '\${' games/<game>/src/gatling/resources/`.                                                                               |
| `simulation class not found`                                                                     | A class name in the `SIMULATIONS` map in `games/<game>/build.gradle` doesn't match the Java / Scala file's `package`. Check it with `grep "^package" …`.                                                                                                        |
| `InaccessibleObjectException` when the simulation starts                                         | Not running on JDK 17. Check that `java -version` shows 17. The needed `--add-opens` flags are already set in each game's `build.gradle`.                                                                                                                      |
| A gRPC test needs a non-default host or port                                                     | The wrapper has no gRPC host / port flags. Run through Gradle with `-DgrpcHost=… -DgrpcPort=…` (no `summary.html` / `verdict.json`). See [Advanced runs](#advanced-runs-without-the-wrapper).                                                                  |

## Configuration

Three things decide how a run behaves, and none of them needs a code change:

| What                          | Where                                                                 | Read by                                              |
|-------------------------------|-----------------------------------------------------------------------|------------------------------------------------------|
| Pass/fail thresholds          | `config/sla-thresholds.yml` + per-game `sla-thresholds.yml`           | `SlaConfigLoader` (Gatling assertions and the verifier) |
| Where to send load, how much  | `core/src/main/resources/load-test-defaults.yml` + per-game `game.yml` | `LoadTestConfigLoader` (Java REST simulations only)  |
| One-off overrides             | `-D<name>=<value>` on a `./gradlew` command                            | The simulation JVM, when the name gets through (see [Gradle system properties](#gradle-system-properties)) |

### Variant ceilings

`--variant` picks the CPU and memory ceilings the verifier applies to the p95 of `resource.csv`.

| Variant    | CPU% p95 ceiling | Mem% p95 ceiling | When to use                                  |
|------------|------------------|------------------|----------------------------------------------|
| `baseline` | 50               | 60               | Headroom check: the server has plenty of room. |
| `target`   | 70               | 80               | **Production gate**, the default for deploys. |
| `stress`   | 85               | 90               | Saturation-behaviour study.                  |
| `critical` | 95               | 95               | Pre-failure validation.                      |

The ceilings live under `variants:` in the SLA YAML (next section). CPU is divided by the host core count before the comparison. The verifier looks the variant name up in the merged YAML, so a new key under `variants:` works with `--variant <name>`. An unknown name makes the verifier exit with `Unknown variant: …`. The menu (`loadtest.sh`) and `generate-final-report.py` only know the four names above.

### SLA thresholds and load order

**Fields.** Each layer lists only the fields it changes.

| YAML key                           | Meaning                                                            | Core value | Used by |
|------------------------------------|--------------------------------------------------------------------|------------|---------|
| `sla.pr4_mean_response_ms_max`     | PR-4: ceiling on the global mean response time (ms).               | `500`      | Gatling assertions in the Soak and gRPC simulations; verifier `pr4_pass`. |
| `sla.pr5_error_percent_max`        | PR-5: ceiling on the KO percentage.                                | `1.0`      | Gatling assertions in the Soak and gRPC simulations; verifier `pr5_pass`. |
| `sla.max_duration_buffer_min`      | Minutes added to Gatling's `maxDuration` so VUs aren't cut off at the last second. | `2` | Soak and gRPC simulations. Stress and Spike use a fixed 5 minutes. |
| `crash.max_consecutive_failures`   | PR-3: this many failed health probes in a row count as a crash.     | `3`        | Verifier `crash_pass`: it fails when the longest failure streak reaches this value. |
| `variants.<name>.cpu_ceil` / `mem_ceil` | p95 ceilings per variant (see above).                          | see table  | Verifier `cpu_pass` / `mem_pass`. |

`Basic` simulations only assert zero KO (plus `-DmaxResponseTimeMs` if set). `Stress` and `Spike` have no assertions.

**Per-game overrides** live in `games/<game>/src/gatling/resources/sla-thresholds.yml`:

| Game                               | Override                                                   |
|------------------------------------|------------------------------------------------------------|
| silkroad                           | `pr4_mean_response_ms_max: 300`                            |
| bonanza                            | `pr4_mean_response_ms_max: 500`, `pr5_error_percent_max: 0.99` |
| naga777, mutantmerge, zeroday      | Comments only, so the core values apply.                   |

**Load order.** `SlaConfigLoader` builds the config from the bottom up. Each layer overlays its fields on the result so far (the last one applied wins):

1. `SlaConfig.defaults()`: hardcoded values, the same as the core YAML.
2. `classpath:sla-thresholds.yml`: the first `sla-thresholds.yml` on the JVM classpath. `:core` bundles one from `core/src/main/resources/`. If that first file is empty or comments-only, the layer is skipped; the loader doesn't look for another copy further down the classpath.
3. `config/sla-thresholds.yml`: the repo-wide source of truth.
4. `games/<gameName>/src/gatling/resources/sla-thresholds.yml`: loaded only when `-DgameName` is set. Every alias task sets it to the Gradle project name, and the wrapper passes `-DgameName=<game>` to the verifier.

`-DslaConfig=/abs/path.yml` short-circuits the whole stack. If the file is readable, it is merged onto the hardcoded defaults only, and layers 2 to 4 are ignored. If it isn't readable, or it is empty or fails to parse, the loader builds the normal stack instead (with a `WARN` line when the file is unreadable or unparsable).

**Merge rules:**

- Merging is per field. Variants merge per variant and per field, so a game can set `variants.target.cpu_ceil` alone. A brand-new variant that omits a field falls back to 70 (CPU) or 80 (memory).
- An empty or comments-only file is skipped. A file that fails to parse is skipped with a `WARN` line. A value that isn't a number is ignored without any warning. To see the values actually applied, check `mean_ms_ceil`, `http_ko_ceil`, `cpu_ceil`, `mem_ceil` and `crash_max_consecutive_ceil` in `verdict.json`.
- `verdict.json` field `sla_config_source` names the highest layer that was actually applied: an absolute path, `classpath:sla-thresholds.yml`, or `defaults`. For naga777, mutantmerge and zeroday it shows `…/config/sla-thresholds.yml`, because their per-game files are comments-only.

**Where each layer is visible:**

- Layers 3 and 4 are filesystem paths resolved against the JVM's working directory. `verifyVariant` is a root-project task, so it runs in the repo root and the verdict sees the full stack.
- Gatling's in-simulation assertions resolve the same paths from the forked simulation JVM. Gradle starts every fork, the plugin's REST run tasks and the gRPC `JavaExec` tasks alike, in its default working directory: the game's subproject directory. There, layers 3 and 4 don't resolve, so only layers 1 and 2 apply. On the gRPC tasks, the first `sla-thresholds.yml` on the classpath is the game's own file, because the source-set output comes before `:core`. For naga777, mutantmerge and zeroday that file is comments-only, so their assertions use the hardcoded defaults.
- Each applied layer logs `[SlaConfig] layered <source>` on stderr. If Gatling's assertion ceilings and the verdict disagree, check those lines first.
- `-DslaConfig` is honoured by `verifyVariant` (it is in the task's `VERIFY_PROPS`). The wrapper never passes it. It isn't in any game's `FORWARDED_PROPS`, so it never reaches a gRPC simulation. A REST simulation still receives it, because the Gatling plugin copies the Gradle JVM's system properties into the fork (see [Gradle system properties](#gradle-system-properties)).
- On `verifyVariant`, `-DmeanMsCeil=<ms>` and `-DhttpKoCeil=<percent>` override the PR-4 and PR-5 ceilings for that verdict only.

### Runtime defaults and game overrides

`LoadTestConfigLoader` resolves host, port, context path and load shape for the **Java REST simulations** (silkroad, bonanza `soak` / `basic`). Layers run from lowest to highest priority:

1. Hardcoded fallback in `LoadTestDefaults.fallback()`, the same values as the next file.
2. `classpath:load-test-defaults.yml`, bundled from `core/src/main/resources/load-test-defaults.yml`.
3. `config/load-test-defaults.yml`: an optional override, resolved against the simulation JVM's working directory. That is the game's subproject directory, so the file is read from `games/<game>/config/load-test-defaults.yml`; a copy at the repo root is not picked up. The repo doesn't ship one.
4. `classpath:game.yml`, which is `games/<game>/src/gatling/resources/game.yml` bundled at the root of that game's classpath.
5. `-D` system properties: `host`, `port`, `contextPath`, `users`, `requests`, `durationMinutes`, `rampMinutes`, `thinkTimeMin`, `thinkTimeMax`.

| YAML key                 | Core default | Meaning                                                    |
|--------------------------|--------------|------------------------------------------------------------|
| `http.host`              | `localhost`  | Base URL is `http://<host>:<port><contextPath>`.           |
| `http.port`              | `3000`       |                                                            |
| `http.contextPath`       | `""`         | Prefix such as `/golden`, with no trailing slash.          |
| `load.users`             | `1000`       | VU count.                                                  |
| `load.requests`          | `10000`      | `Basic` only: total request budget.                        |
| `load.durationMinutes`   | `60`         | Steady-state minutes (Soak), or ramp length (Stress).      |
| `load.rampMinutes`       | `5`          | Ramp-up minutes (silkroad Soak).                           |
| `load.thinkTimeMin` / `load.thinkTimeMax` | `1` / `3` | Seconds of random pause after each primary request (silkroad journeys). |

Shipped `game.yml` files:

- **bonanza** sets `http.port: 3005` and `http.contextPath: /golden`.
- **silkroad, naga777, mutantmerge and zeroday** have comments only.

**Exceptions:**

- **bonanza `SoakSimulation`** reads `rampMinutes` straight from `-D`, with a default of `2`.
- **The Scala gRPC simulations don't use `LoadTestConfig` at all.** That class is compiled against Gatling 3.15's Java API and would drag 3.15 types onto their 3.9.5 classpath. They read every `-D` value directly with their own defaults, so their `game.yml` is documentation only.
- **The wrapper always passes `-Dusers`, `-DdurationMinutes`, `-DrampMinutes` and `-Dport`.** Under the wrapper, the port in `game.yml` is replaced by the wrapper's per-game port (see [Phase 8](#phase-8-wire-the-wrapper-script)), and the YAML load values are replaced by the wrapper's own defaults. `host` and `contextPath` still come from YAML.

### Gradle system properties

`./gradlew <task> -Dname=value` sets a system property in the Gradle JVM, not in the simulation JVM that Gradle forks. Each game's alias tasks (`soak`, `basic`, `grpc`, …) copy the names listed in `FORWARDED_PROPS` (in `games/<game>/build.gradle`) into the simulation JVM. They also add `gameName=<Gradle project name>`. For the gRPC `JavaExec` tasks this list is the **only** path: a flag missing from it never reaches the simulation. The REST tasks run on the Gatling plugin's `GatlingRunTask`, which also copies every other Gradle JVM system property into the fork, except JDK and tool names (prefixes such as `java.`, `os.`, `user.`, `file.` and `gatling.`). When a simulation starts reading a new flag, add the name to `FORWARDED_PROPS` anyway.

These flags reach a simulation only when you call Gradle directly. The wrapper passes just `users`, `durationMinutes`, `rampMinutes`, `port`, and optionally `parallel`, `requests` and `scenario` (see [Wrapper flags](#wrapper-flags)).

**Forwarded names per game** (exact lists):

| Game          | `FORWARDED_PROPS`                                                                                                   |
|---------------|---------------------------------------------------------------------------------------------------------------------|
| `silkroad`    | `users`, `requests`, `durationMinutes`, `rampMinutes`, `thinkTimeMin`, `thinkTimeMax`, `host`, `port`, `contextPath`, `parallel`, `scenario`, `maxResponseTimeMs`, `usersStart`, `usersEnd`, `baseline`, `spike`, `spikeDurationSec`, `cycles`, `cycleIntervalMinutes` |
| `bonanza` (REST and `grpc` share one list) | `users`, `requests`, `durationMinutes`, `rampMinutes`, `thinkTimeMin`, `thinkTimeMax`, `host`, `port`, `contextPath`, `scenario`, `maxResponseTimeMs`, `paceSec`, `requestRate`, `eventCount`, `grpcHost`, `grpcPort` |
| `naga777`     | `users`, `durationMinutes`, `rampMinutes`, `host`, `port`, `paceSec`, `requestRate`, `eventCount`, `grpcHost`, `grpcPort`, `coinValue`, `coinPerLine` |
| `mutantmerge` | `users`, `durationMinutes`, `rampMinutes`, `host`, `port`, `paceSec`, `requestRate`, `eventCount`, `grpcHost`, `grpcPort`, `betLevelId`, `superBet` |
| `zeroday`     | `users`, `durationMinutes`, `rampMinutes`, `host`, `port`, `paceSec`, `requestRate`, `eventCount`, `grpcHost`, `grpcPort`, `bet` |

The gRPC-only games forward `host` and `port`, but their Scala simulations don't read them. The gRPC endpoint comes only from `grpcHost` / `grpcPort`.

**Shared load flags** (REST simulations, via `LoadTestConfig` unless noted):

| Flag                | Default           | Meaning                                                                                  |
|---------------------|-------------------|------------------------------------------------------------------------------------------|
| `users`             | `1000`            | silkroad Soak: VUs injected (open model). bonanza Soak and all gRPC simulations: concurrent VUs (closed model). Basic: VUs started at once. |
| `durationMinutes`   | `60`              | Steady-state minutes. In Stress, the length of the arrival-rate ramp.                   |
| `rampMinutes`       | `5`; `2` for bonanza Soak and all gRPC simulations | Ramp-up minutes before steady state.                      |
| `requests`          | `10000`           | Basic only: total request budget. Each VU repeats `max(1, requests / users)` times.     |
| `host`, `port`, `contextPath` | `localhost`, `3000`, `""` (bonanza: `3005`, `/golden`) | REST base URL. Override these to target staging.   |
| `thinkTimeMin`, `thinkTimeMax` | `1`, `3`  | Seconds of random pause after each primary request in silkroad journeys.                 |
| `scenario`          | first endpoint    | Basic only: an endpoint name, or `all` / `chain` / `burst`. An unknown value fails with `Unknown scenario: '<x>'. Valid: …`. |
| `parallel`          | `false`           | silkroad Soak only: `true` injects all users at once (no ramp).                           |
| `maxResponseTimeMs` | `0` (off)         | Basic only: adds a `max response time ≤ N` assertion.                                     |

**silkroad Stress and Spike** (read by `StressSimulationBase` / `SpikeSimulationBase`):

| Flag                   | Default | Meaning                                                                                  |
|------------------------|---------|------------------------------------------------------------------------------------------|
| `usersStart`, `usersEnd` | `500`, `2500` | Stress: arrival rate at the start and end of the ramp, in **users per minute** (the simulation divides by 60). The ramp lasts `durationMinutes`. |
| `baseline`             | `200`   | Spike: steady arrival rate in **users per minute**.                                        |
| `spike`                | `1500`  | Spike: users injected at once in each burst.                                               |
| `cycles`               | `5`     | Spike: number of bursts.                                                                   |
| `cycleIntervalMinutes` | `5`     | Spike: length of one cycle. Total run = `cycles × cycleIntervalMinutes`. Spike ignores `durationMinutes` and `rampMinutes`. |
| `spikeDurationSec`     | `30`    | Spike: the wait before each burst is `cycleIntervalMinutes × 60 − spikeDurationSec` seconds. |

**bonanza Soak and all gRPC simulations:**

| Flag          | Default                  | Meaning                                                                                  |
|---------------|--------------------------|------------------------------------------------------------------------------------------|
| `paceSec`     | `5`                      | Minimum seconds between loop iterations per VU (Gatling `pace`).                         |
| `requestRate` | `50`                     | Gatling assertion: global requests per second must beat this floor. Use `0` for short smokes. |
| `eventCount`  | `100000`                 | Gatling assertion: successful requests must exceed this count. Use `0` for short smokes.  |
| `grpcHost`    | `localhost`              | gRPC simulations only.                                                                    |
| `grpcPort`    | `9091` bonanza, `9096` naga777, `9104` mutantmerge, `9103` zeroday | gRPC simulations only.                          |

**Game-specific bet flags:**

| Game          | Flag           | Default  | Meaning                                                                                 |
|---------------|----------------|----------|-----------------------------------------------------------------------------------------|
| `naga777`     | `coinValue`    | `5`      | Sent as `coinValueId`. The backend accepts 1, 5, 20, 50, 100, 200 or 500.                 |
| `naga777`     | `coinPerLine`  | `3`      | Sent as `betLevelId` (1 to 10). The server derives bet = `coinValue × coinPerLine × 5` (default 75). |
| `mutantmerge` | `betLevelId`   | `3`      | 1-based index into the bet ladder, sent as a string. The simulation notes the default as $1.00. |
| `mutantmerge` | `superBet`     | `false`  | `true` adds `superBet: true` to each spin.                                               |
| `zeroday`     | `bet`          | `1.00`   | Decimal on the bet ladder `0.20`–`100.00` (25 steps). An off-ladder value fails when the simulation loads, before any request. |

## Advanced runs without the wrapper

Call `./gradlew :games:<game>:<alias>` directly when you need something the wrapper doesn't expose:

- **Stress or Spike shape**: `-DusersStart`, `-DusersEnd`, `-Dbaseline`, `-Dspike`, `-Dcycles`, …
- **gRPC endpoint override**: `-DgrpcHost`, `-DgrpcPort`.
- **gRPC and bonanza Soak tuning**: `-DpaceSec`, `-DrequestRate`, `-DeventCount`, and the bet flags above.
- **Running an arbitrary simulation by fully qualified class name (FQCN).**
- **Fast iteration while developing a game**: no monitor start-up and no verdict.

You get only the Gatling HTML report at `games/<game>/build/reports/gatling/<simulationclass>-<timestamp>/index.html`. There is **no `summary.html`, no `verdict.json` and no CSVs**. If a Gatling assertion fails, the Gradle task fails. Short runs of bonanza Soak and the gRPC simulations always fail the `requestRate` / `eventCount` floors unless you pass `-DrequestRate=0 -DeventCount=0`.

### Gradle commands per game

Run these from the repo root. Replace the `<…>` placeholders with your own staging values.

```bash
# silkroad (REST, Gatling 3.15)
./gradlew :games:silkroad:basic  -Dscenario=spin -Dusers=1 -Drequests=1
./gradlew :games:silkroad:soak   -Dusers=50 -DdurationMinutes=1 -DrampMinutes=1
./gradlew :games:silkroad:stress -DusersStart=10 -DusersEnd=200 -DdurationMinutes=5
./gradlew :games:silkroad:spike  -Dbaseline=5 -Dspike=50 -Dcycles=3 -DcycleIntervalMinutes=1

# bonanza REST (port 3005 and contextPath /golden come from game.yml)
./gradlew :games:bonanza:basic -Dscenario=BetLevels -Dusers=1 -Drequests=1
./gradlew :games:bonanza:soak  -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1 \
  -DrequestRate=0 -DeventCount=0
# bonanza REST against another host
./gradlew :games:bonanza:soak -Dhost=<rest-host> -Dport=<rest-port> -DcontextPath=/golden

# bonanza gRPC (Gatling 3.9.5; separate channel, no contextPath)
./gradlew :games:bonanza:grpc -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1 \
  -DgrpcHost=localhost -DgrpcPort=9091

# naga777 gRPC
./gradlew :games:naga777:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
  -DrequestRate=0 -DeventCount=0
./gradlew :games:naga777:grpc -Dusers=1000 -DdurationMinutes=60 \
  -DgrpcHost=localhost -DgrpcPort=9096 -DcoinValue=5 -DcoinPerLine=3

# mutantmerge gRPC
./gradlew :games:mutantmerge:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
  -DrequestRate=0 -DeventCount=0
./gradlew :games:mutantmerge:grpc -Dusers=1000 -DdurationMinutes=60 -DbetLevelId=3 -DsuperBet=true

# zeroday gRPC
./gradlew :games:zeroday:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
  -DrequestRate=0 -DeventCount=0
./gradlew :games:zeroday:grpc -Dusers=1000 -DdurationMinutes=60 -Dbet=1.00

# Any gRPC game against another host
./gradlew :games:<game>:grpc -DgrpcHost=<grpc-host> -DgrpcPort=<grpc-port>
```

To list a game's tasks, run `./gradlew :games:<game>:tasks --group=gatling`.

### Run any simulation by class name

Only the modules that apply the Gatling Gradle plugin (silkroad and bonanza) have the plugin's `gatlingRun` task:

```bash
./gradlew :games:silkroad:gatlingRun \
  --simulation com.rgp.loadtest.silkroad.simulations.SoakSimulation
./gradlew :games:bonanza:gatlingRun \
  --simulation com.rgp.loadtest.bonanza.simulations.BasicSimulation
```

- `gatlingRun` is the plugin's own task. It doesn't add `gameName` or read `FORWARDED_PROPS`, but the plugin still copies your `-D` flags into the fork (see [Gradle system properties](#gradle-system-properties)). Its SLA assertions match the alias's, because the per-game file layer never resolves inside a simulation fork anyway (see [SLA thresholds and load order](#sla-thresholds-and-load-order)).
- On bonanza, `gatlingRun` sees only the REST source set (`src/gatling/java`). The gRPC simulation lives in the separate `gatlingGrpc` source set, and only the `grpc` task runs it.
- naga777, mutantmerge and zeroday have no `gatlingRun`. Each entry in their `SIMULATIONS` map becomes a `JavaExec` task, so a new Scala simulation there needs a new map entry. bonanza's `grpc` task is hard-wired to `BonanzaGrpcSimulation`.

### Rerun the verifier on existing results

`verifyVariant` (root `build.gradle`) runs `ThresholdVerifier` against a run's CSVs and log, and prints the verdict JSON on stdout. Run it from the repo root and use `-q` to get clean JSON:

```bash
RUN=target/variants/silkroad/target-<timestamp>
./gradlew verifyVariant -q \
  -DgameName=silkroad \
  -DresourceCsv=$RUN/resource.csv \
  -DhealthCsv=$RUN/health.csv \
  -DgatlingLog=$RUN/gatling.log \
  -Dvariant=stress -Dusers=1000 -DdurationSec=3900
```

| Property      | Required | Meaning                                                                                       |
|---------------|----------|-----------------------------------------------------------------------------------------------|
| `resourceCsv` | yes      | CPU/Mem samples (`monitor-resources.sh` output).                                             |
| `healthCsv`   | yes      | Health probe samples (`health-poll.sh` output).                                              |
| `variant`     | yes      | Variant whose ceilings to apply.                                                              |
| `users`       | yes      | Recorded in the verdict as `users`.                                                           |
| `durationSec` | yes      | Recorded as `duration_sec`. The wrapper passes Gatling's measured wall-clock seconds.         |
| `gatlingLog`  | no       | Parsed for totals, KO %, mean and p95. Both the Gatling 3.15 and 3.9 console formats are understood. |
| `gatlingExit` | no       | Used for PR-5 only when the log yields no KO %. Recorded as `gatling_exit_code`.              |
| `gameName`    | no       | Adds the per-game SLA layer. Recorded as `game_name`.                                          |
| `slaConfig`   | no       | Full SLA override file (see [load order](#sla-thresholds-and-load-order)).                  |
| `meanMsCeil`, `httpKoCeil` | no | Override the PR-4 and PR-5 ceilings for this verdict.                                  |

**Exit codes and follow-up:**

- `ThresholdVerifier` exits `0` on PASS, `1` on FAIL and `2` on a missing required property or an unknown variant. The task sets `ignoreExitValue = true`, so `./gradlew` itself still succeeds. Read the `verdict` field in the JSON.
- To refresh `summary.html`, redirect the output to `$RUN/verdict.json` (this replaces the original) and run `python3 scripts/generate-summary-html.py --variant-dir $RUN`.
- For a report across variants, see `generate-final-report.py` in the [scripts reference](#scripts-reference).

## Project layout

### Tech stack and versions

Shared versions are pinned in the root `build.gradle` (`ext { … }`). The gRPC runtime is pinned in each gRPC game's `build.gradle` (`gatlingOssVersion`, `gatlingGrpcVersion`).

| Component                     | Version     | Where it's used                                                                   |
|-------------------------------|-------------|-----------------------------------------------------------------------------------|
| Java toolchain                | **17**      | Every subproject (`javaTargetVersion`).                                            |
| Gradle wrapper                | 9.2.1       | `gradle/wrapper/gradle-wrapper.properties`. No separate install needed.           |
| Gatling (REST simulations)    | **3.15.0**  | `gatlingVersion`, exported by `:core` as an `api` dependency. silkroad, bonanza `soak` / `basic`. |
| Gatling Gradle plugin         | 3.15.0.2    | `gatlingGradleVersion`, applied by silkroad and bonanza only.                     |
| Gatling (gRPC simulations)    | **3.9.5**   | `gatlingOssVersion` in bonanza, naga777, mutantmerge and zeroday. See [gRPC runtimes](#grpc-runtimes). |
| gRPC DSL                      | `com.github.phisgr:gatling-grpc` 0.17.0 | `gatlingGrpcVersion`. Community plugin with no VU cap.  |
| Scala library                 | **2.13.12** | The four gRPC simulations only. See [Scala gRPC simulations](#scala-grpc-simulations). |
| gRPC Java / Protobuf          | 1.75.0 / 4.32.1 | `grpcVersion` / `protobufVersion`. Stubs generated in `:core` from `plugin_service.proto`. |
| Protobuf Gradle plugin        | 0.9.5       | `protobufPluginVersion` (`com.google.protobuf`, in `:core`).                      |
| SnakeYAML                     | 2.2         | SLA and runtime YAML loaders in `:core`.                                          |
| GaaS `common-data`            | 1.0.0       | `core/libs/common-data-1.0.0.jar`, a proprietary MessagePack library. Shipped as Java 21 bytecode and rewritten to Java 17 by `:core:downgradeGaasJar` (output in `core/build/libs-jdk17/`). |
| MessagePack / json-smart      | `msgpack-core` 0.9.8, `msgpack` 0.6.12, `json-smart` 2.5.0 | Runtime dependencies of the GaaS library. |
| Python                        | 3.9+        | `generate-summary-html.py`, `generate-final-report.py`.                            |

### gRPC runtimes

All gRPC simulations run on **Gatling 3.9.5** with the community plugin `com.github.phisgr:gatling-grpc`, not on the Gatling 3.15 that the REST simulations use.

The reason is a hard limit. Gatling 3.15's first-party gRPC DSL (`io.gatling:gatling-grpc-java`) is a Gatling Enterprise feature. Without a licence it runs in trial mode and **aborts the simulation above 5 concurrent VUs or 5 minutes**. At 10 VUs the run dies within seconds with `Some of the simulations crashed`, never reaching user #6, so a 1000-VU production gate is impossible on it.

The community plugin is Apache 2.0 and has no cap. Its last release (0.17.0) targets Gatling 3.9.5, but the matching `io.gatling.gradle` 3.9.5.x fails on Gradle 9 (`unknown property 'reportsDir'`). So the gRPC simulations skip the Gatling Gradle plugin entirely. Each one builds its own Gatling 3.9.5 classpath in a dedicated configuration and launches `io.gatling.app.Gatling` through a plain `JavaExec` task. The task writes its reports into `build/reports/gatling`, the same place the plugin uses, so the wrapper picks them up unchanged.

- **naga777, mutantmerge and zeroday** are gRPC-only. The whole module is on 3.9.5: configuration `gatlingRt`, source set `gatling`, sources in `src/gatling/scala`.
- **bonanza** ships both runtimes, so it is split. `src/gatling/java` (REST) stays on 3.15 under the Gatling Gradle plugin. `src/gatlingGrpc/scala` compiles and runs against 3.9.5 (configuration `gatlingGrpcRt`, source set `gatlingGrpc`). The two classpaths never mix.
- **silkroad** is REST-only and untouched. Gatling's HTTP DSL is fully OSS and uncapped, so there is no reason to move it.
- Every gRPC runtime pulls in `project(':core')` for the proto stubs, the MessagePack `Codec` and `SlaConstants`. It uses `exclude group: 'io.gatling'` and `exclude group: 'io.gatling.highcharts'` so that the Gatling 3.15 which `:core` exports never lands on the 3.9.5 classpath.

Everything downstream works unchanged: the wrapper, the threshold verifier and `summary.html`. Gatling 3.9 prints its console summary as `> request count  640 (OK=640  KO=0 )` instead of the pipe-delimited table that 3.15 uses, so the threshold verifier parses both shapes.

**Caveat:** the community plugin is **archived upstream** (last commit February 2024) and will never support Gatling 3.10+. If the REST simulations ever move to a newer Gatling, the gRPC ones stay behind on 3.9.5 unless someone forks the plugin or buys an Enterprise licence.

### Scala gRPC simulations

The harness is Java except for **four Scala files**, one gRPC simulation per game:

| File                                                                                         | `pluginName`            | Default `grpcPort` | Extra flags                | Spin check |
|----------------------------------------------------------------------------------------------|-------------------------|--------------------|----------------------------|------------|
| `games/bonanza/src/gatlingGrpc/scala/com/rgp/loadtest/bonanza/grpc/BonanzaGrpcSimulation.scala` | `golden-boat-bonanza` | `9091`             | none                       | gRPC status only |
| `games/naga777/src/gatling/scala/com/rgp/loadtest/naga777/grpc/Naga777GrpcSimulation.scala`   | `game-naga-fortune-777` | `9096`           | `coinValue`, `coinPerLine` | gRPC status only (the result is pushed over ZMQ) |
| `games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala` | `yama_01024` | `9104`          | `betLevelId`, `superBet`   | Decodes the reply and fails on a non-zero `c` |
| `games/zeroday/src/gatling/scala/com/rgp/loadtest/zeroday/grpc/ZeroDayGrpcSimulation.scala`   | `yama_01023`            | `9103`             | `bet`                      | gRPC status only (the reply is empty and the result goes over ZMQ) |

**Why Scala?** `com.github.phisgr:gatling-grpc` only ships a Scala DSL (`com.github.phisgr.gatling.grpc.Predef._`), so these simulations are written in Scala 2.13. Their payloads still come from `:core`:

- **Java proto stubs** (`com.rgp.loadtest.grpc.*`), generated from `core/src/main/proto/plugin_service.proto`. No ScalaPB is involved.
- **The MessagePack `Codec`** (`com.rgp.loadtest.core.protocol.Codec`), backed by the GaaS JAR.
- **`SlaConstants`**, for the PR-4 and PR-5 ceilings and the `maxDuration` buffer. It is plain Java and safe on the 3.9.5 classpath. `LoadTestConfig` is not safe there (see [Runtime defaults and game overrides](#runtime-defaults-and-game-overrides)).

**Shared shape.** Every simulation uses the same journey and assertions:

- **Closed model:** `rampConcurrentUsers(0).to(users)` over `rampMinutes`, then `constantConcurrentUsers(users)` for `durationMinutes`.
- **Per VU:** one `Join` (`ConnectAndCall`), then a `Spin` loop (`Call`) paced at `paceSec`. In naga777, mutantmerge and zeroday, a failed Join stops the VU (`exitHereIfFailed`); bonanza carries on to the loop.
- **Assertions:** global mean ≤ PR-4, failed % ≤ PR-5, requests/s > `requestRate`, successful requests > `eventCount`, `Spin` p95 ≤ 800 ms, and `Spin` and `Join` failed % ≤ 0.5.

**How it's wired, gRPC-only module** (from `games/zeroday/build.gradle`; naga777 and mutantmerge are identical apart from names and flags):

```groovy
plugins {
    id 'java'
    id 'scala'                      // no io.gatling.gradle here
}

def gatlingOssVersion = '3.9.5'
def gatlingGrpcVersion = '0.17.0'

configurations {
    gatlingRt
}

dependencies {
    gatlingRt "io.gatling.highcharts:gatling-charts-highcharts:${gatlingOssVersion}"
    gatlingRt "com.github.phisgr:gatling-grpc:${gatlingGrpcVersion}"
    gatlingRt 'org.scala-lang:scala-library:2.13.12'
    gatlingRt(project(':core')) {           // proto stubs + Codec, minus Gatling 3.15
        exclude group: 'io.gatling'
        exclude group: 'io.gatling.highcharts'
    }
}

sourceSets {
    gatling {
        scala.srcDirs = ['src/gatling/scala']
        java.srcDirs = []
        resources.srcDirs = ['src/gatling/resources']
        compileClasspath = configurations.gatlingRt
        runtimeClasspath = output + configurations.gatlingRt
    }
}

// FORWARDED_PROPS = [...]; SIMULATIONS = [grpc: 'com.rgp.loadtest.zeroday.grpc.ZeroDayGrpcSimulation']
// Each SIMULATIONS entry registers a JavaExec task: dependsOn gatlingClasses,
// mainClass 'io.gatling.app.Gatling', args '-s', fqcn, '-rf', build/reports/gatling,
// the same --add-opens jvmArgs as the REST modules, and in doFirst:
// systemProperty 'gameName', project.name plus every FORWARDED_PROPS name that was set.
```

**bonanza differs** in these ways:

- It keeps `id 'io.gatling.gradle'` for its REST simulations.
- It names the configuration `gatlingGrpcRt` and the source set `gatlingGrpc` (`scala.srcDirs = ['src/gatlingGrpc/scala']`).
- It puts `resources.srcDirs = ['src/gatlingGrpc/resources', 'src/gatling/resources']` on that source set, so its `logback-test.xml` comes first and the shared `game.yml` / `sla-thresholds.yml` are also on the gRPC classpath.
- It registers one hand-written `grpc` `JavaExec` task that depends on `gatlingGrpcClasses`.

**Run it.** Use the wrapper to get `summary.html` and a verdict. Use Gradle directly to override the gRPC endpoint or tuning flags.

```bash
./scripts/run-variant.sh --game zeroday --variant target --simulation Grpc \
  --users 10 --duration-minutes 5 --ramp-minutes 1 --container game-zero-day

./gradlew :games:zeroday:grpc -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1 \
  -DgrpcHost=<grpc-host> -DgrpcPort=9103
```

**Wire format:** `ConnectAndCallRequest.user.parameters` and `PluginRequest.data` are MessagePack-encoded blobs, not protobuf substructures. The backend decodes them with the same GaaS library. Build them as `java.util.LinkedHashMap` and keep the insertion order intact when changing payloads. On bonanza, wire-format drift surfaces server-side as `UserContext.fromPuObject` NPEs.

### Directory tree

Tracked files, grouped. Generated output (`build/`, `target/`) is gitignored.

```
.
├── README.md                         this file
├── loadtest.sh                       interactive menu → scripts/run-variant.sh
├── build.gradle                      shared Java 17 toolchain, version properties, verifyVariant task
├── settings.gradle                   includes :core and the five games (4 more commented out)
├── gradlew, gradlew.bat, gradle/wrapper/   Gradle 9.2.1 wrapper
├── config/
│   └── sla-thresholds.yml            repo-wide pass/fail thresholds
├── core/                             shared library: don't edit when adding a game
│   ├── build.gradle                  java-library + protobuf; downgradeGaasJar task
│   ├── libs/
│   │   ├── common-data-1.0.0.jar     proprietary GaaS MessagePack library (Java 21 bytecode)
│   │   └── README.md
│   └── src/main/
│       ├── java/com/rgp/loadtest/core/
│       │   ├── config/               LoadTestConfig, LoadTestConfigLoader, LoadTestDefaults,
│       │   │                         SlaConfig, SlaConfigLoader (YAML layering)
│       │   ├── protocol/             Codec (MessagePack via the GaaS library)
│       │   ├── scenarios/            SessionJourneyTemplate (loop + ratio routing)
│       │   ├── simulations/          {Soak,Stress,Spike,Basic}SimulationBase
│       │   ├── utils/                SlaConstants, SystemProps
│       │   └── verify/               ThresholdVerifier (verdict JSON)
│       ├── proto/plugin_service.proto    WSProxy PluginService → Java gRPC stubs
│       └── resources/                load-test-defaults.yml, sla-thresholds.yml (bundled fallbacks)
├── games/
│   ├── silkroad/                     REST only, Java, stateless (open model)
│   │   ├── build.gradle
│   │   ├── docker-compose.loadtest.yml
│   │   └── src/gatling/
│   │       ├── java/com/rgp/loadtest/silkroad/   simulations/ scenarios/ requests/ utils/
│   │       └── resources/            game.yml, sla-thresholds.yml, gatling.conf, logback-test.xml,
│   │                                 games/silkroad/bodies/*.json
│   ├── bonanza/                      REST (Java, Gatling 3.15) + gRPC (Scala, Gatling 3.9.5), stateful
│   │   ├── build.gradle              (no docker-compose.loadtest.yml)
│   │   └── src/
│   │       ├── gatling/
│   │       │   ├── java/com/rgp/loadtest/bonanza/   simulations/ scenarios/ requests/ utils/
│   │       │   └── resources/        game.yml, sla-thresholds.yml, games/bonanza/bodies/*.json
│   │       └── gatlingGrpc/
│   │           ├── scala/com/rgp/loadtest/bonanza/grpc/BonanzaGrpcSimulation.scala
│   │           └── resources/logback-test.xml
│   ├── naga777/                      gRPC only, Scala ─┐
│   ├── mutantmerge/                  gRPC only, Scala  ├─ same layout:
│   └── zeroday/                      gRPC only, Scala ─┘
│       ├── build.gradle
│       ├── docker-compose.loadtest.yml
│       └── src/gatling/
│           ├── scala/com/rgp/loadtest/<game>/grpc/<Name>GrpcSimulation.scala
│           └── resources/            game.yml, sla-thresholds.yml, logback-test.xml
├── scripts/
│   ├── run-variant.sh                canonical entry point for verdict runs
│   ├── ensure-sut.sh                 starts the SUT from games/<game>/docker-compose.loadtest.yml
│   ├── monitor-resources.sh          docker stats → resource.csv
│   ├── health-poll.sh                HTTP probe → health.csv
│   ├── generate-summary-html.py      builds summary.html
│   └── generate-final-report.py      cross-variant Markdown compliance report
├── HUONG-DAN.md                      Vietnamese beginner guide
├── naga777-load-test-guide.md        naga777 run-and-read-results guide
│
│   generated, not tracked:
├── core/build/libs-jdk17/            downgraded GaaS JAR
├── games/<game>/build/reports/gatling/<simulation>-<timestamp>/   raw Gatling reports
└── target/variants/<game>/<variant>-<timestamp>/                  per-run bundle from the wrapper
```

### Scripts reference

| Script | Purpose | Usage / key flags | Called by |
|--------|---------|-------------------|-----------|
| `loadtest.sh` | Interactive menu. It picks game, simulation and size, shows the equivalent `run-variant.sh` command, runs it and offers to open `summary.html`. | No flags. Needs a terminal (it exits and points to `run-variant.sh` otherwise). Games come from the `GAMES` table at the top of the file; `●` / `○` shows whether each game's container is running. | You |
| `scripts/run-variant.sh` | Full verdict run: SUT check, monitors, Gatling, verifier, summary. | See [Wrapper flags](#wrapper-flags). | `loadtest.sh`, you, CI |
| `scripts/ensure-sut.sh` | Makes sure the SUT is up and offers to start it if not. | `--game <g> --container <name> --port <N> --health-url <url>` | `run-variant.sh` (step 0) |
| `scripts/monitor-resources.sh` | Samples CPU and memory into a CSV. | `--container <name> [--fallback-port N] [--interval-sec N] --out <csv> --duration-sec N` (interval defaults to 5) | `run-variant.sh` (background) |
| `scripts/health-poll.sh` | Probes an HTTP URL into a CSV. | `[--url <url>] [--interval-sec N] --out <csv> --duration-sec N` (interval defaults to 2) | `run-variant.sh` (background) |
| `scripts/generate-summary-html.py` | Builds `summary.html` from one run directory. | `--variant-dir <dir>` | `run-variant.sh`, or by hand after re-verifying |
| `scripts/generate-final-report.py` | Builds a Markdown compliance report across variants. | `--variants-dir target/variants/<game> --report-out <file.md> [--host localhost] [--port 3000]` | By hand (see `naga777-load-test-guide.md`) |

**`loadtest.sh` presets:**

| Preset      | Users | Minutes | Ramp minutes              | Variant    |
|-------------|-------|---------|---------------------------|------------|
| Smoke       | 10    | 1       | 0                         | `target`   |
| Quick check | 200   | 5       | 1                         | `baseline` |
| Full test   | 1000  | 60      | 5 (2 for `Grpc`)          | `target`   |

- **Custom** asks for users, minutes, ramp, all-at-once (`--parallel`, Soak only), variant and HTTP port.
- **Basic** asks which API (the row's scenarios), then users (default 500) and total requests (default 5000). It runs for 1 minute with no ramp.

**`run-variant.sh` step by step:**

1. Checks the arguments (`--variant`, `--simulation`, `--container` and `--game` / `GAME` are required). `games/<game>/` must exist.
2. Derives `PORT` from a `case "$GAME"` block unless `--port` is given, then always builds `HEALTH_URL` from a second `case "$GAME"` block using that port.
3. Runs `ensure-sut.sh`.
4. Creates `target/variants/<game>/<variant>-<timestamp>/`.
5. Starts `monitor-resources.sh` (every 5 s) and `health-poll.sh` (every 2 s) in the background. Both run for `(duration + ramp) × 60 + 120` seconds.
6. Runs `./gradlew :games:<game>:<simulation in lowercase> -Dusers -DdurationMinutes -DrampMinutes -Dport [-Dparallel=true] [-Drequests] [-Dscenario] --console=plain` and tees the output to `gatling.log`.
7. Copies the newest folder under `games/<game>/build/reports/gatling/` to `gatling-report/`.
8. Runs `./gradlew verifyVariant -q -DgameName=<game> …` and writes `verdict.json`.
9. Runs `generate-summary-html.py`.
10. Exits with the exit code of the **Gatling run** from step 6 (the `./gradlew` call), not the verdict's. Ctrl-C stops the monitors and keeps the partial bundle.

**`ensure-sut.sh` details:**

1. Exits `0` straight away if the container is running (exact name match) or the health URL answers 2xx.
2. Otherwise it needs three things: `games/<game>/docker-compose.loadtest.yml`, a terminal, and a running Docker. If any is missing, it stops with an explanation. Without a terminal (CI), it also prints the manual `cp … && docker compose … up -d --build` command.
3. It asks for a local source folder, or a git URL (cloned from `main` into a temp directory). That folder must contain a `Dockerfile`.
4. It copies the compose file in. A different existing copy is kept as `docker-compose.loadtest.yml.bak`.
5. It runs `HTTP_PORT=<port> docker compose -f docker-compose.loadtest.yml up -d --build`.
6. It polls the health URL every 3 s for up to 300 s. On timeout it prints the container's last 50 log lines.

**Monitor and probe notes:**

- **`monitor-resources.sh`** uses `docker stats` on the container. If the container doesn't exist, it samples the host process listening on `--fallback-port` (found via `lsof`, read via `ps`). Otherwise it writes `N/A` rows.
- **`health-poll.sh`** marks a sample as failed when the status is non-2xx or the request takes 5 s or more (`curl --max-time 5`). **It stops and exits non-zero after 3 consecutive failures**, whatever `crash.max_consecutive_failures` says. Without `--url`, it tries silkroad-specific paths on `localhost:3000`.

**`generate-final-report.py` details:**

- It takes the newest `<variant>-*` directory for each of `baseline`, `target`, `stress` and `critical`.
- It reads `verdict.json` and `gatling-report/js/stats.json` from each. Missing variants render as `N/A`.
- Only `target` drives the final verdict. The ceiling labels are hardcoded in the script, not read from the YAML.

### Layers inside a game subproject

REST games (silkroad, bonanza REST), under `src/gatling/`:

| Layer               | Responsibility                                                                  |
|---------------------|---------------------------------------------------------------------------------|
| `simulations/`      | Injection profile, SLA assertions, HTTP protocol.                               |
| `scenarios/`        | Compose requests into a journey (loop + think time + ratio / `randomSwitch`).   |
| `requests/`         | One HTTP call: URL + method + body + check + session captures.                  |
| `utils/Endpoints.java` | Endpoint names used in reports and as `--scenario` values.                  |
| `resources/games/<pkg>/bodies/*.json` | Body templates in Gatling EL `#{userId}` syntax.              |
| `resources/game.yml`, `resources/sla-thresholds.yml` | Per-game runtime and SLA overrides.            |

gRPC simulations have no layers. One Scala file holds the payload builders (`buildJoinRequest` / `buildSpinRequest`), the gRPC protocol, the journey, the injection and the assertions.

### Two journey patterns

The REST games follow one of two patterns:

| Aspect       | Silkroad (template)                              | Bonanza (stateful)                                              |
|--------------|--------------------------------------------------|-----------------------------------------------------------------|
| Injection    | Open (`rampUsers` / `atOnceUsers`)               | Closed (`rampConcurrentUsers` + `constantConcurrentUsers`)      |
| Routing      | Ratio-modulo (`userIndex % N == 0`)              | `randomSwitch` weighted (80/8/5/3/2/2)                          |
| State        | Stateless                                        | Captures `sessionId` / `roundId` / `gameId` / `bonusTriggered`  |
| Init         | None                                             | Per VU: `BetLevels` → `ReelStrips` → `CreateSession`, then loop |
| Endpoints    | 3                                                | 10                                                              |
| Spin status  | `200`                                            | **`201`**                                                       |
| Languages    | Java only                                        | Java (REST) + Scala (gRPC)                                      |

The four gRPC simulations share a third, simpler shape: closed model, one `Join`, then a paced `Spin` loop (see [Scala gRPC simulations](#scala-grpc-simulations)).

## Add a new game

**Don't edit `:core`.** Clone the closest existing game, then work through the phases below.

The running example adds `fruit-respin-mania` on `localhost:4000` with context path `/fruit`. It has three REST endpoints (`spin`, `last-spin`, `history-summary`) and follows the stateless silkroad pattern. Wherever you see `fruit-respin-mania`, `fruitrespinmania`, `4000` or `/fruit`, substitute your real values. Phases that differ for a gRPC-only game say so.

### Phase 0 Plan

Before touching code, write these down. Every later step depends on them.

| Decision                        | Where it shows up                                                                        | Example                            |
|---------------------------------|-------------------------------------------------------------------------------------------|------------------------------------|
| **Game id**                     | `games/<id>/` directory, Gradle path `:games:<id>`, `--game`, `gameName`, output directory | `fruit-respin-mania`              |
| **Package name**                | `com.rgp.loadtest.<pkg>`; REST body directory `games/<pkg>/bodies/`                       | `fruitrespinmania` (lowercase, no separators: Java identifiers can't contain `-`) |
| **Template**                    | Which game to clone (see the table below)                                                 | silkroad                           |
| **HTTP host / port / contextPath** | REST: `game.yml`. All games: the wrapper's `PORT` and `HEALTH_URL`                     | `localhost` / `4000` / `/fruit`    |
| **Health URL**                  | `run-variant.sh` `HEALTH_URL` case. Must return 2xx cheaply                               | `http://localhost:4000/fruit/actuator/health` |
| **Endpoints** (REST)            | `utils/Endpoints.java`, `requests/SlotRequests.java`                                      | `spin`, `last-spin`, `history-summary` |
| **Spin status code** (REST)     | `status().is(...)` in `SlotRequests`                                                      | `200` (silkroad) or `201` (bonanza) |
| **Body shape** (REST)           | `bodies/*.json`                                                                           | `{userId, gameId, betAmount}`      |
| **gRPC port, `pluginName`, bet fields** (gRPC) | Defaults in the Scala simulation; the port is also published in the compose file | `9110`, `<plugin-name>`      |
| **Container name**              | `container_name` in the compose file, the `GAMES` row in `loadtest.sh`, `--container`     | `game-fruit-respin-mania`          |
| **Per-game SLA?**               | `games/<id>/src/gatling/resources/sla-thresholds.yml` (optional)                          | PR-4 ≤ 400 ms                      |

> **The game id and the package name differ on purpose.** Gradle and the wrapper accept kebab-case (`fruit-respin-mania`), but Java and Scala identifiers can't contain `-`. Use the id for the directory, the Gradle project, `--game` and the container. Use the lowercase form for packages and the REST body directory. The body path inside `ElFileBody(...)` uses the **package** form. The existing games all use one lowercase word, which avoids the split entirely.

#### Which template to clone

| Your game's shape                                                         | Clone                                                                   | Languages / runtime                         |
|---------------------------------------------------------------------------|-------------------------------------------------------------------------|---------------------------------------------|
| Stateless REST: each request is independent                               | `games/silkroad/`                                                       | Java, Gatling 3.15 plugin; aliases `soak` / `stress` / `spike` / `basic` |
| Stateful REST: an init phase (create session), then a loop with captured state | `games/bonanza/`, then remove its gRPC half (see Phase 1)          | Java, Gatling 3.15 plugin; aliases `soak` / `basic` |
| REST plus the WSProxy gRPC plugin path                                    | `games/bonanza/` as is                                                  | Java + Scala 2.13.12; 3.15 plugin + 3.9.5 (`gatlingGrpcRt`) |
| gRPC only, and the `Call` reply carries the result (business errors as non-zero `c`) | `games/mutantmerge/`                                         | Scala 2.13.12, Gatling 3.9.5 (`gatlingRt`), alias `grpc` |
| gRPC only, and the result is pushed over ZMQ (the `Call` reply is empty or an ack) | `games/zeroday/` (or `games/naga777/`, whose comments are in Vietnamese) | Scala 2.13.12, Gatling 3.9.5 (`gatlingRt`), alias `grpc` |

Find the backend's contract in its sources: HTTP controllers for REST, or the gRPC plugin's command handlers and `pluginName`. Use the FE integration doc if one exists.

### Phase 1 Clone the template

```bash
# Set once; used by every step below.
NEW=fruit-respin-mania    # game id: games/ directory, Gradle project, --game
PKG=fruitrespinmania      # package segment
TEMPLATE=silkroad         # silkroad | bonanza | mutantmerge | zeroday | naga777

# 1.1  Copy the template and drop its build output
cp -r games/$TEMPLATE games/$NEW
rm -rf games/$NEW/build

# 1.2  Rename every package directory named after the template
#      (src/gatling/java, src/gatling/scala, src/gatlingGrpc/scala, whichever exist)
find games/$NEW/src -type d -path "*/com/rgp/loadtest/$TEMPLATE" -prune | while read -r d; do
  mv "$d" "${d%/*}/$PKG"
done

# 1.3  REST templates: rename the body-template directory (its path is referenced from Java)
[ -d games/$NEW/src/gatling/resources/games/$TEMPLATE ] && \
  mv games/$NEW/src/gatling/resources/games/$TEMPLATE games/$NEW/src/gatling/resources/games/$PKG

# 1.4  Rewrite the lowercase template name everywhere in the new module
#      (BSD sed on macOS; on Linux use `sed -i` without the empty '')
grep -rl "$TEMPLATE" games/$NEW | xargs sed -i '' "s/$TEMPLATE/$PKG/g"

# 1.5  Sanity check: nothing should still mention the template
grep -rn "$TEMPLATE" games/$NEW || echo "OK clean"
```

**What step 1.4 touched:**

- Package declarations, imports and `ElFileBody` paths.
- Scenario names, such as `silkroad-session-journey` and `zeroday-grpc-player-journey`.
- The FQCNs in `build.gradle`'s `SIMULATIONS` map, and in bonanza's hand-written `grpc` task.
- Comments.
- In a cloned compose file, the `name:` line.
- Any other string that **contains** the lowercase template id. For example, bonanza's plugin name `golden-boat-bonanza` becomes `golden-boat-<pkg>`. Phase 4 sets the real plugin name anyway.

It does **not** touch names that spell the template differently: mixed-case class names like `ZeroDayGrpcSimulation`, container names like `game-zero-day` or `game-silk-road-caravans`, or body values like `silk_road_usecase`. Later phases cover those.

**gRPC templates: rename the simulation class and its file.** The old prefixes are `Bonanza`, `Naga777`, `MutantMerge` and `ZeroDay`:

```bash
OLD_CLASS=ZeroDay; NEW_CLASS=FruitRespinMania
f=$(find games/$NEW/src -name "${OLD_CLASS}GrpcSimulation.scala")
mv "$f" "${f%/*}/${NEW_CLASS}GrpcSimulation.scala"
grep -rl "$OLD_CLASS" games/$NEW | xargs sed -i '' "s/$OLD_CLASS/$NEW_CLASS/g"
```

**Stateful REST cloned from bonanza: remove the gRPC half.**

1. Delete `src/gatlingGrpc/`.
2. In `build.gradle`, delete:
   - `id 'scala'`
   - `gatlingOssVersion` and `gatlingGrpcVersion`
   - the `gatlingGrpcRt` configuration and its dependencies
   - the `sourceSets { gatlingGrpc { … } }` block
   - the `grpc` task
   - `grpcHost` and `grpcPort` from `FORWARDED_PROPS`

### Phase 2 Wire into Gradle

**2.1 Register the subproject.** `settings.gradle` already has commented stubs for four games, including `// include ':games:fruit-respin-mania'`. Uncomment the matching stub, or add a line next to the existing `include` lines:

```groovy
include ':games:zeroday'
include ':games:fruit-respin-mania'          // add
```

**2.2 Check `SIMULATIONS`** in `games/$NEW/build.gradle`. Step 1.4 already moved the FQCNs to the new package. The alias names matter because the wrapper runs `:games:<game>:<--simulation lowercased>`: `--simulation Soak` runs `soak` and `--simulation Grpc` runs `grpc`.

```groovy
def SIMULATIONS = [
    soak  : 'com.rgp.loadtest.fruitrespinmania.simulations.SoakSimulation',
    stress: 'com.rgp.loadtest.fruitrespinmania.simulations.StressSimulation',
    spike : 'com.rgp.loadtest.fruitrespinmania.simulations.SpikeSimulation',
    basic : 'com.rgp.loadtest.fruitrespinmania.simulations.BasicSimulation',
]
// gRPC-only template:
// def SIMULATIONS = [ grpc: 'com.rgp.loadtest.fruitrespinmania.grpc.FruitRespinManiaGrpcSimulation' ]
```

**2.3 Check `FORWARDED_PROPS`.** Add every `-D` name your simulation reads that the template doesn't already forward, such as a new bet flag or an auth override. Remove template-specific names you no longer read (for example `coinValue`, `betLevelId` or `bet`). `gameName` is injected automatically.

**2.4 Verify Gradle loads the project:**

```bash
./gradlew :games:$NEW:tasks --group=gatling
```

The `gatling` group should list every alias from `SIMULATIONS`. `Project … not found` means step 2.1 is missing. A missing alias points to step 2.2.

### Phase 3 Configure the runtime

**REST games.** If the game runs on `localhost:3000` with no context path, skip this. Otherwise edit `games/$NEW/src/gatling/resources/game.yml`:

```yaml
http:
  host: localhost            # only if not localhost
  port: 4000                 # only if not 3000
  contextPath: /fruit        # only if not empty
```

List only the fields you override (see [Runtime defaults and game overrides](#runtime-defaults-and-game-overrides)). `-Dhost`, `-Dport` and `-DcontextPath` still win at run time. The wrapper always passes `-Dport`, so the `PORT` you add in Phase 8 must match this file.

**gRPC-only games.** The Scala simulation doesn't read `game.yml`. Keep the file as a comments-only note of the health URL and the default gRPC port, as naga777, mutantmerge and zeroday do. Put the defaults in the simulation itself: `Integer.getInteger("grpcPort", <port>)`, `PluginName`, `Zone`, and any bet flag. The wrapper can't pass `grpcHost` / `grpcPort`, so the in-code default must match the port your compose file publishes.

### Phase 4 Endpoints and requests

**REST games:**

**4.1** `games/$NEW/src/gatling/java/com/rgp/loadtest/$PKG/utils/Endpoints.java`: these constants name your endpoints in the Gatling report and are the values `--scenario` accepts.

```java
public static final String SPIN = "spin";                        // POST  /fruit/v1/slot/spin
public static final String LAST_SPIN = "last-spin";              // POST  /fruit/v1/slot/last-spin
public static final String HISTORY_SUMMARY = "history-summary";  // GET   /fruit/v1/history/summary
```

Use short, dash-separated names, since they show up in CLI flags and in the `loadtest.sh` menu.

**4.2** `games/$NEW/src/gatling/java/com/rgp/loadtest/$PKG/requests/SlotRequests.java` has one method per endpoint. Update **all** of these in each method:

| Edit                         | Example                                                                                  |
|------------------------------|------------------------------------------------------------------------------------------|
| HTTP method                  | `.post(...)` vs `.get(...)`                                                              |
| URL path (after the context) | `.post("/v1/slot/spin")`. Leading `/` only; host and contextPath come from `game.yml`.   |
| Body file path               | `.body(ElFileBody("games/fruitrespinmania/bodies/spin.json"))`. Uses the **package** form. |
| Response status check        | `.check(status().is(200))`. bonanza's spin returns **201**.                              |
| Stateful captures (optional) | `.check(jsonPath("$.data.sessionId").saveAs("sessionId"))`, needed only if a later step reads `#{sessionId}` |

**4.3** `games/$NEW/src/gatling/resources/games/$PKG/bodies/*.json`: one file per endpoint that POSTs a body.

```json
{
  "userId": "#{userId}",
  "gameId": "fruit_respin_mania_usecase",
  "betAmount": 1.0
}
```

Two rules come up over and over:

- **Use Gatling EL `#{userId}`, never `${userId}`.** The latter ships the literal string `${userId}`, so every VU sends the same value. That causes distributed-lock collisions and about 51 % HTTP 400.
- **Match the backend's exact field types.** A JSON string and a JSON number make different requests. bonanza's `/spin` takes `betAmount` as a **string**, while `/jackpot/*` takes it as a **number**.

**gRPC-only games.** Edit `buildJoinRequest` and `buildSpinRequest` in the Scala file:

- the `PluginName` and `Zone` constants
- the `user.parameters` map (identity fields the backend's connect handler needs)
- the `PluginRequest.data` map (`cmd` code and bet fields)

Keep both maps as `java.util.LinkedHashMap` and encode them with `codec.encode(...)`. If the `Call` reply carries the result, copy mutantmerge's `spinStatus` check, which decodes the reply and fails on a non-zero `c`. If results go over ZMQ, Gatling only sees transport failures. Document a backend-log check, as the zeroday simulation does.

### Phase 5 Scenario

**REST games** use `scenarios/SessionJourneyScenario.java` (silkroad clone) or `scenarios/PlayerJourneyScenario.java` (bonanza clone).

**5.1** Make the scenario name unique: `DEFAULT_NAME` (silkroad) or `NAME` (bonanza), in the form `<game>-<purpose>`. Step 1.4 usually did this already.

```java
public static final String DEFAULT_NAME = "fruitrespinmania-session-journey";
```

Gatling rejects two scenarios with the same name in one `setUp()`. Game-specific names also keep reports from different games distinguishable.

**5.2** Adapting silkroad's ratio-modulo routing: edit the secondary requests and their `oneInN` values. A VU runs a secondary request when `userIndex % oneInN == 0`, using the feeder's `userIndex`.

**5.3** Adapting the bonanza pattern: edit the init chain (`betLevels → reelStrips → createSession`) and the `randomSwitch` weights. Drop entries that don't apply.

**gRPC-only games:** the scenario is inline in the Scala file (`scenario("<pkg>-grpc-player-journey")`). The request names `"Join"` and `"Spin"` are referenced again by the `details("Spin")` / `details("Join")` assertions. Rename them together or not at all.

### Phase 6 Simulation

**REST games.** The `simulations/` classes are thin wrappers around the `:core` bases, and step 1.4 did most of the edits. Re-check these:

| File (silkroad clone)    | What to verify                                                                            |
|--------------------------|-------------------------------------------------------------------------------------------|
| `SoakSimulation.java`    | Compiles; uses the scenario name from 5.1.                                                |
| `StressSimulation.java`  | Compiles.                                                                                 |
| `SpikeSimulation.java`   | Has **two** hardcoded population names, `"<pkg>-spike-baseline"` and `"<pkg>-spike-burst"` (step 1.4 renames them from `silkroad-…`). They must stay distinct. |
| `BasicSimulation.java`   | The `endpoints()` list matches `Endpoints.java` and `SlotRequests.java`.                  |

For the bonanza pattern, you only need `SoakSimulation` and `BasicSimulation`. Its Soak class **extends `Simulation` directly** rather than `SoakSimulationBase`, because the base hard-codes open-model injection. Leave that alone.

**gRPC-only games:**

- Keep the `setUp(...)` block from the template: closed injection, `maxDuration` from `SlaConstants.MAX_DURATION_BUFFER_MIN`, and the seven assertions.
- Read every setting with `Integer.getInteger` / `sys.props`. Don't import `LoadTestConfig` or anything else compiled against Gatling 3.15.
- Update the class comment's list of defaults.

### Phase 7 Game SLA overrides

This phase is optional. If the new game has different performance characteristics, list only the fields that differ in `games/$NEW/src/gatling/resources/sla-thresholds.yml`. Anything you leave out is inherited from `config/sla-thresholds.yml`.

```yaml
sla:
  pr4_mean_response_ms_max: 400   # tighter than the default 500 ms
  pr5_error_percent_max: 0.5      # tighter than the default 1.0 %
```

The wrapper passes `-DgameName=$NEW` to the verifier, so the verdict applies the file automatically. Gatling's own assertions can only pick it up through the classpath layer (see [SLA thresholds and load order](#sla-thresholds-and-load-order)). A comments-only file is a no-op.

### Phase 8 Wire the wrapper script

`scripts/run-variant.sh` has two per-game `case "$GAME"` blocks. Without a row for your game, the wrapper falls back to port 3000 and `/actuator/health`.

**8.1 Default port.** Add a row to the block that sets `PORT` when `--port` isn't passed:

```bash
case "$GAME" in
  bonanza)   PORT=3005 ;;
  # … existing games …
  fruit-respin-mania) PORT=4000 ;;       # add
  *)        PORT=3000 ;;
esac
```

**8.2 Health probe URL.** Add a row that returns 2xx on a healthy backend:

```bash
case "$GAME" in
  bonanza)   HEALTH_URL="http://localhost:${PORT}/golden/api/configs/bet-levels" ;;
  # … existing games …
  fruit-respin-mania) HEALTH_URL="http://localhost:${PORT}/fruit/actuator/health" ;;   # add
  *)        HEALTH_URL="http://localhost:${PORT}/actuator/health" ;;
esac
```

The same URL drives `health.csv`, the PR-3 crash check and `ensure-sut.sh`'s "is it up?" test. Pick something cheap (under 50 ms typical). Spring Boot's `/actuator/health` is a safe default when exposed; the gRPC-only games use `/health`, or `/api/game/zeroday/actuator/health` for zeroday.

**8.3 Optional:** add the new id to the `--game <…>` lists in the script's usage comment and error message.

### Phase 9 Wire the menu

Add a row to the `GAMES` array at the top of `loadtest.sh`, using the format `id|display name|container|simulations|Basic scenarios`:

```bash
GAMES=(
  # … existing rows …
  "fruit-respin-mania|Fruit Respin Mania|game-fruit-respin-mania|Soak Stress Spike Basic|spin last-spin history-summary all chain burst"
)
```

- **Simulations** is a space-separated list from `Soak Stress Spike Basic Grpc`. Each must map to an alias task in the game's `build.gradle` (a `SIMULATIONS` entry, or bonanza's `grpc` task).
- **Basic scenarios** is the space-separated list offered for `Basic`. A gRPC-only game leaves it empty (`"…|Grpc|"`).
- **The container name** drives the `●` / `○` running marker and is passed as `--container`.

### Phase 10 Wire automatic start

To let `ensure-sut.sh` start the SUT for you, add `games/$NEW/docker-compose.loadtest.yml`, modelled on an existing one (`games/silkroad/` for REST, `games/zeroday/` or `games/mutantmerge/` for gRPC). Without it, a run against a stopped SUT fails with "can't be started automatically". bonanza is in that state today.

The script copies the file into the backend's root folder (next to its `Dockerfile`) and runs `HTTP_PORT=<wrapper port> docker compose -f docker-compose.loadtest.yml up -d --build`. The file needs these parts:

| Element | Why |
|---------|-----|
| `name: <game>-loadtest` | Gives it its own Compose project, so it never collides with the backend's own compose stack or another game. |
| `build: { context: ., dockerfile: Dockerfile }` | Builds from the folder the script copied it into. |
| A fixed `container_name` | Must equal the `GAMES` row's container. `ensure-sut.sh`, `loadtest.sh` and `monitor-resources.sh` match it by exact name. |
| Ports `"${HTTP_PORT:-3000}:<app port>"`, plus the gRPC port for gRPC games | `ensure-sut.sh` always sets `HTTP_PORT` to the wrapper's port. The `:-3000` default only matters when you run compose by hand. |
| Its own Mongo, Redis, … with healthchecks, and `depends_on: condition: service_healthy` | Self-contained; no shared infrastructure. |
| Mock wallet settings | For example `WALLET_GATEWAY: mock` (naga777, mutantmerge) or `LUIGI_WALLET_ENABLED: "false"` (silkroad, zeroday), so no real wallet or auth service is needed. |
| `mem_limit`, `logging: driver: json-file` | A realistic memory cap, and `docker logs` keeps working for post-run log checks. |

Skeleton (replace every `<…>` and the environment variables with your backend's own):

```yaml
# Local SUT for rgp-game-load-test: <Game> + its own Mongo/Redis.
# Copy into the backend repo root (the folder with the Dockerfile), then:
#   docker compose -f docker-compose.loadtest.yml up -d --build
name: fruit-respin-mania-loadtest

services:
  game-fruit-respin-mania:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: game-fruit-respin-mania
    mem_limit: 768m
    environment:
      MONGODB_URI: mongodb://mongo:27017/<db-name>
      REDIS_HOST: redis
      # mock wallet / disable external integrations here
    ports:
      - "${HTTP_PORT:-3000}:3000"     # host port : port the app listens on in the container
      # - "<grpc-port>:<grpc-port>"   # gRPC games
    logging:
      driver: json-file
    depends_on:
      mongo:
        condition: service_healthy
      redis:
        condition: service_healthy

  mongo:
    image: mongo:7-jammy
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping')"]
      interval: 5s
      retries: 20

  redis:
    image: redis:7-alpine
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 20
```

### Phase 11 Update the docs

**11.1** Add a row to [Games today](#games-today) with the game id, backend repo, container, HTTP port and supported simulations.

**11.2** If you added a compose file, add the game to the list in [Starting the game server automatically](#starting-the-game-server-automatically).

**11.3** Update the game lists in [Wrapper flags](#wrapper-flags) and [The simulations](#the-simulations) if the new game changes them (for example a new `Grpc` game or new `--scenario` values).

### Phase 12 Smoke test and verify

Run these in order. If a step fails, go back to the phase that produced it.

**12.1 Compile.** This catches package, import and FQCN errors:

```bash
./gradlew :games:$NEW:compileGatlingJava                                   # silkroad clone, or bonanza clone without gRPC
./gradlew :games:$NEW:compileGatlingJava :games:$NEW:compileGatlingGrpcScala  # bonanza clone with gRPC
./gradlew :games:$NEW:compileGatlingScala                                  # gRPC-only clone
```

Expect `BUILD SUCCESSFUL`. A gRPC-only module reports `compileGatlingJava NO-SOURCE`, which is fine.

**12.2 First run through Gradle.**

REST: 1 VU × 1 request.

```bash
./gradlew :games:$NEW:basic -Dscenario=spin -Dusers=1 -Drequests=1
```

- `Unknown scenario: '…'. Valid: …` means `-Dscenario` doesn't match an `Endpoints.java` name.
- `simulation class not found` means the FQCN in `SIMULATIONS` doesn't match the `package` line.

Both appear before any request is sent, so they surface even while the backend is down.

gRPC (needs the backend up): 1 VU for 1 minute, with the volume floors off.

```bash
./gradlew :games:$NEW:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
  -DrequestRate=0 -DeventCount=0
```

**12.3 Smoke end to end through the wrapper.** If the SUT isn't running and the game has a compose file (Phase 10), the wrapper offers to start it. Without a compose file, start the SUT yourself first.

```bash
# REST
./scripts/run-variant.sh --game $NEW \
  --variant target --simulation Basic --scenario spin \
  --users 1 --requests 1 --duration-minutes 1 --ramp-minutes 0 \
  --container game-fruit-respin-mania

# gRPC
./scripts/run-variant.sh --game $NEW \
  --variant target --simulation Grpc \
  --users 10 --duration-minutes 1 --ramp-minutes 0 \
  --container game-fruit-respin-mania
```

A green `PASS` in `target/variants/$NEW/target-<timestamp>/summary.html` means every piece is wired:

- Gradle subproject → simulation class → requests with the right paths and schema
- health probe → resource monitor → verdict

On the short gRPC smoke, Gatling's own `requestRate` / `eventCount` assertions fail, because the wrapper can't lower them. The wrapper exits non-zero and `verdict.json` shows `gatling_exit_code: 1`, but the six-row verdict can still be `PASS`.

**12.4 Scale up.** Once the smoke passes, run a 5-minute Soak (or `Grpc`) at a moderate VU count, then the production gate. Finally, run `./loadtest.sh` once to check that the new row appears and runs.

### Developer gotchas

- **Gatling EL is `#{userId}`, not `${userId}`** (Gatling 3.7+ Java DSL). The wrong form ships a literal string and triggers lock collisions on the backend, which shows up as about 51 % HTTP 400.
- **bonanza's spin returns HTTP 201**; silkroad's returns 200. `status().is(…)` is set per game.
- **bonanza field names are inconsistent on purpose** (they match the backend). `/sessions` and `/spin` use `playerId`; `/jackpot/*` and `/history/*` use `userId`. `betAmount` is a JSON string for `/spin` and a number for `/jackpot/*`.
- **Scenario names must be unique within a `setUp()`.** Spike simulations have two populations, so pass distinct names (Phase 6).
- **A new `-D` flag has no effect?** Add it to `FORWARDED_PROPS` in `games/<game>/build.gradle` (Phase 2.3). For the gRPC `JavaExec` tasks, that list is the only way in. `gameName` is injected by the alias tasks.
- **The wrapper always forwards `-Dport`**, which overrides the port in `game.yml`. Keep the `PORT` case in `run-variant.sh` in sync with `game.yml`.
- **The REST body file path uses `$PKG`, not `$NEW`**, as in `ElFileBody("games/<pkg>/bodies/spin.json")`, because the resource directory is renamed to the package form in Phase 1.3.
- **The JDK 17 `--add-opens` flags are mandatory.** They are already set in every module's `gatling.jvmArgs` or `JavaExec` `jvmArgs`. Don't drop them: Gatling reflects into JDK internals, and you'll see `InaccessibleObjectException` at simulation init.
- **Never let Gatling 3.15 onto a gRPC classpath.** Keep the `exclude group: 'io.gatling'` / `'io.gatling.highcharts'` on `project(':core')`, and don't use `LoadTestConfig` (or any other `:core` class built on Gatling types) from Scala.
- **Gatling assertions and the verdict are separate gates.** The `requestRate` / `eventCount` floors and the per-request thresholds (`Spin` p95 ≤ 800 ms, failed ≤ 0.5 %) are Gatling assertions only. When one fails, the Gradle task fails and `./gradlew` exits `1`. That becomes the wrapper's exit code and `gatling_exit_code` in `verdict.json`, but these assertions are not among the six verdict rows.
- **`health-poll.sh` gives up after 3 consecutive failures**, regardless of `crash.max_consecutive_failures`. The recorded streak therefore never exceeds 3, so raising that value above 3 means PR-3 can never fail.
- **A comments-only per-game `sla-thresholds.yml` is skipped**, so `sla_config_source` then points at `config/sla-thresholds.yml`. That is expected, not a bug.
- **The GaaS JAR is Java 21 bytecode.** `:core:downgradeGaasJar` rewrites class-file major versions above 61 down to 61 (Java 17). That only works while the library uses no Java 18+ APIs. If you replace `core/libs/common-data-1.0.0.jar`, keep the file name, or update the task and `core/libs/README.md`.
- **MessagePack payloads are order-sensitive.** Build them as `LinkedHashMap` and don't reorder keys.

## Where to learn more

- [`HUONG-DAN.md`](HUONG-DAN.md): step-by-step beginner guide in Vietnamese. It predates `loadtest.sh` and the gRPC-only games, so it covers silkroad and bonanza only. It links to `docs/getting-started.md`, which is not in this repo.
- [`naga777-load-test-guide.md`](naga777-load-test-guide.md): how to run naga777 and read its results, including `generate-final-report.py`.
- [`core/libs/README.md`](core/libs/README.md): where the bundled GaaS JAR comes from and why it is downgraded.
- Gatling: [EL syntax](https://docs.gatling.io/reference/script/core/session/el/) · [Gradle plugin](https://docs.gatling.io/reference/integrations/build-tools/gradle-plugin/) · [gRPC DSL](https://docs.gatling.io/reference/script/protocols/grpc/). The last one documents the Enterprise-gated first-party DSL, which this repo does **not** use for its gRPC simulations.
- [`phisgr/gatling-grpc`](https://github.com/phisgr/gatling-grpc): the community gRPC plugin (0.17.0, Gatling 3.9.5) behind all four `Grpc` simulations. It is archived upstream.

---

# Hướng dẫn tiếng Việt

[English](#rgp-game-load-test) · **Tiếng Việt**

Bộ công cụ load test cho backend các game slot RGP, xây dựng trên Gatling. Nó giả lập hàng trăm đến hàng nghìn người chơi dùng game cùng lúc, đo thời gian phản hồi, lỗi, CPU và bộ nhớ, rồi đưa ra **kết luận PASS / FAIL (verdict)** để quyết định có được deploy lên production hay không.

**Một lệnh làm tất cả.** Chạy `./loadtest.sh` và trả lời vài câu hỏi, hoặc gọi `./scripts/run-variant.sh` kèm các flag. Dù chọn cách nào, harness cũng:

1. kiểm tra game server đã chạy chưa, và đề nghị khởi động nếu chưa,
2. ghi lại CPU / bộ nhớ và tình trạng health của server trong lúc test,
3. chạy test Gatling,
4. so kết quả với các ngưỡng pass/fail,
5. tạo trang `summary.html` gói gọn trong một trang để bạn mở bằng trình duyệt.

Cũng có thể chạy trực tiếp `./gradlew :games:…`, nhưng cách đó không cho verdict. Xem [Chạy nâng cao](#chạy-nâng-cao-không-qua-wrapper).

Mới dùng lần đầu? Hãy tới [Bắt đầu nhanh](#bắt-đầu-nhanh). Một smoke test đầu tiên chỉ mất vài phút.

## Ai nên đọc phần nào

| Bạn là…                                        | Nên đọc các phần                                                                                                                                                                                                     |
|------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **QC / PM / DevOps (không chuyên kỹ thuật)**   | [Cài đặt](#cài-đặt) → [Bắt đầu nhanh](#bắt-đầu-nhanh) → [Đọc hiểu kết quả](#đọc-hiểu-kết-quả) → [Xử lý sự cố](#xử-lý-sự-cố). Khi cần khởi động server: [Tự động khởi động game server](#tự-động-khởi-động-game-server). |
| **Chạy test thủ công hoặc từ CI**              | Tất cả các phần trên, thêm [Chạy test](#chạy-test) và [Chạy nâng cao](#chạy-nâng-cao-không-qua-wrapper).                                                                                                            |
| **Developer thêm hoặc sửa game**               | Tất cả các phần trên, thêm [Cấu hình](#cấu-hình), [Cấu trúc project](#cấu-trúc-project), [Runtime cho gRPC](#runtime-cho-grpc) và [Thêm game mới](#thêm-game-mới).                                                  |

Đọc thêm: [`HUONG-DAN.md`](HUONG-DAN.md) (hướng dẫn tiếng Việt cho người mới), [`naga777-load-test-guide.md`](naga777-load-test-guide.md) (Naga's Fortune 777: cách chạy và đọc kết quả), và [Tài liệu tham khảo thêm](#tài-liệu-tham-khảo-thêm).

Hai thuật ngữ dùng xuyên suốt bên dưới:

- **VU (virtual user)**: một người chơi giả lập.
- **SUT (system under test)**: game server đang được test.

## Các game hiện có

Bảng này là nguồn chuẩn cho các giá trị mà mọi lệnh bên dưới sử dụng. Các game server ("backend") nằm ở **các repository riêng**, đặt cạnh repo này. Repo này không build chúng.

| Game                | `GAME`        | Thư mục source backend (ví dụ)                   | Container                  | Port HTTP | Port gRPC | Đường dẫn health probe              | Simulation                         | Tự khởi động |
|---------------------|---------------|--------------------------------------------------|----------------------------|-----------|-----------|-------------------------------------|------------------------------------|--------------|
| Silk Road Caravans  | `silkroad`    | `../be-silk-road-caravans`                       | `game-silk-road-caravans`  | `3000`    | —         | `/actuator/health`                  | `Soak`, `Stress`, `Spike`, `Basic` | Có           |
| Golden Boat Bonanza | `bonanza`     | `../be-golden-boat-bonanza`                      | `game-golden-boat-bonanza` | `3005`    | `9091`    | `/golden/api/configs/bet-levels`    | `Soak`, `Basic`, `Grpc`            | **Không**    |
| Naga's Fortune 777  | `naga777`     | `../Stable_NAGAS_777/stable-be-naga-fortune-777` | `stable-naga_fortune_777`  | `3000`    | `9096`    | `/health`                           | `Grpc`                             | Có           |
| Mutant Merge        | `mutantmerge` | `../Stable_Mutant_Merge/be-mutant-merge`         | `stable-game-mutant-merge` | `3000`    | `9104`    | `/health`                           | `Grpc`                             | Có           |
| Zero Day            | `zeroday`     | `../be-zero-day`                                 | `game-zero-day`            | `3000`    | `9103`    | `/api/game/zeroday/actuator/health` | `Grpc`                             | Có           |

Ý nghĩa các cột:

- **Thư mục source backend**: thư mục chứa `Dockerfile` của game. Các đường dẫn chỉ là ví dụ; hãy dùng đúng chỗ repo nằm trên máy bạn.
- **Container**: tên Docker container mà harness theo dõi CPU / bộ nhớ. Các file compose dùng để tự khởi động đặt đúng các tên này. Nếu bạn khởi động server theo cách khác, hãy xem tên thật bằng `docker ps`.
- **Port HTTP**: giá trị mặc định khi bạn không truyền `--port`. URL health probe là `http://localhost:<HTTP port><health probe path>`.
- **Port gRPC**: nơi simulation `Grpc` kết nối tới (luôn trên `localhost` khi bạn dùng wrapper). Silk Road không có test gRPC.
- **Tự khởi động**: "Có" nghĩa là có file `games/<game>/docker-compose.loadtest.yml`, nên harness có thể khởi động server giúp bạn. Xem [Tự động khởi động game server](#tự-động-khởi-động-game-server). Bonanza không có file này, nên bạn phải tự khởi động.

`settings.gradle` còn khai báo sẵn bốn game khác nhưng đang tắt (bị comment).

## Cài đặt

**Yêu cầu**

| Công cụ                                  | Để làm gì                                                                                                            |
|------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| **JDK 17**                               | Mọi simulation và bộ verifier. Build cố định toolchain Java 17 và không cấu hình tự tải JDK, nên phải cài sẵn JDK 17 trên máy. |
| **Docker** với **Compose v2 trở lên**    | Chạy game server. Các script gọi `docker compose` (dạng plugin), không phải `docker-compose` bản cũ.                  |
| **Python 3.9+**                          | Tạo `summary.html` và báo cáo tổng hợp nhiều lần chạy.                                                               |
| **git**                                  | Chỉ cần cho lựa chọn khởi động "tải source code từ git".                                                             |
| **curl**                                 | Gọi health probe. Có sẵn trên macOS và hầu hết các bản Linux.                                                        |
| **macOS hoặc Linux**                     | Chưa được test trên Windows. `loadtest.sh` viết cho bash 3.2 có sẵn của macOS, nên không cần bash mới hơn.           |

Bạn không cần cài Gradle. `./gradlew` đi kèm Gradle 9.2.1 và tự tải về ở lần dùng đầu tiên.

**Kiểm tra**

```bash
java -version            # 17.x
./gradlew --version      # Gradle 9.2.1
docker --version
docker compose version   # v2 trở lên
python3 --version        # 3.9 trở lên
git --version
curl --version
```

Lần chạy đầu còn phải tải các dependency của Gradle, nên sẽ lâu hơn các lần sau.

## Bắt đầu nhanh

### Cách dễ nhất dùng menu tương tác

```bash
./loadtest.sh
```

Menu hỏi vài câu, cho bạn xem đúng lệnh sắp chạy, chạy lệnh đó, rồi đề nghị mở báo cáo. Bạn không cần nhớ flag nào. Hầu hết câu trả lời là một con số, còn câu hỏi có/không thì gõ `y` hoặc `n`. Nhấn Enter để chọn giá trị mặc định ghi trong `[ngoặc vuông]`. Gõ sai số thì menu hỏi lại. Nhấn Ctrl+D tại một câu hỏi để thoát.

**Màn hình 1: chọn game.** Mỗi game hiện ● nếu container của nó đang chạy và ○ nếu không. Server chạy ngoài Docker sẽ hiện ○, nhưng test vẫn chạy được miễn là health probe của nó trả lời.

**Màn hình 2: chọn loại test.** Chỉ các game có nhiều hơn một loại test mới hỏi câu này. Naga's Fortune 777, Mutant Merge và Zero Day chỉ có `gRPC`, nên menu in loại đó ra rồi đi tiếp.

| Chữ hiện trên menu                                                      | Simulation |
|-------------------------------------------------------------------------|------------|
| `Soak    steady load for a while (finds slowdowns and memory leaks)`   | `Soak`     |
| `Stress  keep adding players until the server struggles`               | `Stress`   |
| `Spike   normal load with sudden rushes of players`                     | `Spike`    |
| `Basic   call one API many times`                                       | `Basic`    |
| `gRPC    steady load over gRPC, the way the real game client connects` | `Grpc`     |

**Màn hình 3: quy mô test.** Với mọi loại test trừ `Basic`:

| Lựa chọn        | Số người chơi            | Thời lượng test | Warm-up                        | Ngưỡng pass                                   |
|-----------------|--------------------------|-----------------|--------------------------------|-----------------------------------------------|
| **Smoke**       | 10                       | 1 phút          | không có                       | `target`: CPU dưới 70 %, bộ nhớ dưới 80 %     |
| **Quick check** | 200                      | 5 phút          | 1 phút                         | `baseline`: CPU dưới 50 %, bộ nhớ dưới 60 %   |
| **Full test**   | 1000                     | 60 phút         | 5 phút (gRPC: 2 phút)          | `target`: CPU dưới 70 %, bộ nhớ dưới 80 %     |
| **Custom**      | bạn tự chọn              | bạn tự chọn     | bạn tự chọn                    | bạn tự chọn                                   |

"Warm-up" nghĩa là người chơi vào game dần dần trong khoảng thời gian đó. **Full test** là bài kiểm tra trước khi release.

**Custom** hỏi theo thứ tự sau:

1. `Number of players (virtual users) [1000]` (số người chơi)
2. `Test length in minutes (not counting warm-up) [60]` (thời lượng test tính bằng phút, không tính warm-up)
3. `Warm-up minutes (players join gradually) [5]`. Với gRPC, mặc định là `2`. Được phép nhập `0`.
4. Chỉ với Soak: `Let all players join at once instead of gradually? [y/N]`. Trả lời có sẽ thêm `--parallel`. Flag này chỉ có tác dụng với Silk Road (xem [Tham số của wrapper](#tham-số-của-wrapper)).
5. `When does the test pass? The server's CPU and memory must stay:`, sau đó chọn một trong bốn ngưỡng: `baseline` (CPU dưới 50 %, bộ nhớ dưới 60 %), `target` (70 % / 80 %, chuẩn release), `stress` (85 % / 90 %) hoặc `critical` (95 % / 95 %).
6. `Game server HTTP port [Enter = usual port for this game]`. Chỉ gõ port nếu server chạy trên port khác mặc định.

**Basic** hỏi những câu khác thay cho quy mô:

1. `Which API`: endpoint cần gọi (danh sách ở [Test từng endpoint riêng lẻ](#test-từng-endpoint-riêng-lẻ)).
2. `Number of players (virtual users) [500]`
3. `Total number of requests [5000]`

Một lần chạy Basic luôn dùng ngưỡng `target`. Nó kết thúc khi đã gửi hết request. Menu truyền 1 phút và không warm-up; giá trị này chỉ quyết định các monitor CPU / health được chạy tối đa bao lâu.

> Stress và Spike chạy theo hình dạng tải dựng sẵn của riêng chúng. Quy mô bạn chọn không áp dụng hoàn toàn cho chúng. Xem [Các loại simulation](#các-loại-simulation).

**Màn hình 4: kiểm tra trước khi bắt đầu.** Menu cho bạn thấy nó sẽ chạy gì, kèm lệnh `run-variant.sh` tương đương sau chữ `Same as`. Hãy copy dòng đó nếu muốn chạy lại test sau này mà không qua menu, hoặc để chạy trong CI.

```text
== Check before starting ==
  Game         Silk Road Caravans (server container: game-silk-road-caravans)
  Test type    Soak
  Size         10 players for 1 min, no warm-up
  Pass limit   CPU under 70%, memory under 80%  (target: release standard)
  Same as      ./scripts/run-variant.sh --game silkroad --variant target --simulation Soak --users 10 --duration-minutes 1 --ramp-minutes 0 --container game-silk-road-caravans
Start now? [Y/n]:
```

Nhấn Enter (hoặc `y`) để bắt đầu. Bất kỳ câu trả lời nào khác, ví dụ `n`, sẽ in `Cancelled — nothing was run.` rồi thoát.

**Trong lúc chạy.** Nếu game server chưa chạy, bạn sẽ được hỏi muốn khởi động nó theo cách nào. Xem [Tự động khởi động game server](#tự-động-khởi-động-game-server). Sau đó test chạy, và console hiện các bộ đếm trực tiếp của Gatling ([Đọc output trên console](#đọc-output-trên-console)).

**Khi kết thúc.** Nếu lần chạy tạo ra `summary.html` mới, menu hỏi `Open the result report (summary.html) in your browser? [Y/n]`. Báo cáo được mở bằng `open` trên macOS hoặc `xdg-open` trên Linux.

**Một ví dụ đầy đủ**, smoke test cho Silk Road:

```text
$ ./loadtest.sh
== Which game do you want to test? ==  (● server running  ○ server not running)
  1) ○ Silk Road Caravans
  2) ○ Golden Boat Bonanza
  3) ○ Naga's Fortune 777
  4) ○ Mutant Merge
  5) ● Zero Day
Game [1-5]: 1

== What kind of test? ==
  1) Soak    steady load for a while (finds slowdowns and memory leaks)
  2) Stress  keep adding players until the server struggles
  3) Spike   normal load with sudden rushes of players
  4) Basic   call one API many times
Test type [1-4]: 1

== How big should the test be? ==
  1) Smoke         10 players for 1 minute (just checks that it works)
  2) Quick check   200 players for 5 minutes
  3) Full test     1000 players for 60 minutes (the release check)
  4) Custom        choose the numbers yourself
Size [1-4]: 1
...
```

**Exit code.** `loadtest.sh` kết thúc với cùng exit code như `run-variant.sh`, tức là exit code của lần chạy Gatling: `0` nghĩa là mọi assertion của Gatling đều pass. Verdict release (PASS / FAIL) nằm trong `summary.html` và `verdict.json`, và hai thứ này có thể không khớp nhau (xem [Các loại simulation](#các-loại-simulation)). Nếu không khởi động được server, exit code là `1`.

**Không có terminal (CI, pipe).** Menu từ chối chạy và in `loadtest.sh needs a terminal to ask questions. In CI, run scripts/run-variant.sh directly.` (exit code `1`).

### Cách thủ công bốn bước

Chọn dòng của game bạn trong [Các game hiện có](#các-game-hiện-có) và export các giá trị của dòng đó. Ví dụ với Silk Road:

```bash
export GAME=silkroad
export BACKEND_DIR=../be-silk-road-caravans
export CONTAINER=game-silk-road-caravans
export PORT=3000
```

**1. Khởi động game server.** Bạn có thể bỏ qua bước này với mọi game trừ Bonanza: nếu server chưa chạy, bước 3 sẽ đề nghị khởi động nó ([Tự động khởi động game server](#tự-động-khởi-động-game-server)). Để tự khởi động bằng file compose dành cho load test:

```bash
cp games/$GAME/docker-compose.loadtest.yml "$BACKEND_DIR"/
(cd "$BACKEND_DIR" && HTTP_PORT=$PORT docker compose -f docker-compose.loadtest.yml up -d --build)
```

Lần build đầu có thể mất vài phút. Với Bonanza, hãy dùng cấu hình Docker riêng của repo backend và đảm bảo nó trả lời trên port `3005` (và `9091` cho gRPC).

**2. Kiểm tra server có trả lời không.** Dùng health probe của game bạn. Mã `2xx` bất kỳ (thường là `200`) nghĩa là server đã lên. Nếu là mã khác: đợi một chút rồi thử lại, hoặc xem [Xử lý sự cố](#xử-lý-sự-cố).

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/actuator/health                  # silkroad
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3005/golden/api/configs/bet-levels     # bonanza
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/health                            # naga777, mutantmerge
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:3000/api/game/zeroday/actuator/health  # zeroday
```

**3. Chạy smoke test 1 phút.** Dùng `--simulation Grpc` cho naga777, mutantmerge và zeroday.

```bash
./scripts/run-variant.sh \
  --game "$GAME" \
  --variant target --simulation Soak \
  --users 10 --duration-minutes 1 --ramp-minutes 0 \
  --container "$CONTAINER" --port "$PORT"
```

**4. Mở trang tóm tắt.**

```bash
open "$(ls -td target/variants/$GAME/target-* | head -1)/summary.html"      # macOS (Linux: xdg-open)
```

Banner **PASS** màu xanh lá nghĩa là test đã vượt qua mọi ngưỡng.

## Tự động khởi động game server

`run-variant.sh` (và do đó cả menu) gọi `scripts/ensure-sut.sh` trước mỗi lần test. Khi game server không chạy, script này có thể khởi động nó từ source code của game bằng `games/<game>/docker-compose.loadtest.yml`. File compose đó chạy game cùng Mongo và Redis riêng (và RabbitMQ với Zero Day), tắt wallet thật và không cần dịch vụ bên ngoài nào.

**Khi nào script can thiệp.** Chỉ khi **cả hai** điều sau đều đúng:

- không có container **đang chạy** nào mang tên container của game, **và**
- URL health probe không trả lời bằng mã `2xx`.

Server chạy ngoài Docker vẫn được chấp nhận miễn là health probe trả lời. Container đang chạy cũng được chấp nhận dù nó còn đang khởi động. Khi đó harness không chờ, nên với server vừa khởi động, hãy đợi nó trả lời được đã.

**Bạn sẽ thấy gì.**

```text
[ensure-sut] The silkroad game server isn't running (no running container 'game-silk-road-caravans', and http://localhost:3000/actuator/health doesn't answer).
How do you want to start it?
  1) The game's source code is on this machine (you paste the folder path)
  2) Download the source code from git, branch main (you paste the git URL)
  q) Cancel
Choice [1/2/q]: 1
Folder with the game's source code: ~/Projects/be-silk-road-caravans
[ensure-sut] Starting the game server (the first time can take a few minutes): HTTP_PORT=3000 docker compose -f docker-compose.loadtest.yml up -d --build
[ensure-sut] Waiting for the server to answer http://localhost:3000/actuator/health (up to 300s)
[ensure-sut] Game server is up.
```

**Lựa chọn 1: thư mục trên máy này.** Dán đường dẫn thư mục backend có chứa `Dockerfile` (xem [Các game hiện có](#các-game-hiện-có)).

- Bạn có thể kéo thư mục từ Finder thả vào terminal. Dấu cách đã được escape (`\ `) vẫn được xử lý đúng.
- Dấu nháy đơn và nháy kép bị bỏ đi, nên đường dẫn nằm trong dấu nháy vẫn dùng được.
- Dấu `~` ở đầu nghĩa là thư mục home của bạn.
- Đường dẫn tuyệt đối là an toàn nhất. Đường dẫn tương đối được tính từ thư mục bạn đang đứng khi chạy lệnh.

**Lựa chọn 2: tải từ git.** Dán URL của repository, ví dụ `https://git.example.com/<group>/<backend-repo>.git`. Script chỉ clone nhánh **`main`**, vào một thư mục tạm mới (`$TMPDIR/<game>-sut.XXXXXX`, hoặc `/tmp/…` khi không có `TMPDIR`). git dùng thông tin đăng nhập bạn vẫn dùng hằng ngày (SSH key hoặc credential helper). Mỗi lần chọn lựa chọn 2 là một bản clone mới. Thư mục này không bị xóa sau đó, vì stack đang chạy được build từ nó. Thư mục gốc của repository phải chứa `Dockerfile`. Nếu backend nằm trong một thư mục con của repository lớn hơn, hãy dùng lựa chọn 1.

Câu trả lời khác (ví dụ `q`) sẽ in `Cancelled.` và dừng lần chạy.

**Chuyện gì xảy ra tiếp theo.**

1. Script kiểm tra thư mục có tồn tại và có chứa `Dockerfile` không.
2. Nó copy `games/<game>/docker-compose.loadtest.yml` vào thư mục đó. Nếu ở đó đã có một file **khác** cùng tên, file cũ được đổi tên thành `docker-compose.loadtest.yml.bak` trước, và script báo cho bạn biết. Nếu file giống hệt thì chỉ đơn giản bị thay thế.
3. Nó chạy `HTTP_PORT=<port> docker compose -f docker-compose.loadtest.yml up -d --build` trong thư mục đó. `HTTP_PORT` là giá trị `--port` của wrapper (mặc định lấy từ [Các game hiện có](#các-game-hiện-có)), và trở thành port của game trên máy bạn. Port gRPC cố định theo từng game.
4. Nó kiểm tra health probe mỗi 3 giây, tối đa **300 giây**. Khi probe trả lời, bạn thấy `Game server is up.` và test bắt đầu. Nếu hết thời gian mà vẫn không trả lời, script in 50 dòng log cuối của container rồi dừng với `the server didn't come up within 300s`.

Server vẫn tiếp tục chạy sau khi test xong. Các lần test sau dùng lại nó và bỏ qua toàn bộ các bước này.

**Khi không khởi động được server.** Lần chạy dừng với một dòng `[ensure-sut] ERROR:` trong các trường hợp sau (chi tiết ở [Xử lý sự cố](#xử-lý-sự-cố)):

| Tình huống                                     | Chuyện gì xảy ra                                                                                                                          |
|------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| **Bonanza**                                    | Game này không có `docker-compose.loadtest.yml`, nên script bảo bạn tự khởi động server rồi chạy lại.                                     |
| **Không có terminal** (CI, pipe)               | Script không hỏi bạn được, nên in ra lệnh thủ công rồi dừng: `cp <repo>/games/<game>/docker-compose.loadtest.yml <source-dir>/ && cd <source-dir> && HTTP_PORT=<port> docker compose -f docker-compose.loadtest.yml up -d --build` |
| **Docker chưa chạy**                           | `Docker isn't running. Open Docker Desktop, wait until it's ready, then run again.`                                                        |
| **Port đã bị chiếm**                           | `docker compose` thất bại. Nhiều khả năng stack của một game khác đang giữ port 3000. Hãy dừng stack đó (xem bên dưới), hoặc chạy với `--port 3010` (menu: **Custom**, câu hỏi cuối). |

**Dừng server sau khi test.** Mỗi file compose đặt cho stack của nó một tên project cố định, nên các lệnh sau chạy được từ bất kỳ thư mục nào:

| Game          | Tên compose project    |
|---------------|------------------------|
| `silkroad`    | `silkroad-loadtest`    |
| `naga777`     | `naga777-loadtest`     |
| `mutantmerge` | `mutantmerge-loadtest` |
| `zeroday`     | `zeroday-loadtest`     |

```bash
docker compose ls                               # các stack đang chạy
docker compose -p zeroday-loadtest stop         # tạm dừng; `start` để chạy lại
docker compose -p zeroday-loadtest down         # xóa container và network của stack (image vẫn giữ)
docker compose -p zeroday-loadtest down -v      # xóa luôn các volume database
```

Cả bốn stack đều đặt game ở port host `3000` theo mặc định, nên mỗi lúc chỉ chạy được một stack, trừ khi bạn cho các stack còn lại một `--port` khác.

## Chạy test

`./scripts/run-variant.sh` là điểm vào duy nhất cho mọi lần chạy cần verdict. Theo thứ tự, nó:

1. kiểm tra game server và khởi động nếu cần ([phần trước](#tự-động-khởi-động-game-server)),
2. tạo thư mục output `target/variants/<game>/<variant>-<YYYYMMDD-HHMMSS>/`,
3. chạy nền `monitor-resources.sh` (CPU / bộ nhớ mỗi 5 giây) và `health-poll.sh` (health probe mỗi 2 giây), trong khoảng thời gian bằng ramp cộng duration cộng 2 phút,
4. chạy Gradle task `:games:<game>:<simulation>` (tên simulation viết thường) và lưu output vào `gatling.log`,
5. copy báo cáo HTML mới nhất của Gatling vào bundle (nếu lần chạy này không tạo ra báo cáo, ví dụ vì Gatling không khởi động được, thì đó là báo cáo của một lần chạy trước),
6. chạy `./gradlew verifyVariant`, lệnh này ghi ra `verdict.json`,
7. chạy `scripts/generate-summary-html.py`, script này ghi ra `summary.html`,
8. in đường dẫn các file và thoát với exit code của lần chạy Gatling.

Ctrl+C dừng các monitor chạy nền, in đường dẫn của kết quả dở dang và thoát với `130`. Không có verdict nào được ghi.

### Tham số của wrapper

```bash
./scripts/run-variant.sh \
  --game <silkroad|bonanza|naga777|mutantmerge|zeroday> \
  --variant <baseline|target|stress|critical> \
  --simulation <Soak|Stress|Spike|Basic|Grpc> \
  --container <name> \
  [--users N] [--duration-minutes N] [--ramp-minutes N] \
  [--port N] [--parallel] [--requests N] [--scenario <name>]
```

| Flag                 | Mặc định                                    | Bắt buộc | Ghi chú                                                                                                                                                                                |
|----------------------|---------------------------------------------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `--game`             | không có                                    | Có       | Một trong năm id `GAME`. Cũng có thể lấy từ biến môi trường `GAME`; nếu đặt cả hai thì flag được ưu tiên. Không có mặc định: bỏ trống sẽ dừng với lỗi. Tên không có thư mục `games/<name>/` sẽ dừng với `ERROR: unknown game '<name>'`. |
| `--variant`          | không có                                    | Có       | Quyết định ngưỡng CPU / bộ nhớ: `baseline` 50 / 60 %, `target` 70 / 80 % (cổng release), `stress` 85 / 90 %, `critical` 95 / 95 %. Xem [Cấu hình](#cấu-hình). Chỉ verifier kiểm tra tên này, và kiểm tra **sau** khi test xong, nên hãy gõ đúng chính tả. |
| `--simulation`       | không có                                    | Có       | Viết hoa hay viết thường đều được. Phải là simulation mà game hỗ trợ ([Các game hiện có](#các-game-hiện-có)). Nếu không, Gradle không tìm thấy task và lần chạy thất bại.             |
| `--container`        | không có                                    | Có       | Dùng để phát hiện server đang chạy và để đọc `docker stats`. Nếu không có container nào như vậy, CPU / bộ nhớ được lấy từ process đang lắng nghe trên `--port`. Nếu cũng không có process nào, giá trị là `N/A`. |
| `--users`            | `1000`                                      |          | Số người chơi (VU).                                                                                                                                                                    |
| `--duration-minutes` | `60`                                        |          | Thời gian giữ tải đầy đủ, tính sau ramp.                                                                                                                                               |
| `--ramp-minutes`     | `5`                                         |          | Warm-up: thời gian để người chơi vào dần. Wrapper luôn gửi giá trị này, nên các simulation `Grpc` và `Soak` của Bonanza không bao giờ dùng mặc định 2 của riêng chúng. Menu gửi 2 cho `Grpc` và 5 cho mọi loại test khác. Muốn `Soak` của Bonanza dùng ramp 2 của riêng nó, hãy truyền `--ramp-minutes 2`. |
| `--port`             | `3005` cho `bonanza`, `3000` cho các game khác |       | Port HTTP của game. Dùng cho URL health probe, làm phương án dự phòng để đo CPU / bộ nhớ, làm `HTTP_PORT` khi tự khởi động, và được truyền cho các simulation REST dưới dạng `-Dport`. Các simulation `Grpc` luôn dùng port gRPC riêng của chúng. |
| `--parallel`         | tắt                                         |          | Chỉ cho Soak: mọi người chơi vào cùng lúc thay vì vào dần. Chỉ có tác dụng với **Silk Road**. `Soak` của Bonanza bỏ qua flag này.                                                     |
| `--requests`         | mặc định của simulation, `10000`            |          | Chỉ cho Basic: tổng số request của tất cả VU cộng lại.                                                                                                                                 |
| `--scenario`         | endpoint đầu tiên (`spin` / `BetLevels`)    |          | Chỉ cho Basic: chọn endpoint hoặc chế độ nào. Xem [Test từng endpoint riêng lẻ](#test-từng-endpoint-riêng-lẻ).                                                                         |

Flag lạ sẽ dừng lần chạy với `Unknown arg: <flag>`. Thiếu flag bắt buộc sẽ dừng với `--variant required`, `--simulation required`, `--container required`, hoặc `ERROR: --game <…> required (or set GAME env var)`.

Wrapper không có flag cho host / port gRPC, pace, các ngưỡng sàn `requestRate` / `eventCount`, hình dạng tải của Stress / Spike hay các thiết lập mức cược riêng của từng game. Hãy dùng [Chạy nâng cao](#chạy-nâng-cao-không-qua-wrapper) cho những thứ đó. Wrapper truyền `-DgameName=<game>` cho verifier, nên ngưỡng riêng của từng game được áp dụng tự động (ví dụ mean 300 ms của Silk Road và tỉ lệ lỗi 0.99 % của Bonanza).

### Các loại simulation

Có hai lớp chấm điểm cho mỗi lần chạy, và chúng có thể không khớp nhau:

- **Assertion của Gatling** được kiểm tra ngay bên trong test. Chúng quyết định exit code của Gatling, cũng chính là exit code của `run-variant.sh` và `loadtest.sh`. Một assertion thất bại sẽ in ra `BUILD FAILED`.
- **Verdict** là cổng release. Nó được ghi sau khi test xong vào `verdict.json` và `summary.html`, và gồm sáu dòng trong [Sáu dòng ngưỡng](#sáu-dòng-ngưỡng).

Verdict dùng cùng sáu dòng đó cho mọi simulation. Cột cuối của bảng dưới chỉ liệt kê assertion của Gatling. Những assertion thêm (ví dụ ngưỡng sàn 50 req/s và 100 000 request) chỉ thay đổi exit code, không bao giờ thay đổi verdict.

| Simulation | Game                                    | Làm gì                                                                                                                                                                                                                                                  | Khi nào dùng                                           | Assertion của Gatling (chỉ ảnh hưởng exit code)                                                                                                             |
|------------|-----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Soak`     | silkroad, bonanza                       | Silk Road: người chơi vào dần trong `--ramp-minutes` (hoặc vào cùng lúc với `--parallel`). Sau đó mỗi người chơi trong `--duration-minutes`. Bonanza: số người chơi tăng dần lên `--users` trong thời gian ramp và giữ nguyên trong suốt duration. Mỗi người chơi Bonanza tạo một session, rồi chơi lặp lại với nhịp 5 giây. | Cổng release. Tìm chỗ chậm dần và rò rỉ bộ nhớ.       | Mean response ≤ ngưỡng PR-4, tỉ lệ lỗi ≤ ngưỡng PR-5. Bonanza còn cần: ≥ 50 req/s tổng, > 100 000 request thành công, `Spin` p95 ≤ 800 ms, lỗi của `Spin` và `CreateSession` ≤ 0.5 %. |
| `Stress`   | silkroad                                | Người chơi mới đến với tốc độ tăng dần từ 500 lên 2500 người mỗi phút trong suốt `--duration-minutes`. Sau đó mỗi người chơi trong `--duration-minutes`, nên lần chạy kéo dài thêm tối đa 5 phút. `--users` và `--ramp-minutes` không làm thay đổi nó. | Tìm điểm gãy của server.                               | Không có. Chỉ để đo.                                                                                                                                        |
| `Spike`    | silkroad                                | Trong 25 phút, mỗi phút có 200 người chơi mới đến, cộng thêm 5 đợt 1500 người chơi vào cùng lúc (khoảng 4.5 phút một đợt). Mỗi người chơi trong 25 phút, và lần chạy dừng ở giới hạn 30 phút. `--users`, `--duration-minutes` và `--ramp-minutes` không làm thay đổi nó. | Kiểm tra khả năng hồi phục sau các đợt người chơi ùa vào đột ngột. | Không có. Chỉ để đo.                                                                                                                                        |
| `Basic`    | silkroad, bonanza                       | `--users` VU bắt đầu cùng lúc và gửi tổng cộng `--requests` request tới một endpoint (hoặc một chế độ).                                                                                                                                                 | Smoke test một endpoint duy nhất.                      | Không có request nào thất bại.                                                                                                                              |
| `Grpc`     | bonanza, naga777, mutantmerge, zeroday  | Số người chơi tăng dần lên `--users` trong thời gian ramp và giữ nguyên trong suốt duration. Mỗi người chơi vào game qua gRPC (`ConnectAndCall`), rồi spin (`Call`) một lần mỗi chu kỳ pace. Đây là đường kết nối mà game client thật sử dụng.         | Cổng release cho các game gRPC.                        | Mean response ≤ ngưỡng PR-4, tỉ lệ lỗi ≤ ngưỡng PR-5, > 50 req/s tổng, > 100 000 request thành công, `Spin` p95 ≤ 800 ms, lỗi của `Spin` và `Join` ≤ 0.5 %. |

> **Các lần chạy nhỏ của `Grpc` và `Soak` của Bonanza luôn kết thúc với `BUILD FAILED` và exit code khác 0**, kể cả khi server hoàn hảo. Chúng phải đạt 50 req/s và hơn 100 000 request thành công, điều mà chỉ lần chạy quy mô đầy đủ mới làm được. Một smoke test 10 người chơi chỉ tạo khoảng 2 request mỗi giây. Khi đó hãy đọc `verdict.json` / `summary.html`: các ngưỡng sàn này không phải là dòng của verdict, nên verdict vẫn có thể là PASS. Để hạ các ngưỡng này, dùng [Chạy nâng cao](#chạy-nâng-cao-không-qua-wrapper) (`-DrequestRate=… -DeventCount=…`).
>
> **Stress và Spike chạy lâu hơn quy mô bạn chọn**: Stress mất tối đa `--duration-minutes` + 5 phút, còn Spike khoảng 30 phút. Các monitor CPU / health dừng sau ramp cộng duration cộng 2 phút, nên có thể bỏ lỡ đoạn cuối. Với Spike, truyền `--duration-minutes 30 --ramp-minutes 0`. Với Stress, truyền `--ramp-minutes 4`. Spike bỏ qua cả hai giá trị này và Stress bỏ qua ramp, nên chúng chỉ giúp các monitor bao trọn cả lần chạy, còn dư một phút cho Gradle khởi động. Trong menu, hãy nhập các giá trị này ở **Custom**. Để thay đổi hình dạng tải của Stress hay Spike, dùng [Chạy nâng cao](#chạy-nâng-cao-không-qua-wrapper).
>
> **`Grpc` chạy trên phiên bản Gatling khác** (3.9.5 cùng một plugin gRPC của cộng đồng) so với các test REST (3.15). Xem [Runtime cho gRPC](#runtime-cho-grpc).

**Mặc định của `Grpc` theo từng game.** Đây là các mặc định riêng của simulation. Khi chạy qua wrapper, `--users`, `--duration-minutes` và `--ramp-minutes` luôn thay thế ba giá trị đầu.

| Game          | `users` | `durationMinutes` | `rampMinutes` | `paceSec` | `requestRate` (sàn req/s) | `eventCount` (sàn số request thành công) | `grpcHost` | `grpcPort` | Thiết lập riêng của game (chỉ qua Gradle) |
|---------------|---------|-------------------|---------------|-----------|---------------------------|------------------------------------------|------------|------------|-------------------------------------------|
| `bonanza`     | 1000    | 60                | 2             | 5         | 50                        | 100000                                   | localhost  | 9091       | không có                                  |
| `naga777`     | 1000    | 60                | 2             | 5         | 50                        | 100000                                   | localhost  | 9096       | `coinValue=5`, `coinPerLine=3`            |
| `mutantmerge` | 1000    | 60                | 2             | 5         | 50                        | 100000                                   | localhost  | 9104       | `betLevelId=3`, `superBet=false`          |
| `zeroday`     | 1000    | 60                | 2             | 5         | 50                        | 100000                                   | localhost  | 9103       | `bet=1.00`                                |

`Soak` REST của Bonanza dùng cùng các mặc định `rampMinutes=2`, `paceSec=5`, `requestRate=50` và `eventCount=100000`.

### Ví dụ chuẩn

Đây chính là các lệnh mà menu tạo ra. **Smoke** là 10 người chơi trong 1 phút. **Cổng production** là 1000 người chơi trong 60 phút.

```bash
# Silk Road — smoke / cổng production
./scripts/run-variant.sh --game silkroad --variant target --simulation Soak \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container game-silk-road-caravans
./scripts/run-variant.sh --game silkroad --variant target --simulation Soak \
  --users 1000 --duration-minutes 60 --ramp-minutes 5 --container game-silk-road-caravans

# Golden Boat Bonanza — smoke / cổng production REST / cổng production gRPC (port 3005 được chọn tự động)
./scripts/run-variant.sh --game bonanza --variant target --simulation Soak \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container game-golden-boat-bonanza
./scripts/run-variant.sh --game bonanza --variant target --simulation Soak \
  --users 1000 --duration-minutes 60 --ramp-minutes 5 --container game-golden-boat-bonanza
./scripts/run-variant.sh --game bonanza --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 --container game-golden-boat-bonanza

# Naga's Fortune 777 — smoke / cổng production
./scripts/run-variant.sh --game naga777 --variant target --simulation Grpc \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container stable-naga_fortune_777
./scripts/run-variant.sh --game naga777 --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 --container stable-naga_fortune_777

# Mutant Merge — smoke / cổng production
./scripts/run-variant.sh --game mutantmerge --variant target --simulation Grpc \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container stable-game-mutant-merge
./scripts/run-variant.sh --game mutantmerge --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 --container stable-game-mutant-merge

# Zero Day — smoke / cổng production
./scripts/run-variant.sh --game zeroday --variant target --simulation Grpc \
  --users 10 --duration-minutes 1 --ramp-minutes 0 --container game-zero-day
./scripts/run-variant.sh --game zeroday --variant target --simulation Grpc \
  --users 1000 --duration-minutes 60 --ramp-minutes 2 --container game-zero-day
```

Với **Quick check** của menu, dùng `--variant baseline --users 200 --duration-minutes 5 --ramp-minutes 1`.

### Test từng endpoint riêng lẻ

Dùng `--simulation Basic --scenario <name>` để gọi một endpoint duy nhất thay vì cả hành trình của người chơi. Chỉ Silk Road và Bonanza có endpoint REST để test.

| Game       | Endpoint (`--scenario`)                                                                | Chế độ                  |
|------------|----------------------------------------------------------------------------------------|-------------------------|
| `silkroad` | `spin`, `last-spin`, `history-summary`                                                 | `all`, `chain`, `burst` |
| `bonanza`  | `BetLevels`, `ReelStrips`, `CreateSession`, `Spin`, `JackpotPools`, `HistorySessions`  | `all`, `chain`, `burst` |

- **Một endpoint**: `--users` VU bắt đầu cùng lúc, mỗi VU gửi `--requests ÷ --users` request (ít nhất 1).
- **`all`**: như trên, nhưng cho mọi endpoint cùng lúc.
- **`chain`**: mỗi VU gọi lần lượt mọi endpoint theo thứ tự, lặp lại cho tới khi dùng hết số request.
- **`burst`**: mỗi endpoint có `--users` VU, tất cả chạy cùng lúc, mỗi VU gửi một request.

Tên phân biệt chữ hoa chữ thường. Tên sai sẽ thất bại với `Unknown scenario: '<name>'. Valid: …`. Menu liệt kê các endpoint và chế độ của Silk Road, và các endpoint của Bonanza. Các chế độ của Bonanza vẫn dùng được qua wrapper.

Các endpoint có trạng thái của Bonanza (`BonusStart`, `BonusReveal`, `HistoryRounds`, `RoundDetail`) không test riêng được, vì chúng cần trạng thái từ một session hoặc một lần spin trước đó. Chúng được chạy bên trong `Soak`.

**Mẫu lệnh.** Mẫu này giả định bạn đã export các biến như ở phần Bắt đầu nhanh. Nếu chưa, hãy truyền giá trị cụ thể.

```bash
./scripts/run-variant.sh \
  --game "$GAME" \
  --variant target --simulation Basic --scenario <NAME> \
  --users <USERS> --requests <REQUESTS> \
  --duration-minutes 1 --ramp-minutes 0 \
  --container "$CONTAINER" --port "$PORT"
```

**Tải đề xuất cho từng endpoint**

| Game       | `--scenario`      | `--users` | `--requests` | Ghi chú                                         |
|------------|-------------------|-----------|--------------|-------------------------------------------------|
| `silkroad` | `spin`            | 500       | 5 000        | Hành động chính của game.                       |
| `silkroad` | `last-spin`       | 500       | 5 000        | Chỉ đọc.                                        |
| `silkroad` | `history-summary` | 500       | 5 000        | Chỉ đọc.                                        |
| `bonanza`  | `BetLevels`       | 1 000     | 10 000       | Cấu hình tĩnh, nhẹ.                             |
| `bonanza`  | `ReelStrips`      | 1 000     | 10 000       | Cấu hình tĩnh, nhẹ.                             |
| `bonanza`  | `CreateSession`   | 500       | 2 000        | Nặng hơn: mỗi request tạo một session.          |
| `bonanza`  | `Spin`            | 500       | 5 000        | Trả về HTTP 201, không phải 200.                |
| `bonanza`  | `JackpotPools`    | 1 000     | 10 000       | Chỉ đọc.                                        |
| `bonanza`  | `HistorySessions` | 500       | 5 000        | Chỉ đọc.                                        |

### Kiểm tra riêng cho từng game

**Silk Road.** Game chỉ có REST. File compose cho load test tắt wallet thật và cheat, đồng thời thay ZMQ publisher bằng bản mock, nên không cần dịch vụ bên ngoài nào.

**Golden Boat Bonanza.** Bạn phải tự khởi động game này (không có tự khởi động). Các lời gọi REST đi tới port `3005` dưới `/golden`, và `Spin` trả về **201**. Test `Grpc` cần truy cập được port gRPC `9091` trên `localhost`.

**Naga's Fortune 777.** Chỉ có gRPC: qua HTTP, backend không cung cấp gì ngoài health check. Thời gian spin đo được là thời gian gRPC xác nhận đã nhận lệnh (acknowledgement), vì kết quả spin đầy đủ được đẩy qua ZMQ. Server tính mức cược bằng `coinValue × coinPerLine × 5`, mặc định là 75. Chi tiết hơn ở [`naga777-load-test-guide.md`](naga777-load-test-guide.md).

**Mutant Merge.** Chỉ có gRPC. Test đọc từng phản hồi spin và tính mã nghiệp vụ khác 0 là request thất bại, nên lỗi nghiệp vụ hiện thành `KO` trong Gatling.

**Zero Day.** Chỉ có gRPC. Mỗi spin trả về một phản hồi **rỗng**: kết quả và mọi lỗi nghiệp vụ đều đi qua ZMQ, nên Gatling chỉ thấy lỗi ở tầng truyền tải. Sau mỗi lần chạy, hãy kiểm tra log của backend. Lệnh sau phải in ra `0`:

```bash
docker logs game-zero-day 2>&1 | grep -E "\[gRPC\] (ConnectAndCall|Call) (business )?error" | grep -vc "c=1362"
```

Thỉnh thoảng có các lần từ chối `c=1362` (jackpot đang chờ) là bình thường, nên lệnh loại chúng ra. `docker logs` gồm mọi thứ kể từ khi container khởi động, nên hãy kiểm tra trên một container mới, hoặc so sánh số đếm trước và sau khi chạy. File compose cho load test đã đặt sẵn `LUIGI_WALLET_ENABLED=false`, `CHEAT_ENABLED=false` và logging driver `json-file` mà `docker logs` cần. Nếu bạn khởi động Zero Day theo cách khác, hãy đặt cả những giá trị này.

**Thiết lập mức cược chỉ đổi được qua Gradle.** Wrapper không đổi được chúng. Hãy dùng [Chạy nâng cao](#chạy-nâng-cao-không-qua-wrapper) với:

- naga777: `-DcoinValue=` (một trong 1, 5, 20, 50, 100, 200, 500) và `-DcoinPerLine=` (1–10),
- mutantmerge: `-DbetLevelId=` (bậc trên thang cược, đếm từ 1, `3` = $1.00) và `-DsuperBet=true`,
- zeroday: `-Dbet=` (một giá trị trên thang cược từ `0.20` đến `100.00`; giá trị khác sẽ dừng test ngay khi bắt đầu).

## Đọc hiểu kết quả

### Kết quả nằm ở đâu

| Chạy bằng                       | Output                                                                                                         |
|---------------------------------|----------------------------------------------------------------------------------------------------------------|
| `./loadtest.sh` hoặc `./scripts/run-variant.sh` | `target/variants/<game>/<variant>-<YYYYMMDD-HHMMSS>/`: toàn bộ bundle bên dưới.                |
| `./gradlew :games:…` trực tiếp  | `games/<game>/build/reports/gatling/<simulation class, lower case>-<timestamp>/`: chỉ có HTML của Gatling (tên class simulation viết thường), không có verdict. |

Thư mục `target/` không được commit lên git.

| File trong bundle           | Là gì                                                                      | Mở khi                                         |
|-----------------------------|----------------------------------------------------------------------------|------------------------------------------------|
| `summary.html`              | Verdict trên một trang, các ngưỡng, biểu đồ CPU / bộ nhớ và báo cáo Gatling nhúng sẵn. | **Luôn bắt đầu từ đây.**               |
| `verdict.json`              | Cùng các số liệu như `summary.html`, ở dạng máy đọc được.                  | Tự động hóa việc ra quyết định (CI, dashboard). |
| `gatling-report/index.html` | Báo cáo HTML đầy đủ của Gatling (percentile theo từng request, lỗi).       | Đào sâu vào một endpoint hoặc một lỗi.         |
| `resource.csv`              | Các mẫu CPU / bộ nhớ, mỗi 5 giây một dòng.                                 | Vẽ biểu đồ CPU / bộ nhớ theo thời gian.        |
| `health.csv`                | Các mẫu health probe, mỗi 2 giây một dòng.                                 | Tìm khoảng thời gian server bị down.           |
| `gatling.log`               | Mọi thứ Gradle và Gatling in ra.                                           | Gỡ lỗi một lần chạy thất bại hoặc bất thường.  |

### Trang tóm tắt

Từ trên xuống dưới:

1. **Banner verdict**: chữ `PASS` xanh lá hoặc `FAIL` đỏ thật to, kèm game, variant và thời điểm chạy. Bên phải: số người chơi, thời lượng tính bằng giây (thời gian của bước Gatling, tính cả lúc Gradle khởi động) và số core CPU của máy host.
2. **Run Info**: cùng các thông tin đó, dưới dạng bảng.
3. **Threshold Check**: sáu dòng ở phần dưới. Một ghi chú dưới bảng cho biết các ngưỡng được lấy từ file YAML nào.
4. **HTTP Requests Summary (from Gatling)**: tổng số request, số OK và KO, cùng exit code của Gatling. Với các game gRPC, đây là số lời gọi gRPC.
5. **CPU % over time**: biểu đồ đường kèm giá trị trung bình và lớn nhất. CPU được chia cho số core của host. Đường đứt nét màu đỏ là ngưỡng.
6. **Memory % over time**: tương tự, cho bộ nhớ.
7. **Gatling HTTP Report (Embedded)**: toàn bộ báo cáo Gatling. Nút **Fullscreen** phóng to báo cáo, còn Esc hoặc nút × tròn để đóng.

### Sáu dòng ngưỡng

**Verdict chỉ là PASS khi cả năm dòng đầu đều pass.** Dòng thứ sáu chỉ để tham khảo.

| Dòng trong `summary.html`       | Trường trong `verdict.json`                                                                        | Pass khi                                                                                                                                     | Ngưỡng mặc định                          |
|---------------------------------|----------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------|
| **PR-4: Mean response**         | `mean_response_ms`, `mean_ms_ceil`, `pr4_pass`                                                     | Thời gian phản hồi trung bình tổng thể của Gatling ≤ ngưỡng. Nếu Gatling không in ra mean (các lần chạy rất ngắn), dòng này pass và assertion của Gatling quyết định. | 500 ms (silkroad: 300 ms)      |
| **PR-5: Error rate (KO%)**      | `http_ko_percent`, `http_ko_ceil`, `pr5_pass` (`http_pass` là tên cũ)                              | % request thất bại ≤ ngưỡng. Nếu log không có số đếm request, dòng này chuyển sang dựa vào điều kiện "exit code của Gatling bằng 0".        | 1.0 % (bonanza: 0.99 %)                  |
| **CPU p95**                     | `cpu_p95`, `cpu_ceil`, `cpu_pass`                                                                  | Percentile thứ 95 của CPU % (đã chia cho số core của host) ≤ ngưỡng của variant. **Không có mẫu nào thì FAIL.**                               | 70 % ở `target`                          |
| **Mem p95**                     | `mem_p95`, `mem_ceil`, `mem_pass`                                                                  | Percentile thứ 95 của bộ nhớ % ≤ ngưỡng của variant. **Không có mẫu nào thì FAIL.**                                                          | 80 % ở `target`                          |
| **PR-3: Health probe failures** | `health_failures`, `max_consecutive_failures`, `crash_max_consecutive_ceil`, `crash_pass`          | Chuỗi probe thất bại liên tiếp dài nhất ngắn hơn ngưỡng. 3 lần thất bại liên tiếp (server down khoảng 6 giây) được coi là crash.             | ít hơn 3 lần liên tiếp                   |
| Response time p95               | `p95_response_ms`                                                                                  | Chỉ để tham khảo. Không phải là cổng.                                                                                                        | —                                        |

Ngưỡng CPU / bộ nhớ theo variant là `baseline` 50 / 60, `target` 70 / 80, `stress` 85 / 90 và `critical` 95 / 95. Để đổi bất kỳ ngưỡng nào mà không cần sửa code, xem [Cấu hình](#cấu-hình).

Các trường khác trong `verdict.json`: `variant`, `game_name`, `users`, `duration_sec`, `http_total`, `http_ok`, `http_ko`, `gatling_exit_code`, `host_cores`, `sla_config_source` (file YAML nào cung cấp các ngưỡng) và `verdict` (`PASS` / `FAIL`).

> **Chuẩn hóa CPU.** `docker stats` cộng dồn CPU của mọi core (600 % trên máy 12 core nghĩa là 6 core đang bận). Verifier chia cho số core của host trước: 600 % / 12 = 50 %.

### Khi một dòng bị FAIL

| Dòng bị FAIL            | File cần mở đầu tiên        | Tìm gì                                                                                      |
|-------------------------|-----------------------------|---------------------------------------------------------------------------------------------|
| `pr4_pass` (chậm)       | `gatling-report/index.html` | Bảng thống kê: request nào có mean hoặc p95 cao.                                            |
| `pr5_pass` (lỗi)        | `gatling-report/index.html` | Bảng lỗi: nội dung lỗi và request gây ra lỗi đó.                                            |
| `cpu_pass` / `mem_pass` | `resource.csv`              | Vẽ `cpu_pct` / `mem_pct` theo `timestamp`. Các đỉnh thường trùng với khoảng bị chậm. Toàn `N/A` nghĩa là không tìm thấy container. |
| `crash_pass`            | `health.csv`                | Các dòng có `http_status` là `000` hoặc không phải `2xx`, hoặc `total_seconds` ≥ 5. Ba dòng liên tiếp là crash. Sau đó kiểm tra `docker logs <container>`. |

### Các cột trong file CSV

`resource.csv` có header `timestamp,cpu_pct,mem_pct,mem_used,net_io,block_io` và mỗi 5 giây một dòng. Timestamp theo giờ UTC.

- `cpu_pct`: CPU của container, cộng dồn qua các core. Chia cho số core của host để so với ngưỡng.
- `mem_pct`: bộ nhớ tính theo % giới hạn bộ nhớ của container. Các file compose cho load test giới hạn mỗi game ở 768 MB. Container không có giới hạn thì được đo so với toàn bộ bộ nhớ mà Docker dùng được. `mem_used` ở dạng dễ đọc, ví dụ `448.3MiB / 768MiB`.
- `net_io`, `block_io`: I/O mạng và ổ đĩa lấy từ `docker stats`.
- Dòng `N/A`: tại thời điểm đó không tìm thấy container. Khi container không tồn tại, monitor chuyển sang đo process đang lắng nghe trên `--port`. Ở chế độ đó, `mem_pct` là % bộ nhớ của máy host, còn `net_io` / `block_io` là `N/A`.

`health.csv` có header `timestamp,http_status,total_seconds` và mỗi 2 giây một dòng. Timestamp theo giờ UTC.

- `http_status` là `000` nghĩa là không có phản hồi (lỗi mạng hoặc hết thời gian chờ). Probe bỏ cuộc sau 5 giây.
- Một dòng được tính là thất bại nếu `http_status` không phải `2xx` **hoặc** `total_seconds` từ 5.0 trở lên.
- Sau 3 lần thất bại liên tiếp, prober in `CRASH DETECTED` và dừng, nên file kết thúc tại đó.

### Báo cáo Gatling

Mở trực tiếp `gatling-report/index.html`, hoặc dùng bản nhúng trong `summary.html`.

| Phần                                         | Dùng để                                                                               |
|----------------------------------------------|---------------------------------------------------------------------------------------|
| Tab **Global**                               | Xem toàn bộ lần chạy.                                                                 |
| Assertions                                   | Từng assertion của Gatling và nó có pass hay không. Giải thích vì sao exit code khác 0. |
| Stats                                        | Mỗi loại request một dòng: số lượng, OK / KO, thời gian phản hồi min / mean / percentile / max. |
| Errors (chỉ hiện khi có gì đó thất bại)      | Từng nội dung lỗi kèm số lần xảy ra. Cách nhanh nhất để chẩn đoán `KO`.               |
| Response Time Ranges                         | Bao nhiêu request nhanh, chậm hơn hoặc thất bại.                                      |
| Active Users along the Simulation            | Xác nhận hình dạng tải đúng như bạn mong đợi.                                         |
| Response Time Percentiles over Time (OK)     | Đường tăng đều dần gợi ý rò rỉ. Các đỉnh ngắn gợi ý những lần tạm dừng như GC.        |
| Number of requests / responses per second    | Thông lượng theo thời gian.                                                           |
| Tab **Details**                              | Các biểu đồ tương tự cho một loại request (ví dụ `Spin`).                             |

### Đọc output trên console

Trong lúc Gatling chạy, khoảng mỗi 5 giây nó in ra các bộ đếm. Đây là một lần chạy gRPC:

```text
> Global                                                   (OK=30     KO=0     )
> Join                                                     (OK=10     KO=0     )
> Spin                                                     (OK=20     KO=0     )
```

`OK` nghĩa là request thành công. `KO` nghĩa là request thất bại (hết thời gian chờ, mã trạng thái lỗi hoặc check thất bại). **`KO=0`** là điều bạn muốn thấy.

Cuối cùng, Gatling in một khối tóm tắt (`> request count …`, `> mean response time …`, `> response time 95th percentile …`), theo sau là mỗi assertion một dòng, kết thúc bằng `true` hoặc `false`. Các test REST (Gatling 3.15) in khối đó dưới dạng bảng với các cột `|`, còn các test gRPC (Gatling 3.9.5) in dạng `> request count  140 (OK=140  KO=0 )`. Verifier đọc được số request, mean và percentile thứ 95 từ cả hai dạng. Sau đó là `[run-variant] Verdict:` và nội dung của `verdict.json`, rồi danh sách các file kết quả.

### Báo cáo tổng hợp nhiều lần chạy

`scripts/generate-final-report.py` gộp nhiều lần chạy của một game thành một báo cáo tuân thủ (compliance report) dạng Markdown. Wrapper không chạy script này. Hãy tự chạy khi đã có đủ các lần chạy cần thiết:

```bash
python3 scripts/generate-final-report.py \
  --variants-dir target/variants/<game> \
  --report-out target/variants/<game>/final-report.md \
  [--host localhost] [--port 3000]
```

- Với mỗi variant (`baseline`, `target`, `stress`, `critical`), script lấy thư mục của lần chạy **mới nhất**, bất kể lần chạy đó là simulation nào. Variant chưa từng chạy sẽ hiện `N/A`.
- Script điền bảng PR-1 đến PR-7 từ lần chạy `target`: ít nhất 1000 người chơi, ít nhất 60 phút, không có khoảng health gián đoạn từ 5 giây trở lên, mean ≤ 500 ms, lỗi ≤ 1 %, và kết quả CPU / bộ nhớ lấy từ `verdict.json`. Kết luận cuối cùng tính mọi dòng trừ PR-2 (thời lượng). Các ngưỡng này được cố định trong script; ngưỡng YAML riêng của từng game không được áp dụng.
- Script thêm một bảng CPU / bộ nhớ cho cả bốn variant, cùng các percentile độ trễ và phần tóm tắt tài nguyên cho `target`. CPU % trong phần tóm tắt tài nguyên đó là giá trị thô của `docker stats`, chưa chia cho số core.
- Các dòng độ trễ và lỗi cần file `gatling-report/js/stats.json`, và chỉ các test gRPC (Gatling 3.9.5) mới ghi ra file này. Với các lần chạy REST (Silk Road, `Soak` / `Basic` của Bonanza), các dòng đó là `N/A`, nên kết luận cuối cùng là FAIL. Hãy dùng `summary.html` cho các game đó.
- `--host` / `--port` chỉ dùng để điền dòng "SUT" của báo cáo.

## Xử lý sự cố

| Triệu chứng                                                                                      | Nguyên nhân có thể và cách xử lý                                                                                                                                                                                                                                |
|--------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `loadtest.sh needs a terminal to ask questions. In CI, run scripts/run-variant.sh directly.`     | Menu được chạy mà không có terminal (CI, pipe, script). Dùng lệnh `Same as` lấy từ một lần chạy menu thủ công, hoặc tự dựng lệnh từ [Ví dụ chuẩn](#ví-dụ-chuẩn).                                                                                              |
| Menu hiện ○ dù server đang chạy                                                                  | Server chạy ngoài Docker, hoặc dưới một tên container khác. Test vẫn chạy nếu health probe trả lời, nhưng CPU / bộ nhớ có thể là `N/A`. Truyền tên thật bằng `--container` (khi chạy thủ công).                                                               |
| `[ensure-sut] ERROR: Docker isn't running. …`                                                    | Mở Docker Desktop (hoặc khởi động dịch vụ Docker), đợi nó sẵn sàng rồi chạy lại.                                                                                                                                                                               |
| `[ensure-sut] ERROR: bonanza can't be started automatically …`                                   | Bonanza không có file compose cho load test. Hãy khởi động nó từ repo riêng của nó sao cho nó trả lời trên `3005`, rồi chạy lại.                                                                                                                                |
| `[ensure-sut] ERROR: can't ask where the source code is (no terminal). …`                        | Tự khởi động cần có terminal. Hãy khởi động server bằng lệnh được in dưới thông báo lỗi (xem [Cách thủ công bốn bước](#cách-thủ-công-bốn-bước)), rồi chạy lại.                                                                                                 |
| `[ensure-sut] ERROR: folder not found: …` hoặc `no Dockerfile in …`                              | Sai thư mục. Hãy dán thư mục gốc của backend, tức thư mục có `Dockerfile` (xem [Các game hiện có](#các-game-hiện-có)).                                                                                                                                         |
| `[ensure-sut] ERROR: couldn't download the source code (see the git error above).`               | Kiểm tra URL, quyền truy cập git của bạn (SSH key hoặc thông tin đăng nhập), và repo có nhánh `main` hay không.                                                                                                                                                 |
| `[ensure-sut] ERROR: couldn't start the game server …` kèm `port is already allocated`           | Một stack khác đang giữ port, thường là một game khác trên `3000`. Tìm nó bằng `docker ps --format '{{.Names}}\t{{.Ports}}'`, dừng nó bằng `docker compose -p <project> stop` ([tên project](#tự-động-khởi-động-game-server)), hoặc dùng `--port` khác.        |
| `[ensure-sut] ERROR: couldn't start the game server …` kèm `container name … is already in use`  | Đã có một container trùng tên từ một cấu hình khác, ví dụ compose riêng của backend. Hãy dừng và xóa container đó, rồi chạy lại.                                                                                                                               |
| `[ensure-sut] ERROR: couldn't start the game server …` kèm `env file … .env.staging not found`   | Các file compose của Silk Road, Naga's Fortune 777 và Zero Day đọc `.env.staging` từ thư mục backend. Hãy đảm bảo file đó có ở đó.                                                                                                                              |
| `[ensure-sut] ERROR: the server didn't come up within 300s. …`                                   | Đọc 50 dòng log được in phía trên thông báo lỗi. Thường là Mongo / Redis vẫn đang khởi động, hoặc app bị lỗi lúc boot. Sửa xong thì chạy lại. Lần chạy sau sẽ dùng lại container đang chạy.                                                                   |
| `Connection refused`, hoặc health check bằng curl thất bại                                       | Server chưa lên. Chạy `docker ps --filter name=<container>` và `docker logs <container> 2>&1 \| tail -50`. Nếu vừa mới khởi động, hãy đợi rồi thử lại.                                                                                                          |
| `health.csv` chỉ toàn `000` hoặc các dòng 5 giây                                                 | URL probe không trả lời `2xx`. Wrapper dùng `http://localhost:<port>` cộng với: silkroad `/actuator/health`, bonanza `/golden/api/configs/bet-levels`, naga777 và mutantmerge `/health`, zeroday `/api/game/zeroday/actuator/health`. Kiểm tra server đang ở đúng port và đường dẫn đó, hoặc truyền `--port`. |
| `resource.csv` toàn `N/A`, và các dòng CPU / Mem FAIL                                            | Không có container nào mang tên đã truyền cho `--container`, và không có gì lắng nghe trên `--port`. Kiểm tra bằng `docker ps`. Không có mẫu thì các dòng CPU và bộ nhớ sẽ FAIL.                                                                               |
| `BUILD FAILED` và exit code khác 0, nhưng `verdict.json` báo PASS                                | Assertion của Gatling và verdict là hai thứ riêng biệt. Với các lần chạy `Grpc` hoặc `Soak` của Bonanza quy mô nhỏ, không thể đạt ngưỡng sàn 50 req/s và 100 000 request. Xem [Các loại simulation](#các-loại-simulation).                                      |
| Gradle không tìm thấy task `:games:<game>:<simulation>`                                          | Game đó không có simulation đó. Kiểm tra [Các game hiện có](#các-game-hiện-có).                                                                                                                                                                                |
| `Unknown variant: …`, sau đó `verdict.json` rỗng và không có `summary.html`                      | `--variant` phải là `baseline`, `target`, `stress` hoặc `critical`. Verifier chỉ kiểm tra nó sau khi test xong, nên bản thân test vẫn đã chạy.                                                                                                                  |
| `Unknown arg: …`, hoặc `… required`                                                              | Gõ sai flag, hoặc thiếu `--game` / `--variant` / `--simulation` / `--container`. Xem [Tham số của wrapper](#tham-số-của-wrapper).                                                                                                                              |
| `Unknown scenario: '…'. Valid: …`                                                                | Sai `--scenario` cho `Basic`. Tên phân biệt chữ hoa chữ thường. Xem [Test từng endpoint riêng lẻ](#test-từng-endpoint-riêng-lẻ).                                                                                                                              |
| Một lần "smoke" Spike chạy khoảng 30 phút, hoặc một lần chạy Stress kéo dài thêm tối đa 5 phút   | Hai simulation này chạy theo hình dạng dựng sẵn của riêng chúng, bất kể bạn chọn quy mô nào. Xem [Các loại simulation](#các-loại-simulation).                                                                                                                   |
| Không có `summary.html` sau khi chạy                                                             | Chưa cài Python 3, `generate-summary-html.py` bị lỗi, hoặc `verdict.json` rỗng (ví dụ do variant lạ). Khi `verdict.json` có nội dung, nó vẫn là kết quả chính thức.                                                                                            |
| Test chạy, nhưng khoảng một nửa số request trả về HTTP 400                                       | Một template body dùng `${userId}` thay vì Gatling EL `#{userId}`, nên mọi VU gửi cùng một giá trị nguyên văn. Tìm chúng bằng `grep -r '\${' games/<game>/src/gatling/resources/`.                                                                              |
| `simulation class not found`                                                                     | Một tên class trong map `SIMULATIONS` của `games/<game>/build.gradle` không khớp với `package` của file Java / Scala. Kiểm tra bằng `grep "^package" …`.                                                                                                        |
| `InaccessibleObjectException` khi simulation khởi động                                           | Không chạy trên JDK 17. Kiểm tra `java -version` có hiện 17 không. Các flag `--add-opens` cần thiết đã được đặt sẵn trong `build.gradle` của mỗi game.                                                                                                        |
| Test gRPC cần host hoặc port khác mặc định                                                       | Wrapper không có flag host / port cho gRPC. Hãy chạy qua Gradle với `-DgrpcHost=… -DgrpcPort=…` (khi đó không có `summary.html` / `verdict.json`). Xem [Chạy nâng cao](#chạy-nâng-cao-không-qua-wrapper).                                                       |

## Cấu hình

Có ba thứ quyết định một lần chạy diễn ra thế nào, và không thứ nào cần sửa code:

| Nội dung                      | Nằm ở đâu                                                             | Do ai đọc                                            |
|-------------------------------|-----------------------------------------------------------------------|------------------------------------------------------|
| Ngưỡng pass/fail              | `config/sla-thresholds.yml` + `sla-thresholds.yml` riêng của từng game | `SlaConfigLoader` (Gatling assertion và verifier)    |
| Bắn tải vào đâu, bao nhiêu    | `core/src/main/resources/load-test-defaults.yml` + `game.yml` riêng của từng game | `LoadTestConfigLoader` (chỉ các simulation REST viết bằng Java) |
| Override dùng một lần         | `-D<name>=<value>` trên lệnh `./gradlew`                               | JVM của simulation, khi tên đó đi qua được (xem [System property của Gradle](#system-property-của-gradle)) |

### Ngưỡng trần theo variant

`--variant` chọn ngưỡng trần CPU và memory mà verifier áp lên p95 của `resource.csv`.

| Variant    | Trần CPU% p95    | Trần Mem% p95    | Khi nào dùng                                 |
|------------|------------------|------------------|----------------------------------------------|
| `baseline` | 50               | 60               | Kiểm tra độ dư: server còn nhiều chỗ trống.  |
| `target`   | 70               | 80               | **Production gate**, mặc định cho deploy.    |
| `stress`   | 85               | 90               | Nghiên cứu hành vi khi bão hoà.              |
| `critical` | 95               | 95               | Kiểm chứng trước ngưỡng sập.                 |

Các ngưỡng trần nằm dưới `variants:` trong file SLA YAML (mục tiếp theo). CPU được chia cho số core của máy host trước khi so sánh. Verifier tra tên variant trong YAML đã merge, nên một key mới dưới `variants:` dùng được ngay với `--variant <name>`. Tên không tồn tại khiến verifier thoát với `Unknown variant: …`. Menu (`loadtest.sh`) và `generate-final-report.py` chỉ biết bốn tên ở trên.

### Ngưỡng SLA và thứ tự nạp

**Các field.** Mỗi lớp chỉ liệt kê những field nó thay đổi.

| YAML key                           | Ý nghĩa                                                            | Giá trị core | Dùng bởi |
|------------------------------------|--------------------------------------------------------------------|--------------|----------|
| `sla.pr4_mean_response_ms_max`     | PR-4: trần của mean response time toàn cục (ms).                   | `500`        | Gatling assertion trong simulation Soak và gRPC; verifier `pr4_pass`. |
| `sla.pr5_error_percent_max`        | PR-5: trần của tỉ lệ KO (%).                                        | `1.0`        | Gatling assertion trong simulation Soak và gRPC; verifier `pr5_pass`. |
| `sla.max_duration_buffer_min`      | Số phút cộng thêm vào `maxDuration` của Gatling để VU không bị cắt đúng giây cuối. | `2` | Simulation Soak và gRPC. Stress và Spike dùng cố định 5 phút. |
| `crash.max_consecutive_failures`   | PR-3: số lần health probe thất bại liên tiếp bằng giá trị này được tính là crash. | `3` | Verifier `crash_pass`: fail khi chuỗi thất bại dài nhất chạm tới giá trị này. |
| `variants.<name>.cpu_ceil` / `mem_ceil` | Trần p95 cho từng variant (xem ở trên).                       | xem bảng     | Verifier `cpu_pass` / `mem_pass`. |

Simulation `Basic` chỉ assert không có KO nào (thêm `-DmaxResponseTimeMs` nếu có đặt). `Stress` và `Spike` không có assertion.

**Override theo game** nằm ở `games/<game>/src/gatling/resources/sla-thresholds.yml`:

| Game                               | Override                                                   |
|------------------------------------|------------------------------------------------------------|
| silkroad                           | `pr4_mean_response_ms_max: 300`                            |
| bonanza                            | `pr4_mean_response_ms_max: 500`, `pr5_error_percent_max: 0.99` |
| naga777, mutantmerge, zeroday      | Chỉ có comment, nên giá trị core được áp dụng.             |

**Thứ tự nạp.** `SlaConfigLoader` dựng config từ dưới lên. Mỗi lớp phủ các field của nó lên kết quả đang có (lớp áp dụng sau cùng thắng):

1. `SlaConfig.defaults()`: giá trị hardcode, giống hệt YAML của core.
2. `classpath:sla-thresholds.yml`: file `sla-thresholds.yml` đầu tiên trên classpath của JVM. `:core` đóng gói sẵn một file từ `core/src/main/resources/`. Nếu file đầu tiên đó rỗng hoặc chỉ có comment, lớp này bị bỏ qua; loader không tìm tiếp bản khác ở phía sau trên classpath.
3. `config/sla-thresholds.yml`: nguồn chuẩn dùng chung cho cả repo.
4. `games/<gameName>/src/gatling/resources/sla-thresholds.yml`: chỉ được nạp khi có đặt `-DgameName`. Mọi alias task đều đặt nó bằng tên Gradle project, và wrapper truyền `-DgameName=<game>` cho verifier.

`-DslaConfig=/abs/path.yml` bỏ qua toàn bộ chồng lớp. Nếu file đọc được, nó chỉ được merge lên giá trị hardcode, còn lớp 2 đến 4 bị bỏ qua. Nếu file không đọc được, hoặc rỗng, hoặc parse lỗi, loader sẽ dựng chồng lớp bình thường thay thế (kèm một dòng `WARN` khi file không đọc được hoặc parse lỗi).

**Quy tắc merge:**

- Merge theo từng field. Variant được merge theo từng variant và từng field, nên một game có thể chỉ đặt riêng `variants.target.cpu_ceil`. Một variant hoàn toàn mới mà thiếu field nào thì field đó rơi về 70 (CPU) hoặc 80 (memory).
- File rỗng hoặc chỉ có comment bị bỏ qua. File parse lỗi bị bỏ qua kèm một dòng `WARN`. Giá trị không phải số bị bỏ qua mà không có cảnh báo nào. Muốn xem giá trị thực sự được áp dụng, hãy xem `mean_ms_ceil`, `http_ko_ceil`, `cpu_ceil`, `mem_ceil` và `crash_max_consecutive_ceil` trong `verdict.json`.
- Field `sla_config_source` trong `verdict.json` cho biết lớp cao nhất thực sự được áp dụng: một đường dẫn tuyệt đối, `classpath:sla-thresholds.yml`, hoặc `defaults`. Với naga777, mutantmerge và zeroday, field này hiện `…/config/sla-thresholds.yml`, vì file riêng của các game đó chỉ có comment.

**Mỗi lớp có hiệu lực ở đâu:**

- Lớp 3 và 4 là đường dẫn trên filesystem, được resolve theo working directory của JVM. `verifyVariant` là task của root project, nên nó chạy ở thư mục gốc repo và verdict thấy đủ cả chồng lớp.
- Gatling assertion bên trong simulation resolve cùng các đường dẫn đó từ JVM simulation được fork ra. Gradle khởi động mọi fork, cả các task chạy REST của plugin lẫn các task `JavaExec` của gRPC, trong working directory mặc định của nó: thư mục subproject của game. Ở đó lớp 3 và 4 không resolve được, nên chỉ lớp 1 và 2 có hiệu lực. Với các task gRPC, file `sla-thresholds.yml` đầu tiên trên classpath là file riêng của game, vì output của source set đứng trước `:core`. Với naga777, mutantmerge và zeroday, file đó chỉ có comment, nên assertion của chúng dùng giá trị hardcode.
- Mỗi lớp được áp dụng sẽ log `[SlaConfig] layered <source>` ra stderr. Nếu ngưỡng trần trong Gatling assertion và verdict không khớp nhau, hãy xem các dòng này trước tiên.
- `-DslaConfig` được `verifyVariant` nhận (nó nằm trong `VERIFY_PROPS` của task). Wrapper không bao giờ truyền flag này. Nó không có trong `FORWARDED_PROPS` của game nào, nên không bao giờ tới được simulation gRPC. Simulation REST thì vẫn nhận được, vì Gatling plugin chép các system property của JVM Gradle vào fork (xem [System property của Gradle](#system-property-của-gradle)).
- Trên `verifyVariant`, `-DmeanMsCeil=<ms>` và `-DhttpKoCeil=<percent>` override trần PR-4 và PR-5, chỉ cho verdict đó.

### Mặc định runtime và override theo game

`LoadTestConfigLoader` resolve host, port, context path và hình dạng tải cho **các simulation REST viết bằng Java** (silkroad, bonanza `soak` / `basic`). Các lớp chạy từ ưu tiên thấp nhất đến cao nhất:

1. Giá trị dự phòng hardcode trong `LoadTestDefaults.fallback()`, giống hệt giá trị của file kế tiếp.
2. `classpath:load-test-defaults.yml`, đóng gói từ `core/src/main/resources/load-test-defaults.yml`.
3. `config/load-test-defaults.yml`: override tuỳ chọn, resolve theo working directory của JVM simulation. Đó là thư mục subproject của game, nên file được đọc từ `games/<game>/config/load-test-defaults.yml`; một bản đặt ở thư mục gốc repo sẽ không được dùng. Repo không kèm sẵn file này.
4. `classpath:game.yml`, tức `games/<game>/src/gatling/resources/game.yml` được đóng gói ở gốc classpath của game đó.
5. System property `-D`: `host`, `port`, `contextPath`, `users`, `requests`, `durationMinutes`, `rampMinutes`, `thinkTimeMin`, `thinkTimeMax`.

| YAML key                 | Mặc định core | Ý nghĩa                                                   |
|--------------------------|---------------|-----------------------------------------------------------|
| `http.host`              | `localhost`   | Base URL là `http://<host>:<port><contextPath>`.          |
| `http.port`              | `3000`        |                                                           |
| `http.contextPath`       | `""`          | Tiền tố như `/golden`, không có dấu `/` ở cuối.           |
| `load.users`             | `1000`        | Số VU.                                                    |
| `load.requests`          | `10000`       | Chỉ cho `Basic`: tổng ngân sách request.                  |
| `load.durationMinutes`   | `60`          | Số phút ở trạng thái ổn định (Soak), hoặc độ dài ramp (Stress). |
| `load.rampMinutes`       | `5`           | Số phút ramp-up (silkroad Soak).                          |
| `load.thinkTimeMin` / `load.thinkTimeMax` | `1` / `3` | Số giây dừng ngẫu nhiên sau mỗi request chính (journey của silkroad). |

Các file `game.yml` có sẵn:

- **bonanza** đặt `http.port: 3005` và `http.contextPath: /golden`.
- **silkroad, naga777, mutantmerge và zeroday** chỉ có comment.

**Ngoại lệ:**

- **`SoakSimulation` của bonanza** đọc thẳng `rampMinutes` từ `-D`, mặc định là `2`.
- **Các simulation gRPC viết bằng Scala hoàn toàn không dùng `LoadTestConfig`.** Class đó được compile với Java API của Gatling 3.15 và sẽ kéo các type của 3.15 vào classpath 3.9.5 của chúng. Chúng đọc thẳng mọi giá trị `-D` với mặc định riêng, nên `game.yml` của chúng chỉ mang tính tài liệu.
- **Wrapper luôn truyền `-Dusers`, `-DdurationMinutes`, `-DrampMinutes` và `-Dport`.** Khi chạy qua wrapper, port trong `game.yml` bị thay bằng port theo game của wrapper (xem [Giai đoạn 8](#giai-đoạn-8-nối-vào-script-wrapper)), và các giá trị tải trong YAML bị thay bằng mặc định riêng của wrapper. `host` và `contextPath` vẫn lấy từ YAML.

### System property của Gradle

`./gradlew <task> -Dname=value` đặt system property trong JVM của Gradle, không phải trong JVM simulation mà Gradle fork ra. Các alias task của mỗi game (`soak`, `basic`, `grpc`, …) chép những tên liệt kê trong `FORWARDED_PROPS` (trong `games/<game>/build.gradle`) sang JVM simulation. Chúng cũng thêm `gameName=<Gradle project name>`. Với các task `JavaExec` của gRPC, danh sách này là con đường **duy nhất**: flag nào thiếu trong đó sẽ không bao giờ tới được simulation. Các task REST chạy trên `GatlingRunTask` của Gatling plugin, task này còn chép mọi system property khác của JVM Gradle sang fork, trừ các tên thuộc JDK và công cụ (các tiền tố như `java.`, `os.`, `user.`, `file.` và `gatling.`). Khi một simulation bắt đầu đọc một flag mới, vẫn nên thêm tên đó vào `FORWARDED_PROPS`.

Các flag này chỉ tới được simulation khi bạn gọi Gradle trực tiếp. Wrapper chỉ truyền `users`, `durationMinutes`, `rampMinutes`, `port`, và tuỳ trường hợp thêm `parallel`, `requests` và `scenario` (xem [Tham số của wrapper](#tham-số-của-wrapper)).

**Các tên được forward theo từng game** (danh sách chính xác):

| Game          | `FORWARDED_PROPS`                                                                                                   |
|---------------|---------------------------------------------------------------------------------------------------------------------|
| `silkroad`    | `users`, `requests`, `durationMinutes`, `rampMinutes`, `thinkTimeMin`, `thinkTimeMax`, `host`, `port`, `contextPath`, `parallel`, `scenario`, `maxResponseTimeMs`, `usersStart`, `usersEnd`, `baseline`, `spike`, `spikeDurationSec`, `cycles`, `cycleIntervalMinutes` |
| `bonanza` (REST và `grpc` dùng chung một danh sách) | `users`, `requests`, `durationMinutes`, `rampMinutes`, `thinkTimeMin`, `thinkTimeMax`, `host`, `port`, `contextPath`, `scenario`, `maxResponseTimeMs`, `paceSec`, `requestRate`, `eventCount`, `grpcHost`, `grpcPort` |
| `naga777`     | `users`, `durationMinutes`, `rampMinutes`, `host`, `port`, `paceSec`, `requestRate`, `eventCount`, `grpcHost`, `grpcPort`, `coinValue`, `coinPerLine` |
| `mutantmerge` | `users`, `durationMinutes`, `rampMinutes`, `host`, `port`, `paceSec`, `requestRate`, `eventCount`, `grpcHost`, `grpcPort`, `betLevelId`, `superBet` |
| `zeroday`     | `users`, `durationMinutes`, `rampMinutes`, `host`, `port`, `paceSec`, `requestRate`, `eventCount`, `grpcHost`, `grpcPort`, `bet` |

Các game chỉ có gRPC có forward `host` và `port`, nhưng simulation Scala của chúng không đọc hai giá trị này. Endpoint gRPC chỉ lấy từ `grpcHost` / `grpcPort`.

**Flag tải dùng chung** (simulation REST, qua `LoadTestConfig` trừ khi có ghi chú khác):

| Flag                | Mặc định          | Ý nghĩa                                                                                  |
|---------------------|-------------------|------------------------------------------------------------------------------------------|
| `users`             | `1000`            | silkroad Soak: số VU được inject (open model). bonanza Soak và mọi simulation gRPC: số VU đồng thời (closed model). Basic: số VU khởi động cùng lúc. |
| `durationMinutes`   | `60`              | Số phút ở trạng thái ổn định. Trong Stress, là độ dài của ramp arrival rate.             |
| `rampMinutes`       | `5`; `2` cho bonanza Soak và mọi simulation gRPC | Số phút ramp-up trước trạng thái ổn định.                   |
| `requests`          | `10000`           | Chỉ cho Basic: tổng ngân sách request. Mỗi VU lặp `max(1, requests / users)` lần.        |
| `host`, `port`, `contextPath` | `localhost`, `3000`, `""` (bonanza: `3005`, `/golden`) | Base URL của REST. Override các giá trị này để nhắm vào staging. |
| `thinkTimeMin`, `thinkTimeMax` | `1`, `3`  | Số giây dừng ngẫu nhiên sau mỗi request chính trong journey của silkroad.                |
| `scenario`          | endpoint đầu tiên | Chỉ cho Basic: tên một endpoint, hoặc `all` / `chain` / `burst`. Giá trị không hợp lệ sẽ fail với `Unknown scenario: '<x>'. Valid: …`. |
| `parallel`          | `false`           | Chỉ cho silkroad Soak: `true` inject toàn bộ user cùng lúc (không ramp).                 |
| `maxResponseTimeMs` | `0` (tắt)         | Chỉ cho Basic: thêm assertion `max response time ≤ N`.                                   |

**silkroad Stress và Spike** (đọc bởi `StressSimulationBase` / `SpikeSimulationBase`):

| Flag                   | Mặc định | Ý nghĩa                                                                                 |
|------------------------|----------|-----------------------------------------------------------------------------------------|
| `usersStart`, `usersEnd` | `500`, `2500` | Stress: arrival rate ở đầu và cuối ramp, tính bằng **user mỗi phút** (simulation chia cho 60). Ramp kéo dài `durationMinutes`. |
| `baseline`             | `200`    | Spike: arrival rate ổn định, tính bằng **user mỗi phút**.                               |
| `spike`                | `1500`   | Spike: số user được inject cùng lúc trong mỗi đợt burst.                                |
| `cycles`               | `5`      | Spike: số đợt burst.                                                                    |
| `cycleIntervalMinutes` | `5`      | Spike: độ dài một chu kỳ. Tổng thời gian chạy = `cycles × cycleIntervalMinutes`. Spike bỏ qua `durationMinutes` và `rampMinutes`. |
| `spikeDurationSec`     | `30`     | Spike: thời gian chờ trước mỗi đợt burst là `cycleIntervalMinutes × 60 − spikeDurationSec` giây. |

**bonanza Soak và mọi simulation gRPC:**

| Flag          | Mặc định                 | Ý nghĩa                                                                                  |
|---------------|--------------------------|------------------------------------------------------------------------------------------|
| `paceSec`     | `5`                      | Số giây tối thiểu giữa hai vòng lặp của mỗi VU (Gatling `pace`).                         |
| `requestRate` | `50`                     | Gatling assertion: số request mỗi giây toàn cục phải vượt ngưỡng sàn này. Dùng `0` cho smoke test ngắn. |
| `eventCount`  | `100000`                 | Gatling assertion: số request thành công phải vượt con số này. Dùng `0` cho smoke test ngắn. |
| `grpcHost`    | `localhost`              | Chỉ cho simulation gRPC.                                                                  |
| `grpcPort`    | `9091` bonanza, `9096` naga777, `9104` mutantmerge, `9103` zeroday | Chỉ cho simulation gRPC.                        |

**Flag mức cược riêng của từng game:**

| Game          | Flag           | Mặc định | Ý nghĩa                                                                                 |
|---------------|----------------|----------|-----------------------------------------------------------------------------------------|
| `naga777`     | `coinValue`    | `5`      | Gửi dưới dạng `coinValueId`. Backend chấp nhận 1, 5, 20, 50, 100, 200 hoặc 500.          |
| `naga777`     | `coinPerLine`  | `3`      | Gửi dưới dạng `betLevelId` (1 đến 10). Server tự tính bet = `coinValue × coinPerLine × 5` (mặc định 75). |
| `mutantmerge` | `betLevelId`   | `3`      | Chỉ số bắt đầu từ 1 trong thang cược, gửi dưới dạng string. Simulation ghi chú mặc định này là $1.00. |
| `mutantmerge` | `superBet`     | `false`  | `true` sẽ thêm `superBet: true` vào mỗi lượt spin.                                       |
| `zeroday`     | `bet`          | `1.00`   | Số thập phân trên thang cược `0.20`–`100.00` (25 bậc). Giá trị nằm ngoài thang sẽ fail ngay khi simulation được nạp, trước khi có request nào. |

## Chạy nâng cao không qua wrapper

Gọi trực tiếp `./gradlew :games:<game>:<alias>` khi bạn cần những thứ wrapper không cung cấp:

- **Hình dạng tải Stress hoặc Spike**: `-DusersStart`, `-DusersEnd`, `-Dbaseline`, `-Dspike`, `-Dcycles`, …
- **Override endpoint gRPC**: `-DgrpcHost`, `-DgrpcPort`.
- **Tinh chỉnh gRPC và bonanza Soak**: `-DpaceSec`, `-DrequestRate`, `-DeventCount`, và các flag mức cược ở trên.
- **Chạy một simulation bất kỳ theo tên class đầy đủ (FQCN).**
- **Lặp nhanh khi đang phát triển một game**: không khởi động monitor và không có verdict.

Bạn chỉ nhận được report HTML của Gatling tại `games/<game>/build/reports/gatling/<simulationclass>-<timestamp>/index.html`. **Không có `summary.html`, không có `verdict.json` và không có file CSV nào.** Nếu một Gatling assertion fail, Gradle task sẽ fail. Các lần chạy ngắn của bonanza Soak và simulation gRPC luôn fail ngưỡng sàn `requestRate` / `eventCount`, trừ khi bạn truyền `-DrequestRate=0 -DeventCount=0`.

### Lệnh Gradle cho từng game

Chạy các lệnh này từ thư mục gốc repo. Thay các placeholder `<…>` bằng giá trị staging của bạn.

```bash
# silkroad (REST, Gatling 3.15)
./gradlew :games:silkroad:basic  -Dscenario=spin -Dusers=1 -Drequests=1
./gradlew :games:silkroad:soak   -Dusers=50 -DdurationMinutes=1 -DrampMinutes=1
./gradlew :games:silkroad:stress -DusersStart=10 -DusersEnd=200 -DdurationMinutes=5
./gradlew :games:silkroad:spike  -Dbaseline=5 -Dspike=50 -Dcycles=3 -DcycleIntervalMinutes=1

# bonanza REST (port 3005 và contextPath /golden lấy từ game.yml)
./gradlew :games:bonanza:basic -Dscenario=BetLevels -Dusers=1 -Drequests=1
./gradlew :games:bonanza:soak  -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1 \
  -DrequestRate=0 -DeventCount=0
# bonanza REST nhắm vào host khác
./gradlew :games:bonanza:soak -Dhost=<rest-host> -Dport=<rest-port> -DcontextPath=/golden

# bonanza gRPC (Gatling 3.9.5; kênh riêng, không có contextPath)
./gradlew :games:bonanza:grpc -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1 \
  -DgrpcHost=localhost -DgrpcPort=9091

# naga777 gRPC
./gradlew :games:naga777:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
  -DrequestRate=0 -DeventCount=0
./gradlew :games:naga777:grpc -Dusers=1000 -DdurationMinutes=60 \
  -DgrpcHost=localhost -DgrpcPort=9096 -DcoinValue=5 -DcoinPerLine=3

# mutantmerge gRPC
./gradlew :games:mutantmerge:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
  -DrequestRate=0 -DeventCount=0
./gradlew :games:mutantmerge:grpc -Dusers=1000 -DdurationMinutes=60 -DbetLevelId=3 -DsuperBet=true

# zeroday gRPC
./gradlew :games:zeroday:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
  -DrequestRate=0 -DeventCount=0
./gradlew :games:zeroday:grpc -Dusers=1000 -DdurationMinutes=60 -Dbet=1.00

# Bất kỳ game gRPC nào, nhắm vào host khác
./gradlew :games:<game>:grpc -DgrpcHost=<grpc-host> -DgrpcPort=<grpc-port>
```

Để liệt kê các task của một game, chạy `./gradlew :games:<game>:tasks --group=gatling`.

### Chạy simulation bất kỳ theo tên class

Chỉ những module áp dụng Gatling Gradle plugin (silkroad và bonanza) mới có task `gatlingRun` của plugin:

```bash
./gradlew :games:silkroad:gatlingRun \
  --simulation com.rgp.loadtest.silkroad.simulations.SoakSimulation
./gradlew :games:bonanza:gatlingRun \
  --simulation com.rgp.loadtest.bonanza.simulations.BasicSimulation
```

- `gatlingRun` là task riêng của plugin. Nó không thêm `gameName` và không đọc `FORWARDED_PROPS`, nhưng plugin vẫn chép các flag `-D` của bạn sang fork (xem [System property của Gradle](#system-property-của-gradle)). Assertion SLA của nó giống hệt của alias, vì lớp file theo game vốn không bao giờ resolve được bên trong fork của simulation (xem [Ngưỡng SLA và thứ tự nạp](#ngưỡng-sla-và-thứ-tự-nạp)).
- Trên bonanza, `gatlingRun` chỉ thấy source set REST (`src/gatling/java`). Simulation gRPC nằm trong source set riêng `gatlingGrpc`, và chỉ task `grpc` chạy được nó.
- naga777, mutantmerge và zeroday không có `gatlingRun`. Mỗi entry trong map `SIMULATIONS` của chúng trở thành một task `JavaExec`, nên thêm một simulation Scala mới ở đó thì cần thêm một entry mới vào map. Task `grpc` của bonanza được gắn cứng vào `BonanzaGrpcSimulation`.

### Chạy lại verifier trên kết quả có sẵn

`verifyVariant` (trong `build.gradle` gốc) chạy `ThresholdVerifier` trên các file CSV và log của một lần chạy, rồi in JSON verdict ra stdout. Chạy nó từ thư mục gốc repo và dùng `-q` để có JSON sạch:

```bash
RUN=target/variants/silkroad/target-<timestamp>
./gradlew verifyVariant -q \
  -DgameName=silkroad \
  -DresourceCsv=$RUN/resource.csv \
  -DhealthCsv=$RUN/health.csv \
  -DgatlingLog=$RUN/gatling.log \
  -Dvariant=stress -Dusers=1000 -DdurationSec=3900
```

| Property      | Bắt buộc | Ý nghĩa                                                                                       |
|---------------|----------|-----------------------------------------------------------------------------------------------|
| `resourceCsv` | có       | Các mẫu CPU/Mem (output của `monitor-resources.sh`).                                          |
| `healthCsv`   | có       | Các mẫu health probe (output của `health-poll.sh`).                                           |
| `variant`     | có       | Variant có ngưỡng trần cần áp dụng.                                                           |
| `users`       | có       | Ghi vào verdict dưới tên `users`.                                                             |
| `durationSec` | có       | Ghi vào dưới tên `duration_sec`. Wrapper truyền số giây wall-clock đo được của lần chạy Gatling. |
| `gatlingLog`  | không    | Được parse để lấy tổng số, KO %, mean và p95. Hiểu được cả định dạng console của Gatling 3.15 lẫn 3.9. |
| `gatlingExit` | không    | Chỉ dùng cho PR-5 khi log không cho ra KO %. Ghi vào dưới tên `gatling_exit_code`.            |
| `gameName`    | không    | Thêm lớp SLA theo game. Ghi vào dưới tên `game_name`.                                         |
| `slaConfig`   | không    | File override SLA toàn phần (xem [thứ tự nạp](#ngưỡng-sla-và-thứ-tự-nạp)).                    |
| `meanMsCeil`, `httpKoCeil` | không | Override trần PR-4 và PR-5 cho verdict này.                                            |

**Exit code và các bước tiếp theo:**

- `ThresholdVerifier` thoát với `0` khi PASS, `1` khi FAIL và `2` khi thiếu property bắt buộc hoặc variant không tồn tại. Task đặt `ignoreExitValue = true`, nên bản thân `./gradlew` vẫn thành công. Hãy đọc field `verdict` trong JSON.
- Để làm mới `summary.html`, chuyển hướng output vào `$RUN/verdict.json` (file gốc sẽ bị thay thế) rồi chạy `python3 scripts/generate-summary-html.py --variant-dir $RUN`.
- Để có report tổng hợp qua các variant, xem `generate-final-report.py` trong [tham chiếu các script](#tham-chiếu-các-script).

## Cấu trúc project

### Tech stack và phiên bản

Các phiên bản dùng chung được ghim trong `build.gradle` gốc (`ext { … }`). Runtime gRPC được ghim trong `build.gradle` của từng game gRPC (`gatlingOssVersion`, `gatlingGrpcVersion`).

| Thành phần                    | Phiên bản   | Dùng ở đâu                                                                        |
|-------------------------------|-------------|-----------------------------------------------------------------------------------|
| Java toolchain                | **17**      | Mọi subproject (`javaTargetVersion`).                                              |
| Gradle wrapper                | 9.2.1       | `gradle/wrapper/gradle-wrapper.properties`. Không cần cài riêng.                  |
| Gatling (simulation REST)     | **3.15.0**  | `gatlingVersion`, được `:core` export dưới dạng dependency `api`. silkroad, bonanza `soak` / `basic`. |
| Gatling Gradle plugin         | 3.15.0.2    | `gatlingGradleVersion`, chỉ silkroad và bonanza áp dụng.                          |
| Gatling (simulation gRPC)     | **3.9.5**   | `gatlingOssVersion` trong bonanza, naga777, mutantmerge và zeroday. Xem [Runtime cho gRPC](#runtime-cho-grpc). |
| gRPC DSL                      | `com.github.phisgr:gatling-grpc` 0.17.0 | `gatlingGrpcVersion`. Plugin cộng đồng, không giới hạn số VU. |
| Scala library                 | **2.13.12** | Chỉ bốn simulation gRPC. Xem [Simulation gRPC viết bằng Scala](#simulation-grpc-viết-bằng-scala). |
| gRPC Java / Protobuf          | 1.75.0 / 4.32.1 | `grpcVersion` / `protobufVersion`. Stub được sinh trong `:core` từ `plugin_service.proto`. |
| Protobuf Gradle plugin        | 0.9.5       | `protobufPluginVersion` (`com.google.protobuf`, trong `:core`).                   |
| SnakeYAML                     | 2.2         | Các loader YAML cho SLA và runtime trong `:core`.                                 |
| GaaS `common-data`            | 1.0.0       | `core/libs/common-data-1.0.0.jar`, thư viện MessagePack độc quyền. Được phát hành dưới dạng bytecode Java 21 và được `:core:downgradeGaasJar` viết lại thành Java 17 (output ở `core/build/libs-jdk17/`). |
| MessagePack / json-smart      | `msgpack-core` 0.9.8, `msgpack` 0.6.12, `json-smart` 2.5.0 | Dependency runtime của thư viện GaaS. |
| Python                        | 3.9+        | `generate-summary-html.py`, `generate-final-report.py`.                            |

### Runtime cho gRPC

Mọi simulation gRPC chạy trên **Gatling 3.9.5** với plugin cộng đồng `com.github.phisgr:gatling-grpc`, không phải trên Gatling 3.15 mà các simulation REST dùng.

Lý do là một giới hạn cứng. gRPC DSL chính chủ của Gatling 3.15 (`io.gatling:gatling-grpc-java`) là tính năng của Gatling Enterprise. Không có licence, nó chạy ở chế độ trial và **huỷ simulation khi vượt 5 VU đồng thời hoặc 5 phút**. Ở mức 10 VU, lần chạy chết trong vài giây với `Some of the simulations crashed`, không bao giờ tới được user thứ 6, nên không thể chạy production gate 1000 VU trên nó.

Plugin cộng đồng dùng giấy phép Apache 2.0 và không có giới hạn. Bản phát hành cuối của nó (0.17.0) nhắm tới Gatling 3.9.5, nhưng `io.gatling.gradle` 3.9.5.x tương ứng lại hỏng trên Gradle 9 (`unknown property 'reportsDir'`). Vì vậy các simulation gRPC bỏ hẳn Gatling Gradle plugin. Mỗi simulation tự dựng classpath Gatling 3.9.5 riêng trong một configuration dành riêng và khởi chạy `io.gatling.app.Gatling` qua một task `JavaExec` thuần. Task ghi report vào `build/reports/gatling`, cùng chỗ plugin vẫn dùng, nên wrapper nhặt được report mà không cần thay đổi gì.

- **naga777, mutantmerge và zeroday** chỉ có gRPC. Cả module chạy trên 3.9.5: configuration `gatlingRt`, source set `gatling`, source nằm ở `src/gatling/scala`.
- **bonanza** có cả hai runtime, nên được tách đôi. `src/gatling/java` (REST) vẫn ở 3.15 dưới Gatling Gradle plugin. `src/gatlingGrpc/scala` compile và chạy với 3.9.5 (configuration `gatlingGrpcRt`, source set `gatlingGrpc`). Hai classpath không bao giờ trộn lẫn.
- **silkroad** chỉ có REST và giữ nguyên. HTTP DSL của Gatling hoàn toàn OSS và không giới hạn, nên không có lý do gì để chuyển nó.
- Mọi runtime gRPC đều kéo `project(':core')` vào để dùng proto stub, `Codec` MessagePack và `SlaConstants`. Nó dùng `exclude group: 'io.gatling'` và `exclude group: 'io.gatling.highcharts'` để Gatling 3.15 mà `:core` export không bao giờ lọt vào classpath 3.9.5.

Mọi thứ phía sau vẫn hoạt động như cũ: wrapper, threshold verifier và `summary.html`. Gatling 3.9 in bản tóm tắt trên console dưới dạng `> request count  640 (OK=640  KO=0 )` thay vì bảng phân cách bằng dấu `|` như 3.15, nên threshold verifier parse được cả hai dạng.

**Lưu ý:** plugin cộng đồng đã **bị archive ở upstream** (commit cuối vào tháng 2 năm 2024) và sẽ không bao giờ hỗ trợ Gatling 3.10+. Nếu sau này các simulation REST chuyển lên Gatling mới hơn, các simulation gRPC vẫn ở lại 3.9.5, trừ khi có người fork plugin hoặc mua licence Enterprise.

### Simulation gRPC viết bằng Scala

Harness này viết bằng Java, trừ **bốn file Scala**, mỗi game một simulation gRPC:

| File                                                                                         | `pluginName`            | `grpcPort` mặc định | Flag thêm                  | Kiểm tra Spin |
|----------------------------------------------------------------------------------------------|-------------------------|---------------------|----------------------------|---------------|
| `games/bonanza/src/gatlingGrpc/scala/com/rgp/loadtest/bonanza/grpc/BonanzaGrpcSimulation.scala` | `golden-boat-bonanza` | `9091`              | không có                   | Chỉ status gRPC |
| `games/naga777/src/gatling/scala/com/rgp/loadtest/naga777/grpc/Naga777GrpcSimulation.scala`   | `game-naga-fortune-777` | `9096`            | `coinValue`, `coinPerLine` | Chỉ status gRPC (kết quả được đẩy qua ZMQ) |
| `games/mutantmerge/src/gatling/scala/com/rgp/loadtest/mutantmerge/grpc/MutantMergeGrpcSimulation.scala` | `yama_01024` | `9104`           | `betLevelId`, `superBet`   | Decode reply và fail khi `c` khác 0 |
| `games/zeroday/src/gatling/scala/com/rgp/loadtest/zeroday/grpc/ZeroDayGrpcSimulation.scala`   | `yama_01023`            | `9103`              | `bet`                      | Chỉ status gRPC (reply rỗng và kết quả đi qua ZMQ) |

**Vì sao là Scala?** `com.github.phisgr:gatling-grpc` chỉ cung cấp Scala DSL (`com.github.phisgr.gatling.grpc.Predef._`), nên các simulation này viết bằng Scala 2.13. Payload của chúng vẫn lấy từ `:core`:

- **Java proto stub** (`com.rgp.loadtest.grpc.*`), sinh từ `core/src/main/proto/plugin_service.proto`. Không dùng ScalaPB.
- **`Codec` MessagePack** (`com.rgp.loadtest.core.protocol.Codec`), chạy trên JAR GaaS.
- **`SlaConstants`**, cho trần PR-4 và PR-5 và buffer của `maxDuration`. Nó là Java thuần và an toàn trên classpath 3.9.5. `LoadTestConfig` thì không an toàn ở đó (xem [Mặc định runtime và override theo game](#mặc-định-runtime-và-override-theo-game)).

**Khung chung.** Mọi simulation dùng cùng một journey và bộ assertion:

- **Closed model:** `rampConcurrentUsers(0).to(users)` trong `rampMinutes`, sau đó `constantConcurrentUsers(users)` trong `durationMinutes`.
- **Mỗi VU:** một `Join` (`ConnectAndCall`), sau đó một vòng lặp `Spin` (`Call`) với pace là `paceSec`. Ở naga777, mutantmerge và zeroday, `Join` thất bại sẽ dừng VU (`exitHereIfFailed`); bonanza thì vẫn đi tiếp vào vòng lặp.
- **Assertion:** mean toàn cục ≤ PR-4, tỉ lệ fail ≤ PR-5, request/s > `requestRate`, số request thành công > `eventCount`, p95 của `Spin` ≤ 800 ms, và tỉ lệ fail của `Spin` và `Join` ≤ 0.5.

**Cách nối dây cho module chỉ có gRPC** (lấy từ `games/zeroday/build.gradle`; naga777 và mutantmerge giống hệt, chỉ khác tên và flag):

```groovy
plugins {
    id 'java'
    id 'scala'                      // không có io.gatling.gradle ở đây
}

def gatlingOssVersion = '3.9.5'
def gatlingGrpcVersion = '0.17.0'

configurations {
    gatlingRt
}

dependencies {
    gatlingRt "io.gatling.highcharts:gatling-charts-highcharts:${gatlingOssVersion}"
    gatlingRt "com.github.phisgr:gatling-grpc:${gatlingGrpcVersion}"
    gatlingRt 'org.scala-lang:scala-library:2.13.12'
    gatlingRt(project(':core')) {           // proto stub + Codec, bỏ Gatling 3.15
        exclude group: 'io.gatling'
        exclude group: 'io.gatling.highcharts'
    }
}

sourceSets {
    gatling {
        scala.srcDirs = ['src/gatling/scala']
        java.srcDirs = []
        resources.srcDirs = ['src/gatling/resources']
        compileClasspath = configurations.gatlingRt
        runtimeClasspath = output + configurations.gatlingRt
    }
}

// FORWARDED_PROPS = [...]; SIMULATIONS = [grpc: 'com.rgp.loadtest.zeroday.grpc.ZeroDayGrpcSimulation']
// Mỗi entry trong SIMULATIONS đăng ký một task JavaExec: dependsOn gatlingClasses,
// mainClass 'io.gatling.app.Gatling', args '-s', fqcn, '-rf', build/reports/gatling,
// cùng bộ jvmArgs --add-opens như các module REST, và trong doFirst:
// systemProperty 'gameName', project.name cùng mọi tên trong FORWARDED_PROPS đã được đặt.
```

**bonanza khác** ở các điểm sau:

- Nó giữ `id 'io.gatling.gradle'` cho các simulation REST.
- Nó đặt tên configuration là `gatlingGrpcRt` và source set là `gatlingGrpc` (`scala.srcDirs = ['src/gatlingGrpc/scala']`).
- Nó đặt `resources.srcDirs = ['src/gatlingGrpc/resources', 'src/gatling/resources']` trên source set đó, để `logback-test.xml` của nó đứng trước và `game.yml` / `sla-thresholds.yml` dùng chung cũng có mặt trên classpath gRPC.
- Nó đăng ký một task `JavaExec` tên `grpc` viết tay, phụ thuộc vào `gatlingGrpcClasses`.

**Cách chạy.** Dùng wrapper để có `summary.html` và verdict. Dùng Gradle trực tiếp để override endpoint gRPC hoặc các flag tinh chỉnh.

```bash
./scripts/run-variant.sh --game zeroday --variant target --simulation Grpc \
  --users 10 --duration-minutes 5 --ramp-minutes 1 --container game-zero-day

./gradlew :games:zeroday:grpc -Dusers=10 -DdurationMinutes=5 -DrampMinutes=1 \
  -DgrpcHost=<grpc-host> -DgrpcPort=9103
```

**Wire format:** `ConnectAndCallRequest.user.parameters` và `PluginRequest.data` là các blob mã hoá MessagePack, không phải cấu trúc con protobuf. Backend decode chúng bằng cùng thư viện GaaS. Hãy dựng chúng bằng `java.util.LinkedHashMap` và giữ nguyên thứ tự chèn khi thay đổi payload. Trên bonanza, sai lệch wire format lộ ra phía server dưới dạng NPE ở `UserContext.fromPuObject`.

### Cây thư mục

Các file được track, nhóm theo chức năng. Output sinh ra (`build/`, `target/`) nằm trong gitignore.

```
.
├── README.md                         chính file này
├── loadtest.sh                       menu tương tác → scripts/run-variant.sh
├── build.gradle                      Java 17 toolchain dùng chung, các property phiên bản, task verifyVariant
├── settings.gradle                   include :core và năm game (4 game khác đang bị comment)
├── gradlew, gradlew.bat, gradle/wrapper/   Gradle 9.2.1 wrapper
├── config/
│   └── sla-thresholds.yml            ngưỡng pass/fail cho toàn repo
├── core/                             thư viện dùng chung: đừng sửa khi thêm game
│   ├── build.gradle                  java-library + protobuf; task downgradeGaasJar
│   ├── libs/
│   │   ├── common-data-1.0.0.jar     thư viện MessagePack GaaS độc quyền (bytecode Java 21)
│   │   └── README.md
│   └── src/main/
│       ├── java/com/rgp/loadtest/core/
│       │   ├── config/               LoadTestConfig, LoadTestConfigLoader, LoadTestDefaults,
│       │   │                         SlaConfig, SlaConfigLoader (xếp lớp YAML)
│       │   ├── protocol/             Codec (MessagePack qua thư viện GaaS)
│       │   ├── scenarios/            SessionJourneyTemplate (vòng lặp + định tuyến theo tỉ lệ)
│       │   ├── simulations/          {Soak,Stress,Spike,Basic}SimulationBase
│       │   ├── utils/                SlaConstants, SystemProps
│       │   └── verify/               ThresholdVerifier (JSON verdict)
│       ├── proto/plugin_service.proto    WSProxy PluginService → Java gRPC stub
│       └── resources/                load-test-defaults.yml, sla-thresholds.yml (bản dự phòng đóng gói sẵn)
├── games/
│   ├── silkroad/                     chỉ REST, Java, stateless (open model)
│   │   ├── build.gradle
│   │   ├── docker-compose.loadtest.yml
│   │   └── src/gatling/
│   │       ├── java/com/rgp/loadtest/silkroad/   simulations/ scenarios/ requests/ utils/
│   │       └── resources/            game.yml, sla-thresholds.yml, gatling.conf, logback-test.xml,
│   │                                 games/silkroad/bodies/*.json
│   ├── bonanza/                      REST (Java, Gatling 3.15) + gRPC (Scala, Gatling 3.9.5), stateful
│   │   ├── build.gradle              (không có docker-compose.loadtest.yml)
│   │   └── src/
│   │       ├── gatling/
│   │       │   ├── java/com/rgp/loadtest/bonanza/   simulations/ scenarios/ requests/ utils/
│   │       │   └── resources/        game.yml, sla-thresholds.yml, games/bonanza/bodies/*.json
│   │       └── gatlingGrpc/
│   │           ├── scala/com/rgp/loadtest/bonanza/grpc/BonanzaGrpcSimulation.scala
│   │           └── resources/logback-test.xml
│   ├── naga777/                      chỉ gRPC, Scala ─┐
│   ├── mutantmerge/                  chỉ gRPC, Scala  ├─ cùng bố cục:
│   └── zeroday/                      chỉ gRPC, Scala ─┘
│       ├── build.gradle
│       ├── docker-compose.loadtest.yml
│       └── src/gatling/
│           ├── scala/com/rgp/loadtest/<game>/grpc/<Name>GrpcSimulation.scala
│           └── resources/            game.yml, sla-thresholds.yml, logback-test.xml
├── scripts/
│   ├── run-variant.sh                điểm vào chuẩn cho các lần chạy có verdict
│   ├── ensure-sut.sh                 khởi động SUT từ games/<game>/docker-compose.loadtest.yml
│   ├── monitor-resources.sh          docker stats → resource.csv
│   ├── health-poll.sh                HTTP probe → health.csv
│   ├── generate-summary-html.py      dựng summary.html
│   └── generate-final-report.py      report tuân thủ dạng Markdown qua các variant
├── HUONG-DAN.md                      hướng dẫn tiếng Việt cho người mới
├── naga777-load-test-guide.md        hướng dẫn chạy naga777 và đọc kết quả
│
│   được sinh ra, không track:
├── core/build/libs-jdk17/            JAR GaaS đã hạ phiên bản
├── games/<game>/build/reports/gatling/<simulation>-<timestamp>/   report Gatling gốc
└── target/variants/<game>/<variant>-<timestamp>/                  bộ kết quả của mỗi lần chạy từ wrapper
```

### Tham chiếu các script

| Script | Mục đích | Cách dùng / flag chính | Được gọi bởi |
|--------|----------|------------------------|--------------|
| `loadtest.sh` | Menu tương tác. Nó chọn game, simulation và quy mô, hiển thị lệnh `run-variant.sh` tương đương, chạy lệnh đó và đề nghị mở `summary.html`. | Không có flag. Cần một terminal (nếu không có, nó thoát và chỉ sang `run-variant.sh`). Danh sách game lấy từ bảng `GAMES` ở đầu file; `●` / `○` cho biết container của từng game có đang chạy hay không. | Bạn |
| `scripts/run-variant.sh` | Lần chạy có verdict đầy đủ: kiểm tra SUT, monitor, Gatling, verifier, summary. | Xem [Tham số của wrapper](#tham-số-của-wrapper). | `loadtest.sh`, bạn, CI |
| `scripts/ensure-sut.sh` | Đảm bảo SUT đang chạy và đề nghị khởi động nếu chưa. | `--game <g> --container <name> --port <N> --health-url <url>` | `run-variant.sh` (bước 0) |
| `scripts/monitor-resources.sh` | Lấy mẫu CPU và memory vào một file CSV. | `--container <name> [--fallback-port N] [--interval-sec N] --out <csv> --duration-sec N` (interval mặc định là 5) | `run-variant.sh` (chạy nền) |
| `scripts/health-poll.sh` | Probe một URL HTTP và ghi vào một file CSV. | `[--url <url>] [--interval-sec N] --out <csv> --duration-sec N` (interval mặc định là 2) | `run-variant.sh` (chạy nền) |
| `scripts/generate-summary-html.py` | Dựng `summary.html` từ thư mục của một lần chạy. | `--variant-dir <dir>` | `run-variant.sh`, hoặc chạy tay sau khi verify lại |
| `scripts/generate-final-report.py` | Dựng report tuân thủ dạng Markdown qua các variant. | `--variants-dir target/variants/<game> --report-out <file.md> [--host localhost] [--port 3000]` | Chạy tay (xem `naga777-load-test-guide.md`) |

**Các preset của `loadtest.sh`:**

| Preset      | Users | Số phút | Số phút ramp              | Variant    |
|-------------|-------|---------|---------------------------|------------|
| Smoke       | 10    | 1       | 0                         | `target`   |
| Quick check | 200   | 5       | 1                         | `baseline` |
| Full test   | 1000  | 60      | 5 (2 với `Grpc`)          | `target`   |

- **Custom** hỏi số user, số phút, ramp, có cho tất cả vào cùng lúc hay không (`--parallel`, chỉ với Soak), variant và HTTP port.
- **Basic** hỏi API nào (các scenario trong dòng của game), sau đó hỏi số user (mặc định 500) và tổng số request (mặc định 5000). Nó chạy 1 phút, không có ramp.

**`run-variant.sh` từng bước:**

1. Kiểm tra tham số (`--variant`, `--simulation`, `--container` và `--game` / `GAME` là bắt buộc). `games/<game>/` phải tồn tại.
2. Suy ra `PORT` từ một khối `case "$GAME"`, trừ khi có truyền `--port`, rồi luôn dựng `HEALTH_URL` từ khối `case "$GAME"` thứ hai bằng port đó.
3. Chạy `ensure-sut.sh`.
4. Tạo `target/variants/<game>/<variant>-<timestamp>/`.
5. Khởi động `monitor-resources.sh` (mỗi 5 giây) và `health-poll.sh` (mỗi 2 giây) ở chế độ nền. Cả hai chạy trong `(duration + ramp) × 60 + 120` giây.
6. Chạy `./gradlew :games:<game>:<simulation in lowercase> -Dusers -DdurationMinutes -DrampMinutes -Dport [-Dparallel=true] [-Drequests] [-Dscenario] --console=plain` và tee output vào `gatling.log`.
7. Sao chép thư mục mới nhất trong `games/<game>/build/reports/gatling/` sang `gatling-report/`.
8. Chạy `./gradlew verifyVariant -q -DgameName=<game> …` và ghi `verdict.json`.
9. Chạy `generate-summary-html.py`.
10. Thoát với exit code của **lần chạy Gatling** ở bước 6 (lệnh `./gradlew`), không phải của verdict. Ctrl-C dừng các monitor và giữ lại bộ kết quả dở dang.

**Chi tiết `ensure-sut.sh`:**

1. Thoát `0` ngay nếu container đang chạy (khớp tên chính xác) hoặc health URL trả về 2xx.
2. Nếu không, nó cần ba thứ: `games/<game>/docker-compose.loadtest.yml`, một terminal, và Docker đang chạy. Thiếu thứ nào, nó dừng và giải thích. Khi không có terminal (CI), nó còn in ra lệnh chạy tay `cp … && docker compose … up -d --build`.
3. Nó hỏi một thư mục source trên máy, hoặc một git URL (được clone từ nhánh `main` vào một thư mục tạm). Thư mục đó phải có `Dockerfile`.
4. Nó sao chép file compose vào. Nếu đã có một bản khác ở đó, bản cũ được giữ lại dưới tên `docker-compose.loadtest.yml.bak`.
5. Nó chạy `HTTP_PORT=<port> docker compose -f docker-compose.loadtest.yml up -d --build`.
6. Nó poll health URL mỗi 3 giây, tối đa 300 giây. Khi hết giờ, nó in 50 dòng log cuối của container.

**Ghi chú về monitor và probe:**

- **`monitor-resources.sh`** dùng `docker stats` trên container. Nếu container không tồn tại, nó lấy mẫu process trên host đang listen ở `--fallback-port` (tìm bằng `lsof`, đọc bằng `ps`). Nếu cũng không có, nó ghi các dòng `N/A`.
- **`health-poll.sh`** đánh dấu một mẫu là thất bại khi status không phải 2xx hoặc request mất 5 giây trở lên (`curl --max-time 5`). **Nó dừng và thoát với mã khác 0 sau 3 lần thất bại liên tiếp**, bất kể `crash.max_consecutive_failures` đặt bao nhiêu. Khi không có `--url`, nó thử các đường dẫn riêng của silkroad trên `localhost:3000`.

**Chi tiết `generate-final-report.py`:**

- Nó lấy thư mục `<variant>-*` mới nhất cho từng variant `baseline`, `target`, `stress` và `critical`.
- Nó đọc `verdict.json` và `gatling-report/js/stats.json` từ mỗi thư mục. Variant nào thiếu sẽ hiển thị là `N/A`.
- Chỉ `target` quyết định verdict cuối cùng. Nhãn ngưỡng trần được hardcode trong script, không đọc từ YAML.

### Các lớp bên trong subproject của game

Game REST (silkroad, bonanza REST), dưới `src/gatling/`:

| Lớp                 | Trách nhiệm                                                                     |
|---------------------|---------------------------------------------------------------------------------|
| `simulations/`      | Profile inject, assertion SLA, HTTP protocol.                                   |
| `scenarios/`        | Ghép các request thành một journey (vòng lặp + think time + tỉ lệ / `randomSwitch`). |
| `requests/`         | Một lời gọi HTTP: URL + method + body + check + lưu giá trị vào session.        |
| `utils/Endpoints.java` | Tên endpoint dùng trong report và làm giá trị cho `--scenario`.              |
| `resources/games/<pkg>/bodies/*.json` | Template body theo cú pháp Gatling EL `#{userId}`.            |
| `resources/game.yml`, `resources/sla-thresholds.yml` | Override runtime và SLA theo game.             |

Simulation gRPC không chia lớp. Một file Scala chứa các hàm dựng payload (`buildJoinRequest` / `buildSpinRequest`), gRPC protocol, journey, injection và assertion.

### Hai mẫu journey

Các game REST theo một trong hai mẫu:

| Khía cạnh    | Silkroad (template)                              | Bonanza (stateful)                                              |
|--------------|--------------------------------------------------|-----------------------------------------------------------------|
| Injection    | Open (`rampUsers` / `atOnceUsers`)               | Closed (`rampConcurrentUsers` + `constantConcurrentUsers`)      |
| Định tuyến   | Modulo theo tỉ lệ (`userIndex % N == 0`)         | `randomSwitch` có trọng số (80/8/5/3/2/2)                       |
| State        | Stateless                                        | Lưu `sessionId` / `roundId` / `gameId` / `bonusTriggered`       |
| Khởi tạo     | Không có                                         | Mỗi VU: `BetLevels` → `ReelStrips` → `CreateSession`, rồi vào vòng lặp |
| Endpoint     | 3                                                | 10                                                              |
| Status của Spin | `200`                                         | **`201`**                                                       |
| Ngôn ngữ     | Chỉ Java                                         | Java (REST) + Scala (gRPC)                                      |

Bốn simulation gRPC dùng chung một khung thứ ba, đơn giản hơn: closed model, một `Join`, rồi một vòng lặp `Spin` có pace (xem [Simulation gRPC viết bằng Scala](#simulation-grpc-viết-bằng-scala)).

## Thêm game mới

**Đừng sửa `:core`.** Hãy clone game có sẵn gần giống nhất, rồi làm lần lượt các giai đoạn dưới đây.

Ví dụ xuyên suốt sẽ thêm `fruit-respin-mania` chạy trên `localhost:4000` với context path `/fruit`. Game này có ba endpoint REST (`spin`, `last-spin`, `history-summary`) và theo mẫu stateless của silkroad. Ở bất cứ đâu bạn thấy `fruit-respin-mania`, `fruitrespinmania`, `4000` hoặc `/fruit`, hãy thay bằng giá trị thật của bạn. Giai đoạn nào khác đi với game chỉ có gRPC thì sẽ ghi rõ.

### Giai đoạn 0 Lập kế hoạch

Trước khi đụng vào code, hãy ghi lại những thứ sau. Mọi bước sau đều phụ thuộc vào chúng.

| Quyết định                      | Xuất hiện ở đâu                                                                           | Ví dụ                              |
|---------------------------------|-------------------------------------------------------------------------------------------|------------------------------------|
| **Game id**                     | Thư mục `games/<id>/`, Gradle path `:games:<id>`, `--game`, `gameName`, thư mục output    | `fruit-respin-mania`              |
| **Tên package**                 | `com.rgp.loadtest.<pkg>`; thư mục body REST `games/<pkg>/bodies/`                         | `fruitrespinmania` (chữ thường, không có ký tự phân cách: identifier của Java không được chứa `-`) |
| **Template**                    | Clone game nào (xem bảng bên dưới)                                                        | silkroad                           |
| **HTTP host / port / contextPath** | REST: `game.yml`. Mọi game: `PORT` và `HEALTH_URL` của wrapper                         | `localhost` / `4000` / `/fruit`    |
| **Health URL**                  | Nhánh `case` của `HEALTH_URL` trong `run-variant.sh`. Phải trả về 2xx và nhẹ              | `http://localhost:4000/fruit/actuator/health` |
| **Endpoint** (REST)             | `utils/Endpoints.java`, `requests/SlotRequests.java`                                      | `spin`, `last-spin`, `history-summary` |
| **Status code của spin** (REST) | `status().is(...)` trong `SlotRequests`                                                   | `200` (silkroad) hoặc `201` (bonanza) |
| **Cấu trúc body** (REST)        | `bodies/*.json`                                                                           | `{userId, gameId, betAmount}`      |
| **Port gRPC, `pluginName`, các field mức cược** (gRPC) | Giá trị mặc định trong simulation Scala; port cũng được publish trong file compose | `9110`, `<plugin-name>` |
| **Tên container**               | `container_name` trong file compose, dòng `GAMES` trong `loadtest.sh`, `--container`      | `game-fruit-respin-mania`          |
| **SLA riêng cho game?**         | `games/<id>/src/gatling/resources/sla-thresholds.yml` (tuỳ chọn)                          | PR-4 ≤ 400 ms                      |

> **Game id và tên package khác nhau là có chủ đích.** Gradle và wrapper chấp nhận kebab-case (`fruit-respin-mania`), nhưng identifier của Java và Scala không được chứa `-`. Dùng id cho thư mục, Gradle project, `--game` và container. Dùng dạng chữ thường cho package và thư mục body REST. Đường dẫn body bên trong `ElFileBody(...)` dùng dạng **package**. Các game hiện có đều dùng một từ viết thường duy nhất, nên tránh được hẳn sự tách đôi này.

#### Chọn template để clone

| Dạng game của bạn                                                         | Clone                                                                   | Ngôn ngữ / runtime                          |
|---------------------------------------------------------------------------|-------------------------------------------------------------------------|---------------------------------------------|
| REST stateless: mỗi request độc lập với nhau                              | `games/silkroad/`                                                       | Java, plugin Gatling 3.15; alias `soak` / `stress` / `spike` / `basic` |
| REST stateful: có pha khởi tạo (tạo session), rồi một vòng lặp dùng state đã lưu | `games/bonanza/`, rồi bỏ nửa gRPC của nó (xem Giai đoạn 1)       | Java, plugin Gatling 3.15; alias `soak` / `basic` |
| REST cộng thêm đường gRPC qua plugin WSProxy                              | `games/bonanza/` giữ nguyên                                             | Java + Scala 2.13.12; plugin 3.15 + 3.9.5 (`gatlingGrpcRt`) |
| Chỉ gRPC, và reply của `Call` mang kết quả (lỗi nghiệp vụ là `c` khác 0)   | `games/mutantmerge/`                                                    | Scala 2.13.12, Gatling 3.9.5 (`gatlingRt`), alias `grpc` |
| Chỉ gRPC, và kết quả được đẩy qua ZMQ (reply của `Call` rỗng hoặc chỉ là ack) | `games/zeroday/` (hoặc `games/naga777/`, có comment bằng tiếng Việt) | Scala 2.13.12, Gatling 3.9.5 (`gatlingRt`), alias `grpc` |

Tìm contract của backend trong source của nó: các HTTP controller với REST, hoặc các command handler và `pluginName` của gRPC plugin. Dùng tài liệu tích hợp FE nếu có.

### Giai đoạn 1 Clone template

```bash
# Đặt một lần; mọi bước bên dưới đều dùng.
NEW=fruit-respin-mania    # game id: thư mục trong games/, Gradle project, --game
PKG=fruitrespinmania      # đoạn tên package
TEMPLATE=silkroad         # silkroad | bonanza | mutantmerge | zeroday | naga777

# 1.1  Sao chép template và bỏ build output của nó
cp -r games/$TEMPLATE games/$NEW
rm -rf games/$NEW/build

# 1.2  Đổi tên mọi thư mục package mang tên template
#      (src/gatling/java, src/gatling/scala, src/gatlingGrpc/scala, cái nào có)
find games/$NEW/src -type d -path "*/com/rgp/loadtest/$TEMPLATE" -prune | while read -r d; do
  mv "$d" "${d%/*}/$PKG"
done

# 1.3  Template REST: đổi tên thư mục template body (Java tham chiếu tới đường dẫn này)
[ -d games/$NEW/src/gatling/resources/games/$TEMPLATE ] && \
  mv games/$NEW/src/gatling/resources/games/$TEMPLATE games/$NEW/src/gatling/resources/games/$PKG

# 1.4  Thay tên template viết thường ở mọi chỗ trong module mới
#      (BSD sed trên macOS; trên Linux dùng `sed -i` không có '' rỗng)
grep -rl "$TEMPLATE" games/$NEW | xargs sed -i '' "s/$TEMPLATE/$PKG/g"

# 1.5  Kiểm tra nhanh: không còn chỗ nào nhắc tới template
grep -rn "$TEMPLATE" games/$NEW || echo "OK clean"
```

**Bước 1.4 đã sửa những gì:**

- Khai báo package, import và đường dẫn `ElFileBody`.
- Tên scenario, ví dụ `silkroad-session-journey` và `zeroday-grpc-player-journey`.
- Các FQCN trong map `SIMULATIONS` của `build.gradle`, và trong task `grpc` viết tay của bonanza.
- Comment.
- Trong file compose được clone, dòng `name:`.
- Mọi chuỗi khác có **chứa** game id của template ở dạng chữ thường. Ví dụ, plugin name `golden-boat-bonanza` của bonanza sẽ thành `golden-boat-<pkg>`. Dù sao Giai đoạn 4 cũng sẽ đặt plugin name thật.

Nó **không** đụng tới những tên viết template theo cách khác: tên class viết hoa xen kẽ như `ZeroDayGrpcSimulation`, tên container như `game-zero-day` hay `game-silk-road-caravans`, hoặc giá trị trong body như `silk_road_usecase`. Các giai đoạn sau sẽ xử lý những chỗ đó.

**Template gRPC: đổi tên class simulation và file của nó.** Các tiền tố cũ là `Bonanza`, `Naga777`, `MutantMerge` và `ZeroDay`:

```bash
OLD_CLASS=ZeroDay; NEW_CLASS=FruitRespinMania
f=$(find games/$NEW/src -name "${OLD_CLASS}GrpcSimulation.scala")
mv "$f" "${f%/*}/${NEW_CLASS}GrpcSimulation.scala"
grep -rl "$OLD_CLASS" games/$NEW | xargs sed -i '' "s/$OLD_CLASS/$NEW_CLASS/g"
```

**REST stateful clone từ bonanza: bỏ nửa gRPC.**

1. Xoá `src/gatlingGrpc/`.
2. Trong `build.gradle`, xoá:
   - `id 'scala'`
   - `gatlingOssVersion` và `gatlingGrpcVersion`
   - configuration `gatlingGrpcRt` cùng các dependency của nó
   - khối `sourceSets { gatlingGrpc { … } }`
   - task `grpc`
   - `grpcHost` và `grpcPort` khỏi `FORWARDED_PROPS`

### Giai đoạn 2 Đăng ký vào Gradle

**2.1 Đăng ký subproject.** `settings.gradle` đã có sẵn dòng mẫu bị comment cho bốn game, trong đó có `// include ':games:fruit-respin-mania'`. Bỏ comment dòng tương ứng, hoặc thêm một dòng cạnh các dòng `include` hiện có:

```groovy
include ':games:zeroday'
include ':games:fruit-respin-mania'          // thêm
```

**2.2 Kiểm tra `SIMULATIONS`** trong `games/$NEW/build.gradle`. Bước 1.4 đã chuyển các FQCN sang package mới. Tên alias quan trọng vì wrapper chạy `:games:<game>:<--simulation lowercased>`: `--simulation Soak` chạy `soak` và `--simulation Grpc` chạy `grpc`.

```groovy
def SIMULATIONS = [
    soak  : 'com.rgp.loadtest.fruitrespinmania.simulations.SoakSimulation',
    stress: 'com.rgp.loadtest.fruitrespinmania.simulations.StressSimulation',
    spike : 'com.rgp.loadtest.fruitrespinmania.simulations.SpikeSimulation',
    basic : 'com.rgp.loadtest.fruitrespinmania.simulations.BasicSimulation',
]
// Template chỉ có gRPC:
// def SIMULATIONS = [ grpc: 'com.rgp.loadtest.fruitrespinmania.grpc.FruitRespinManiaGrpcSimulation' ]
```

**2.3 Kiểm tra `FORWARDED_PROPS`.** Thêm mọi tên `-D` mà simulation của bạn đọc nhưng template chưa forward, ví dụ một flag mức cược mới hoặc một override cho auth. Bỏ những tên riêng của template mà bạn không còn đọc (ví dụ `coinValue`, `betLevelId` hoặc `bet`). `gameName` được inject tự động.

**2.4 Kiểm tra Gradle đã nạp được project:**

```bash
./gradlew :games:$NEW:tasks --group=gatling
```

Group `gatling` phải liệt kê mọi alias trong `SIMULATIONS`. `Project … not found` nghĩa là thiếu bước 2.1. Thiếu alias nào thì xem lại bước 2.2.

### Giai đoạn 3 Cấu hình runtime

**Game REST.** Nếu game chạy trên `localhost:3000` và không có context path, bỏ qua bước này. Nếu không, sửa `games/$NEW/src/gatling/resources/game.yml`:

```yaml
http:
  host: localhost            # chỉ khi không phải localhost
  port: 4000                 # chỉ khi không phải 3000
  contextPath: /fruit        # chỉ khi không rỗng
```

Chỉ liệt kê các field bạn override (xem [Mặc định runtime và override theo game](#mặc-định-runtime-và-override-theo-game)). `-Dhost`, `-Dport` và `-DcontextPath` vẫn thắng lúc chạy. Wrapper luôn truyền `-Dport`, nên `PORT` bạn thêm ở Giai đoạn 8 phải khớp với file này.

**Game chỉ có gRPC.** Simulation Scala không đọc `game.yml`. Giữ file này như một ghi chú chỉ có comment về health URL và port gRPC mặc định, giống naga777, mutantmerge và zeroday. Đặt các giá trị mặc định ngay trong simulation: `Integer.getInteger("grpcPort", <port>)`, `PluginName`, `Zone`, và mọi flag mức cược. Wrapper không truyền được `grpcHost` / `grpcPort`, nên giá trị mặc định trong code phải khớp với port mà file compose của bạn publish.

### Giai đoạn 4 Endpoint và request

**Game REST:**

**4.1** `games/$NEW/src/gatling/java/com/rgp/loadtest/$PKG/utils/Endpoints.java`: các hằng số này đặt tên cho endpoint trong report Gatling và là các giá trị mà `--scenario` chấp nhận.

```java
public static final String SPIN = "spin";                        // POST  /fruit/v1/slot/spin
public static final String LAST_SPIN = "last-spin";              // POST  /fruit/v1/slot/last-spin
public static final String HISTORY_SUMMARY = "history-summary";  // GET   /fruit/v1/history/summary
```

Dùng tên ngắn, nối bằng dấu gạch ngang, vì chúng xuất hiện trong CLI flag và trong menu `loadtest.sh`.

**4.2** `games/$NEW/src/gatling/java/com/rgp/loadtest/$PKG/requests/SlotRequests.java` có một method cho mỗi endpoint. Cập nhật **tất cả** những thứ sau trong từng method:

| Cần sửa                      | Ví dụ                                                                                    |
|------------------------------|------------------------------------------------------------------------------------------|
| HTTP method                  | `.post(...)` hay `.get(...)`                                                             |
| URL path (sau context)       | `.post("/v1/slot/spin")`. Chỉ có `/` ở đầu; host và contextPath lấy từ `game.yml`.       |
| Đường dẫn file body          | `.body(ElFileBody("games/fruitrespinmania/bodies/spin.json"))`. Dùng dạng **package**.   |
| Kiểm tra status của response | `.check(status().is(200))`. Spin của bonanza trả về **201**.                             |
| Lưu state (tuỳ chọn)         | `.check(jsonPath("$.data.sessionId").saveAs("sessionId"))`, chỉ cần khi một bước sau đọc `#{sessionId}` |

**4.3** `games/$NEW/src/gatling/resources/games/$PKG/bodies/*.json`: mỗi endpoint có POST body thì có một file.

```json
{
  "userId": "#{userId}",
  "gameId": "fruit_respin_mania_usecase",
  "betAmount": 1.0
}
```

Có hai quy tắc lặp đi lặp lại:

- **Dùng Gatling EL `#{userId}`, đừng bao giờ dùng `${userId}`.** Dạng sau gửi đi nguyên chuỗi `${userId}`, nên mọi VU gửi cùng một giá trị. Điều đó gây xung đột distributed lock và khoảng 51 % HTTP 400.
- **Khớp chính xác kiểu của từng field theo backend.** Một JSON string và một JSON number tạo ra hai request khác nhau. `/spin` của bonanza nhận `betAmount` là **string**, còn `/jackpot/*` nhận nó là **number**.

**Game chỉ có gRPC.** Sửa `buildJoinRequest` và `buildSpinRequest` trong file Scala:

- các hằng số `PluginName` và `Zone`
- map `user.parameters` (các field định danh mà connect handler của backend cần)
- map `PluginRequest.data` (mã `cmd` và các field mức cược)

Giữ cả hai map dưới dạng `java.util.LinkedHashMap` và encode chúng bằng `codec.encode(...)`. Nếu reply của `Call` mang kết quả, hãy chép phần kiểm tra `spinStatus` của mutantmerge, phần này decode reply và fail khi `c` khác 0. Nếu kết quả đi qua ZMQ, Gatling chỉ thấy lỗi ở tầng transport. Hãy ghi lại một bước kiểm tra log backend, như simulation của zeroday đã làm.

### Giai đoạn 5 Scenario

**Game REST** dùng `scenarios/SessionJourneyScenario.java` (clone từ silkroad) hoặc `scenarios/PlayerJourneyScenario.java` (clone từ bonanza).

**5.1** Đặt tên scenario là duy nhất: `DEFAULT_NAME` (silkroad) hoặc `NAME` (bonanza), theo dạng `<game>-<purpose>`. Bước 1.4 thường đã làm việc này.

```java
public static final String DEFAULT_NAME = "fruitrespinmania-session-journey";
```

Gatling từ chối hai scenario trùng tên trong cùng một `setUp()`. Tên riêng theo game cũng giúp phân biệt report của các game khác nhau.

**5.2** Điều chỉnh cách định tuyến modulo theo tỉ lệ của silkroad: sửa các request phụ và giá trị `oneInN` của chúng. Một VU chạy request phụ khi `userIndex % oneInN == 0`, dùng `userIndex` từ feeder.

**5.3** Điều chỉnh theo mẫu bonanza: sửa chuỗi khởi tạo (`betLevels → reelStrips → createSession`) và trọng số của `randomSwitch`. Bỏ những entry không áp dụng.

**Game chỉ có gRPC:** scenario nằm ngay trong file Scala (`scenario("<pkg>-grpc-player-journey")`). Tên request `"Join"` và `"Spin"` được các assertion `details("Spin")` / `details("Join")` tham chiếu lại. Đổi tên thì đổi cả hai nơi, hoặc đừng đổi.

### Giai đoạn 6 Simulation

**Game REST.** Các class trong `simulations/` chỉ là lớp bọc mỏng quanh các base của `:core`, và bước 1.4 đã làm phần lớn việc sửa. Kiểm tra lại những điểm sau:

| File (clone từ silkroad) | Cần kiểm tra                                                                              |
|--------------------------|-------------------------------------------------------------------------------------------|
| `SoakSimulation.java`    | Compile được; dùng tên scenario từ 5.1.                                                   |
| `StressSimulation.java`  | Compile được.                                                                             |
| `SpikeSimulation.java`   | Có **hai** tên population hardcode, `"<pkg>-spike-baseline"` và `"<pkg>-spike-burst"` (bước 1.4 đổi chúng từ `silkroad-…`). Hai tên này phải luôn khác nhau. |
| `BasicSimulation.java`   | Danh sách `endpoints()` khớp với `Endpoints.java` và `SlotRequests.java`.                 |

Với mẫu bonanza, bạn chỉ cần `SoakSimulation` và `BasicSimulation`. Class Soak của nó **kế thừa trực tiếp `Simulation`** thay vì `SoakSimulationBase`, vì base đó hardcode injection theo open model. Cứ để nguyên như vậy.

**Game chỉ có gRPC:**

- Giữ khối `setUp(...)` từ template: closed injection, `maxDuration` từ `SlaConstants.MAX_DURATION_BUFFER_MIN`, và bảy assertion.
- Đọc mọi thiết lập bằng `Integer.getInteger` / `sys.props`. Đừng import `LoadTestConfig` hay bất cứ thứ gì khác được compile với Gatling 3.15.
- Cập nhật danh sách giá trị mặc định trong comment của class.

### Giai đoạn 7 Override SLA cho game

Giai đoạn này là tuỳ chọn. Nếu game mới có đặc tính hiệu năng khác, chỉ liệt kê các field khác biệt trong `games/$NEW/src/gatling/resources/sla-thresholds.yml`. Field nào bạn bỏ trống sẽ được kế thừa từ `config/sla-thresholds.yml`.

```yaml
sla:
  pr4_mean_response_ms_max: 400   # chặt hơn mặc định 500 ms
  pr5_error_percent_max: 0.5      # chặt hơn mặc định 1.0 %
```

Wrapper truyền `-DgameName=$NEW` cho verifier, nên verdict tự động áp dụng file này. Gatling assertion của chính simulation chỉ có thể nhận file này qua lớp classpath (xem [Ngưỡng SLA và thứ tự nạp](#ngưỡng-sla-và-thứ-tự-nạp)). File chỉ có comment thì không có tác dụng gì.

### Giai đoạn 8 Nối vào script wrapper

`scripts/run-variant.sh` có hai khối `case "$GAME"` theo game. Nếu không có dòng cho game của bạn, wrapper rơi về port 3000 và `/actuator/health`.

**8.1 Port mặc định.** Thêm một dòng vào khối đặt `PORT` khi không truyền `--port`:

```bash
case "$GAME" in
  bonanza)   PORT=3005 ;;
  # … các game hiện có …
  fruit-respin-mania) PORT=4000 ;;       # thêm
  *)        PORT=3000 ;;
esac
```

**8.2 URL của health probe.** Thêm một dòng trả về 2xx khi backend khoẻ:

```bash
case "$GAME" in
  bonanza)   HEALTH_URL="http://localhost:${PORT}/golden/api/configs/bet-levels" ;;
  # … các game hiện có …
  fruit-respin-mania) HEALTH_URL="http://localhost:${PORT}/fruit/actuator/health" ;;   # thêm
  *)        HEALTH_URL="http://localhost:${PORT}/actuator/health" ;;
esac
```

Cùng URL này điều khiển `health.csv`, kiểm tra crash PR-3 và phép thử "đã chạy chưa?" của `ensure-sut.sh`. Hãy chọn thứ gì đó nhẹ (thường dưới 50 ms). `/actuator/health` của Spring Boot là lựa chọn mặc định an toàn khi được expose; các game chỉ có gRPC dùng `/health`, riêng zeroday dùng `/api/game/zeroday/actuator/health`.

**8.3 Tuỳ chọn:** thêm id mới vào danh sách `--game <…>` trong comment hướng dẫn sử dụng và thông báo lỗi của script.

### Giai đoạn 9 Nối vào menu

Thêm một dòng vào mảng `GAMES` ở đầu `loadtest.sh`, theo định dạng `id|display name|container|simulations|Basic scenarios`:

```bash
GAMES=(
  # … các dòng hiện có …
  "fruit-respin-mania|Fruit Respin Mania|game-fruit-respin-mania|Soak Stress Spike Basic|spin last-spin history-summary all chain burst"
)
```

- **Simulations** là danh sách cách nhau bằng dấu cách, lấy từ `Soak Stress Spike Basic Grpc`. Mỗi mục phải ứng với một alias task trong `build.gradle` của game (một entry `SIMULATIONS`, hoặc task `grpc` của bonanza).
- **Basic scenarios** là danh sách cách nhau bằng dấu cách, được đưa ra khi chọn `Basic`. Game chỉ có gRPC để trống mục này (`"…|Grpc|"`).
- **Tên container** quyết định dấu `●` / `○` cho biết đang chạy hay không, và được truyền dưới dạng `--container`.

### Giai đoạn 10 Nối chức năng tự khởi động

Để `ensure-sut.sh` tự khởi động SUT cho bạn, hãy thêm `games/$NEW/docker-compose.loadtest.yml`, dựa theo một file có sẵn (`games/silkroad/` cho REST, `games/zeroday/` hoặc `games/mutantmerge/` cho gRPC). Nếu không có file này, một lần chạy nhắm vào SUT đang dừng sẽ fail với "can't be started automatically". bonanza hiện đang ở tình trạng đó.

Script sao chép file vào thư mục gốc của backend (cạnh `Dockerfile` của nó) và chạy `HTTP_PORT=<wrapper port> docker compose -f docker-compose.loadtest.yml up -d --build`. File cần có các phần sau:

| Thành phần | Lý do |
|------------|-------|
| `name: <game>-loadtest` | Tạo một Compose project riêng, để không bao giờ đụng với compose stack riêng của backend hay của game khác. |
| `build: { context: ., dockerfile: Dockerfile }` | Build từ chính thư mục mà script đã chép file vào. |
| Một `container_name` cố định | Phải trùng với container trong dòng `GAMES`. `ensure-sut.sh`, `loadtest.sh` và `monitor-resources.sh` so khớp theo đúng tên. |
| Port `"${HTTP_PORT:-3000}:<app port>"`, thêm port gRPC với game gRPC | `ensure-sut.sh` luôn đặt `HTTP_PORT` bằng port của wrapper. Mặc định `:-3000` chỉ có tác dụng khi bạn chạy compose bằng tay. |
| Mongo, Redis, … riêng có healthcheck, và `depends_on: condition: service_healthy` | Tự đủ; không dùng hạ tầng chung. |
| Thiết lập mock wallet | Ví dụ `WALLET_GATEWAY: mock` (naga777, mutantmerge) hoặc `LUIGI_WALLET_ENABLED: "false"` (silkroad, zeroday), để không cần wallet hay auth service thật. |
| `mem_limit`, `logging: driver: json-file` | Giới hạn memory sát thực tế, và `docker logs` vẫn dùng được để kiểm tra log sau khi chạy. |

Khung mẫu (thay mọi `<…>` và các biến môi trường bằng giá trị của backend của bạn):

```yaml
# SUT local cho rgp-game-load-test: <Game> + Mongo/Redis riêng của nó.
# Chép vào thư mục gốc repo backend (thư mục có Dockerfile), rồi chạy:
#   docker compose -f docker-compose.loadtest.yml up -d --build
name: fruit-respin-mania-loadtest

services:
  game-fruit-respin-mania:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: game-fruit-respin-mania
    mem_limit: 768m
    environment:
      MONGODB_URI: mongodb://mongo:27017/<db-name>
      REDIS_HOST: redis
      # mock wallet / tắt các tích hợp bên ngoài ở đây
    ports:
      - "${HTTP_PORT:-3000}:3000"     # port trên host : port app listen trong container
      # - "<grpc-port>:<grpc-port>"   # game gRPC
    logging:
      driver: json-file
    depends_on:
      mongo:
        condition: service_healthy
      redis:
        condition: service_healthy

  mongo:
    image: mongo:7-jammy
    healthcheck:
      test: ["CMD", "mongosh", "--quiet", "--eval", "db.adminCommand('ping')"]
      interval: 5s
      retries: 20

  redis:
    image: redis:7-alpine
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 20
```

### Giai đoạn 11 Cập nhật tài liệu

**11.1** Thêm một dòng vào [Các game hiện có](#các-game-hiện-có) với game id, repo backend, container, HTTP port và các simulation được hỗ trợ.

**11.2** Nếu bạn đã thêm file compose, thêm game vào danh sách trong [Tự động khởi động game server](#tự-động-khởi-động-game-server).

**11.3** Cập nhật danh sách game trong [Tham số của wrapper](#tham-số-của-wrapper) và [Các loại simulation](#các-loại-simulation) nếu game mới làm chúng thay đổi (ví dụ thêm một game `Grpc` hoặc các giá trị `--scenario` mới).

### Giai đoạn 12 Smoke test và kiểm tra

Chạy các bước sau theo thứ tự. Nếu một bước fail, quay lại giai đoạn đã tạo ra phần đó.

**12.1 Compile.** Bước này bắt lỗi package, import và FQCN:

```bash
./gradlew :games:$NEW:compileGatlingJava                                   # clone từ silkroad, hoặc clone từ bonanza đã bỏ gRPC
./gradlew :games:$NEW:compileGatlingJava :games:$NEW:compileGatlingGrpcScala  # clone từ bonanza có gRPC
./gradlew :games:$NEW:compileGatlingScala                                  # clone từ game chỉ có gRPC
```

Kết quả mong đợi là `BUILD SUCCESSFUL`. Module chỉ có gRPC sẽ báo `compileGatlingJava NO-SOURCE`, điều đó bình thường.

**12.2 Lần chạy đầu tiên qua Gradle.**

REST: 1 VU × 1 request.

```bash
./gradlew :games:$NEW:basic -Dscenario=spin -Dusers=1 -Drequests=1
```

- `Unknown scenario: '…'. Valid: …` nghĩa là `-Dscenario` không khớp với tên nào trong `Endpoints.java`.
- `simulation class not found` nghĩa là FQCN trong `SIMULATIONS` không khớp với dòng `package`.

Cả hai lỗi đều xuất hiện trước khi có request nào được gửi, nên chúng lộ ra ngay cả khi backend đang tắt.

gRPC (cần backend đang chạy): 1 VU trong 1 phút, tắt các ngưỡng sàn về khối lượng.

```bash
./gradlew :games:$NEW:grpc -Dusers=1 -DdurationMinutes=1 -DrampMinutes=0 \
  -DrequestRate=0 -DeventCount=0
```

**12.3 Smoke test đầu cuối qua wrapper.** Nếu SUT chưa chạy và game có file compose (Giai đoạn 10), wrapper sẽ đề nghị khởi động nó. Nếu không có file compose, hãy tự khởi động SUT trước.

```bash
# REST
./scripts/run-variant.sh --game $NEW \
  --variant target --simulation Basic --scenario spin \
  --users 1 --requests 1 --duration-minutes 1 --ramp-minutes 0 \
  --container game-fruit-respin-mania

# gRPC
./scripts/run-variant.sh --game $NEW \
  --variant target --simulation Grpc \
  --users 10 --duration-minutes 1 --ramp-minutes 0 \
  --container game-fruit-respin-mania
```

Một chữ `PASS` màu xanh trong `target/variants/$NEW/target-<timestamp>/summary.html` nghĩa là mọi mắt xích đã được nối đúng:

- Gradle subproject → class simulation → các request với đúng path và schema
- health probe → resource monitor → verdict

Với smoke test gRPC ngắn, các assertion `requestRate` / `eventCount` của chính Gatling sẽ fail, vì wrapper không hạ được chúng. Wrapper thoát với mã khác 0 và `verdict.json` hiện `gatling_exit_code: 1`, nhưng verdict sáu dòng vẫn có thể là `PASS`.

**12.4 Tăng quy mô.** Khi smoke test đã pass, chạy một lượt Soak (hoặc `Grpc`) 5 phút với số VU vừa phải, rồi đến production gate. Cuối cùng, chạy `./loadtest.sh` một lần để kiểm tra dòng mới xuất hiện và chạy được.

### Các lỗi developer hay gặp

- **Gatling EL là `#{userId}`, không phải `${userId}`** (Java DSL của Gatling 3.7+). Dạng sai gửi đi một chuỗi nguyên văn và gây xung đột lock ở backend, biểu hiện là khoảng 51 % HTTP 400.
- **Spin của bonanza trả về HTTP 201**; của silkroad trả về 200. `status().is(…)` được đặt riêng theo game.
- **Tên field của bonanza không nhất quán là có chủ đích** (chúng khớp với backend). `/sessions` và `/spin` dùng `playerId`; `/jackpot/*` và `/history/*` dùng `userId`. `betAmount` là JSON string với `/spin` và là number với `/jackpot/*`.
- **Tên scenario phải là duy nhất trong một `setUp()`.** Simulation Spike có hai population, nên phải truyền hai tên khác nhau (Giai đoạn 6).
- **Một flag `-D` mới không có tác dụng?** Thêm nó vào `FORWARDED_PROPS` trong `games/<game>/build.gradle` (Giai đoạn 2.3). Với các task `JavaExec` của gRPC, danh sách đó là lối vào duy nhất. `gameName` do các alias task inject.
- **Wrapper luôn forward `-Dport`**, và nó override port trong `game.yml`. Giữ nhánh `case` của `PORT` trong `run-variant.sh` đồng bộ với `game.yml`.
- **Đường dẫn file body REST dùng `$PKG`, không phải `$NEW`**, như trong `ElFileBody("games/<pkg>/bodies/spin.json")`, vì thư mục resource đã được đổi sang dạng package ở Giai đoạn 1.3.
- **Các flag `--add-opens` cho JDK 17 là bắt buộc.** Chúng đã được đặt sẵn trong `gatling.jvmArgs` hoặc `jvmArgs` của `JavaExec` ở mọi module. Đừng bỏ chúng: Gatling dùng reflection vào phần nội bộ của JDK, và bạn sẽ gặp `InaccessibleObjectException` lúc khởi tạo simulation.
- **Đừng bao giờ để Gatling 3.15 lọt vào classpath gRPC.** Giữ `exclude group: 'io.gatling'` / `'io.gatling.highcharts'` trên `project(':core')`, và đừng dùng `LoadTestConfig` (hay bất kỳ class `:core` nào khác xây trên type của Gatling) từ Scala.
- **Gatling assertion và verdict là hai cổng kiểm tra riêng.** Ngưỡng sàn `requestRate` / `eventCount` và các ngưỡng theo từng request (p95 của `Spin` ≤ 800 ms, tỉ lệ fail ≤ 0.5 %) chỉ là Gatling assertion. Khi một assertion fail, Gradle task fail và `./gradlew` thoát với `1`. Giá trị đó trở thành exit code của wrapper và `gatling_exit_code` trong `verdict.json`, nhưng các assertion này không nằm trong sáu dòng của verdict.
- **`health-poll.sh` bỏ cuộc sau 3 lần thất bại liên tiếp**, bất kể `crash.max_consecutive_failures` là bao nhiêu. Vì vậy chuỗi thất bại được ghi lại không bao giờ vượt quá 3, nên nâng giá trị đó lên trên 3 đồng nghĩa với việc PR-3 không bao giờ fail được.
- **File `sla-thresholds.yml` riêng của game mà chỉ có comment sẽ bị bỏ qua**, nên khi đó `sla_config_source` chỉ tới `config/sla-thresholds.yml`. Đó là hành vi mong đợi, không phải bug.
- **JAR GaaS là bytecode Java 21.** `:core:downgradeGaasJar` viết lại major version của class file từ trên 61 xuống 61 (Java 17). Cách này chỉ hoạt động khi thư viện không dùng API nào của Java 18+. Nếu bạn thay `core/libs/common-data-1.0.0.jar`, hãy giữ nguyên tên file, hoặc cập nhật task và `core/libs/README.md`.
- **Payload MessagePack nhạy với thứ tự.** Dựng chúng bằng `LinkedHashMap` và đừng đổi thứ tự key.

## Tài liệu tham khảo thêm

- [`HUONG-DAN.md`](HUONG-DAN.md): hướng dẫn từng bước bằng tiếng Việt cho người mới. Tài liệu này có trước `loadtest.sh` và các game chỉ có gRPC, nên chỉ nói về silkroad và bonanza. Nó có link tới `docs/getting-started.md`, file này không có trong repo.
- [`naga777-load-test-guide.md`](naga777-load-test-guide.md): cách chạy naga777 và đọc kết quả, bao gồm cả `generate-final-report.py`.
- [`core/libs/README.md`](core/libs/README.md): JAR GaaS đóng gói sẵn lấy từ đâu và vì sao phải hạ phiên bản.
- Gatling: [Cú pháp EL](https://docs.gatling.io/reference/script/core/session/el/) · [Gradle plugin](https://docs.gatling.io/reference/integrations/build-tools/gradle-plugin/) · [gRPC DSL](https://docs.gatling.io/reference/script/protocols/grpc/). Link cuối mô tả DSL chính chủ bị khoá sau Enterprise, thứ mà repo này **không** dùng cho các simulation gRPC.
- [`phisgr/gatling-grpc`](https://github.com/phisgr/gatling-grpc): plugin gRPC cộng đồng (0.17.0, Gatling 3.9.5) đứng sau cả bốn simulation `Grpc`. Plugin này đã bị archive ở upstream.
