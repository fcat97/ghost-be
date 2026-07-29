# GhostBe Capture Path (Phases 0–1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace GhostBe's hand-written TCP/IP stack with gVisor netstack, fix app attribution end-to-end, and make every capture failure visible — so that fixtures written in Phase 2 can be trusted.

**Architecture:** The Android app stays a dumb TUN forwarder. On the Go side, a framed packet stream from the device is injected into a gVisor `channel.Endpoint`. gVisor's TCP forwarder yields one `net.Conn` per flow, handed directly to the MITM code in-process — removing today's loopback `CONNECT` round-trip to `127.0.0.1:8877`. A `flow.Flow` value is the seam between capture, interception and storage, which lets the entire pipeline be tested with two cross-wired gVisor stacks and no device.

**Tech Stack:** Go 1.26.3+, gvisor.dev/gvisor (pinned), go-chi/chi v5, gorilla/websocket, mattn/go-sqlite3, Java 17 / Android compileSdk 33.

## Global Constraints

- **gVisor is pinned to `v0.0.0-20260701204157-69c2d17aea96`.** Never use `@latest` — it does not compile (a stray `package bridge` test file inside `pkg/tcpip/stack`).
- **`go.mod` must declare `go 1.26.3` or higher.** gVisor requires it; the Go toolchain auto-switches. Requires `GOTOOLCHAIN` not set to `local`.
- **All binaries build with `-ldflags="-s -w"`.** This recovers 5.24 MB and is what makes gVisor's +2.62 MB a net reduction.
- **Never guess app attribution.** If the owning package cannot be resolved, record `App: ""`. A wrong attribution is worse than an empty one.
- **Never fail silently.** Every dropped or rejected flow produces a logged `flow.Flow` carrying a diagnosis in `Error`.
- **A parse or validation failure never replaces working state.** Applies to config loading throughout.
- **Body cap is 1 MB** for logging and modification; larger bodies stream through untouched and set `BodyTruncated`.
- **Android stays dependency-minimal:** AndroidX core + appcompat only, Java, XML layouts, no Kotlin, no Compose.
- **The gVisor TCP forwarder handler already runs in its own goroutine** (`go f.handler(...)`), so blocking there is safe. **The UDP forwarder handler is called synchronously** and must never block.

---

## File Structure

**Created:**

| File | Responsibility |
|---|---|
| `backend/internal/flow/flow.go` | `Flow`, `Request`, `Response`, `Action`, `Meta` — the seam every other package is defined against |
| `backend/internal/flow/body.go` | Body reading with the 1 MB cap |
| `backend/internal/netstack/stack.go` | gVisor stack construction, NIC-per-device, teardown |
| `backend/internal/netstack/tcp.go` | TCP forwarder → `net.Conn` → `Handler` |
| `backend/internal/netstack/udp.go` | DNS relay, QUIC rejection, stats |
| `backend/internal/netstack/testrig.go` | Cross-wired dual-stack rig (test helper, non-`_test` so other packages can use it) |
| `backend/internal/device/frame.go` | Framed wire protocol: `[len][type][payload]` |
| `backend/internal/device/conn.go` | Handshake, control-message request/response, app resolution |
| `backend/Makefile` | Build with `-s -w` |
| `android/app/src/debug/res/xml/network_security_config.xml` | Debug-only user-CA trust |

**Modified:**

| File | Change |
|---|---|
| `backend/go.mod` | Go 1.26.3, add gVisor pinned |
| `backend/internal/proxy/proxy.go` | New `Handle(net.Conn, flow.Meta)` entry point; drop `X-GhostBe-App`; drop rules engine wiring |
| `backend/internal/db/db.go` | Drop `rules` table and rule CRUD; traffic logs only |
| `backend/internal/api/api.go` | Remove rules endpoints; add netstack stats to `/api/status` |
| `backend/cmd/ghostbe/main.go` | Wire device server + netstack; optional standalone proxy listener |
| `android/app/src/main/java/com/ghostbe/GhostVpnService.java` | Protocol v2, handshake, control channel, UID resolution |
| `android/app/build.gradle.kts` | Point debug variant at the network security config |
| `android/app/src/main/AndroidManifest.xml` | `networkSecurityConfig` attribute |

**Deleted:** `backend/internal/tun/handler.go`, `backend/internal/rules/rules.go`

---

## Task 1: Build hygiene and dependency pinning

**Files:**
- Modify: `backend/go.mod`
- Create: `backend/Makefile`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: nothing
- Produces: a buildable module with gVisor available at the pinned version

- [ ] **Step 1: Stop tracking the committed binary**

The 14 MB `backend/ghostbe` binary is checked in and currently shows as modified.

```bash
cd /home/portonics/development/project/ghost-be
git rm --cached backend/ghostbe
printf '\n# build output\nbackend/ghostbe\n' >> .gitignore
```

- [ ] **Step 2: Bump Go and add gVisor**

```bash
cd backend
go mod edit -go=1.26.3
go mod edit -require=gvisor.dev/gvisor@v0.0.0-20260701204157-69c2d17aea96
go mod tidy
```

- [ ] **Step 3: Verify the pin resolves and builds**

Run: `cd backend && go build ./... && go list -m gvisor.dev/gvisor`
Expected: builds clean; prints `gvisor.dev/gvisor v0.0.0-20260701204157-69c2d17aea96`

If it prints a different version, something re-resolved it — re-run Step 2. Do not accept `@latest`.

- [ ] **Step 4: Add the Makefile**

```makefile
BIN := ghostbe
LDFLAGS := -s -w

.PHONY: build test clean
build:
	go build -ldflags="$(LDFLAGS)" -o $(BIN) ./cmd/ghostbe/

test:
	go test ./...

clean:
	rm -f $(BIN)
```

- [ ] **Step 5: Verify the size claim**

Run: `cd backend && make build && ls -l ghostbe`
Expected: a binary under 9 MB. Before this change an unstripped build was 14.07 MB.

- [ ] **Step 6: Commit**

```bash
git add .gitignore backend/go.mod backend/go.sum backend/Makefile
git commit -m "build: pin gvisor, strip binaries, untrack build output"
```

---

## Task 2: The `flow` package — the seam

**Files:**
- Create: `backend/internal/flow/flow.go`
- Create: `backend/internal/flow/body.go`
- Test: `backend/internal/flow/body_test.go`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `flow.Flow` struct with fields `ID, App string`, `Request *Request`, `Response *Response`, `Matched string`, `Action Action`, `Error string`, `Latency time.Duration`, `StartedAt time.Time`
  - `flow.Meta` struct with fields `Src, Dst netip.AddrPort`, `App string`
  - `flow.Request` with `Method, URL string`, `Headers http.Header`, `Body []byte`, `BodyTruncated bool`
  - `flow.Response` with `Status int`, `Headers http.Header`, `Body []byte`, `BodyTruncated bool`
  - `flow.Action` string enum: `ActionPassthrough, ActionMocked, ActionModified, ActionFaulted, ActionFailed`
  - `flow.MaxBodyBytes` constant = `1 << 20`
  - `flow.ReadBody(r io.Reader) (body []byte, truncated bool, err error)`
  - `flow.New(meta Meta) *Flow`

- [ ] **Step 1: Write the failing test**

`backend/internal/flow/body_test.go`:

```go
package flow

import (
	"bytes"
	"strings"
	"testing"
)

func TestReadBodySmallBodyNotTruncated(t *testing.T) {
	body, truncated, err := ReadBody(strings.NewReader("hello"))
	if err != nil {
		t.Fatalf("ReadBody: %v", err)
	}
	if truncated {
		t.Error("truncated = true, want false")
	}
	if string(body) != "hello" {
		t.Errorf("body = %q, want %q", body, "hello")
	}
}

func TestReadBodyOversizeIsTruncatedAtCap(t *testing.T) {
	src := bytes.Repeat([]byte("x"), MaxBodyBytes+5000)
	body, truncated, err := ReadBody(bytes.NewReader(src))
	if err != nil {
		t.Fatalf("ReadBody: %v", err)
	}
	if !truncated {
		t.Error("truncated = false, want true")
	}
	if len(body) != MaxBodyBytes {
		t.Errorf("len(body) = %d, want %d", len(body), MaxBodyBytes)
	}
}

func TestReadBodyExactlyAtCapIsNotTruncated(t *testing.T) {
	src := bytes.Repeat([]byte("y"), MaxBodyBytes)
	body, truncated, err := ReadBody(bytes.NewReader(src))
	if err != nil {
		t.Fatalf("ReadBody: %v", err)
	}
	if truncated {
		t.Error("truncated = true, want false for exactly-at-cap body")
	}
	if len(body) != MaxBodyBytes {
		t.Errorf("len(body) = %d, want %d", len(body), MaxBodyBytes)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/flow/ -v`
Expected: FAIL — `undefined: ReadBody`, `undefined: MaxBodyBytes`

- [ ] **Step 3: Write `flow.go`**

```go
// Package flow defines the unit of interception that every other package is
// written against: capture produces Flows, interception transforms them,
// storage logs them, the CLI queries them.
package flow

import (
	"net/http"
	"net/netip"
	"time"
)

// Action records what GhostBe did to a flow.
type Action string

const (
	ActionPassthrough Action = "passthrough"
	ActionMocked      Action = "mocked"
	ActionModified    Action = "modified"
	ActionFaulted     Action = "faulted"
	ActionFailed      Action = "failed"
)

// Meta is the connection-level information known before any bytes are parsed.
type Meta struct {
	Src netip.AddrPort
	Dst netip.AddrPort
	// App is the owning package name, or "" when it could not be resolved.
	// It is never guessed: an empty value is correct and visible, a wrong
	// value costs an afternoon of debugging.
	App string
}

type Request struct {
	Method        string
	URL           string
	Headers       http.Header
	Body          []byte
	BodyTruncated bool
}

type Response struct {
	Status        int
	Headers       http.Header
	Body          []byte
	BodyTruncated bool
}

// Flow is one intercepted request/response exchange.
type Flow struct {
	ID        string
	App       string
	StartedAt time.Time
	Meta      Meta
	Request   *Request
	Response  *Response
	// Matched is the name of the fixture that fired, or "" if none did.
	// Distinguishable from a flow that was never captured at all.
	Matched string
	Action  Action
	// Error carries a human-readable diagnosis for failed flows, e.g. a
	// rejected certificate or an unreachable origin.
	Error   string
	Latency time.Duration
}

func New(meta Meta) *Flow {
	return &Flow{
		ID:        newID(),
		App:       meta.App,
		StartedAt: time.Now(),
		Meta:      meta,
		Action:    ActionPassthrough,
	}
}
```

