package main

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"
)

func TestNetworkDNSReloadAndNoPublicFallback(t *testing.T) {
	path := filepath.Join(t.TempDir(), "dns.conf")
	if _, err := networkDNSServers(path); err == nil {
		t.Fatal("missing file must fail closed")
	}
	for _, value := range []string{"", "dns.example:53", "127.0.0.1:53", "0.0.0.0:53", "1.1.1.1:443"} {
		if err := os.WriteFile(path, []byte(value), 0600); err != nil {
			t.Fatal(err)
		}
		if _, err := networkDNSServers(path); err == nil {
			t.Fatalf("accepted %q", value)
		}
	}
	for _, value := range []string{"192.168.1.1:53", "10.0.0.1:53", "[2001:db8::53]:53"} {
		if err := os.WriteFile(path, []byte(value), 0600); err != nil {
			t.Fatal(err)
		}
		servers, err := networkDNSServers(path)
		if err != nil || len(servers) != 1 || servers[0] != value {
			t.Fatalf("network change not reloaded: %v %v", servers, err)
		}
	}
}

func TestApplicationDNSStaysInNetstack(t *testing.T) {
	original := net.DefaultResolver
	defer func() { net.DefaultResolver = original }()
	var outside atomic.Int32
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(context.Context, string, string) (net.Conn, error) {
		outside.Add(1)
		return nil, fmt.Errorf("bootstrap must not resolve application names")
	}}
	device, inside, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.88.0.2")},
		[]netip.Addr{netip.MustParseAddr("10.88.0.1")}, 1280)
	if err != nil {
		t.Fatal(err)
	}
	defer device.Close()
	packets := make(chan []byte, 1)
	go func() {
		buffer := make([]byte, 1500)
		sizes := make([]int, 1)
		n, err := device.Read([][]byte{buffer}, sizes, 0)
		if err == nil && n > 0 {
			packets <- append([]byte(nil), buffer[:sizes[0]]...)
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	_, _ = (netstackSocksDialer{network: inside}).LookupContextHost(ctx, "application.example")
	if outside.Load() != 0 {
		t.Fatal("application DNS escaped through bootstrap resolver")
	}
	select {
	case packet := <-packets:
		if len(packet) < 28 || packet[0]>>4 != 4 || packet[9] != 17 || string(packet[16:20]) != string([]byte{10, 88, 0, 1}) {
			t.Fatal("DNS request did not target the DNS server inside the tunnel")
		}
	case <-time.After(time.Second):
		t.Fatal("no DNS packet inside the tunnel")
	}
}
