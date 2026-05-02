package dpiengine

import (
	"io"
	"math/rand/v2"
	"net"
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

func relayPipe(client, remote net.Conn, opts Options) {
	go func() {
		_, _ = io.Copy(client, remote)
		_ = client.Close()
		_ = remote.Close()
	}()

	closeBoth := func() {
		_ = remote.Close()
		_ = client.Close()
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

	s1 := opts.SplitAfter
	if s1 > n {
		s1 = n
	}
	seg0 := buf[:s1]
	s2abs := opts.SplitAfter2
	three := s2abs > 0 && s2abs < n && s2abs > s1
	var seg1, seg2 []byte
	if three {
		seg1 = buf[s1:s2abs]
		seg2 = buf[s2abs:n]
	} else {
		seg1 = buf[s1:n]
	}

	sleepJittered(opts.LeadInMs, opts.JitterMaxMs)

	gap1 := opts.TTLMillis
	gap2 := opts.TTL2Millis
	if gap2 <= 0 {
		gap2 = gap1
	}

	writeSeg := func(b []byte) bool {
		if len(b) == 0 {
			return true
		}
		_, werr := remote.Write(b)
		return werr == nil
	}

	if !three {
		first, second := seg0, seg1
		if opts.Disorder && len(second) > 0 && len(first) > 0 {
			if !writeSeg(second) {
				closeBoth()
				return
			}
			sleepJittered(gap1, opts.JitterMaxMs)
			if !writeSeg(first) {
				closeBoth()
				return
			}
		} else {
			if !writeSeg(first) {
				closeBoth()
				return
			}
			sleepJittered(gap1, opts.JitterMaxMs)
			if !writeSeg(second) {
				closeBoth()
				return
			}
		}
		_, _ = io.Copy(remote, client)
		_ = remote.Close()
		_ = client.Close()
		return
	}

	a, b, c := seg0, seg1, seg2
	if opts.Disorder {
		if !writeSeg(b) {
			closeBoth()
			return
		}
		sleepJittered(gap1, opts.JitterMaxMs)
		if !writeSeg(a) {
			closeBoth()
			return
		}
		sleepJittered(gap2, opts.JitterMaxMs)
		if !writeSeg(c) {
			closeBoth()
			return
		}
	} else {
		if !writeSeg(a) {
			closeBoth()
			return
		}
		sleepJittered(gap1, opts.JitterMaxMs)
		if !writeSeg(b) {
			closeBoth()
			return
		}
		sleepJittered(gap2, opts.JitterMaxMs)
		if !writeSeg(c) {
			closeBoth()
			return
		}
	}
	_, _ = io.Copy(remote, client)
	_ = remote.Close()
	_ = client.Close()
}