- [ ] **Step 4: Write `body.go`**

```go
package flow

import (
	"crypto/rand"
	"encoding/hex"
	"io"
)

// MaxBodyBytes caps how much of a body GhostBe buffers for logging and
// modification. Larger bodies stream through untouched with BodyTruncated set,
// so a large download cannot exhaust memory.
const MaxBodyBytes = 1 << 20

// ReadBody reads up to MaxBodyBytes from r. truncated reports whether there
// was more data than the cap allowed.
func ReadBody(r io.Reader) (body []byte, truncated bool, err error) {
	// Read one byte past the cap so we can tell "exactly at cap" from "over".
	buf, err := io.ReadAll(io.LimitReader(r, MaxBodyBytes+1))
	if err != nil {
		return nil, false, err
	}
	if len(buf) > MaxBodyBytes {
		return buf[:MaxBodyBytes], true, nil
	}
	return buf, false, nil
}

func newID() string {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "flow_unknown"
	}
	return "flow_" + hex.EncodeToString(b[:])
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && go test ./internal/flow/ -v`
Expected: PASS — all three tests

- [ ] **Step 6: Commit**

```bash
git add backend/internal/flow/
git commit -m "feat: add flow package as the interception seam"
```

---

## Task 3: gVisor stack with TCP forwarding

This is the core replacement. The test rig built here is what every later task tests against.

**Files:**
- Create: `backend/internal/netstack/stack.go`
- Create: `backend/internal/netstack/tcp.go`
- Create: `backend/internal/netstack/testrig.go`
- Test: `backend/internal/netstack/tcp_test.go`

**Interfaces:**
- Consumes: `flow.Meta` from Task 2
- Produces:
  - `netstack.Handler` interface: `HandleTCP(conn net.Conn, meta flow.Meta)`
  - `netstack.AppResolver` interface: `ResolveApp(src, dst netip.AddrPort) string`
  - `netstack.Stack` with `New(h Handler) (*Stack, error)`, `AttachDevice(r AppResolver) (*Device, error)`, `Close()`
  - `netstack.Device` with `Inject(pkt []byte)`, `ReadOutbound(ctx context.Context) []byte`, `Detach()`
  - `netstack.NewTestRig(t *testing.T, h Handler) *TestRig` with field `Client *stack.Stack` and method `Dial(ctx, addrPort string) (net.Conn, error)`

- [ ] **Step 1: Write the failing test**

`backend/internal/netstack/tcp_test.go`:

```go
package netstack

import (
	"context"
	"io"
	"net"
	"testing"
	"time"

	"github.com/ghostbe/backend/internal/flow"
)

// echoHandler stands in for the MITM proxy: it records the flow metadata and
// echoes bytes back, which proves the TCP path is bidirectional.
type echoHandler struct {
	metas chan flow.Meta
}

func (h *echoHandler) HandleTCP(conn net.Conn, meta flow.Meta) {
	h.metas <- meta
	defer conn.Close()
	io.Copy(conn, conn)
}

func TestTCPForwarderDeliversFlowWithOriginalDestination(t *testing.T) {
	h := &echoHandler{metas: make(chan flow.Meta, 1)}
	rig := NewTestRig(t, h)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	conn, err := rig.Dial(ctx, "93.184.216.34:443")
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()

	select {
	case meta := <-h.metas:
		if got := meta.Dst.String(); got != "93.184.216.34:443" {
			t.Errorf("meta.Dst = %s, want 93.184.216.34:443", got)
		}
		if meta.App != "com.example.app" {
			t.Errorf("meta.App = %q, want com.example.app", meta.App)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("handler never fired")
	}

	want := "GET / HTTP/1.1\r\n\r\n"
	if _, err := conn.Write([]byte(want)); err != nil {
		t.Fatalf("write: %v", err)
	}
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	got := make([]byte, len(want))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(got) != want {
		t.Errorf("echo = %q, want %q", got, want)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/netstack/ -v`
Expected: FAIL — `undefined: NewTestRig`

- [ ] **Step 3: Write `stack.go`**

Note the promiscuous-mode and spoofing calls: without them the stack drops packets addressed to arbitrary origin IPs and cannot reply *as* those IPs, which is the whole job here.

```go
// Package netstack turns a stream of raw IP packets from an Android device
// into per-flow net.Conn values, using gVisor's userspace TCP/IP stack.
package netstack

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	gvstack "gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"

	"github.com/ghostbe/backend/internal/flow"
)

// Handler receives one call per intercepted connection.
type Handler interface {
	HandleTCP(conn net.Conn, meta flow.Meta)
}

// AppResolver maps a connection 4-tuple to the owning Android package name.
// Implementations return "" when the owner cannot be determined.
type AppResolver interface {
	ResolveApp(src, dst netip.AddrPort) string
}

// Stats are exposed through /api/status so that missed traffic is visible
// rather than silent.
type Stats struct {
	QUICRejected uint64 `json:"quic_rejected"`
	DNSForwarded uint64 `json:"dns_forwarded"`
	TCPFlows     uint64 `json:"tcp_flows"`
}

type Stack struct {
	s       *gvstack.Stack
	handler Handler

	mu      sync.Mutex
	nextNIC tcpip.NICID
	devices map[tcpip.NICID]*Device

	quicRejected atomic.Uint64
	dnsForwarded atomic.Uint64
	tcpFlows     atomic.Uint64
}

const (
	// maxInFlight bounds concurrent pending TCP handshakes.
	maxInFlight = 2048
	mtu         = 1500
	queueDepth  = 512
)

func New(h Handler) (*Stack, error) {
	s := gvstack.New(gvstack.Options{
		NetworkProtocols: []gvstack.NetworkProtocolFactory{
			ipv4.NewProtocol, ipv6.NewProtocol,
		},
		TransportProtocols: []gvstack.TransportProtocolFactory{
			tcp.NewProtocol, udp.NewProtocol,
		},
	})

	ns := &Stack{
		s:       s,
		handler: h,
		nextNIC: 1,
		devices: make(map[tcpip.NICID]*Device),
	}

	ns.registerTCP()
	ns.registerUDP()
	return ns, nil
}

func (ns *Stack) Stats() Stats {
	return Stats{
		QUICRejected: ns.quicRejected.Load(),
		DNSForwarded: ns.dnsForwarded.Load(),
		TCPFlows:     ns.tcpFlows.Load(),
	}
}

func (ns *Stack) Close() {
	ns.mu.Lock()
	devs := make([]*Device, 0, len(ns.devices))
	for _, d := range ns.devices {
		devs = append(devs, d)
	}
	ns.mu.Unlock()
	for _, d := range devs {
		d.Detach()
	}
	ns.s.Close()
}

// Device is one attached Android device: its own NIC and packet queue.
type Device struct {
	ns       *Stack
	nicID    tcpip.NICID
	ep       *channel.Endpoint
	resolver AppResolver
	cancel   context.CancelFunc
	ctx      context.Context
}

// AttachDevice allocates a NIC for a newly connected device. Each device gets
// its own NIC so two QA devices can be attached without their flows colliding.
func (ns *Stack) AttachDevice(r AppResolver) (*Device, error) {
	ns.mu.Lock()
	nicID := ns.nextNIC
	ns.nextNIC++
	ns.mu.Unlock()

	ep := channel.New(queueDepth, mtu, "")
	if err := ns.s.CreateNIC(nicID, ep); err != nil {
		return nil, fmt.Errorf("create nic %d: %v", nicID, err)
	}
	// Accept packets addressed to any origin IP, and reply as that IP.
	ns.s.SetPromiscuousMode(nicID, true)
	ns.s.SetSpoofing(nicID, true)

	ns.mu.Lock()
	routes := ns.s.GetRouteTable()
	routes = append(routes,
		tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: nicID},
		tcpip.Route{Destination: header.IPv6EmptySubnet, NIC: nicID},
	)
	ns.s.SetRouteTable(routes)
	ns.mu.Unlock()

	ctx, cancel := context.WithCancel(context.Background())
	d := &Device{ns: ns, nicID: nicID, ep: ep, resolver: r, ctx: ctx, cancel: cancel}

	ns.mu.Lock()
	ns.devices[nicID] = d
	ns.mu.Unlock()
	return d, nil
}

// Inject hands a raw IP packet from the device to the stack.
func (d *Device) Inject(pkt []byte) {
	if len(pkt) == 0 {
		return
	}
	proto := ipv4.ProtocolNumber
	if pkt[0]>>4 == 6 {
		proto = ipv6.ProtocolNumber
	}
	pb := gvstack.NewPacketBuffer(gvstack.PacketBufferOptions{
		Payload: buffer.MakeWithData(pkt),
	})
	d.ep.InjectInbound(proto, pb)
	pb.DecRef()
}

// ReadOutbound blocks until the stack emits a packet for this device, or ctx
// is done, in which case it returns nil.
func (d *Device) ReadOutbound(ctx context.Context) []byte {
	pb := d.ep.ReadContext(ctx)
	if pb == nil {
		return nil
	}
	defer pb.DecRef()
	return append([]byte(nil), pb.ToView().AsSlice()...)
}

func (d *Device) Detach() {
	d.cancel()
	d.ns.mu.Lock()
	delete(d.ns.devices, d.nicID)
	d.ns.mu.Unlock()
	d.ns.s.RemoveNIC(d.nicID)
}

func (d *Device) resolveApp(src, dst netip.AddrPort) string {
	if d.resolver == nil {
		return ""
	}
	return d.resolver.ResolveApp(src, dst)
}

// deviceForNIC finds the device a flow arrived on.
func (ns *Stack) deviceForNIC(id tcpip.NICID) *Device {
	ns.mu.Lock()
	defer ns.mu.Unlock()
	return ns.devices[id]
}
```

- [ ] **Step 4: Write `tcp.go`**

`CreateEndpoint` performs the 3-way handshake and blocks. That is safe here because gVisor's forwarder already invokes this handler with `go f.handler(...)`.

