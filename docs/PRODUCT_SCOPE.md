# Product scope

## Problem

An engineer looking at a physical board needs both hands, visual context, and
fast answers. Existing workflows force them to alternate among a camera,
schematic, search, manual visual inspection, and instruments. They should be
able to place a PCB under an iQOO phone and receive private, offline visual and
spoken assistance even when no Tokito project is connected.

## Outcome

The product is a standalone camera-first Android AR assistant. It discovers and
tracks the board, reasons locally, converses locally, invokes bounded physical
tools, and renders registered visual guidance. Optional Tokito design context
adds exact references and expected-state comparison but is not required.

The first complete workflow is:

1. open the standalone application with networking disabled;
2. place a PCB within the camera workspace and detect its board boundary;
3. stabilize and register the board while the phone or board moves;
4. accept a spoken or touch request through the local dialogue orchestrator;
5. execute the required bounded visual or measurement tool;
6. return evidence-backed observations and registered AR/spoken guidance;
7. optionally bind a Tokito design package for exact reference mapping;
8. verify a requested change without silently modifying any design.

## In scope

- board registration and stable coordinate mapping;
- component localization, presence, package/orientation inspection;
- LED state and blink-rate observation;
- event-driven ROI inference;
- standalone board discovery and camera-first AR presentation;
- local text LLM orchestration and bounded local VLM reasoning;
- wake word, local English STT, measurement extraction, local English TTS;
- structured observation and evidence envelopes;
- physical tool runtime, cancellation, deadlines, and capability discovery;
- Android-native packaging and hardware acceleration;
- Python training/export/evaluation tooling;
- on-device performance, thermal, privacy, and accuracy evaluation.

## Explicit non-goals

- requiring Tokito, Companion, Cloud, or an Internet connection for baseline
  operation;
- editing schematics, PCB layout, BOM, or project structure;
- turning Companion into a mobile CAD editor;
- streaming continuous camera/audio to Tokito Cloud;
- autonomous probing, power control, or instrument actuation in the first
  release;
- universal recognition of every PCB/package/component without a declared
  capability and validated package family;
- using a single large VLM for every frame;
- hiding uncertainty behind natural-language answers.

## Users and operating conditions

The primary user is an electronics engineer at a bench, holding or mounting an
iQOO Android phone above a board under ordinary indoor lighting and bench noise.
The application must remain fully useful without network access or a paired
Tokito installation. Gloves, microscope feeds,
thermal cameras, external instruments, and automated fixture control are future
capabilities, not assumptions in the initial contract.

## Success definition

A release is successful only when a fresh install with networking disabled can
discover and register a board, sustain the AR preview, conduct a local spoken
interaction, locate a requested supported component, read supported LED states,
capture a spoken electrical measurement, and return traceable observations
within the latency and thermal gates. A polished demo without held-out device
evidence is not completion.
