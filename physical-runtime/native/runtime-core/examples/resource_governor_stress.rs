use std::collections::HashMap;
use std::env;
use std::process::ExitCode;
use std::thread;
use std::time::{Duration, Instant};

use serde::Serialize;
use tokito_physical_runtime_core::{
    BoundedRoiScheduler, CancellationSignal, EnqueueOutcome, ExecutionGuard, ExecutionStage,
    FrameEnvelope, LatestFrame, RegionOfInterest, RuntimeError, StorageQuota, ThermalPolicy,
    ThermalState, WorkKey, WorkPriority, WorkRequest,
};

#[derive(Debug)]
struct Config {
    duration: Duration,
    scheduler_capacity: usize,
    quota_bytes: u64,
    producer_batch: u64,
    consumer_interval: u64,
    sleep: Duration,
    nominal_concurrency: usize,
    constrained_concurrency: usize,
    critical_concurrency: usize,
}

#[derive(Debug, Serialize)]
struct StressReport {
    duration_ms: u128,
    iterations: u64,
    frames_published: u64,
    frames_consumed: u64,
    frames_dropped: u64,
    scheduler_accepted: u64,
    scheduler_coalesced: u64,
    scheduler_dropped: u64,
    quota_rejections: u64,
    stage_checks: u64,
}

fn required_u64(arguments: &HashMap<String, String>, name: &str) -> Result<u64, String> {
    arguments
        .get(name)
        .ok_or_else(|| format!("missing required --{name}"))?
        .parse::<u64>()
        .map_err(|_| format!("--{name} must be an unsigned integer"))
}

fn required_usize(arguments: &HashMap<String, String>, name: &str) -> Result<usize, String> {
    usize::try_from(required_u64(arguments, name)?)
        .map_err(|_| format!("--{name} exceeds this platform's addressable range"))
}

fn parse_config() -> Result<Config, String> {
    let raw: Vec<String> = env::args().skip(1).collect();
    if raw.len() % 2 != 0 {
        return Err("arguments must be supplied as --name value pairs".into());
    }
    let mut arguments = HashMap::new();
    for pair in raw.chunks_exact(2) {
        let name = pair[0]
            .strip_prefix("--")
            .ok_or_else(|| format!("unexpected positional argument: {}", pair[0]))?;
        if name.is_empty() || arguments.insert(name.to_owned(), pair[1].clone()).is_some() {
            return Err(format!("invalid or duplicate argument: {}", pair[0]));
        }
    }

    let duration_seconds = required_u64(&arguments, "duration-seconds")?;
    let scheduler_capacity = required_usize(&arguments, "scheduler-capacity")?;
    let quota_bytes = required_u64(&arguments, "quota-bytes")?;
    let producer_batch = required_u64(&arguments, "producer-batch")?;
    let consumer_interval = required_u64(&arguments, "consumer-interval")?;
    let sleep_millis = required_u64(&arguments, "sleep-millis")?;
    let nominal_concurrency = required_usize(&arguments, "nominal-concurrency")?;
    let constrained_concurrency = required_usize(&arguments, "constrained-concurrency")?;
    let critical_concurrency = required_usize(&arguments, "critical-concurrency")?;

    if duration_seconds == 0
        || scheduler_capacity == 0
        || quota_bytes == 0
        || producer_batch == 0
        || consumer_interval == 0
    {
        return Err("duration, capacities, batch, and interval must be non-zero".into());
    }

    Ok(Config {
        duration: Duration::from_secs(duration_seconds),
        scheduler_capacity,
        quota_bytes,
        producer_batch,
        consumer_interval,
        sleep: Duration::from_millis(sleep_millis),
        nominal_concurrency,
        constrained_concurrency,
        critical_concurrency,
    })
}