```go
package netstack

import (
	"net/netip"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	gvstack "gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/waiter"

	"github.com/ghostbe/backend/internal/flow"
)

func (ns *Stack) registerTCP() {
	fwd := tcp.NewForwarder(ns.s, 0 /* default rcvWnd */, maxInFlight, func(r *tcp.ForwarderRequest) {
		id := r.ID()

		var wq waiter.Queue
		ep, tcpErr := r.CreateEndpoint(&wq) // performs the handshake
		if tcpErr != nil {
			r.Complete(true) // send RST
			return
		}
		r.Complete(false)

		ns.tcpFlows.Add(1)
		conn := gonet.NewTCPConn(&wq, ep)
		meta := ns.metaFor(ep, id)
		ns.handler.HandleTCP(conn, meta)
	})
	ns.s.SetTransportProtocolHandler(tcp.ProtocolNumber, fwd.HandlePacket)
}

// metaFor builds flow metadata. In gVisor's TransportEndpointID the "Local"
// side is the destination the app dialed, and "Remote" is the app itself.
func (ns *Stack) metaFor(ep tcpip.Endpoint, id gvstack.TransportEndpointID) flow.Meta {
	src := addrPort(id.RemoteAddress, id.RemotePort)
	dst := addrPort(id.LocalAddress, id.LocalPort)

	app := ""
	if nicID, ok := nicOf(ep); ok {
		if d := ns.deviceForNIC(nicID); d != nil {
			app = d.resolveApp(src, dst)
		}
	}
	return flow.Meta{Src: src, Dst: dst, App: app}
}

func addrPort(a tcpip.Address, port uint16) netip.AddrPort {
	addr, _ := netip.AddrFromSlice(a.AsSlice())
	return netip.AddrPortFrom(addr.Unmap(), port)
}

// nicOf recovers which NIC an endpoint is bound to, so a flow can be attributed
// to the device it arrived on.
func nicOf(ep tcpip.Endpoint) (tcpip.NICID, bool) {
	local, err := ep.GetLocalAddress()
	if err != nil {
		return 0, false
	}
	return local.NIC, local.NIC != 0
}
```

- [ ] **Step 5: Write `testrig.go`**

Two gVisor stacks with their `channel.Endpoint`s cross-wired give tests a real TCP client — real handshake, real retransmission, real windowing — with no device, kernel, or root.

```go
package netstack

import (
	"context"
	"net"
	"testing"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	gvstack "gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// fixedResolver reports the same package for every flow, standing in for the
// Android control channel.
type fixedResolver struct{ pkg string }

func (f fixedResolver) ResolveApp(_, _ netip.AddrPort) string { return f.pkg }

// TestRig is a GhostBe stack plus a synthetic client stack wired to it.
type TestRig struct {
	Under  *Stack
	Client *gvstack.Stack
	Device *Device
}

const testClientNIC tcpip.NICID = 1

// NewTestRig builds a GhostBe stack with h attached, plus a client stack whose
// traffic is delivered to it. Cleanup is registered with t.
func NewTestRig(t *testing.T, h Handler) *TestRig {
	t.Helper()

	under, err := New(h)
	if err != nil {
		t.Fatalf("netstack.New: %v", err)
	}
	t.Cleanup(under.Close)

	dev, err := under.AttachDevice(fixedResolver{pkg: "com.example.app"})
	if err != nil {
		t.Fatalf("AttachDevice: %v", err)
	}

	client := gvstack.New(gvstack.Options{
		NetworkProtocols: []gvstack.NetworkProtocolFactory{
			ipv4.NewProtocol, ipv6.NewProtocol,
		},
		TransportProtocols: []gvstack.TransportProtocolFactory{
			tcp.NewProtocol, udp.NewProtocol,
		},
	})
	t.Cleanup(client.Close)

	clientEP := channel.New(queueDepth, mtu, "")
	if err := client.CreateNIC(testClientNIC, clientEP); err != nil {
		t.Fatalf("client CreateNIC: %v", err)
	}
	clientAddr := tcpip.AddrFrom4([4]byte{10, 0, 0, 2})
	pa := tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: clientAddr.WithPrefix(),
	}
	if err := client.AddProtocolAddress(testClientNIC, pa, gvstack.AddressProperties{}); err != nil {
		t.Fatalf("client AddProtocolAddress: %v", err)
	}
	client.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: testClientNIC},
		{Destination: header.IPv6EmptySubnet, NIC: testClientNIC},
	})

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)

	// client -> GhostBe
	go func() {
		for {
			pkt := clientEP.ReadContext(ctx)
			if pkt == nil {
				return
			}
			b := append([]byte(nil), pkt.ToView().AsSlice()...)
			pkt.DecRef()
			dev.Inject(b)
		}
	}()
	// GhostBe -> client
	go func() {
		for {
			b := dev.ReadOutbound(ctx)
			if b == nil {
				return
			}
			proto := ipv4.ProtocolNumber
			if len(b) > 0 && b[0]>>4 == 6 {
				proto = ipv6.ProtocolNumber
			}
			pb := gvstack.NewPacketBuffer(gvstack.PacketBufferOptions{
				Payload: buffer.MakeWithData(b),
			})
			clientEP.InjectInbound(proto, pb)
			pb.DecRef()
		}
	}()

	return &TestRig{Under: under, Client: client, Device: dev}
}

// Dial opens a TCP connection from the synthetic client to any address. The
// GhostBe stack accepts it promiscuously, exactly as it would a real app's.
func (r *TestRig) Dial(ctx context.Context, addrPort string) (net.Conn, error) {
	ap, err := netip.ParseAddrPort(addrPort)
	if err != nil {
		return nil, err
	}
	full := tcpip.FullAddress{
		NIC:  testClientNIC,
		Addr: tcpip.AddrFromSlice(ap.Addr().AsSlice()),
		Port: ap.Port(),
	}
	proto := ipv4.ProtocolNumber
	if ap.Addr().Is6() {
		proto = ipv6.ProtocolNumber
	}
	return gonet.DialContextTCP(ctx, r.Client, full, proto)
}
```

Add `"net/netip"` to this file's imports alongside the others.

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && go test ./internal/netstack/ -run TestTCPForwarder -v`
Expected: PASS, in well under a second — the whole exchange is in-process.

If it hangs, the most likely cause is a missing `SetPromiscuousMode`/`SetSpoofing` or an empty route table: the SYN is silently dropped and the forwarder never fires.

- [ ] **Step 7: Commit**

```bash
git add backend/internal/netstack/
git commit -m "feat: add gvisor-backed netstack with TCP forwarding and test rig"
```

---

## Task 4: UDP — DNS relay and QUIC rejection

**Files:**
- Create: `backend/internal/netstack/udp.go`
- Test: `backend/internal/netstack/udp_test.go`

**Interfaces:**
- Consumes: `Stack` from Task 3
- Produces: `Stack.registerUDP()`, and `Stats.QUICRejected` / `Stats.DNSForwarded` incrementing

- [ ] **Step 1: Write the failing test**

`backend/internal/netstack/udp_test.go`:

```go
package netstack

import (
	"context"
	"net"
	"testing"
	"time"

	"github.com/ghostbe/backend/internal/flow"
)

type nopHandler struct{}

func (nopHandler) HandleTCP(conn net.Conn, _ flow.Meta) { conn.Close() }

func TestUDPToQUICPortIsRejectedAndCounted(t *testing.T) {
	rig := NewTestRig(t, nopHandler{})

	// A UDP datagram to port 443 must be refused so the app falls back to TCP.
	sendUDP(t, rig, "93.184.216.34:443", []byte("quic-ish"))

	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if rig.Under.Stats().QUICRejected > 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("QUICRejected = 0, want > 0")
}

func TestDNSQueryIsForwardedToUpstream(t *testing.T) {
	// A local UDP server stands in for the upstream resolver.
	upstream, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatalf("listen upstream: %v", err)
	}
	defer upstream.Close()

	got := make(chan []byte, 1)
	go func() {
		buf := make([]byte, 512)
		n, addr, err := upstream.ReadFrom(buf)
		if err != nil {
			return
		}
		got <- append([]byte(nil), buf[:n]...)
		upstream.WriteTo([]byte("response"), addr)
	}()

	rig := NewTestRig(t, nopHandler{})
	rig.Under.SetDNSUpstream(upstream.LocalAddr().String())

	sendUDP(t, rig, "8.8.8.8:53", []byte("query"))

	select {
	case q := <-got:
		if string(q) != "query" {
			t.Errorf("upstream got %q, want %q", q, "query")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("DNS query never reached upstream")
	}
}

// sendUDP writes one datagram from the rig's synthetic client.
func sendUDP(t *testing.T, rig *TestRig, addrPort string, payload []byte) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	conn, err := rig.DialUDP(ctx, addrPort)
	if err != nil {
		t.Fatalf("dial udp: %v", err)
	}
	defer conn.Close()
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("write udp: %v", err)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/netstack/ -run 'TestUDP|TestDNS' -v`
Expected: FAIL — `rig.Under.SetDNSUpstream undefined`, `rig.DialUDP undefined`

- [ ] **Step 3: Add `DialUDP` to the test rig**

Append to `backend/internal/netstack/testrig.go`:

```go
// DialUDP opens a UDP "connection" from the synthetic client.
func (r *TestRig) DialUDP(ctx context.Context, addrPort string) (net.Conn, error) {
	ap, err := netip.ParseAddrPort(addrPort)
	if err != nil {
		return nil, err
	}
	full := tcpip.FullAddress{
		NIC:  testClientNIC,
		Addr: tcpip.AddrFromSlice(ap.Addr().AsSlice()),
		Port: ap.Port(),
	}
	proto := ipv4.ProtocolNumber
	if ap.Addr().Is6() {
		proto = ipv6.ProtocolNumber
	}
	return gonet.DialUDP(r.Client, nil, &full, proto)
}
```

- [ ] **Step 4: Write `udp.go`**

The handler must return quickly: gVisor calls the UDP forwarder handler **synchronously** on the packet path, unlike the TCP one. Returning `false` makes the stack emit ICMP port unreachable, which is what pushes a QUIC-capable app back onto interceptable TCP.

```go
package netstack

import (
	"io"
	"net"
	"sync"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	quicPort = 443
	dnsPort  = 53
)

var defaultDNSUpstream = "8.8.8.8:53"

type dnsConfig struct {
	mu       sync.RWMutex
	upstream string
}

