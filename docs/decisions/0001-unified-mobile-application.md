# ADR 0001: One Tokito mobile application

- Status: Accepted
- Date: 2026-09-28
- Tracking: <https://github.com/TokitoAI/tokito-app/issues/1>

## Context

Companion and Physical were isolated products. That creates duplicate Android
applications, navigation, release ownership, permissions, settings, and user
concepts despite one continuous project-to-bench workflow.

Companion owns the Cloud/auth/review shell and signing lineage. Physical owns
CameraX, AR, audio, model, tool, and evidence runtimes. Reimplementing those
pipelines in React Native or transporting high-rate data through JavaScript
would violate latency, privacy, and lifecycle requirements.

## Decision

Tokito ships as one Android application from `TokitoAI/tokito-app`.

1. Companion history and signing lineage form the migration base.
2. React Native owns navigation, Cloud resources, review, account state,
   ordinary settings, and session history.
3. Native Android owns camera, registration, AR, audio, local inference,
   physical tools, and physical-session lifecycle.
4. A versioned in-process boundary exposes capabilities, commands, bounded
   lifecycle events, observations, and errors. Raw frames, PCM, tensors, and
   high-rate tracking do not cross it.
5. Bench sessions may start without authentication, networking, or a project.
6. Structured observations sync only with explicit authorization. Raw media is
   local and ephemeral by default.
7. There is no second launcher, hidden Physical app, remote-model fallback, or
   mobile CAD editor.

## UX hierarchy

- `Project -> Inspect physical board -> Acquire -> Work -> Review evidence`
- `Start bench session -> Acquire -> Work -> Optionally attach project`

Authentication gates Cloud resources, never local physical capability.
Permissions are requested only when a session needs them.

## Retirement gate

Former repositories remain available until accepted source and history, open
work, releases, docs, issue traceability, upgrade behavior, and rollback
artifacts are preserved; one launcher and pipeline exist; available checks
pass; and exact-device checks are either evidenced or visibly deferred.

Afterward old repositories are archived rather than permanently deleted so
audit history and rollback remain available.

## Consequences

The APK contains Hermes plus native runtimes, so size, startup, ABI, memory,
thermal, and model-delivery budgets are release-blocking. Navigation and process
recreation require an explicit cross-runtime session coordinator.

