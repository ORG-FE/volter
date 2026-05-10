package dpiengine

import (
	"bytes"
	"io"
	"math/rand/v2"
	"net"
	"strings"
	"syscall"
	"time"
)

func sleepJittered(baseMs, jitterMax int) {
	if baseMs < 0 {
		baseMs = 0
	}
	if jitterMax < 0 {
		jitterMax = 0
	}
	if baseMs == 0 && jitterMax == 0 {
		return
	}
	d := time.Duration(baseMs) * time.Millisecond
	if jitterMax > 0 {
		d += time.Duration(rand.IntN(jitterMax+1)) * time.Millisecond
	}
	time.Sleep(d)
}

func findSplitPosition(data []byte, position string) int {
	if len(data) < 10 {
		return len(data) / 2
	}
	pos := strings.ToLower(strings.TrimSpace(position))
	switch pos {
	case "sni":
		if idx := bytes.Index(data, []byte{0x00, 0x00}); idx > 5 && idx < len(data)-10 {
			return idx + 2
		}
	case "method":
		if idx := bytes.IndexByte(data, ' '); idx > 0 && idx < 20 {
			return idx
		}
	case "host":
		if idx := bytes.Index(data, []byte("Host:")); idx > 0 && idx < len(data)-10 {
			return idx + 5
		}
		if idx := bytes.Index(data, []byte("host:")); idx > 0 && idx < len(data)-10 {
			return idx + 5
		}
	case "random":
		min := len(data) / 4
		max := (len(data) * 3) / 4
		if max > min {
			return min + rand.IntN(max-min)
		}
	}
	return len(data) / 2
}

func writeFakeSNI(conn net.Conn, host string) {
	if host == "" {
		host = "www.google.com"
	}
	fake := []byte{0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01, 0x00}
	fake = append(fake, []byte(host)...)
	_, _ = conn.Write(fake)
}

func setTCPSegmentSize(conn net.Conn, size int) {
	if size <= 0 || size > 65536 {
		return
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		if raw, err := tc.SyscallConn(); err == nil {
			_ = raw.Control(func(fd uintptr) {
				_ = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, syscall.TCP_MAXSEG, size)
			})
		}
	}
}

func sendOOB(conn net.Conn, data []byte) bool {
	if tc, ok := conn.(*net.TCPConn); ok {
		if raw, err := tc.SyscallConn(); err == nil {
			var writeErr error
			_ = raw.Write(func(fd uintptr) bool {
				_, writeErr = syscall.SendmsgN(int(fd), data, []byte{0xFF}, nil, syscall.MSG_OOB)
				return true
			})
			return writeErr == nil
		}
	}
	return false
}

func relayPipe(client, remote net.Conn, opts Options) {
	go func() {
		_, _ = io.Copy(client, remote)
		_ = remote.Close()
	}()

	closeBoth := func() {
		_ = remote.Close()
		_ = client.Close()
	}

	if opts.TCPSegment > 0 {
		setTCPSegmentSize(remote, opts.TCPSegment)
	}

	if opts.FakeSNI {
		writeFakeSNI(remote, opts.FakeSNIHost)
		sleepJittered(5, opts.JitterMaxMs)
	}

	if opts.SplitAfter <= 0 {
		sleepJittered(opts.LeadInMs, opts.JitterMaxMs)
		_, _ = io.Copy(remote, client)
		_ = remote.Close()
		_ = client.Close()
		return
	}

	buf := make([]byte, 65536)
	n, err := client.Read(buf)
	if err != nil || n == 0 {
		closeBoth()
		return
	}

	data := buf[:n]
	
	s1 := opts.SplitAfter
	if opts.SplitPosition != "" {
		s1 = findSplitPosition(data, opts.SplitPosition)
	}
	if s1 > n {
		s1 = n
	}
	if s1 < 1 {
		s1 = 1
	}

	segments := [][]byte{data[:s1]}
	remaining := data[s1:]
	
	if opts.SplitAfter2 > 0 && opts.SplitAfter2 < n && opts.SplitAfter2 > s1 {
		s2 := opts.SplitAfter2 - s1
		if s2 < len(remaining) {
			segments = append(segments, remaining[:s2])
			remaining = remaining[s2:]
		}
	}
	
	if opts.MultiSplit > 0 && len(remaining) > opts.MultiSplit {
		chunkSize := len(remaining) / (opts.MultiSplit + 1)
		if chunkSize < 1 {
			chunkSize = 1
		}
		for i := 0; i < opts.MultiSplit && len(remaining) > 0; i++ {
			if chunkSize > len(remaining) {
				chunkSize = len(remaining)
			}
			segments = append(segments, remaining[:chunkSize])
			remaining = remaining[chunkSize:]
		}
	}
	
	if len(remaining) > 0 {
		segments = append(segments, remaining)
	}

	sleepJittered(opts.LeadInMs, opts.JitterMaxMs)

	gap1 := opts.TTLMillis
	gap2 := opts.TTL2Millis
	if gap2 <= 0 {
		gap2 = gap1
	}
	if opts.AutoTTL && gap1 > 0 {
		gap1 = gap1 / 2
		gap2 = gap2 / 2
	}

	writeSeg := func(b []byte, useOOB bool) bool {
		if len(b) == 0 {
			return true
		}
		if useOOB && opts.OOBData && len(b) > 1 {
			if sendOOB(remote, b[:1]) {
				_, werr := remote.Write(b[1:])
				return werr == nil
			}
		}
		_, werr := remote.Write(b)
		return werr == nil
	}

	if opts.Disorder && len(segments) >= 2 {
		for i := len(segments) - 1; i >= 0; i-- {
			if !writeSeg(segments[i], i == 0) {
				closeBoth()
				return
			}
			if i > 0 {
				if i == 1 {
					sleepJittered(gap2, opts.JitterMaxMs)
				} else {
					sleepJittered(gap1, opts.JitterMaxMs)
				}
			}
		}
	} else {
		for i, seg := range segments {
			if !writeSeg(seg, i == 0) {
				closeBoth()
				return
			}
			if i < len(segments)-1 {
				if i == 0 {
					sleepJittered(gap1, opts.JitterMaxMs)
				} else {
					sleepJittered(gap2, opts.JitterMaxMs)
				}
			}
		}
	}

	_, _ = io.Copy(remote, client)
	_ = remote.Close()
	_ = client.Close()
}
