//go:build !linux

package main

import (
	"errors"
	"time"

	"dev.c0redev.volter/internal/metrics"
)

func connectTUIViaPkexecProfile(_ string, _ *metrics.SessionRecord, _ time.Time) (stop func(), err error) {
	return nil, errors.New("pkexec profile connect is only supported on Linux")
}