// SetDNSUpstream overrides where DNS queries are relayed. Used by tests and by
// operators who want a specific resolver.
func (ns *Stack) SetDNSUpstream(addr string) {
	ns.dns.mu.Lock()
	defer ns.dns.mu.Unlock()
	ns.dns.upstream = addr
}

func (ns *Stack) dnsUpstream() string {
	ns.dns.mu.RLock()
	defer ns.dns.mu.RUnlock()
	if ns.dns.upstream == "" {
		return defaultDNSUpstream
	}
	return ns.dns.upstream
}

func (ns *Stack) registerUDP() {
	fwd := udp.NewForwarder(ns.s, func(r *udp.ForwarderRequest) bool {
		id := r.ID()

		// QUIC: refuse so the app retries over TCP, where we can see it.
		// Returning false makes the stack send ICMP port unreachable.
		if id.LocalPort == quicPort {
			ns.quicRejected.Add(1)
			return false
		}

		if id.LocalPort != dnsPort {
			// Anything else is not interceptable and not silently swallowed:
			// refusing it surfaces as a connection error in the app.
			return false
		}

		var wq waiter.Queue
		ep, tcpErr := r.CreateEndpoint(&wq)
		if tcpErr != nil {
			return true
		}
		ns.dnsForwarded.Add(1)
		conn := gonet.NewUDPConn(&wq, ep)
		// Relay off the packet path: this handler is called synchronously.
		go ns.relayDNS(conn)
		return true
	})
	ns.s.SetTransportProtocolHandler(udp.ProtocolNumber, fwd.HandlePacket)
}

// relayDNS forwards one query to the upstream resolver and returns the answer.
func (ns *Stack) relayDNS(conn net.Conn) {
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 1500)
	n, err := conn.Read(buf)
	if err != nil || n == 0 {
		return
	}

	up, err := net.DialTimeout("udp", ns.dnsUpstream(), 3*time.Second)
	if err != nil {
		return
	}
	defer up.Close()
	up.SetDeadline(time.Now().Add(5 * time.Second))

	if _, err := up.Write(buf[:n]); err != nil {
		return
	}
	resp := make([]byte, 1500)
	rn, err := up.Read(resp)
	if err != nil && rn == 0 {
		return
	}
	if rn > 0 {
		if _, err := conn.Write(resp[:rn]); err != nil && err != io.EOF {
			return
		}
	}
}
```

- [ ] **Step 5: Add the `dns` field to `Stack`**

In `backend/internal/netstack/stack.go`, add to the `Stack` struct:

```go
	dns dnsConfig
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && go test ./internal/netstack/ -v`
Expected: PASS — all four tests, including the two from Task 3

- [ ] **Step 7: Commit**

```bash
git add backend/internal/netstack/
git commit -m "feat: forward DNS and reject QUIC so fallback traffic stays visible"
```

---

## Task 5: Device wire protocol v2

**Files:**
- Create: `backend/internal/device/frame.go`
- Test: `backend/internal/device/frame_test.go`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `device.FrameType` byte enum: `TypePacket = 0x00`, `TypeControl = 0x01`
  - `device.Frame` struct with `Type FrameType`, `Payload []byte`
  - `device.ProtocolVersion` constant = `2`
  - `device.WriteFrame(w io.Writer, f Frame) error`
  - `device.ReadFrame(r io.Reader) (Frame, error)`
  - `device.ErrFrameTooLarge`, `device.MaxFrameSize` = `65535`

- [ ] **Step 1: Write the failing test**

`backend/internal/device/frame_test.go`:

```go
package device

import (
	"bytes"
	"errors"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	var buf bytes.Buffer
	want := Frame{Type: TypePacket, Payload: []byte{0x45, 0x00, 0x00, 0x28}}
	if err := WriteFrame(&buf, want); err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}
	got, err := ReadFrame(&buf)
	if err != nil {
		t.Fatalf("ReadFrame: %v", err)
	}
	if got.Type != want.Type {
		t.Errorf("Type = %d, want %d", got.Type, want.Type)
	}
	if !bytes.Equal(got.Payload, want.Payload) {
		t.Errorf("Payload = %v, want %v", got.Payload, want.Payload)
	}
}

func TestFrameTypeIsPreservedForControl(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteFrame(&buf, Frame{Type: TypeControl, Payload: []byte(`{"v":2}`)}); err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}
	got, err := ReadFrame(&buf)
	if err != nil {
		t.Fatalf("ReadFrame: %v", err)
	}
	if got.Type != TypeControl {
		t.Errorf("Type = %d, want TypeControl", got.Type)
	}
}

func TestOversizeFrameIsRejectedNotTruncated(t *testing.T) {
	var buf bytes.Buffer
	// length = MaxFrameSize+1, declared but not written
	buf.Write([]byte{0x00, 0x01, 0x00, 0x00})
	_, err := ReadFrame(&buf)
	if !errors.Is(err, ErrFrameTooLarge) {
		t.Errorf("err = %v, want ErrFrameTooLarge", err)
	}
}

