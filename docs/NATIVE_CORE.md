# Native runtime core

`native/runtime-core` is the dependency-free native safety core for the future
Android library. It implements no model, camera binding, network service, or
public physical tool. Tool support remains absent until a measured device
profile, model bundle, backend conformance result, calibration policy, and
evidence contract enable it.

The core owns only deterministic mechanisms required by the delivery gates:

- lifecycle transitions that force recalibration after suspend/resume;
- capability gating that fails closed for unadvertised tools;
- versioned tool-call validation and exactly-one terminal outcome ledgers;
- structured observations with linked context, registration, evidence, and provenance;
- caller-configured registration confidence and homography validation;
- bounded, deadline-aware, cancellable ROI work scheduling; and
- latest-frame handoff and caller-configured storage quotas; and
- caller-configured thermal concurrency policy.

It deliberately carries no device name, ABI, model, backend, threshold, queue
capacity, or thermal cutoff. The Android layer must provide those only from the
versioned device profile and accepted benchmark evidence.
