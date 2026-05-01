package routeorch

import (
	"testing"
	"time"
)

func TestStunGatherBudget(t *testing.T) {
	if StunGatherBudget() != 12*time.Second {
		t.Fatalf("%v", StunGatherBudget())
	}
}