func TestEmptyFrameIsRejected(t *testing.T) {
	var buf bytes.Buffer
	buf.Write([]byte{0x00, 0x00, 0x00, 0x00}) // length 0: no room for a type byte
	if _, err := ReadFrame(&buf); err == nil {
		t.Error("err = nil, want an error for a zero-length frame")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/device/ -v`
Expected: FAIL — `undefined: WriteFrame`

- [ ] **Step 3: Write `frame.go`**

```go
// Package device implements the wire protocol between the Android VPN app and
// the backend.
//
// Wire format:
//
//	[4-byte big-endian length][1-byte type][payload...]
//
// length counts the type byte plus the payload. The version handshake happens
// before any packets flow, so a stale APK fails loudly instead of silently
// corrupting the stream — which is what the unversioned v1 protocol did.
package device

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

type FrameType byte

const (
	TypePacket  FrameType = 0x00
	TypeControl FrameType = 0x01
)

// ProtocolVersion must match the constant in GhostVpnService.java.
const ProtocolVersion = 2

const MaxFrameSize = 65535

var ErrFrameTooLarge = errors.New("device: frame exceeds maximum size")

type Frame struct {
	Type    FrameType
	Payload []byte
}

func WriteFrame(w io.Writer, f Frame) error {
	n := 1 + len(f.Payload)
	if n > MaxFrameSize {
		return fmt.Errorf("%w: %d bytes", ErrFrameTooLarge, n)
	}
	buf := make([]byte, 4+n)
	binary.BigEndian.PutUint32(buf[0:4], uint32(n))
	buf[4] = byte(f.Type)
	copy(buf[5:], f.Payload)
	_, err := w.Write(buf)
	return err
}

func ReadFrame(r io.Reader) (Frame, error) {
	var hdr [4]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return Frame{}, err
	}
	n := binary.BigEndian.Uint32(hdr[:])
	if n > MaxFrameSize {
		return Frame{}, fmt.Errorf("%w: %d bytes", ErrFrameTooLarge, n)
	}
	if n == 0 {
		return Frame{}, errors.New("device: zero-length frame")
	}
	body := make([]byte, n)
	if _, err := io.ReadFull(r, body); err != nil {
		return Frame{}, err
	}
	return Frame{Type: FrameType(body[0]), Payload: body[1:]}, nil
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && go test ./internal/device/ -v`
Expected: PASS — all four tests

- [ ] **Step 5: Commit**

```bash
git add backend/internal/device/
git commit -m "feat: add versioned device wire protocol with control frames"
```

---

## Task 6: Device session — handshake, app resolution, packet pump

**Files:**
- Create: `backend/internal/device/conn.go`
- Test: `backend/internal/device/conn_test.go`

**Interfaces:**
- Consumes: `Frame`/`ReadFrame`/`WriteFrame` (Task 5), `netstack.Device` (Task 3)
- Produces:
  - `device.Hello` struct: `Version int` (json `version`), `Apps []string` (json `apps`)
  - `device.AppQuery` struct: `ID int` (json `id`), `Src string` (json `src`), `Dst string` (json `dst`)
  - `device.AppReply` struct: `ID int` (json `id`), `Package string` (json `package`)
  - `device.Session` with `Serve(ctx context.Context) error`, `ResolveApp(src, dst netip.AddrPort) string`
  - `device.NewSession(conn net.Conn, attach AttachFunc) *Session`
  - `device.AttachFunc` = `func(r netstack.AppResolver) (PacketPipe, error)`
  - `device.PacketPipe` interface: `Inject(pkt []byte)`, `ReadOutbound(ctx context.Context) []byte`, `Detach()`
  - `device.ErrVersionMismatch`

`netstack.Device` from Task 3 already satisfies `PacketPipe`.

- [ ] **Step 1: Write the failing test**

`backend/internal/device/conn_test.go`:

```go
package device

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"testing"
	"time"
)

// fakePipe records injected packets and lets a test push outbound ones.
type fakePipe struct {
	injected chan []byte
	outbound chan []byte
}

func newFakePipe() *fakePipe {
	return &fakePipe{
		injected: make(chan []byte, 8),
		outbound: make(chan []byte, 8),
	}
}

func (p *fakePipe) Inject(pkt []byte) { p.injected <- append([]byte(nil), pkt...) }
func (p *fakePipe) ReadOutbound(ctx context.Context) []byte {
	select {
	case b := <-p.outbound:
		return b
	case <-ctx.Done():
		return nil
	}
}
func (p *fakePipe) Detach() {}

func startSession(t *testing.T, pipe *fakePipe) (client net.Conn, errCh chan error) {
	t.Helper()
	serverSide, clientSide := net.Pipe()
	sess := NewSession(serverSide, func(r netstack.AppResolver) (PacketPipe, error) {
		return pipe, nil
	})
	errCh = make(chan error, 1)
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go func() { errCh <- sess.Serve(ctx) }()
	t.Cleanup(func() { clientSide.Close() })
	return clientSide, errCh
}

func TestSessionRejectsWrongProtocolVersion(t *testing.T) {
	client, errCh := startSession(t, newFakePipe())

	hello, _ := json.Marshal(Hello{Version: 1, Apps: []string{"com.example.app"}})
	if err := WriteFrame(client, Frame{Type: TypeControl, Payload: hello}); err != nil {
		t.Fatalf("WriteFrame: %v", err)
	}

	select {
	case err := <-errCh:
		if !errors.Is(err, ErrVersionMismatch) {
			t.Errorf("err = %v, want ErrVersionMismatch", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("session accepted a v1 handshake; it must fail loudly")
	}
}

func TestSessionForwardsPacketsToPipe(t *testing.T) {
	pipe := newFakePipe()
	client, _ := startSession(t, pipe)

	hello, _ := json.Marshal(Hello{Version: ProtocolVersion, Apps: []string{"com.example.app"}})
	if err := WriteFrame(client, Frame{Type: TypeControl, Payload: hello}); err != nil {
		t.Fatalf("hello: %v", err)
	}

	want := []byte{0x45, 0x00, 0x00, 0x14}
	if err := WriteFrame(client, Frame{Type: TypePacket, Payload: want}); err != nil {
		t.Fatalf("packet: %v", err)
	}

	select {
	case got := <-pipe.injected:
		if string(got) != string(want) {
			t.Errorf("injected = %v, want %v", got, want)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("packet never reached the pipe")
	}
}

func TestResolveAppReturnsEmptyOnTimeoutRatherThanGuessing(t *testing.T) {
	pipe := newFakePipe()
	client, _ := startSession(t, pipe)

	hello, _ := json.Marshal(Hello{Version: ProtocolVersion, Apps: []string{"com.example.app"}})
	WriteFrame(client, Frame{Type: TypeControl, Payload: hello})

	// Drain the query the session sends, but never answer it.
	go func() {
		for {
			if _, err := ReadFrame(client); err != nil {
				return
			}
		}
	}()

	src := netip.MustParseAddrPort("10.0.0.2:54321")
	dst := netip.MustParseAddrPort("93.184.216.34:443")

	start := time.Now()
	got := sessionUnderTest.ResolveApp(src, dst)
	if got != "" {
		t.Errorf("ResolveApp = %q, want \"\" when the device does not answer", got)
	}
	if elapsed := time.Since(start); elapsed > 5*time.Second {
		t.Errorf("ResolveApp blocked for %v, want a bounded timeout", elapsed)
	}
}
```

Note: `startSession` must also return the `*Session` so the third test can call
`ResolveApp`. Change its signature to
`func startSession(t *testing.T, pipe *fakePipe) (net.Conn, *Session, chan error)`
and update the two earlier tests to ignore the extra value with `_`. Replace
`sessionUnderTest` with that returned session.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/device/ -v`
Expected: FAIL — `undefined: NewSession`

- [ ] **Step 3: Write `conn.go`**

```go
package device

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/ghostbe/backend/internal/netstack"
)

var ErrVersionMismatch = errors.New("device: protocol version mismatch")

// resolveTimeout bounds how long a flow waits for the device to name the
// owning app. On expiry the flow is attributed to "" rather than guessed.
const resolveTimeout = 2 * time.Second

// Hello is the first control frame a device sends.
type Hello struct {
	Version int      `json:"version"`
	Apps    []string `json:"apps"`
}

// AppQuery asks the device which package owns a connection.
type AppQuery struct {
	ID  int    `json:"id"`
	Src string `json:"src"`
	Dst string `json:"dst"`
}

// AppReply answers an AppQuery. Package is "" when the device cannot tell.
type AppReply struct {
	ID      int    `json:"id"`
	Package string `json:"package"`
}

// PacketPipe is the netstack side of a device session.
type PacketPipe interface {
	Inject(pkt []byte)
	ReadOutbound(ctx context.Context) []byte
	Detach()
}

// AttachFunc allocates a NIC for this device once its handshake succeeds.
type AttachFunc func(r netstack.AppResolver) (PacketPipe, error)

type Session struct {
	conn   net.Conn
	attach AttachFunc

	writeMu sync.Mutex

	mu      sync.Mutex
	nextID  int
	pending map[int]chan string
	apps    []string
}

func NewSession(conn net.Conn, attach AttachFunc) *Session {
	return &Session{
		conn:    conn,
		attach:  attach,
		pending: make(map[int]chan string),
	}
}

// Serve runs the session until the device disconnects or ctx is cancelled.
func (s *Session) Serve(ctx context.Context) error {
	defer s.conn.Close()

	hello, err := s.readHello()
	if err != nil {
		return err
	}
	s.mu.Lock()
	s.apps = hello.Apps
	s.mu.Unlock()

	pipe, err := s.attach(s)
	if err != nil {
		return fmt.Errorf("attach device: %w", err)
	}
	defer pipe.Detach()

	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	// Stack -> device.
	go func() {
		for {
			pkt := pipe.ReadOutbound(ctx)
			if pkt == nil {
				return
			}
			if err := s.writeFrame(Frame{Type: TypePacket, Payload: pkt}); err != nil {
				cancel()
				return
			}
		}
	}()

	// Device -> stack.
	for {
		f, err := ReadFrame(s.conn)
		if err != nil {
			return err
		}
		switch f.Type {
		case TypePacket:
			pipe.Inject(f.Payload)
		case TypeControl:
			s.handleControl(f.Payload)
		}
	}
}

func (s *Session) readHello() (Hello, error) {
	f, err := ReadFrame(s.conn)
	if err != nil {
		return Hello{}, fmt.Errorf("read hello: %w", err)
	}
	if f.Type != TypeControl {
		return Hello{}, errors.New("device: first frame must be a control frame")
	}
	var h Hello
	if err := json.Unmarshal(f.Payload, &h); err != nil {
		return Hello{}, fmt.Errorf("decode hello: %w", err)
	}
	if h.Version != ProtocolVersion {
		return Hello{}, fmt.Errorf("%w: device sent %d, backend speaks %d",
			ErrVersionMismatch, h.Version, ProtocolVersion)
	}
	return h, nil
}

// handleControl routes an AppReply to whoever is waiting for it.
func (s *Session) handleControl(payload []byte) {
	var reply AppReply
	if err := json.Unmarshal(payload, &reply); err != nil {
		return
	}
	s.mu.Lock()
	ch, ok := s.pending[reply.ID]
	delete(s.pending, reply.ID)
	s.mu.Unlock()
	if ok {
		ch <- reply.Package
	}
}

// ResolveApp asks the device which package owns this connection. It returns ""
// on timeout, on error, or when the device itself does not know — never a
// guess, because a wrong attribution silently misfires fixtures.
func (s *Session) ResolveApp(src, dst netip.AddrPort) string {
	s.mu.Lock()
	s.nextID++
	id := s.nextID
	ch := make(chan string, 1)
	s.pending[id] = ch
	s.mu.Unlock()

	q, err := json.Marshal(AppQuery{ID: id, Src: src.String(), Dst: dst.String()})
	if err != nil {
		s.forget(id)
		return ""
	}
	if err := s.writeFrame(Frame{Type: TypeControl, Payload: q}); err != nil {
		s.forget(id)
		return ""
	}

	select {
	case pkg := <-ch:
		return pkg
	case <-time.After(resolveTimeout):
		s.forget(id)
		return ""
	}
}

func (s *Session) forget(id int) {
	s.mu.Lock()
	delete(s.pending, id)
	s.mu.Unlock()
}

func (s *Session) writeFrame(f Frame) error {
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	return WriteFrame(s.conn, f)
}
```

- [ ] **Step 4: Add the missing import to the test**

`conn_test.go` references `netstack.AppResolver`, so add to its imports:

```go
	"github.com/ghostbe/backend/internal/netstack"
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && go test ./internal/device/ -v`
Expected: PASS — all seven tests across both files

- [ ] **Step 6: Commit**

```bash
git add backend/internal/device/
git commit -m "feat: add device session with handshake and app attribution"
```

---

## Task 7: Direct TLS termination — remove the loopback hop

**Files:**
- Modify: `backend/internal/proxy/proxy.go`
- Test: `backend/internal/proxy/proxy_test.go`

**Interfaces:**
- Consumes: `flow.Meta`, `flow.Flow` (Task 2); `certs.Manager` (existing)
- Produces:
  - `proxy.Proxy.HandleTCP(conn net.Conn, meta flow.Meta)` — satisfies `netstack.Handler`
  - `proxy.New(db *db.DB, certMgr *certs.Manager) *Proxy`
  - `proxy.Proxy.Flows() <-chan *flow.Flow`

Deleted from `proxy.go`: `handleConnect`, `forwardHTTP`, `handleHTTP`, `Start`, the
`X-GhostBe-App` header read and write, and the `rules`/`engine` fields.

- [ ] **Step 1: Write the failing test**

`backend/internal/proxy/proxy_test.go`:

```go
package proxy

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"

	"github.com/ghostbe/backend/internal/certs"
	"github.com/ghostbe/backend/internal/db"
	"github.com/ghostbe/backend/internal/flow"
)

func newTestProxy(t *testing.T) *Proxy {
	t.Helper()
	dir := t.TempDir()
	database, err := db.New(dir + "/test.db")
	if err != nil {
		t.Fatalf("db.New: %v", err)
	}
	t.Cleanup(func() { database.Close() })
	certMgr, err := certs.New(dir)
	if err != nil {
		t.Fatalf("certs.New: %v", err)
	}
	return New(database, certMgr)
}

// dialPair returns the two ends of an in-memory connection.
func dialPair() (client, server net.Conn) { return net.Pipe() }

func TestHandleTCPTerminatesTLSAndRecordsFlowWithApp(t *testing.T) {
	origin := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"ok":true}`)
	}))
	defer origin.Close()

	p := newTestProxy(t)
	p.SetOriginTLSConfig(&tls.Config{InsecureSkipVerify: true}) // origin uses a self-signed cert

	originAddr := netip.MustParseAddrPort(origin.Listener.Addr().String())
	meta := flow.Meta{
		Src: netip.MustParseAddrPort("10.0.0.2:54321"),
		Dst: originAddr,
		App: "com.example.app",
	}

	client, server := dialPair()
	go p.HandleTCP(server, meta)

	// Trust the GhostBe CA, as a correctly configured debug app would.
	pool := x509.NewCertPool()
	pool.AddCert(p.certs.GetCACertificate())
	tlsClient := tls.Client(client, &tls.Config{
		RootCAs:    pool,
		ServerName: "api.example.com",
	})
	defer tlsClient.Close()

	req, _ := http.NewRequest("GET", "https://api.example.com/v1/me", nil)
	if err := req.Write(tlsClient); err != nil {
		t.Fatalf("write request: %v", err)
	}
	resp, err := http.ReadResponse(newBufReader(tlsClient), req)
	if err != nil {
		t.Fatalf("read response: %v", err)
	}
	body, _ := io.ReadAll(resp.Body)
	if string(body) != `{"ok":true}` {
		t.Errorf("body = %q, want {\"ok\":true}", body)
	}

	select {
	case f := <-p.Flows():
		if f.App != "com.example.app" {
			t.Errorf("flow.App = %q, want com.example.app", f.App)
		}
		if f.Request.URL != "https://api.example.com/v1/me" {
			t.Errorf("flow.Request.URL = %q", f.Request.URL)
		}
		if f.Response.Status != 200 {
			t.Errorf("flow.Response.Status = %d, want 200", f.Response.Status)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no flow recorded")
	}
}
```

Add this helper at the bottom of the test file:

```go
func newBufReader(c net.Conn) *bufio.Reader { return bufio.NewReader(c) }
```

and add `"bufio"` to the imports.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/proxy/ -v`
Expected: FAIL — `p.HandleTCP undefined`, `New` signature mismatch

