package main

import (
	"context"
	"encoding/binary"
	"encoding/json"
	"github.com/xtls/libxray/xray"
	"io"
	"net"
	"strconv"
	"testing"
	"time"
)

func TestXrayQwdttAuthenticatedTCPAndUDP(t *testing.T) {
	backend, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	proxy := &socks5Server{dialer: systemSocksDialer{}, authEnabled: true, username: "autoturn", password: "test-session-only", udpEnabled: true}
	go func() {
		for {
			client, err := backend.Accept()
			if err != nil {
				return
			}
			go func() { defer client.Close(); _ = proxy.serveClient(ctx, client) }()
		}
	}()
	reserved, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := reserved.Addr().(*net.TCPAddr).Port
	reserved.Close()
	cfg := map[string]any{
		"log":      map[string]any{"loglevel": "error"},
		"inbounds": []any{map[string]any{"tag": "tun-in", "listen": "127.0.0.1", "port": port, "protocol": "socks", "settings": map[string]any{"auth": "noauth", "udp": true}}},
		"outbounds": []any{
			map[string]any{"tag": "qwdtt-out", "protocol": "socks", "settings": map[string]any{"servers": []any{map[string]any{"address": "127.0.0.1", "port": backend.Addr().(*net.TCPAddr).Port, "users": []any{map[string]any{"user": "autoturn", "pass": "test-session-only"}}}}}},
			map[string]any{"tag": "dns-out", "protocol": "dns", "settings": map[string]any{"rules": []any{map[string]any{"action": "return", "qType": 28, "rCode": 0}}}},
		},
		"dns":     map[string]any{"servers": []string{"https://1.1.1.1/dns-query"}, "queryStrategy": "UseIPv4"},
		"routing": map[string]any{"rules": []any{map[string]any{"type": "field", "inboundTag": []string{"tun-in"}, "port": "53", "outboundTag": "dns-out"}}},
	}
	raw, _ := json.Marshal(cfg)
	if err := xray.TestXray(string(raw)); err != nil {
		t.Fatal(err)
	}
	if err := xray.RunXray(string(raw)); err != nil {
		t.Fatal(err)
	}
	defer xray.StopXray()
	tcpEcho := startTCPEchoServer(t)
	defer tcpEcho.Close()
	connect := func(command byte, targetPort uint16) net.Conn {
		client, err := net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", fmtPort(port)), time.Second)
		if err != nil {
			t.Fatal(err)
		}
		client.SetDeadline(time.Now().Add(5 * time.Second))
		client.Write([]byte{5, 1, 0})
		assertReadBytes(t, client, []byte{5, 0})
		request := []byte{5, command, 0, 1, 127, 0, 0, 1, 0, 0}
		binary.BigEndian.PutUint16(request[8:], targetPort)
		if command == 3 {
			request[4] = 0
			request[7] = 0
		}
		client.Write(request)
		return client
	}
	client := connect(1, uint16(tcpEcho.Addr().(*net.TCPAddr).Port))
	defer client.Close()
	reply := make([]byte, 10)
	if _, err := io.ReadFull(client, reply); err != nil || reply[1] != 0 {
		t.Fatalf("TCP reply %v %v", reply, err)
	}
	client.Write([]byte("xray-through-qwdtt-tcp"))
	assertReadBytes(t, client, []byte("xray-through-qwdtt-tcp"))
	udpEcho := startUDPEchoServer(t)
	defer udpEcho.Close()
	control := connect(3, 0)
	defer control.Close()
	if _, err := io.ReadFull(control, reply); err != nil || reply[1] != 0 {
		t.Fatalf("UDP reply %v %v", reply, err)
	}
	relay := &net.UDPAddr{IP: net.IP(reply[4:8]), Port: int(binary.BigEndian.Uint16(reply[8:10]))}
	udp, err := net.DialUDP("udp4", nil, relay)
	if err != nil {
		t.Fatal(err)
	}
	defer udp.Close()
	udp.SetDeadline(time.Now().Add(5 * time.Second))
	packet, err := buildSocksUDPDatagram(udpEcho.LocalAddr().(*net.UDPAddr), []byte("xray-through-qwdtt-udp"))
	if err != nil {
		t.Fatal(err)
	}
	udp.Write(packet)
	response := make([]byte, 2048)
	n, err := udp.Read(response)
	if err != nil {
		t.Fatal(err)
	}
	_, payload, err := parseSocksUDPDatagram(response[:n])
	if err != nil || string(payload) != "xray-through-qwdtt-udp" {
		t.Fatalf("UDP payload %q %v", payload, err)
	}
}

func fmtPort(port int) string { return strconv.Itoa(port) }
