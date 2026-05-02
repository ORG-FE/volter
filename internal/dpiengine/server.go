package dpiengine

import (
	"context"
	"net"

	"dev.c0redev.volter/internal/clientlog"
)

func Serve(ctx context.Context, opts Options) (socksListenAddr string, err error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	addr := ln.Addr().String()
	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go handleSocksConn(c, opts)
		}
	}()
	clientlog.OK("dpiengine: SOCKS %s", addr)
	return addr, nil
}