- [ ] **Step 3: Rewrite `proxy.go`**

```go
// Package proxy terminates TLS from the app, inspects the plaintext exchange,
// and forwards it to the origin.
//
// It is called directly with a net.Conn from netstack. There is no loopback
// HTTP proxy hop and no X-GhostBe-App header: attribution arrives in flow.Meta.
package proxy

import (
	"bufio"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/ghostbe/backend/internal/certs"
	"github.com/ghostbe/backend/internal/db"
	"github.com/ghostbe/backend/internal/flow"
)

type Proxy struct {
	db    *db.DB
	certs *certs.Manager

	flows chan *flow.Flow

	mu        sync.RWMutex
	leafCache map[string]*tls.Certificate
	originTLS *tls.Config
}

func New(database *db.DB, certMgr *certs.Manager) *Proxy {
	return &Proxy{
		db:        database,
		certs:     certMgr,
		flows:     make(chan *flow.Flow, 1000),
		leafCache: make(map[string]*tls.Certificate),
	}
}

func (p *Proxy) Flows() <-chan *flow.Flow { return p.flows }

// SetOriginTLSConfig overrides verification of the origin's certificate. Tests
// use it to accept a self-signed httptest origin.
func (p *Proxy) SetOriginTLSConfig(c *tls.Config) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.originTLS = c
}

func (p *Proxy) originTLSConfig() *tls.Config {
	p.mu.RLock()
	defer p.mu.RUnlock()
	if p.originTLS == nil {
		return &tls.Config{}
	}
	return p.originTLS.Clone()
}

// HandleTCP owns conn for the lifetime of the connection. It satisfies
// netstack.Handler.
func (p *Proxy) HandleTCP(conn net.Conn, meta flow.Meta) {
	defer conn.Close()

	if meta.Dst.Port() == 443 {
		p.handleTLS(conn, meta)
		return
	}
	p.handlePlain(conn, meta)
}

func (p *Proxy) handleTLS(conn net.Conn, meta flow.Meta) {
	tlsConn := tls.Server(conn, &tls.Config{
		GetCertificate: func(hi *tls.ClientHelloInfo) (*tls.Certificate, error) {
			return p.leafFor(hi.ServerName, meta)
		},
	})

	if err := tlsConn.Handshake(); err != nil {
		// The app refused our certificate. Report it instead of dropping:
		// this is the single most common setup failure.
		p.emitFailure(meta, p.diagnoseHandshake(err, meta))
		return
	}
	defer tlsConn.Close()

	host := tlsConn.ConnectionState().ServerName
	if host == "" {
		host = meta.Dst.Addr().String()
	}
	p.serveRequests(tlsConn, meta, "https", host)
}

func (p *Proxy) handlePlain(conn net.Conn, meta flow.Meta) {
	p.serveRequests(conn, meta, "http", "")
}

// serveRequests handles every request on the connection, so HTTP keep-alive
// works. The old implementation read one buffer and sent FIN.
func (p *Proxy) serveRequests(conn net.Conn, meta flow.Meta, scheme, host string) {
	br := bufio.NewReader(conn)
	for {
		conn.SetReadDeadline(time.Now().Add(120 * time.Second))
		req, err := http.ReadRequest(br)
		if err != nil {
			return
		}

		h := host
		if h == "" {
			h = req.Host
		}
		if h == "" {
			h = meta.Dst.String()
		}

		f := flow.New(meta)
		reqBody, truncated, _ := flow.ReadBody(req.Body)
		req.Body.Close()
		f.Request = &flow.Request{
			Method:        req.Method,
			URL:           fmt.Sprintf("%s://%s%s", scheme, h, req.URL.RequestURI()),
			Headers:       req.Header.Clone(),
			Body:          reqBody,
			BodyTruncated: truncated,
		}

		resp, err := p.forward(req, scheme, h, reqBody)
		if err != nil {
			f.Action = flow.ActionFailed
			f.Response = &flow.Response{Status: http.StatusBadGateway}
			f.Error = fmt.Sprintf("origin %s unreachable: %v", h, err)
			f.Latency = time.Since(f.StartedAt)
			writeGatewayError(conn, err)
			p.publish(f)
			return
		}

		respBody, respTruncated, _ := flow.ReadBody(resp.Body)
		resp.Body.Close()
		f.Response = &flow.Response{
			Status:        resp.StatusCode,
			Headers:       resp.Header.Clone(),
			Body:          respBody,
			BodyTruncated: respTruncated,
		}
		f.Latency = time.Since(f.StartedAt)

		if err := writeResponse(conn, resp, respBody); err != nil {
			p.publish(f)
			return
		}
		p.publish(f)

		if req.Close || resp.Close {
			return
		}
	}
}

func (p *Proxy) forward(req *http.Request, scheme, host string, body []byte) (*http.Response, error) {
	outURL := fmt.Sprintf("%s://%s%s", scheme, host, req.URL.RequestURI())
	out, err := http.NewRequest(req.Method, outURL, strings.NewReader(string(body)))
	if err != nil {
		return nil, err
	}
	out.Header = req.Header.Clone()
	out.Header.Del("Accept-Encoding") // keep bodies inspectable

	client := &http.Client{
		Timeout: 30 * time.Second,
		Transport: &http.Transport{
			TLSClientConfig: p.originTLSConfig(),
		},
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	return client.Do(out)
}

func writeResponse(w net.Conn, resp *http.Response, body []byte) error {
	resp.Body = nopCloser{strings.NewReader(string(body))}
	resp.ContentLength = int64(len(body))
	resp.TransferEncoding = nil
	return resp.Write(w)
}

func writeGatewayError(w net.Conn, cause error) {
	resp := &http.Response{
		StatusCode: http.StatusBadGateway,
		ProtoMajor: 1, ProtoMinor: 1,
		Header: make(http.Header),
		Body:   nopCloser{strings.NewReader("ghostbe: " + cause.Error())},
	}
	resp.Write(w)
}

type nopCloser struct{ *strings.Reader }

func (nopCloser) Close() error { return nil }

func (p *Proxy) leafFor(hostname string, meta flow.Meta) (*tls.Certificate, error) {
	if hostname == "" {
		hostname = meta.Dst.Addr().String()
	}
	p.mu.RLock()
	cert, ok := p.leafCache[hostname]
	p.mu.RUnlock()
	if ok {
		return cert, nil
	}
	signed, err := p.certs.SignLeafCert(hostname)
	if err != nil {
		return nil, err
	}
	p.mu.Lock()
	p.leafCache[hostname] = &signed
	p.mu.Unlock()
	return &signed, nil
}

func (p *Proxy) publish(f *flow.Flow) {
	app := f.App
	status := 0
	if f.Response != nil {
		status = f.Response.Status
	}
	_ = p.db.LogTraffic(db.TrafficLog{
		AppPackage:     &app,
		Method:         f.Request.Method,
		URL:            f.Request.URL,
		ResponseStatus: status,
		LatencyMs:      f.Latency.Milliseconds(),
		Mocked:         f.Action == flow.ActionMocked,
	})
	select {
	case p.flows <- f:
	default: // never block the data path on a slow consumer
	}
}

func (p *Proxy) emitFailure(meta flow.Meta, msg string) {
	f := flow.New(meta)
	f.Action = flow.ActionFailed
	f.Error = msg
	f.Request = &flow.Request{Method: "-", URL: "tls://" + meta.Dst.String()}
	f.Latency = time.Since(f.StartedAt)
	select {
	case p.flows <- f:
	default:
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && go test ./internal/proxy/ -run TestHandleTCPTerminates -v`
Expected: PASS

`diagnoseHandshake` is referenced but not yet defined — it lands in Task 8. To keep this task
compiling on its own, add a placeholder-free minimal version now:

```go
func (p *Proxy) diagnoseHandshake(err error, meta flow.Meta) string {
	return fmt.Sprintf("TLS handshake with %s failed: %v", meta.Dst, err)
}
```

- [ ] **Step 5: Commit**

```bash
git add backend/internal/proxy/
git commit -m "refactor: terminate TLS directly from netstack, drop loopback proxy hop"
```

---

## Task 8: Diagnose rejected certificates

**Files:**
- Modify: `backend/internal/proxy/proxy.go`
- Test: `backend/internal/proxy/diagnose_test.go`

**Interfaces:**
- Consumes: `Proxy.emitFailure`, `Proxy.Flows()` (Task 7)
- Produces: `Proxy.diagnoseHandshake(err error, meta flow.Meta) string` returning an actionable message

- [ ] **Step 1: Write the failing test**

`backend/internal/proxy/diagnose_test.go`:

```go
package proxy

import (
	"crypto/tls"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/ghostbe/backend/internal/flow"
)

func TestClientRejectingCertProducesActionableDiagnosis(t *testing.T) {
	p := newTestProxy(t)

	meta := flow.Meta{
		Src: netip.MustParseAddrPort("10.0.0.2:54321"),
		Dst: netip.MustParseAddrPort("93.184.216.34:443"),
		App: "com.example.app",
	}

	client, server := net.Pipe()
	go p.HandleTCP(server, meta)

	// An app with no knowledge of the GhostBe CA — i.e. one missing the
	// debug network security config.
	tlsClient := tls.Client(client, &tls.Config{ServerName: "api.example.com"})
	_ = tlsClient.Handshake() // expected to fail
	tlsClient.Close()

	select {
	case f := <-p.Flows():
		if f.Action != flow.ActionFailed {
			t.Errorf("Action = %q, want %q", f.Action, flow.ActionFailed)
		}
		if !strings.Contains(f.Error, "com.example.app") {
			t.Errorf("Error missing app package: %q", f.Error)
		}
		if !strings.Contains(f.Error, "debug-overrides") {
			t.Errorf("Error should point at the network security config: %q", f.Error)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no failure flow emitted; the rejection was swallowed")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/proxy/ -run TestClientRejecting -v`
