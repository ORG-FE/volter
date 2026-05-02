package dpiengine

import (
	"encoding/binary"
	"io"
	"net"
	"strconv"
	"time"

	"dev.c0redev.volter/internal/clientlog"
)

func socksReplyErr(c net.Conn, rep byte) {
	_, _ = c.Write([]byte{5, rep, 0, 1, 0, 0, 0, 0, 0, 0})
}

func socksReplyOK(c net.Conn) {
	_, _ = c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
}

func handleSocksConn(client net.Conn, opts Options) {
	defer client.Close()

	buf := make([]byte, 257)
	if _, err := io.ReadFull(client, buf[:2]); err != nil {
		return
	}
	if buf[0] != 5 {
		return
	}
	nmethods := int(buf[1])
	if nmethods > 0 {
		if _, err := io.ReadFull(client, buf[:nmethods]); err != nil {
			return
		}
	}
	if _, err := client.Write([]byte{5, 0}); err != nil {
		return
	}

	if _, err := io.ReadFull(client, buf[:4]); err != nil {
		return
	}
	if buf[0] != 5 {
		return
	}
	cmd := buf[1]
	if cmd != 1 {
		socksReplyErr(client, 7)
		return
	}
	atyp := buf[3]
	var host string
	var port uint16
	switch atyp {
	case 1:
		if _, err := io.ReadFull(client, buf[:6]); err != nil {
			return
		}
		host = net.IP(buf[:4]).String()
		port = binary.BigEndian.Uint16(buf[4:6])
	case 3:
		if _, err := io.ReadFull(client, buf[:1]); err != nil {
			return
		}
		n := int(buf[0])
		if _, err := io.ReadFull(client, buf[:n+2]); err != nil {
			return
		}
		host = string(buf[:n])
		port = binary.BigEndian.Uint16(buf[n : n+2])
	case 4:
		if _, err := io.ReadFull(client, buf[:18]); err != nil {
			return
		}
		host = net.IP(buf[:16]).String()
		port = binary.BigEndian.Uint16(buf[16:18])
	default:
		socksReplyErr(client, 8)
		return
	}

	ip := net.ParseIP(host)
	if ip == nil {
		ips, err := net.LookupIP(host)
		if err != nil || len(ips) == 0 {
			socksReplyErr(client, 4)
			return
		}
		for _, a := range ips {
			if a.To4() != nil {
				ip = a
				break
			}
		}
		if ip == nil {
			ip = ips[0]
		}
	}

	target := net.JoinHostPort(ip.String(), strconv.Itoa(int(port)))
	d := net.Dialer{Timeout: 15 * time.Second}
	remote, err := d.Dial("tcp", target)
	if err != nil {
		clientlog.Err("dpiengine: dial %s: %v", target, err)
		socksReplyErr(client, 1)
		return
	}

	socksReplyOK(client)
	relayPipe(client, remote, opts)
}
