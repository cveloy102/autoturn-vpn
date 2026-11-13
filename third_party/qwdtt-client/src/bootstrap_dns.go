package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

// Android supplies physical-network resolvers in a private file. Re-read it for
// every lookup so a Wi-Fi/mobile handoff doesn't pin bootstrap to the old DNS.
// This resolver is not used by the SOCKS netstack's tunneled application DNS.
func networkDNSServers(path string) ([]string, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("network DNS file unavailable: %w", err)
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 4097))
	if err != nil || len(data) > 4096 {
		return nil, fmt.Errorf("invalid network DNS file")
	}
	var servers []string
	for _, value := range strings.Fields(string(data)) {
		addr, err := netip.ParseAddrPort(value)
		if err != nil || addr.Port() != 53 || addr.Addr().IsUnspecified() || addr.Addr().IsLoopback() || addr.Addr().IsMulticast() {
			return nil, fmt.Errorf("invalid physical network DNS address")
		}
		servers = append(servers, addr.String())
	}
	if len(servers) == 0 || len(servers) > 8 {
		return nil, fmt.Errorf("no usable physical network DNS")
	}
	return servers, nil
}

func setupNetworkResolver(path string) {
	var next atomic.Uint64
	dialer := &net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			servers, err := networkDNSServers(path)
			if err != nil {
				return nil, err
			}
			if network != "udp" && network != "tcp" {
				return nil, fmt.Errorf("unsupported DNS transport")
			}
			start := int((next.Add(1) - 1) % uint64(len(servers)))
			for i := range servers {
				conn, err := dialer.DialContext(ctx, network, servers[(start+i)%len(servers)])
				if err == nil {
					return conn, nil
				}
			}
			return nil, fmt.Errorf("physical network DNS unavailable")
		},
	}
}
