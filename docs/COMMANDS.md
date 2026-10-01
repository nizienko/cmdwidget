# Useful commands for Cmd Widget

Copy a command into Settings / Preferences → Tools → Cmd Widget and use **Test
command** to check it on the selected host. Outputs below are illustrative;
actual values depend on your project, machine, and CLI configuration.

Choose BACKEND for information about the project or remote development host, and
FRONTEND for information about your local machine. Leave Working directory empty
to use the selected host's default directory, or set it explicitly. Git commands
require a working directory inside a repository. Every CLI must be installed and
available to the selected host's login shell.

## Git

These commands use the repository in the widget's working directory. A refresh
interval of 5–10 seconds is a useful starting point.

| Command | Description | Example output |
| --- | --- | --- |
| `git branch --show-current` | Current branch; output is empty when HEAD is detached. | `feature/widgets` |
| `git rev-parse --short HEAD` | Short commit ID. | `a1b2c3d` |
| `git log -1 --format=%s` | Latest commit subject. | `Add disk usage widget` |
| `git describe --tags --always` | Most recent reachable tag plus commit distance and ID, or a commit ID when no tag is available. | `v1.2.0-3-ga1b2c3d` |
| `git rev-list --left-right --count 'HEAD...@{upstream}'` | Commits ahead of and behind the configured upstream, in that order. Requires an upstream branch; uses local refs without fetching. | `2 1` |
| `if test -z "$(git status --porcelain)"; then printf 'clean\n'; else printf 'modified\n'; fi` | Whether the working tree has changes, including untracked files. Use inside a valid repository. | `modified` |
| `git diff --shortstat` | Summary of unstaged changes to tracked files; empty when there are none. | `2 files changed, 12 insertions(+), 3 deletions(-)` |
| `git diff --cached --shortstat` | Summary of staged changes; empty when there are none. | `1 file changed, 5 insertions(+)` |
| `git stash list \| awk 'END {print NR " stashes"}'` | Number of saved stashes. | `2 stashes` |
| `git log -1 --format='%cr'` | Time elapsed since the latest commit's committer date. | `2 hours ago` |
| `git log -1 --format='%an'` | Author of the latest commit. | `Alex Smith` |
| `git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}'` | Upstream branch name. Fails when the current branch has no upstream. | `origin/main` |

## Disk and system — macOS and Linux

These commands use standard system utilities. Try a 10–30 second interval; scanning
a large directory with `du` can take longer than the plugin's ten-second timeout.

| Command | Description | Example output |
| --- | --- | --- |
| `df -h / \| awk 'NR==2 {print $4 " free"}'` | Available space on the root filesystem, in human-readable units. Replace `/` with another filesystem path if needed. | `120G free` |
| `df -Pk / \| awk 'NR==2 {print $(NF-1)}'` | Root filesystem usage. A percentage from 0 to 100 activates the widget's progress bar. | `42%` |
| `du -sh . \| awk '{print $1}'` | Total size of the working directory, including hidden files. | `256M` |
| `hostname -s` | Short hostname of the selected execution host. | `devbox` |
| `date '+%H:%M'` | Current time in the selected host's timezone. | `14:35` |
| `date -u '+%H:%M UTC'` | Current UTC time. | `12:35 UTC` |
| `uname -m` | Host architecture. | `arm64` |
| `uname -sr` | Operating system kernel name and release. | `Linux 6.8.0-60-generic` |
| `ps -A -o pid= \| awk 'END {print NR " processes"}'` | Number of processes visible to the selected host's user. | `248 processes` |
| `df -Pi / \| awk 'NR==2 {print $(NF-1) " inodes"}'` | Inode usage on the root filesystem. Useful when many small files consume filesystem capacity. | `12% inodes` |

## macOS only

Use FRONTEND for a local Mac when developing on a remote Linux backend. A
30–60 second interval is generally enough for battery information.

