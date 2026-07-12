# ADR-0004: Retain the RPC subprocess boundary

- **Status:** Accepted
- **Date:** 2026-07-13

## Context

Pi Mobile needs authenticated remote access while preserving project isolation. Pi now offers both an embedding SDK and a supported RPC protocol. SDK embedding was reconsidered while reviewing the bridge architecture.

## Decision

Retain this architecture:

**Android → authenticated WebSocket bridge → isolated `pi --mode rpc` subprocess per cwd.**

The bridge remains responsible for authentication, network transport, bridge-owned session discovery, cwd and session locks, reconnect policy, process lifecycle, and only capabilities that Pi RPC does not expose. Current Pi RPC provides entry and tree reads; it does not provide cross-project session listing or direct navigation to an arbitrary tree entry.

SDK embedding is rejected for this roadmap. RPC preserves process isolation and avoids implementing an RPC-compatibility dispatcher in the bridge.

## Consequences

- RPC compatibility is tested and documented as an external contract.
- Each cwd retains an isolated Pi process and control lock.
- Bridge-specific functionality must be kept to actual RPC gaps.
- The extra subprocess cost remains an operational tradeoff.

## Revisit criteria

Reconsider SDK embedding only when measured process-cost evidence shows a meaningful problem and a protocol-conformance suite can prove equivalent behavior, isolation, and compatibility.