Expected: FAIL — the error message lacks `debug-overrides`

- [ ] **Step 3: Replace `diagnoseHandshake`**

Replace the minimal version added in Task 7:

```go
// diagnoseHandshake turns a TLS handshake failure into something a developer
// can act on. A rejected certificate is the most common setup failure, and an
// undiagnosed one is indistinguishable from a network problem.
func (p *Proxy) diagnoseHandshake(err error, meta flow.Meta) string {
	app := meta.App
	if app == "" {
		app = "the app"
	}

	msg := err.Error()
	switch {
	case strings.Contains(msg, "bad certificate"),
		strings.Contains(msg, "unknown certificate"),
		strings.Contains(msg, "certificate required"),
		strings.Contains(msg, "unknown authority"):
		return fmt.Sprintf(
			"%s rejected our certificate for %s — certificate pinning, or the debug-overrides "+
				"network security config is missing. See docs: apps targeting API 24+ ignore "+
				"user-installed CAs unless they opt in.",
			app, meta.Dst)
	case strings.Contains(msg, "protocol version"):
		return fmt.Sprintf("%s and GhostBe share no TLS version for %s: %v", app, meta.Dst, err)
	case strings.Contains(msg, "EOF"), strings.Contains(msg, "closed"):
		return fmt.Sprintf(
			"%s closed the connection during the TLS handshake with %s — usually a rejected "+
				"certificate (pinning, or missing debug-overrides config): %v",
			app, meta.Dst, err)
	default:
		return fmt.Sprintf("TLS handshake with %s failed for %s: %v", meta.Dst, app, err)
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && go test ./internal/proxy/ -v`
Expected: PASS — both proxy tests

- [ ] **Step 5: Commit**

```bash
git add backend/internal/proxy/
git commit -m "feat: diagnose rejected certificates instead of dropping the flow"
```

---

## Task 9: Wire it together and delete the old stack

**Files:**
- Modify: `backend/cmd/ghostbe/main.go`
- Modify: `backend/internal/db/db.go`
- Modify: `backend/internal/api/api.go`
- Delete: `backend/internal/tun/handler.go`
- Delete: `backend/internal/rules/rules.go`
- Test: `backend/internal/api/status_test.go`

**Interfaces:**
- Consumes: `netstack.New` (Task 3), `device.NewSession` (Task 6), `proxy.New`/`HandleTCP` (Task 7)
- Produces: a running server; `/api/status` reporting `netstack.Stats`

- [ ] **Step 1: Write the failing test**

`backend/internal/api/status_test.go`:

```go
package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestStatusReportsCaptureStats(t *testing.T) {
	s := newTestServer(t)

	rec := httptest.NewRecorder()
	s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/status", nil))

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	var got StatusResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if !got.Running {
		t.Error("Running = false, want true")
	}
	if got.CAFingerprint == "" {
		t.Error("CAFingerprint is empty")
	}
	// Present so that traffic GhostBe could not intercept is visible.
	if got.Capture == nil {
		t.Fatal("Capture is nil; capture stats must be reported")
	}
}

func TestRulesEndpointsAreGone(t *testing.T) {
	s := newTestServer(t)
	for _, path := range []string{"/api/rules", "/api/rules/1"} {
		rec := httptest.NewRecorder()
		s.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		if rec.Code != http.StatusNotFound {
			t.Errorf("GET %s = %d, want 404 (rules moved to fixture files)", path, rec.Code)
		}
	}
}
```

Add this helper in the same file:

```go
func newTestServer(t *testing.T) *Server {
	t.Helper()
	dir := t.TempDir()
	database, err := db.New(dir + "/test.db")
	if err != nil {
		t.Fatalf("db.New: %v", err)
	}
	t.Cleanup(func() { database.Close() })
	certMgr, err := certs.New(dir)
	if err != nil {
		t.Fatalf("certs.New: %v", err)
	}
	pxy := proxy.New(database, certMgr)
	ns, err := netstack.New(pxy)
	if err != nil {
		t.Fatalf("netstack.New: %v", err)
	}
	t.Cleanup(ns.Close)
	return New(8878, database, certMgr, pxy, ns, dir)
}
```

with imports `"github.com/ghostbe/backend/internal/certs"`, `"github.com/ghostbe/backend/internal/db"`, `"github.com/ghostbe/backend/internal/netstack"`, `"github.com/ghostbe/backend/internal/proxy"`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/api/ -v`
Expected: FAIL — `New` signature mismatch, `StatusResponse.Capture` undefined

- [ ] **Step 3: Update `api.go`**

Change `StatusResponse` and `New`, and delete the rules routes and handlers:

```go
type StatusResponse struct {
	Running       bool            `json:"running"`
	Port          int             `json:"port"`
	CAFingerprint string          `json:"ca_fingerprint"`
	Capture       *netstack.Stats `json:"capture"`
}
```

In `New`, take `ns *netstack.Stack` as a parameter, store it on `Server`, and remove these five
route registrations:

```go
	r.Get("/api/rules", s.handleGetRules)
	r.Post("/api/rules", s.handleCreateRule)
	r.Put("/api/rules/{id}", s.handleUpdateRule)
	r.Delete("/api/rules/{id}", s.handleDeleteRule)
	r.Patch("/api/rules/{id}/toggle", s.handleToggleRule)
```

Delete the handler functions `handleGetRules`, `handleCreateRule`, `handleUpdateRule`,
`handleDeleteRule`, `handleToggleRule`, and `updateProxyRules`. Update `handleStatus`:

```go
func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	stats := s.netstack.Stats()
	resp := StatusResponse{
		Running:       true,
		Port:          s.port,
		CAFingerprint: s.certMgr.GetCAFingerprint(),
		Capture:       &stats,
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}
```

Change the traffic broadcaster to consume `*flow.Flow` from `s.proxy.Flows()` instead of
`proxy.TrafficEvent`:

```go
func (s *Server) broadcastTraffic() {
	for f := range s.proxy.Flows() {
		for subscriber := range s.subscribers {
			select {
			case subscriber <- f:
			default:
			}
		}
	}
}
```

and change `subscribers` to `map[chan *flow.Flow]bool`, plus the matching type in
`handleWebSocket`.

- [ ] **Step 4: Drop rules from the database**

In `backend/internal/db/db.go`, delete the `CREATE TABLE IF NOT EXISTS rules (...)` statement
from the migration string, delete the `Rule` type, and delete `GetRules`, `CreateRule`,
`UpdateRule`, `DeleteRule`, and `ToggleRule`. Add a one-line migration so existing databases
shed the table:

```go
	// Rules now live in version-controlled fixture files.
	DROP TABLE IF EXISTS rules;
```

- [ ] **Step 5: Rewrite `main.go`**

```go
package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/ghostbe/backend/internal/api"
	"github.com/ghostbe/backend/internal/certs"
	"github.com/ghostbe/backend/internal/db"
	"github.com/ghostbe/backend/internal/device"
	"github.com/ghostbe/backend/internal/netstack"
	"github.com/ghostbe/backend/internal/proxy"
)

func main() {
	port := 8877
	if p := os.Getenv("GHOSTBE_PORT"); p != "" {
		if parsed, err := strconv.Atoi(p); err == nil {
			port = parsed
		}
	}
	dataDir := "./data"
	if d := os.Getenv("GHOSTBE_DATA_DIR"); d != "" {
		dataDir = d
	}
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		log.Fatalf("create data dir: %v", err)
	}

	database, err := db.New(dataDir + "/ghostbe.db")
	if err != nil {
		log.Fatalf("init database: %v", err)
	}
	defer database.Close()

	certMgr, err := certs.New(dataDir)
	if err != nil {
		log.Fatalf("init certificates: %v", err)
	}
	log.Printf("CA fingerprint: %s", certMgr.GetCAFingerprint())

	pxy := proxy.New(database, certMgr)
	ns, err := netstack.New(pxy)
	if err != nil {
		log.Fatalf("init netstack: %v", err)
	}
	defer ns.Close()

	if up := os.Getenv("GHOSTBE_DNS"); up != "" {
		ns.SetDNSUpstream(up)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	// Device listener: one TCP connection per Android device.
	devicePort := port - 1
	go serveDevices(ctx, devicePort, ns)

	apiPort := port + 1
	apiServer := api.New(apiPort, database, certMgr, pxy, ns, dataDir)
	apiHTTP := &http.Server{Addr: fmt.Sprintf(":%d", apiPort), Handler: apiServer}
	go func() {
		log.Printf("API server on :%d", apiPort)
		if err := apiHTTP.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("api server: %v", err)
		}
	}()

	<-ctx.Done()
	log.Println("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := apiHTTP.Shutdown(shutdownCtx); err != nil {
		log.Printf("api shutdown: %v", err)
	}
}