| Command | Description | Example output |
| --- | --- | --- |
| `pmset -g batt \| awk 'NR==2 {sub(/;.*/, "", $3); print $3}'` | Battery charge as a percentage, suitable for a progress bar. Output is empty on a Mac without a battery. | `87%` |
| `sw_vers -productVersion` | macOS version on the selected host. | `15.6` |
| `sysctl -n hw.logicalcpu` | Number of logical CPUs. | `10` |
| `sysctl -n hw.memsize \| awk '{printf "%.0f GiB RAM\n", $1/1073741824}'` | Total physical memory. | `32 GiB RAM` |
| `sysctl -n vm.loadavg \| awk '{print $2 " " $3 " " $4}'` | System load averages over 1, 5, and 15 minutes. | `1.24 1.10 0.98` |

## Linux only

These commands read Linux system information. Try a 10–30 second interval.

| Command | Description | Example output |
| --- | --- | --- |
| `awk '{print $1 " " $2 " " $3}' /proc/loadavg` | System load averages over 1, 5, and 15 minutes. These are load values, not CPU percentages. | `0.42 0.35 0.28` |
| `awk '/MemTotal:/ {total=$2} /MemAvailable:/ {available=$2} END {if (total>0) printf "%.0f%%\n", (total-available)/total*100}' /proc/meminfo` | Memory usage based on total minus available memory, suitable for a progress bar. | `63%` |
| `awk '{printf "%.1f h uptime\n", $1/3600}' /proc/uptime` | Time since boot, in hours. | `36.5 h uptime` |
| `awk '/MemAvailable:/ {printf "%.1f GiB available\n", $2/1048576}' /proc/meminfo` | Memory available to applications without swapping. | `8.4 GiB available` |
| `awk '/SwapTotal:/ {total=$2} /SwapFree:/ {free=$2} END {printf "%.0f MiB swap used\n", (total-free)/1024}' /proc/meminfo` | Used swap space; zero when no swap is configured or used. | `128 MiB swap used` |
| `getconf _NPROCESSORS_ONLN` | Number of online logical CPUs reported by the system. | `8` |

## Docker

Requires the Docker CLI. Container queries also require a reachable Docker daemon
for the active context. Try a 15–30 second interval.

| Command | Description | Example output |
| --- | --- | --- |
| `docker context show` | Active Docker context. | `colima` |
| `docker ps --format '{{.Names}}' \| awk 'END {print NR " running"}'` | Number of running containers in the active context. | `3 running` |
| `docker inspect --format '{{.State.Status}}' my-container` | State of a specific container. Replace `my-container` with its name or ID. | `running` |
| `docker ps -a --filter status=exited --format '{{.ID}}' \| awk 'END {print NR " exited"}'` | Number of stopped containers whose state is exited. | `2 exited` |
| `docker inspect --format '{{.RestartCount}} restarts' my-container` | Restart count for a specific container. | `3 restarts` |
| `docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no healthcheck{{end}}' my-container` | Container health, or an explicit label when no health check is configured. | `healthy` |
| `docker stats --no-stream --format '{{.CPUPerc}}' my-container` | One sample of container CPU usage. Docker can report values above 100% on multiple CPUs; those remain text. | `12.50%` |
| `docker stats --no-stream --format '{{.MemUsage}}' my-container` | One sample of container memory usage and its limit. | `256MiB / 2GiB` |

## Kubernetes

Requires `kubectl` and a configured kubeconfig on the selected host. These commands
read local configuration without contacting the cluster. Try a 30-second interval.

| Command | Description | Example output |
| --- | --- | --- |
| `kubectl config current-context` | Active Kubernetes context. | `dev-cluster` |
| `kubectl config view --minify -o 'jsonpath={.contexts[0].context.namespace}' \| awk '{print; found=1} END {if (!found) print "default"}'` | Namespace of the active context, or `default` when none is set. Requires a valid current context. | `staging` |

The following commands contact the cluster in the current context. Replace `my-app`
and `my-pod` with your resource names; add `-n staging` to select a namespace.
Each request uses a five-second timeout, within the plugin's ten-second limit.
Try a 30–60 second refresh interval.