fn execute(config: &Config) -> Result<StressReport, String> {
    let started = Instant::now();
    let deadline = started
        .checked_add(config.duration)
        .ok_or_else(|| "duration exceeds the monotonic clock range".to_owned())?;
    let guard = ExecutionGuard::new(deadline, CancellationSignal::default());
    let stages = [
        ExecutionStage::Camera,
        ExecutionStage::Audio,
        ExecutionStage::Preprocessing,
        ExecutionStage::Inference,
    ];
    let thermal = ThermalPolicy::new(HashMap::from([
        (ThermalState::Nominal, config.nominal_concurrency),
        (ThermalState::Constrained, config.constrained_concurrency),
        (ThermalState::Critical, config.critical_concurrency),
    ]))
    .map_err(|error| format!("invalid thermal policy: {error:?}"))?;
    let mut frames = LatestFrame::default();
    let mut scheduler = BoundedRoiScheduler::new(config.scheduler_capacity)
        .map_err(|error| format!("invalid scheduler policy: {error:?}"))?;
    let mut quota = StorageQuota::new(config.quota_bytes)
        .map_err(|error| format!("invalid quota policy: {error:?}"))?;
    let scheduler_key_span = u64::try_from(config.scheduler_capacity)
        .map_err(|_| "scheduler capacity exceeds u64".to_owned())?
        .checked_mul(2)
        .ok_or_else(|| "scheduler key span overflow".to_owned())?;

    let mut report = StressReport {
        duration_ms: 0,
        iterations: 0,
        frames_published: 0,
        frames_consumed: 0,
        frames_dropped: 0,
        scheduler_accepted: 0,
        scheduler_coalesced: 0,
        scheduler_dropped: 0,
        quota_rejections: 0,
        stage_checks: 0,
    };

    'stress: while Instant::now() < deadline {
        for _ in 0..config.producer_batch {
            report.frames_published = report
                .frames_published
                .checked_add(1)
                .ok_or_else(|| "frame counter overflow".to_owned())?;
            frames.publish(FrameEnvelope {
                frame_id: report.frames_published,
                session_generation: 0,
                payload: report.frames_published,
            });

            let now = Instant::now();
            let request = WorkRequest {
                key: WorkKey {
                    session_id: "stress-session".into(),
                    tool_call_id: format!(
                        "stress-call-{}",
                        report.frames_published % scheduler_key_span
                    ),
                },
                session_generation: 0,
                priority: if report.frames_published % 2 == 0 {
                    WorkPriority::Interactive
                } else {
                    WorkPriority::Background
                },
                deadline,
                roi: RegionOfInterest {
                    frame_id: report.frames_published,
                    x: 0.0,
                    y: 0.0,
                    width: 1.0,
                    height: 1.0,
                },
            };
            match scheduler.enqueue(request, now) {
                Ok(EnqueueOutcome::Accepted) => report.scheduler_accepted += 1,
                Ok(EnqueueOutcome::Coalesced) => report.scheduler_coalesced += 1,
                Ok(EnqueueOutcome::DroppedStale) => report.scheduler_dropped += 1,
                Err(RuntimeError::DeadlineExceeded) if now >= deadline => break,
                Err(error) => return Err(format!("scheduler invariant failed: {error:?}")),
            }
        }

        report.iterations = report
            .iterations
            .checked_add(1)
            .ok_or_else(|| "iteration counter overflow".to_owned())?;
        if report.iterations % config.consumer_interval == 0
            && frames.take_for_generation(0).is_some()
        {
            report.frames_consumed += 1;
            let _ = scheduler.next(Instant::now(), 0);
        }

        quota
            .reserve(config.quota_bytes)
            .map_err(|error| format!("quota reserve failed: {error:?}"))?;
        if quota.reserve(1) == Err(RuntimeError::ResourceExhausted) {
            report.quota_rejections += 1;
        } else {
            return Err("quota accepted an over-capacity reservation".into());
        }
        quota
            .release(config.quota_bytes)
            .map_err(|error| format!("quota release failed: {error:?}"))?;

        for stage in stages {
            if Instant::now() >= deadline {
                break 'stress;
            }
            guard
                .enter(stage, Instant::now())
                .map_err(|error| format!("execution guard failed early: {error:?}"))?;
            report.stage_checks += 1;
        }
        for state in [
            ThermalState::Nominal,
            ThermalState::Constrained,
            ThermalState::Critical,
        ] {
            thermal
                .concurrency_for(state)
                .map_err(|error| format!("thermal policy lost a state: {error:?}"))?;
        }
        if !config.sleep.is_zero() {
            thread::sleep(config.sleep);
        }
    }

    report.duration_ms = started.elapsed().as_millis();
    report.frames_dropped = frames.dropped();
    report.scheduler_dropped = scheduler.dropped_stale();
    if report.frames_published == 0
        || report.frames_dropped == 0
        || report.scheduler_dropped == 0
        || report.quota_rejections == 0
        || report.stage_checks == 0
    {
        return Err("stress run did not exercise every bounded rejection path".into());
    }
    Ok(report)
}

fn main() -> ExitCode {
    let result = parse_config().and_then(|config| execute(&config));
    match result {
        Ok(report) => match serde_json::to_string(&report) {
            Ok(json) => {
                println!("{json}");
                ExitCode::SUCCESS
            }
            Err(_) => {
                eprintln!("failed to serialize stress report");
                ExitCode::FAILURE
            }
        },
        Err(error) => {
            eprintln!("{error}");
            ExitCode::FAILURE
        }
    }
}
