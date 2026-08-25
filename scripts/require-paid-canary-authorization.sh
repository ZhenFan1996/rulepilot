#!/bin/sh

if [ "${RULEPILOT_ALLOW_PAID_CANARY:-false}" != "true" ]; then
	echo "FAIL set RULEPILOT_ALLOW_PAID_CANARY=true to authorize this paid or real-model run" >&2
	exit 2
fi
