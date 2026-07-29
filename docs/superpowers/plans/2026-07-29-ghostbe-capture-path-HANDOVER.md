# Handover Prompt — GhostBe Capture Path (Phases 0–1)

Paste everything below the line into a fresh agent session started in
`/home/portonics/development/project/ghost-be`.

---

## Your assignment

Implement Phases 0–1 of the GhostBe capture path, following the existing plan task-by-task.

**Read these two documents first, in this order:**

1. `docs/superpowers/specs/2026-07-29-ghostbe-programmable-interception-design.md` — the approved
   design. Explains *why* each decision was made. Read §4 ("no silent misses") and §6 closely;
   they are the reasoning you will need when a detail in the plan looks arbitrary.
2. `docs/superpowers/plans/2026-07-29-ghostbe-capture-path.md` — the plan you are executing.
   10 tasks, each with real code and a TDD cycle. Do not improvise an alternative structure.

**Invoke `superpowers:subagent-driven-development`** (preferred) or `superpowers:executing-plans`
and follow it. Work through tasks in order — later tasks consume interfaces earlier ones produce.

## What this project is

GhostBe is a MITM proxy for testing Android apps. An Android VPN app forwards raw IP packets to a
Go backend, which terminates TLS, inspects and modifies HTTP traffic, then forwards to the real
origin. The eventual product is **fixtures-as-code**: JSON files plus Starlark scripts, authored
by AI agents, that modify requests and responses so developers and QA can automate testing.

You are building the layer beneath that: a capture path that is correct and, crucially, **honest
about its failures**. Phase 2 (the fixture engine) is designed but not yet planned; do not build
it.

## The one principle that explains most of the plan

**No silent misses.** For a testing tool, the most expensive failure is ambiguity between "my
fixture didn't match" and "the traffic was never captured." Both look like "it didn't work," and
an agent will then try to fix a fixture that was never broken.

So: attribution is left empty rather than guessed, QUIC is actively rejected rather than dropped,
TLS rejections become logged flows carrying a diagnosis, and a config that fails to parse never
replaces a working one. If you find yourself writing a code path that drops something quietly,
you have misread the plan.

## Verified facts — do not re-derive these, and do not "fix" them

These were established by building and running a prototype against the pinned gVisor version.
Several are counter-intuitive and the plan depends on them:

1. **gVisor is pinned to `v0.0.0-20260701204157-69c2d17aea96`.** `@latest` **does not compile** —
   there is a stray `package bridge` test file inside `pkg/tcpip/stack`. If you ever see that
   error, you have drifted off the pin. Never run `go get gvisor.dev/gvisor@latest`.
2. **`go.mod` must say `go 1.26.3` or higher.** gVisor requires it; the toolchain auto-switches.
   Fails if `GOTOOLCHAIN=local`.
3. **`ForwarderRequest.Complete(false)` does NOT send a SYN-ACK.** `CreateEndpoint` performs the
   3-way handshake and is what emits it. Calling only `Complete` leaves the connection hanging
   with no error — a silent failure that costs an hour to find.
4. **The TCP forwarder handler already runs in its own goroutine** (gVisor does
   `go f.handler(...)` internally), so blocking in `CreateEndpoint` is correct and safe.
5. **The UDP forwarder handler is called SYNCHRONOUSLY on the packet path.** It must never block.
   Relay work goes in a goroutine. This is the opposite of the TCP case — do not unify them.
6. **Returning `false` from the UDP forwarder makes the stack emit ICMP port unreachable.** That
   is the entire QUIC-rejection mechanism; do not hand-build ICMP packets.
7. **`SetPromiscuousMode` and `SetSpoofing` are load-bearing.** Without them the stack silently
   drops packets addressed to arbitrary origin IPs and cannot reply as those IPs. The symptom is
   a forwarder that never fires. Same symptom if the route table is empty.
