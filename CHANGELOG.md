# Changelog

## 1.3.1 — 2026-09-12

Accepted HaloCore baseline after Halo 1.20.1 Fabric multiplayer validation.

- Add an optional `ClientRuntime` missing-definition warning callback for host-localized feedback.
  Warnings are immediate, throttled across entities for 30 seconds, and reset with world/session state.
  Missing resources continue to preserve ownership and recover on reload.
- Keep definition schema 1.0.10 and the existing anchor API v2 unchanged.

## 1.3.0

Initial extraction from Halo `1.20.1-fabric` at `b30dad7` (including its preceding local changes).
The functional version and JSON schema 1.0.10 are unchanged.

- Plain Java 17 build, independent fixtures/tests, no Minecraft/Fabric/Loom/GPU dependency.
- Separate server authority, client presentation, local ownership and connection mode services.
- Source-scoped atomic definition reloads; neutral frame, body-pose and ordered geometry contracts.
- Retained damping/orientation, transition curves, hierarchical animation, ring/billboard geometry,
  and render-scoped anchor API v2 behavior.
- Explicit time, storage, message, diagnostics and configuration snapshot boundaries.

New adapters should use the documented contracts in README. Retained implementation packages are
not an additional public compatibility promise. The externally documented anchor API v2 remains stable.
