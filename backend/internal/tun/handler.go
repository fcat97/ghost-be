package tun

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

type Server struct {
	addr      string
	proxyAddr string
}

func New(port int, proxyAddr string) *Server {
	return &Server{
		addr:      fmt.Sprintf(":%d", port),
		proxyAddr: proxyAddr,
	}
}

func (s *Server) Start() error {
	ln, err := net.Listen("tcp", s.addr)
	if err != nil {
		return fmt.Errorf("tun server listen: %w", err)
	}
	log.Printf("TUN server listening on %s", s.addr)
	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("tun accept error: %v", err)
			continue
		}
		go s.handleDevice(conn)
	}
}

type deviceCtx struct {
	conn       net.Conn
	appPackage string
	proxyAddr  string
	mu         sync.Mutex
}

func (s *Server) handleDevice(conn net.Conn) {
	defer conn.Close()

	var pkgLen uint32
	if err := binary.Read(conn, binary.BigEndian, &pkgLen); err != nil {
		log.Printf("failed to read package length: %v", err)
		return
	}
	pkgBuf := make([]byte, pkgLen)
	if _, err := io.ReadFull(conn, pkgBuf); err != nil {
		log.Printf("failed to read package name: %v", err)
		return
	}
	appPackage := string(pkgBuf)
	log.Printf("Device connected, app: %s", appPackage)

	ctx := &deviceCtx{
		conn:       conn,
		appPackage: appPackage,
		proxyAddr:  s.proxyAddr,
	}

	packetHandler(ctx)
}

func packetHandler(ctx *deviceCtx) {
	conns := make(map[string]*tcpConn)
	headerBuf := make([]byte, 4)

	for {
		if _, err := io.ReadFull(ctx.conn, headerBuf); err != nil {
			log.Printf("packetHandler: read header: %v", err)
			break
		}
		length := binary.BigEndian.Uint32(headerBuf)
		if length > 65535 || length < 20 {
			continue
		}

		ipData := make([]byte, length)
		if _, err := io.ReadFull(ctx.conn, ipData); err != nil {
			log.Printf("packetHandler: read data: %v", err)
			break
		}

		processIPPacket(ctx, conns, ipData)
	}

	for _, tc := range conns {
		tc.close()
	}
}

func processIPPacket(ctx *deviceCtx, conns map[string]*tcpConn, ipData []byte) {
	if len(ipData) < 20 {
		return
	}

	version := (ipData[0] >> 4) & 0xf
	if version != 4 {
		return
	}

	ipHdrLen := int((ipData[0] & 0xf) * 4)
	if ipHdrLen < 20 || ipHdrLen > len(ipData) {
		return
	}

	protocol := ipData[9]

	srcIP := ipData[12:16]
	dstIP := ipData[16:20]

	srcPort := int(ipData[ipHdrLen])<<8 | int(ipData[ipHdrLen+1])
	dstPort := int(ipData[ipHdrLen+2])<<8 | int(ipData[ipHdrLen+3])

	if protocol == 17 {
		if dstPort == 53 {
			forwardDNS(ctx, srcIP, dstIP, srcPort, dstPort, ipData, ipHdrLen)
		}
		return
	}

	if protocol != 6 {
		return
	}

	if len(ipData) < ipHdrLen+20 {
		return
	}

	if dstPort != 80 && dstPort != 443 {
		return
	}

	seq := uint32(ipData[ipHdrLen+4])<<24 | uint32(ipData[ipHdrLen+5])<<16 |
		uint32(ipData[ipHdrLen+6])<<8 | uint32(ipData[ipHdrLen+7])

	ack := uint32(ipData[ipHdrLen+8])<<24 | uint32(ipData[ipHdrLen+9])<<16 |
		uint32(ipData[ipHdrLen+10])<<8 | uint32(ipData[ipHdrLen+11])

	tcpHdrLen := int(((ipData[ipHdrLen+12] >> 4) & 0xf) * 4)
	flags := ipData[ipHdrLen+13]

	payloadOffset := ipHdrLen + tcpHdrLen
	payloadLen := len(ipData) - payloadOffset
	if payloadLen < 0 {
		payloadLen = 0
	}

	key := fmt.Sprintf("%s:%d-%s:%d", ipStr(srcIP), srcPort, ipStr(dstIP), dstPort)

	tc := conns[key]
	if tc == nil && flags&0x02 != 0 {
		tc = newTCPConn(ctx, srcIP, dstIP, srcPort, dstPort, seq)
		if tc == nil {
			return
		}
		conns[key] = tc
		tc.sendSYNACK()
		return
	}

	if tc == nil {
		return
	}

	if flags&0x04 != 0 {
		tc.sendRST()
		delete(conns, key)
		return
	}

	if flags&0x01 != 0 {
		tc.sendFINACK(seq, ack)
		delete(conns, key)
		return
	}

	if payloadLen > 0 {
		payload := make([]byte, payloadLen)
		copy(payload, ipData[payloadOffset:])
		tc.handleData(seq, ack, payload)
	}
}

