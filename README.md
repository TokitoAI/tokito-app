# Tokito

Tokito is one mobile workspace for electronics projects and physical bench
work. It combines Cloud-backed project, schematic, BOM, chat, and suggestion
review with a private, offline-first camera and AR assistant.

## Product flow

- Open a project and choose **Inspect physical board** to carry its versioned
  design context into a bench session.
- Or choose **Start bench session** without an account or project, discover a
  board locally, and attach context later.
- Camera, audio, local reasoning, visual inference, and AR guidance execute on
  the Android device. Raw camera and microphone streams are not uploaded.
- Structured observations may be synchronized only through explicit actions.

## Architecture

Optional [board symbol drafts](docs/BOARD_SYMBOL_DRAFTS.md) use reviewed
app-dataset bindings and explicitly authorized DS-ViRe requests. Generated
symbols remain private drafts; local board inspection works independently.

```text
React Native product shell
  |-- authentication, projects, review, chat, settings
  |-- navigation and physical-session entry
  +-- typed native boundary
        |-- CameraX and registration
        |-- AR presentation
        |-- local voice and measurement parsing
        |-- local LLM/VLM and physical tools
        +-- evidence, capability, thermal and model governance
```

The native boundary transports commands and typed low-rate state. Frames,
audio buffers, tensors, and high-rate tracking data stay native.

Physical session history is local-first, durable, and revisioned. Cloud project
sessions synchronize only structured observations explicitly authorized by the
user; raw camera/audio and evidence bytes never enter realtime sync.

## Workspace

- `apps/mobile` - Expo/React Native TypeScript application shell
- `crates/core` - Cloud contracts, cache, outbox, and realtime journal
- `crates/ffi` - UniFFI bridge for the Cloud core
- `physical-runtime` - history-preserved native runtime during migration
- `docs` - product, API, migration, and architecture decisions

The consolidation is tracked by
[TokitoAI/tokito-app#1](https://github.com/TokitoAI/tokito-app/issues/1).

CI runs on the organization-managed Buildkite `linux` queue; signed Android
releases run only on the isolated `release` queue. Both use the pinned mobile
build image. See [CI and releases](docs/CI_CD.md) for the five required gates,
release signing, and artifact flow. GitHub Actions is not part of the release
path.

Companion history is the repository base and Physical Runtime history is
preserved in `physical-runtime/`. Both former GitHub repositories are archived,
their local checkouts are retired, and `tokito-app` is the only active mobile
source and release repository.

The historical work map and archival rules are documented in
[Former mobile repository retirement](docs/REPOSITORY_RETIREMENT.md).

