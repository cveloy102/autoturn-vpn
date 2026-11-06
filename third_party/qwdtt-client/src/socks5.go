package main

import (
	"context"
	"golang.zx2c4.com/wireguard/tun/netstack"
	"log"
)

// Adapt WDTT Plus's authenticated TCP + UDP server to qWDTT's transport API.
func runSocks5Server(ctx context.Context, addr string, tnet *netstack.Net, auth bool, user, pass string) error {
	return runAutoturnSocksServer(ctx, addr, tnet, auth, user, pass, true, "", func(bound string) {
		log.Printf("[SOCKS] listening %s", bound)
	})
}
