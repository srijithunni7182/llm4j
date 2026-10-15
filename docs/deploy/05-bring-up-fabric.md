# D5. Bring up a Fabric server

**Goal:** one server that many processes report to, with an admin who can sign in, started and checked.

Fabric needs only **Java 17+** and its own jar: no Loom runtime, no model keys, no scripts. It keeps its history in a data directory, which is its whole state.

## The first start, on this computer

Ask the user for an admin password, or have one made. **It never goes on a command line.**

```
# the user sets it in their own shell (12 or more characters), then you run:
scripts/fabric-up.sh --data ./fabric-data --port 9090
# or, to have a strong one written to a private file (the script prints only the path):
scripts/fabric-up.sh --data ./fabric-data --port 9090 --generate-password
```

(Windows: `scripts\fabric-up.ps1 -Data .\fabric-data -Port 9090 [-GeneratePassword]`.) The script initialises the data directory the first time (the vault, the admin password hash, `master.key`), starts the server in the background, waits for it to answer, and says where it is and where the password is. Running it again changes nothing. Then:

```
scripts/fabric-status.sh --data ./fabric-data --port 9090
```

Open `http://127.0.0.1:9090`, sign in with the admin password. The **Processes** page is empty until a process reports (chapter 6).

`master.key` sits next to the vault for development. **In production move it** (a secrets manager, a root-only file) and give it back at run time with `LOOM_FABRIC_MASTER_KEY` or `--master-key-file`: a backup that holds both holds the keys.

## Beyond this computer: TLS first, and ask the user

Fabric refuses to listen on a non-loopback address without TLS or a trusted proxy in front, and so do the scripts. The choices:

| Choice | How | Processes need |
|---|---|---|
| A reverse proxy terminates TLS (nginx, Caddy, a cloud load balancer) | `fabric-up.sh --port 9090 -- --trust-proxy` on loopback; the proxy forwards `https://fabric.example.com` to it; keep its request timeout above 30 s (commands use a 25 s long-poll) | nothing extra |
| Fabric terminates TLS with a public certificate | `fabric-up.sh --bind 0.0.0.0 -- --tls-cert chain.pem --tls-key key-pkcs8.pem` | nothing extra |
| Fabric terminates TLS with a private CA | `java -jar fabric.jar tls init --host fabric.example.com --out ./tls`, then `fabric-up.sh --bind 0.0.0.0 -- --tls-keystore ./tls/server.p12 --tls-password-file ./tls/server.password` | `--observe-ca ca.pem` |
| One self-signed server | as above; `serve` prints a key pin | `--observe-pin sha256:...` |

Host names are always verified; a process accepts plain `http` only for a loopback address. **Ask the user which choice and for which host before you run any of this**, and ask who will reach the page.

## As a service

For anything permanent use a service manager: `src/loom/loom-fabric/fabric.service` in the repository is a systemd unit (loopback plus proxy, hardening flags included); set `LOOM_FABRIC_MASTER_KEY` through its `EnvironmentFile=` (mode 600). The scripts here are for development and for trying it; they are not a service manager.

## Stop it

`scripts/fabric-down.sh --data ./fabric-data` (what it holds stays on disk).

## Gate

`fabric-status` says running; the user signed in; they know where the data directory is, where the admin password is kept (not in the chat), and that `master.key` must move before production.
