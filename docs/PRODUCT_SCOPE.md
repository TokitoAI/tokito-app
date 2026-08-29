# Product scope

## Problem

Tokito knows the expected digital design but cannot directly observe the board
on an engineer's desk. Bench debugging becomes manual reconciliation between a
schematic, layout, BOM, datasheets, visual inspection, and spoken measurements.

## Outcome

The physical runtime gives a Tokito reasoning client bounded tools for observing
and guiding bench work. It converts phone sensors and engineer interaction into
structured observations that can be compared with the design graph.

The first complete workflow is:

1. establish a live physical session for one design and board;
2. register the board view against known design context;
3. execute an explicit tool request against a bounded region;
4. return a typed observation with evidence and confidence;
5. ask for and parse a spoken measurement when visual evidence is insufficient;
6. guide the engineer through TTS or a visual target;
7. verify a requested change without silently modifying the design.

## In scope

- board registration and stable coordinate mapping;
- component localization, presence, package/orientation inspection;
- LED state and blink-rate observation;
- event-driven ROI inference;
- wake word, local STT, measurement extraction, local TTS;
- structured observation and evidence envelopes;
- physical tool runtime, cancellation, deadlines, and capability discovery;
- Android-native packaging and hardware acceleration;
- Python training/export/evaluation tooling;
- on-device performance, thermal, privacy, and accuracy evaluation.

## Explicit non-goals

- replacing Tokito's desktop reasoning agent;
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
The runtime must remain useful without network access. Gloves, microscope feeds,
thermal cameras, external instruments, and automated fixture control are future
capabilities, not assumptions in the initial contract.

## Success definition

A release is successful only when a held-out physical-board evaluation proves
that the runtime can register a board, locate a requested supported component,
read supported LED states, capture a spoken electrical measurement, and return
traceable observations within the latency and thermal gates in the Technical
Bible. A polished demo without held-out device evidence is not completion.
