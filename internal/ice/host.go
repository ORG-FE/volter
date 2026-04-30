package ice

import (
	"net"
)

func InterfaceIPs() ([]net.IP, error) {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	var out []net.IP
	for _, iface := range ifs {
		if iface.Flags&net.FlagUp == 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			switch v := a.(type) {
			case *net.IPNet:
				ip := v.IP.To16()
				if ip == nil || ip.IsLoopback() || ip.IsUnspecified() {
					continue
				}
				out = append(out, ip)
			}
		}
	}
	return out, nil
}

func IPOnLocalMachine(ip net.IP, locals []net.IP) bool {
	if ip == nil {
		return false
	}
	ip = ip.To16()
	for _, x := range locals {
		if x.Equal(ip) {
			return true
		}
	}
	return false
}