8. **The dual-stack test rig works** — two cross-wired `channel.Endpoint`s give tests a real TCP
   client (real handshake, retransmission, windowing) with no device, kernel, or root. A full
   round-trip runs in ~3 ms. Task 3 contains the verified code.

## Existing bugs you are replacing — context, not tasks

Do not spend time fixing these in place; Task 9 deletes the file. They explain why the rewrite
exists:

- `internal/tun/handler.go:262` and the same line in `sendACK`/`sendRST`/`sendFIN`/`sendFINACK`
  compute the TCP checksum over `buf[ipHdrLen:tcpHdrLen]` — both constants are 20, so it is a
  **zero-length slice**. Every control packet has an invalid checksum.
- `internal/proxy/proxy.go:93` reads an `X-GhostBe-App` header that nothing ever sets, so
  `rules.Engine.Match` could never match. App attribution has never worked.
- `handleHTTP` reads one 65535-byte buffer then sends FIN: keep-alive broken, responses over
  64 KB truncated.
- The stack has no retransmission, no receive-window accounting, no out-of-order reassembly, and
  no connection eviction.

## Ground rules

- **TDD, strictly.** Write the failing test, run it and confirm it fails for the stated reason,
  implement minimally, confirm it passes, commit. The plan spells out the expected failure for
  each test — if you see a *different* failure, stop and understand why before implementing.
- **Commit after every task** using the message in the plan.
- **The full suite must run with `go test ./...`** and require no device, emulator, network, or
  root. If a test you write needs any of those, it is the wrong test.
- **Never guess app attribution.** Return `""`. There is one exception, and it is not a guess:
  when exactly one app is selected, `addAllowedApplication` guarantees no other app's traffic is
  in the tunnel.
- **Do not add dependencies** beyond gVisor. Android stays on AndroidX core + appcompat, Java,
  XML layouts — no Kotlin, no Compose.
- **Build with `-ldflags="-s -w"`** (the Makefile from Task 1 does this).

## Point of no return

**Tasks 1–8 are additive** — the existing `internal/tun` path keeps working the whole time.

**Task 9 deletes `internal/tun/handler.go` and `internal/rules/rules.go`, and drops the `rules`
SQLite table.** The database is empty (verified: zero rules, zero traffic logs), so no data is
lost. But this is the commit that removes the old architecture. Make sure Tasks 3–8 are green
before you take it, and confirm with the user if anything is uncertain.

## Definition of done

- [ ] `cd backend && go build ./... && go vet ./...` clean
- [ ] `cd backend && go test ./...` passes across `flow`, `netstack`, `device`, `proxy`, `api`
- [ ] `grep -rn "X-GhostBe-App\|internal/tun\|internal/rules" --include=*.go backend/` prints
      nothing
- [ ] `cd backend && make build && ls -l ghostbe` shows under 9 MB (was 14.07 MB committed)
- [ ] `cd android && ./gradlew :app:assembleDebug` succeeds (**needs Java 17** — Java 21 has a
      jlink incompatibility with compileSdk 33)
- [ ] Manual smoke test in Task 10 Step 5 shows non-zero `capture.tcp_flows` in `/api/status` and
      traffic logged with the correct `app_package`

## Known gap, deliberately deferred

The optional standalone HTTP proxy listener on `:8877` for the CI path
(`emulator -http-proxy 10.0.2.2:8877`) is in the design (§6.4) but has **no task in this plan**.
Phases 0–1 are complete and testable without it. It is recorded as the first task of the future
Phase 3 plan. Do not add it here.

## If the plan is wrong

The plan was written against a verified prototype, but it has not been executed end to end. If a
step does not work:

1. Re-read the relevant design section — the intent is usually there.
2. Check it against the eight verified facts above.
3. If the plan is genuinely wrong, **say so explicitly**, explain what you found, propose the
   correction, and get agreement before diverging. Do not silently improvise a different design.

Report honestly: if a test fails, show the output. If you skip something, say so.
