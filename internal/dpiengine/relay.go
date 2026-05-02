package dpiengine

import (
	"io"
	"net"
	"time"
)

func relayPipe(client, remote net.Conn, opts Options) {
	go func() {
		_, _ = io.Copy(client, remote)
		_ = client.Close()
		_ = remote.Close()
	}()

	if opts.SplitAfter <= 0 {
		_, _ = io.Copy(remote, client)
		_ = remote.Close()
		_ = client.Close()
		return
	}

	buf := make([]byte, 65536)
	n, err := client.Read(buf)
	if err != nil || n == 0 {
		_ = remote.Close()
		_ = client.Close()
		return
	}
	split := opts.SplitAfter
	if split > n {
		split = n
	}
	first := buf[:split]
	second := buf[split:n]

	if opts.Disorder && len(second) > 0 && len(first) > 0 {
		if _, err := remote.Write(second); err != nil {
			_ = remote.Close()
			_ = client.Close()
			return
		}
		if opts.TTLMillis > 0 {
			time.Sleep(time.Duration(opts.TTLMillis) * time.Millisecond)
		}
		if _, err := remote.Write(first); err != nil {
			_ = remote.Close()
			_ = client.Close()
			return
		}
	} else {
		if _, err := remote.Write(first); err != nil {
			_ = remote.Close()
			_ = client.Close()
			return
		}
		if opts.TTLMillis > 0 {
			time.Sleep(time.Duration(opts.TTLMillis) * time.Millisecond)
		}
		if len(second) > 0 {
			if _, err := remote.Write(second); err != nil {
				_ = remote.Close()
				_ = client.Close()
				return
			}
		}
	}
	_, _ = io.Copy(remote, client)
	_ = remote.Close()
	_ = client.Close()
}