| Command | Description | Example output |
| --- | --- | --- |
| `kubectl --request-timeout=5s get pods --field-selector=status.phase=Running -o name \| awk 'END {print NR " running pods"}'` | Number of pods in the Running phase in the current namespace. Running does not necessarily mean ready. | `4 running pods` |
| `kubectl --request-timeout=5s get pods --field-selector=status.phase=Pending -o name \| awk 'END {print NR " pending pods"}'` | Number of pods waiting in the Pending phase in the current namespace. | `1 pending pods` |
| `kubectl --request-timeout=5s get deployment my-app -o 'jsonpath={.status.availableReplicas}'` | Available replicas of a deployment; may be empty when the status field is absent. | `3` |
| `kubectl --request-timeout=5s get pod my-pod -o 'jsonpath={.status.phase}'` | Phase of a specific pod. | `Running` |
| `kubectl --request-timeout=5s get pod my-pod -o 'jsonpath={.status.containerStatuses[*].restartCount}'` | Restart counts of the pod's regular containers, in status-list order. | `0 2` |

## Network and HTTP services

Requires `curl`. Replace the example URL with your own service or health endpoint.
These commands send a GET request on each refresh; choose an endpoint intended for
repeated reads. A 30–60 second interval is a useful starting point. The five-second
request limit leaves time for shell startup.

| Command | Description | Example output |
| --- | --- | --- |
| `curl -sS -o /dev/null --connect-timeout 2 --max-time 5 -w 'HTTP %{http_code}' http://localhost:8080/health` | HTTP status of a local service. HTTP errors such as 503 are shown as text; connection failures exit nonzero. | `HTTP 200` |
| `curl -sS -o /dev/null --connect-timeout 2 --max-time 5 -w '%{time_total}s' http://localhost:8080/health` | Total duration of the HTTP request, in seconds. | `0.024531s` |
| `curl -fsS --connect-timeout 2 --max-time 5 http://localhost:8080/health` | Health endpoint body. Best for endpoints returning a short plain-text value; HTTP 400 and above fail the command. | `UP` |

`localhost` refers to the selected execution host. In remote development, choose
BACKEND to query a service running on the backend machine.

## Development environment

Each command requires the corresponding runtime or package manager on the selected
host. Version widgets usually need only a 60–300 second interval. They help reveal
which tools the widget's login shell actually finds.

| Command | Description | Example output |
| --- | --- | --- |
| `node --version` | Node.js version. | `v22.14.0` |
| `npm --version` | npm version. | `10.9.2` |
| `python3 --version` | Python version. | `Python 3.12.9` |
| `java -version 2>&1 \| awk 'NR==1 {print}'` | First line of the Java version report, which Java normally writes to stderr. | `openjdk version "21.0.6" 2025-01-21 LTS` |
| `go version` | Go version and target platform. | `go version go1.24.1 darwin/arm64` |
| `rustc --version` | Rust compiler version. | `rustc 1.85.0 (4d91de4e4 2025-02-17)` |
| `printf '%s\n' "${VIRTUAL_ENV##*/}"` | Active Python virtual environment directory name; empty when the variable is unset. Requires `VIRTUAL_ENV` in the widget's shell environment. | `.venv` |

## Tips

- Keep output short: the status bar collapses whitespace and limits text to 80
  Unicode characters. Percentages such as `42%` automatically display a progress
  bar; add a label such as `disk 42%` if you prefer text.
- Use finite, non-interactive commands. Watch modes, streaming logs, password
  prompts, and commands that wait for input do not suit automatic refresh.
- Commands run once per open project. Use longer intervals for expensive queries.
- Pipelines can hide an earlier command's failure because the shell normally
  reports the exit status of the last command. If a count or fallback looks wrong,
  test the underlying CLI command by itself and inspect stderr. For strict error
  handling, use `set -o pipefail;` only with a shell that supports it.
- For paths containing spaces, quote them inside the command, for example
  `du -sh '/path/to/my project'`. The separate Working directory field takes a
  literal path without quotes or expansion of `~` and shell variables.

See [Execution and refresh](../README.md#execution-and-refresh) for host selection,
shell behavior, and execution limits.