type tcpConn struct {
	ctx           *deviceCtx
	srcIP         []byte
	dstIP         []byte
	srcPort       int
	dstPort       int
	clientSeq     uint32
	serverSeq     uint32
	clientAck     uint32
	serverAck     uint32
	mu            sync.Mutex
	proxyConn     net.Conn
	relayStarted  bool
	relayStop     chan struct{}
}

func newTCPConn(ctx *deviceCtx, srcIP, dstIP []byte, srcPort, dstPort int, clientSeq uint32) *tcpConn {
	serverSeq := pseudoRand()
	return &tcpConn{
		ctx:       ctx,
		srcIP:     srcIP,
		dstIP:     dstIP,
		srcPort:   srcPort,
		dstPort:   dstPort,
		clientSeq: clientSeq,
		serverSeq: serverSeq,
		clientAck: clientSeq + 1,
		serverAck: serverSeq,
		relayStop: make(chan struct{}),
	}
}

func (tc *tcpConn) sendSYNACK() {
	ipHdrLen := 20
	tcpHdrLen := 20
	totalLen := ipHdrLen + tcpHdrLen

	buf := make([]byte, totalLen)

	buf[0] = 0x45
	buf[1] = 0
	binary.BigEndian.PutUint16(buf[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(buf[4:6], uint16(pseudoRand() & 0xffff))
	buf[6] = 0
	buf[7] = 0
	buf[8] = 64
	buf[9] = 6
	buf[10] = 0
	buf[11] = 0
	copy(buf[12:16], tc.dstIP)
	copy(buf[16:20], tc.srcIP)

	binary.BigEndian.PutUint16(buf[20:22], uint16(tc.dstPort))
	binary.BigEndian.PutUint16(buf[22:24], uint16(tc.srcPort))
	binary.BigEndian.PutUint32(buf[24:28], tc.serverSeq)
	binary.BigEndian.PutUint32(buf[28:32], tc.clientAck)
	buf[32] = byte(tcpHdrLen << 4)
	buf[33] = 0x12
	binary.BigEndian.PutUint16(buf[34:36], 65535)
	buf[36] = 0
	buf[37] = 0
	binary.BigEndian.PutUint16(buf[38:40], 0)

	ipChecksum := checksum(buf[0:ipHdrLen])
	binary.BigEndian.PutUint16(buf[10:12], ipChecksum)

	tcpChecksum := tcpChecksummed(buf[ipHdrLen:tcpHdrLen], tc.dstIP, tc.srcIP)
	binary.BigEndian.PutUint16(buf[36:38], tcpChecksum)

	tc.writeTUN(buf)
}

func (tc *tcpConn) handleData(clientSeq, clientAck uint32, payload []byte) {
	tc.mu.Lock()
	tc.clientAck = clientSeq + uint32(len(payload))
	tc.serverAck = clientAck
	tc.mu.Unlock()

	tc.sendACK()

	if tc.dstPort == 443 {
		tc.handleHTTPS(payload)
	} else {
		tc.handleHTTP(payload)
	}
}

func (tc *tcpConn) handleHTTP(payload []byte) {
	proxyConn, err := net.DialTimeout("tcp", tc.ctx.proxyAddr, 10*time.Second)
	if err != nil {
		log.Printf("handleHTTP: dial proxy: %v", err)
		tc.sendRST()
		return
	}
	defer proxyConn.Close()

	if _, err := proxyConn.Write(payload); err != nil {
		log.Printf("handleHTTP: proxy write: %v", err)
		tc.sendRST()
		return
	}

	proxyConn.SetReadDeadline(time.Now().Add(15 * time.Second))
	respBuf := make([]byte, 65535)
	n, err := proxyConn.Read(respBuf)
	if err != nil {
		return
	}

	tc.writeResponse(respBuf[:n])
	tc.sendFIN()
}

func (tc *tcpConn) handleHTTPS(payload []byte) {
	sniHost := extractSNIFromBytes(payload)
	if sniHost == "" {
		sniHost = ipStr(tc.dstIP)
	}

	tc.mu.Lock()
	if tc.relayStarted {
		if _, err := tc.proxyConn.Write(payload); err != nil {
			tc.mu.Unlock()
			tc.sendRST()
			return
		}
		tc.mu.Unlock()
		return
	}
	tc.mu.Unlock()

	proxyConn, err := net.DialTimeout("tcp", tc.ctx.proxyAddr, 10*time.Second)
	if err != nil {
		log.Printf("handleHTTPS: dial proxy: %v", err)
		tc.sendRST()
		return
	}

	connectReq := fmt.Sprintf("CONNECT %s:443 HTTP/1.1\r\nHost: %s\r\n\r\n", sniHost, sniHost)
	if _, err := fmt.Fprint(proxyConn, connectReq); err != nil {
		log.Printf("handleHTTPS: CONNECT write: %v", err)
		proxyConn.Close()
		tc.sendRST()
		return
	}

	resp, err := bufio.NewReader(proxyConn).ReadString('\n')
	if err != nil || !strings.Contains(resp, "200") {
		log.Printf("handleHTTPS: CONNECT rejected: %s", resp)
		proxyConn.Close()
		tc.sendRST()
		return
	}
	for {
		line, err := bufio.NewReader(proxyConn).ReadString('\n')
		if err != nil || line == "\r\n" || line == "\n" {
			break
		}
	}

	if _, err := proxyConn.Write(payload); err != nil {
		log.Printf("handleHTTPS: write first payload: %v", err)
		proxyConn.Close()
		tc.sendRST()
		return
	}

	tc.mu.Lock()
	tc.proxyConn = proxyConn
	tc.relayStarted = true
	tc.mu.Unlock()

	go tc.proxyToTUNRelay()
}

func (tc *tcpConn) proxyToTUNRelay() {
	buf := make([]byte, 65535)
	for {
		select {
		case <-tc.relayStop:
			return
		default:
		}
		tc.proxyConn.SetReadDeadline(time.Now().Add(30 * time.Second))
		n, err := tc.proxyConn.Read(buf)
		if n > 0 {
			tc.writeResponse(buf[:n])
		}
		if err != nil {
			return
		}
	}
}

func (tc *tcpConn) writeResponse(data []byte) {
	maxPayload := 1460
	offset := 0
	for offset < len(data) {
		chunk := data[offset:]
		if len(chunk) > maxPayload {
			chunk = chunk[:maxPayload]
		}
		tc.sendDataPacket(chunk, offset+len(chunk) >= len(data))
		offset += len(chunk)
	}
}

func (tc *tcpConn) sendDataPacket(data []byte, psh bool) {
	ipHdrLen := 20
	tcpHdrLen := 20
	totalLen := ipHdrLen + tcpHdrLen + len(data)

	buf := make([]byte, totalLen)

	buf[0] = 0x45
	buf[1] = 0
	binary.BigEndian.PutUint16(buf[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(buf[4:6], uint16(pseudoRand() & 0xffff))
	buf[6] = 0
	buf[7] = 0
	buf[8] = 64
	buf[9] = 6
	buf[10] = 0
	buf[11] = 0
	copy(buf[12:16], tc.dstIP)
	copy(buf[16:20], tc.srcIP)

	binary.BigEndian.PutUint16(buf[20:22], uint16(tc.dstPort))
	binary.BigEndian.PutUint16(buf[22:24], uint16(tc.srcPort))
	binary.BigEndian.PutUint32(buf[24:28], tc.serverSeq)
	binary.BigEndian.PutUint32(buf[28:32], tc.clientAck)
	buf[32] = byte(tcpHdrLen << 4)
	if psh {
		buf[33] = 0x18
	} else {
		buf[33] = 0x10
	}
	binary.BigEndian.PutUint16(buf[34:36], 65535)
	buf[36] = 0
	buf[37] = 0
	binary.BigEndian.PutUint16(buf[38:40], 0)

	copy(buf[ipHdrLen+tcpHdrLen:], data)

	ipChecksum := checksum(buf[0:ipHdrLen])
	binary.BigEndian.PutUint16(buf[10:12], ipChecksum)

	tcpWithPayload := buf[ipHdrLen:]
	totalTcpLen := tcpHdrLen + len(data)
	tcpChecksum := tcpChecksummedWithData(tcpWithPayload, totalTcpLen, tc.dstIP, tc.srcIP)
	binary.BigEndian.PutUint16(buf[36:38], tcpChecksum)

	tc.serverSeq += uint32(len(data))

	tc.writeTUN(buf)
}

func (tc *tcpConn) sendACK() {
	ipHdrLen := 20
	tcpHdrLen := 20
	totalLen := ipHdrLen + tcpHdrLen

	buf := make([]byte, totalLen)

	buf[0] = 0x45
	buf[1] = 0
	binary.BigEndian.PutUint16(buf[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(buf[4:6], 0)
	buf[6] = 0
	buf[7] = 0
	buf[8] = 64
	buf[9] = 6
	buf[10] = 0
	buf[11] = 0
	copy(buf[12:16], tc.dstIP)
	copy(buf[16:20], tc.srcIP)

	binary.BigEndian.PutUint16(buf[20:22], uint16(tc.dstPort))
	binary.BigEndian.PutUint16(buf[22:24], uint16(tc.srcPort))
	binary.BigEndian.PutUint32(buf[24:28], tc.serverSeq)
	binary.BigEndian.PutUint32(buf[28:32], tc.clientAck)
	buf[32] = byte(tcpHdrLen << 4)
	buf[33] = 0x10
	binary.BigEndian.PutUint16(buf[34:36], 65535)
	buf[36] = 0
	buf[37] = 0
	binary.BigEndian.PutUint16(buf[38:40], 0)

	ipChecksum := checksum(buf[0:ipHdrLen])
	binary.BigEndian.PutUint16(buf[10:12], ipChecksum)

	tcpChecksum := tcpChecksummed(buf[ipHdrLen:tcpHdrLen], tc.dstIP, tc.srcIP)
	binary.BigEndian.PutUint16(buf[36:38], tcpChecksum)

	tc.writeTUN(buf)
}

func (tc *tcpConn) sendRST() {
	ipHdrLen := 20
	tcpHdrLen := 20
	totalLen := ipHdrLen + tcpHdrLen

	buf := make([]byte, totalLen)

	buf[0] = 0x45
	buf[1] = 0
	binary.BigEndian.PutUint16(buf[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(buf[4:6], 0)
	buf[6] = 0
	buf[7] = 0
	buf[8] = 64
	buf[9] = 6
	buf[10] = 0
	buf[11] = 0
	copy(buf[12:16], tc.dstIP)
	copy(buf[16:20], tc.srcIP)

	binary.BigEndian.PutUint16(buf[20:22], uint16(tc.dstPort))
	binary.BigEndian.PutUint16(buf[22:24], uint16(tc.srcPort))
	binary.BigEndian.PutUint32(buf[24:28], tc.serverSeq)
	binary.BigEndian.PutUint32(buf[28:32], tc.clientAck)
	buf[32] = byte(tcpHdrLen << 4)
	buf[33] = 0x14
	binary.BigEndian.PutUint16(buf[34:36], 0)
	buf[36] = 0
	buf[37] = 0
	binary.BigEndian.PutUint16(buf[38:40], 0)

	ipChecksum := checksum(buf[0:ipHdrLen])
	binary.BigEndian.PutUint16(buf[10:12], ipChecksum)

	tcpChecksum := tcpChecksummed(buf[ipHdrLen:tcpHdrLen], tc.dstIP, tc.srcIP)
	binary.BigEndian.PutUint16(buf[36:38], tcpChecksum)

	tc.writeTUN(buf)
}

func (tc *tcpConn) sendFIN() {
	ipHdrLen := 20
	tcpHdrLen := 20
	totalLen := ipHdrLen + tcpHdrLen

	buf := make([]byte, totalLen)

	buf[0] = 0x45
	buf[1] = 0
	binary.BigEndian.PutUint16(buf[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(buf[4:6], 0)
	buf[6] = 0
	buf[7] = 0
	buf[8] = 64
	buf[9] = 6
	buf[10] = 0
	buf[11] = 0
	copy(buf[12:16], tc.dstIP)
	copy(buf[16:20], tc.srcIP)

	binary.BigEndian.PutUint16(buf[20:22], uint16(tc.dstPort))
	binary.BigEndian.PutUint16(buf[22:24], uint16(tc.srcPort))
	binary.BigEndian.PutUint32(buf[24:28], tc.serverSeq)
	binary.BigEndian.PutUint32(buf[28:32], tc.clientAck)
	buf[32] = byte(tcpHdrLen << 4)
	buf[33] = 0x11
	binary.BigEndian.PutUint16(buf[34:36], 65535)
	buf[36] = 0
	buf[37] = 0
	binary.BigEndian.PutUint16(buf[38:40], 0)

	ipChecksum := checksum(buf[0:ipHdrLen])
	binary.BigEndian.PutUint16(buf[10:12], ipChecksum)

	tcpChecksum := tcpChecksummed(buf[ipHdrLen:tcpHdrLen], tc.dstIP, tc.srcIP)
	binary.BigEndian.PutUint16(buf[36:38], tcpChecksum)

	tc.writeTUN(buf)
}

func (tc *tcpConn) sendFINACK(seq, ack uint32) {
	tc.mu.Lock()
	tc.serverAck = ack
	tc.mu.Unlock()

	ipHdrLen := 20
	tcpHdrLen := 20
	totalLen := ipHdrLen + tcpHdrLen

	buf := make([]byte, totalLen)

	buf[0] = 0x45
	buf[1] = 0
	binary.BigEndian.PutUint16(buf[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(buf[4:6], 0)
	buf[6] = 0
	buf[7] = 0
	buf[8] = 64
	buf[9] = 6
	buf[10] = 0
	buf[11] = 0
	copy(buf[12:16], tc.dstIP)
	copy(buf[16:20], tc.srcIP)

	binary.BigEndian.PutUint16(buf[20:22], uint16(tc.dstPort))
	binary.BigEndian.PutUint16(buf[22:24], uint16(tc.srcPort))
	binary.BigEndian.PutUint32(buf[24:28], tc.serverSeq)
	binary.BigEndian.PutUint32(buf[28:32], tc.clientAck)
	buf[32] = byte(tcpHdrLen << 4)
	buf[33] = 0x19
	binary.BigEndian.PutUint16(buf[34:36], 65535)
	buf[36] = 0
	buf[37] = 0
	binary.BigEndian.PutUint16(buf[38:40], 0)

	ipChecksum := checksum(buf[0:ipHdrLen])
	binary.BigEndian.PutUint16(buf[10:12], ipChecksum)

	tcpChecksum := tcpChecksummed(buf[ipHdrLen:tcpHdrLen], tc.dstIP, tc.srcIP)
	binary.BigEndian.PutUint16(buf[36:38], tcpChecksum)

	tc.writeTUN(buf)
}

func (tc *tcpConn) writeTUN(buf []byte) {
	tc.ctx.mu.Lock()
	defer tc.ctx.mu.Unlock()
	framed := make([]byte, 4+len(buf))
	binary.BigEndian.PutUint32(framed, uint32(len(buf)))
	copy(framed[4:], buf)
	tc.ctx.conn.Write(framed)
}

func (tc *tcpConn) close() {
	close(tc.relayStop)
	tc.mu.Lock()
	defer tc.mu.Unlock()
	if tc.proxyConn != nil {
		tc.proxyConn.Close()
		tc.proxyConn = nil
	}
}

func checksum(data []byte) uint16 {
	var sum uint32
	for i := 0; i < len(data)-1; i += 2 {
		sum += uint32(data[i])<<8 | uint32(data[i+1])
	}
	if len(data)%2 == 1 {
		sum += uint32(data[len(data)-1]) << 8
	}
	for (sum >> 16) > 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

func tcpChecksummed(tcpHdr []byte, srcIP, dstIP []byte) uint16 {
	pseudoLen := 12 + len(tcpHdr)
	pseudo := make([]byte, pseudoLen)
	copy(pseudo[0:4], srcIP)
	copy(pseudo[4:8], dstIP)
	pseudo[8] = 0
	pseudo[9] = 6
	pseudo[10] = byte(len(tcpHdr) >> 8)
	pseudo[11] = byte(len(tcpHdr))
	copy(pseudo[12:], tcpHdr)
	return checksum(pseudo)
}

func tcpChecksummedWithData(data []byte, totalLen int, srcIP, dstIP []byte) uint16 {
	pseudoLen := 12 + totalLen
	pseudo := make([]byte, pseudoLen)
	copy(pseudo[0:4], srcIP)
	copy(pseudo[4:8], dstIP)
	pseudo[8] = 0
	pseudo[9] = 6
	pseudo[10] = byte(totalLen >> 8)
	pseudo[11] = byte(totalLen)
	copy(pseudo[12:], data)
	return checksum(pseudo)
}

var randSeed uint32 = 0x12345678

func pseudoRand() uint32 {
	randSeed = randSeed*1103515245 + 12345
	return randSeed
}

func extractSNIFromBytes(data []byte) string {
	n := len(data)
	if n < 43 || data[0] != 0x16 || data[5] != 0x01 {
		return ""
	}

	pos := 43
	if pos >= n {
		return ""
	}
	sessionIDLen := int(data[pos])
	pos++
	pos += sessionIDLen

	if pos+2 > n {
		return ""
	}
	cipherLen := (int(data[pos]) << 8) | int(data[pos+1])
	pos += 2 + cipherLen

	if pos+1 > n {
		return ""
	}
	compLen := int(data[pos])
	pos += 1 + compLen

	if pos+2 > n {
		return ""
	}
	extLen := (int(data[pos]) << 8) | int(data[pos+1])
	pos += 2
	extEnd := pos + extLen

	for pos+4 <= extEnd && pos+4 <= n {
		extType := (int(data[pos]) << 8) | int(data[pos+1])
		extDataLen := (int(data[pos+2]) << 8) | int(data[pos+3])
		pos += 4
		if extType == 0 {
			if pos+2 > extEnd || pos+2 > n {
				break
			}
			_ = (int(data[pos]) << 8) | int(data[pos+1])
			pos += 2
			if pos+1 > extEnd || pos+1 > n {
				break
			}
			if data[pos] == 0 && pos+2 <= extEnd && pos+2 <= n {
				nameLen := (int(data[pos+1]) << 8) | int(data[pos+2])
				pos += 3
				if pos+nameLen <= extEnd && pos+nameLen <= n {
					return string(data[pos : pos+nameLen])
				}
			}
		}
		pos += extDataLen
	}

	return ""
}

func ipStr(ip []byte) string {
	return fmt.Sprintf("%d.%d.%d.%d", ip[0], ip[1], ip[2], ip[3])
}

func forwardDNS(ctx *deviceCtx, srcIP, dstIP []byte, srcPort, dstPort int, ipData []byte, ipHdrLen int) {
	udpOffset := ipHdrLen
	if len(ipData) < udpOffset+8 {
		return
	}

	udpLen := int(ipData[udpOffset+4])<<8 | int(ipData[udpOffset+5])
	if udpLen < 8 || udpOffset+udpLen > len(ipData) {
		return
	}

	payloadOffset := udpOffset + 8
	payloadLen := udpLen - 8

	dnsSrv := net.JoinHostPort(ipStr(dstIP), "53")
	conn, err := net.Dial("udp", dnsSrv)
	if err != nil {
		return
	}
	defer conn.Close()

	conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(ipData[payloadOffset : payloadOffset+payloadLen]); err != nil {
		return
	}

	respBuf := make([]byte, 1500)
	n, err := conn.Read(respBuf)
	if err != nil || n <= 0 {
		return
	}

	udpRespLen := 8 + n
	ipTotalLen := ipHdrLen + udpRespLen

	out := make([]byte, ipTotalLen)

	out[0] = 0x45
	binary.BigEndian.PutUint16(out[2:4], uint16(ipTotalLen))
	out[8] = 64
	out[9] = 17
	copy(out[12:16], dstIP)
	copy(out[16:20], srcIP)

	binary.BigEndian.PutUint16(out[udpOffset:], 53)
	binary.BigEndian.PutUint16(out[udpOffset+2:], uint16(srcPort))
	binary.BigEndian.PutUint16(out[udpOffset+4:], uint16(udpRespLen))
	out[udpOffset+6] = 0
	out[udpOffset+7] = 0

	copy(out[payloadOffset:], respBuf[:n])

	ipChecksum := checksum(out[0:ipHdrLen])
	binary.BigEndian.PutUint16(out[10:12], ipChecksum)

	writeRawTUN(ctx, out)
}

func writeRawTUN(ctx *deviceCtx, data []byte) {
	ctx.mu.Lock()
	defer ctx.mu.Unlock()
	framed := make([]byte, 4+len(data))
	binary.BigEndian.PutUint32(framed, uint32(len(data)))
	copy(framed[4:], data)
	ctx.conn.Write(framed)
}