func serveDevices(ctx context.Context, port int, ns *netstack.Stack) {
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		log.Fatalf("device listener: %v", err)
	}
	defer ln.Close()
	log.Printf("device listener on :%d", port)

	go func() {
		<-ctx.Done()
		ln.Close()
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("device accept: %v", err)
			continue
		}
		sess := device.NewSession(conn, func(r netstack.AppResolver) (device.PacketPipe, error) {
			return ns.AttachDevice(r)
		})
		go func() {
			if err := sess.Serve(ctx); err != nil {
				log.Printf("device session ended: %v", err)
			}
		}()
	}
}
```

- [ ] **Step 6: Delete the old packages**

```bash
cd /home/portonics/development/project/ghost-be
git rm backend/internal/tun/handler.go backend/internal/rules/rules.go
```

- [ ] **Step 7: Verify nothing references the removed code**

Run:
```bash
cd backend && go build ./... && go vet ./... && grep -rn "X-GhostBe-App\|internal/tun\|internal/rules" --include=*.go . ; echo "exit=$?"
```
Expected: builds and vets clean; grep prints nothing and reports `exit=1`.

- [ ] **Step 8: Run the whole suite**

Run: `cd backend && go test ./...`
Expected: PASS across `flow`, `netstack`, `device`, `proxy`, `api`

- [ ] **Step 9: Commit**

```bash
git add -A backend/
git commit -m "refactor: wire netstack into main, delete hand-rolled stack and rules table"
```

---

## Task 10: Android protocol v2 and certificate setup

Not unit tested — verified by `go test` on the backend plus the manual smoke check below.

**Files:**
- Modify: `android/app/src/main/java/com/ghostbe/GhostVpnService.java`
- Create: `android/app/src/debug/res/xml/network_security_config.xml`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `README.md`

**Interfaces:**
- Consumes: `device.ProtocolVersion` = 2, `Hello`/`AppQuery`/`AppReply` JSON shapes (Task 6)
- Produces: an APK speaking protocol v2

- [ ] **Step 1: Replace the framing and handshake in `GhostVpnService.java`**

Replace `connectToBackend`, `tunReader`, and `backendReader` with:

```java
    private static final int PROTOCOL_VERSION = 2;
    private static final byte TYPE_PACKET = 0x00;
    private static final byte TYPE_CONTROL = 0x01;

    private void connectToBackend() {
        try {
            int tunPort = hostPort - 1;
            Log.d(TAG, "Connecting to backend " + hostIP + ":" + tunPort);
            backendSocket = new Socket(hostIP, tunPort);
            backendOut = backendSocket.getOutputStream();
            backendIn = backendSocket.getInputStream();

            JSONObject hello = new JSONObject();
            hello.put("version", PROTOCOL_VERSION);
            hello.put("apps", new JSONArray(selectedApps));
            writeFrame(TYPE_CONTROL, hello.toString().getBytes("UTF-8"));
            Log.d(TAG, "Sent hello: " + hello);

            tunReadThread = new Thread(this::tunReader);
            backendReadThread = new Thread(this::backendReader);
            tunReadThread.start();
            backendReadThread.start();
        } catch (Exception e) {
            Log.e(TAG, "connectToBackend error", e);
        }
    }

    private void writeFrame(byte type, byte[] payload) throws Exception {
        int len = 1 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(4 + len);
        buf.putInt(len);
        buf.put(type);
        buf.put(payload);
        synchronized (backendOut) {
            backendOut.write(buf.array());
            backendOut.flush();
        }
    }

    private void tunReader() {
        try {
            byte[] packet = new byte[32767];
            while (running) {
                int length = tunIn.read(packet);
                if (length <= 0) break;
                byte[] payload = new byte[length];
                System.arraycopy(packet, 0, payload, 0, length);
                writeFrame(TYPE_PACKET, payload);
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "tunReader error", e);
        }
    }

    private void backendReader() {
        try {
            while (running) {
                byte[] header = readFully(4);
                if (header == null) return;
                int len = ((header[0] & 0xff) << 24) | ((header[1] & 0xff) << 16)
                        | ((header[2] & 0xff) << 8) | (header[3] & 0xff);
                if (len <= 0 || len > 65535) { running = false; return; }

                byte[] body = readFully(len);
                if (body == null) return;
                byte type = body[0];
                byte[] payload = new byte[len - 1];
                System.arraycopy(body, 1, payload, 0, len - 1);

                if (type == TYPE_PACKET) {
                    synchronized (tunOut) {
                        tunOut.write(payload);
                        tunOut.flush();
                    }
                } else if (type == TYPE_CONTROL) {
                    handleControl(new String(payload, "UTF-8"));
                }
            }
        } catch (Exception e) {
            if (running) Log.e(TAG, "backendReader error", e);
        }
    }

    private byte[] readFully(int n) throws Exception {
        byte[] buf = new byte[n];
        int read = 0;
        while (read < n) {
            int r = backendIn.read(buf, read, n - read);
            if (r < 0) { running = false; return null; }
            read += r;
        }
        return buf;
    }

    // handleControl answers the backend's "who owns this connection?" queries.
    // Reporting "" is correct when the owner cannot be determined; guessing
    // would silently misattribute traffic.
    private void handleControl(String json) {
        try {
            JSONObject q = new JSONObject(json);
            int id = q.getInt("id");
            String pkg = resolveOwner(q.getString("src"), q.getString("dst"));

            JSONObject reply = new JSONObject();
            reply.put("id", id);
            reply.put("package", pkg == null ? "" : pkg);
            writeFrame(TYPE_CONTROL, reply.toString().getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "handleControl error", e);
        }
    }

    private String resolveOwner(String src, String dst) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return selectedApps.size() == 1 ? selectedApps.iterator().next() : "";
        }
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            int uid = cm.getConnectionOwnerUid(
                    OsConstants.IPPROTO_TCP, parseAddr(src), parseAddr(dst));
            if (uid == android.os.Process.INVALID_UID) return "";
            String[] pkgs = getPackageManager().getPackagesForUid(uid);
            return (pkgs != null && pkgs.length > 0) ? pkgs[0] : "";
        } catch (Exception e) {
            Log.w(TAG, "resolveOwner failed", e);
            return "";
        }
    }

    private InetSocketAddress parseAddr(String hostPort) throws Exception {
        int i = hostPort.lastIndexOf(':');
        return new InetSocketAddress(
                InetAddress.getByName(hostPort.substring(0, i)),
                Integer.parseInt(hostPort.substring(i + 1)));
    }
```

Add these imports: `android.net.ConnectivityManager`, `java.net.InetAddress`,
`java.net.InetSocketAddress`, `org.json.JSONObject`.

Note: when only one app is selected on API < 29, reporting that single package is not a guess —
`addAllowedApplication` guarantees no other app's traffic is in the tunnel.

- [ ] **Step 2: Create the debug network security config**

`android/app/src/debug/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- debug-overrides apply only when android:debuggable="true", so this
         cannot affect a release build. -->
    <debug-overrides>
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </debug-overrides>
</network-security-config>
```

- [ ] **Step 3: Reference it from the manifest**

In `android/app/src/main/AndroidManifest.xml`, add to the `<application>` element:

```xml
        android:networkSecurityConfig="@xml/network_security_config"
```

- [ ] **Step 4: Build the APK**

Run:
```bash
cd android && ./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk`

Requires Java 17 — Java 21 has a jlink incompatibility with compileSdk 33.

- [ ] **Step 5: Manual smoke test**

```bash
cd backend && make build && ./ghostbe &
cd ../android && adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.ghostbe/.MainActivity
# In the app: set host to your LAN IP, select one app, Connect.
# Then exercise that app and watch the backend log.
curl -s localhost:8878/api/status | jq
```

Expected: backend logs `Device connected` with the app list; `/api/status` shows non-zero
`capture.tcp_flows`; traffic appears with the correct `app_package`.

If TLS flows fail with a certificate diagnosis, the target app has not picked up the debug
network security config — confirm it is a debug build and that the CA is installed as a user
certificate.

- [ ] **Step 6: Update the README**

Replace the stale data-flow diagram at `README.md:119` and the "Mini Proxy" bullet at
`README.md:110`, which describe the removed on-device proxy. The accurate flow is:

```
Selected app → TUN → GhostBeVpn.apk (raw forwarder, protocol v2)
  → backend :8876  device listener
  → gVisor netstack → one net.Conn per flow
  → proxy: TLS termination with CA-signed leaf → inspect → origin
  → SQLite + WebSocket → web UI
```

Also correct `README.md:241`: user-certificate installation is insufficient for **all** apps
targeting API 24+, not "some Android versions". Document the debug network security config as
the supported path.

- [ ] **Step 7: Commit**

```bash
git add android/ README.md
git commit -m "feat: android protocol v2 with app attribution and debug cert config"
```

---

## Self-Review

**Spec coverage.** Walking §5–§6 and §12 of the design against the tasks:

| Spec requirement | Task |
|---|---|
| §5 Phase 0: test rig, `-s -w`, untrack binary | 1, 3 (`testrig.go`) |
| §6.1 `Flow` seam | 2 |
| §6.2 package layout (`device`, `netstack`, `flow`) | 2, 3, 5, 6 |
| §6.3 gVisor wiring, IPv6, deleted helpers | 3, 9 |
| §6.3 NIC per device | 3 (`AttachDevice`) |
| §6.4 loopback hop removed | 7 |
| §6.5 attribution, never guessed, API 29+ | 6, 10 |
| §6.6 protocol v2 with type byte + version | 5, 6, 10 |
| §6.7 TLS rejection diagnosed | 8 |
| §6.7 QUIC rejected and counted | 4 |
| §6.7 origin unreachable → 502 flow | 7 |
| §9 body cap 1 MB | 2 |
| §10 tests need no device or network | 3, 4, 6, 7, 8 |
| §11 rules table and CRUD dropped | 9 |
| Optional :8877 proxy listener for `emulator -http-proxy` | **gap** |

**Gap found and closed:** the standalone HTTP proxy listener on 8877 for the CI emulator path
(design §6.4) had no task. It is not required for Phases 0–1 to be complete and testable —
device capture works without it — so rather than pad this plan, it is recorded as the first task
of the Phase 3 plan, where the CI story is built. Noted here so it is not lost.

**Placeholder scan.** No TBDs, no "add error handling", no "similar to Task N". Task 7
deliberately defines a minimal `diagnoseHandshake` so the task compiles standalone, and Task 8
replaces it — the interim version is complete code, not a stub.

**Type consistency.** Checked across tasks: `flow.Meta` fields (`Src`, `Dst`, `App`) are used
identically in Tasks 3, 7, 8; `netstack.Handler.HandleTCP(net.Conn, flow.Meta)` matches
`proxy.Proxy.HandleTCP` exactly; `device.PacketPipe` (`Inject`, `ReadOutbound`, `Detach`)
matches the methods `netstack.Device` actually exposes in Task 3; `AttachFunc` in Task 6 matches
the closure passed in Task 9; `ProtocolVersion = 2` in Task 5 matches `PROTOCOL_VERSION = 2` in
Task 10; the `Hello`/`AppQuery`/`AppReply` JSON keys (`version`, `apps`, `id`, `src`, `dst`,
`package`) match between Go structs in Task 6 and the Java in Task 10.
