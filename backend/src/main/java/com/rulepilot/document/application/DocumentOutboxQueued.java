package com.rulepilot.document.application;

/** Payload-free in-process hint that a document outbox row committed. The outbox remains the source of truth. */
record DocumentOutboxQueued() {}
