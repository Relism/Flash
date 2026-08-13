# HTTP performance baselines

These numbers are regression controls, not cross-machine promises. They were measured on
2026-08-13 under Linux 6.12/KVM, six exposed cores of an AMD Ryzen 7 1700X, Temurin 21.0.11 and
JMH 1.37. CI uses short independent forks for allocation and sample latency so the sampling
harness does not contaminate `gc.alloc.rate.norm`.

## Gated hot paths

| Benchmark | B/op | p50 ns | p99 ns | p999 ns | CI p99 ceiling ns |
|---|---:|---:|---:|---:|---:|
| h1 parse and route | 0.022 | 540 | 33,472 | 60,822 | 45,000 |
| h2 pooled stream lifecycle | 0.010 | 530 | 2,138 | 37,724 | 2,900 |
| h2 response encoding | 0.004 | 210 | 993 | 14,626 | 1,350 |
| HPACK browser-request decode | 0.015 | 730 | 5,245 | 27,577 | 7,100 |
| HPACK typical-response encode | 0.003 | 180 | 620 | 12,025 | 850 |
| frame read/validate/discard | 0.006 | 70 | 1,999 | 90,508 | 2,700 |

The sub-byte allocation values occur with no collection and are JMH/GC-profiler rate
normalization noise. The CI allocation ceiling is 0.05 B/op. A benchmark exceeding it fails; a
baseline or ceiling change requires an explicit edit and justification here.

The table records the higher percentile observed across three consecutive controlled runs; this is
important because short sample-mode runs on the shared KVM host showed visible scheduler noise.
The p999 values expose those tails but are recorded rather than gated. The p99 ceilings are the
worst observed p99 plus about 35% headroom.

## HTTP/1 historical comparison

The plan required a pre-Phase-1 number, but no benchmark was committed at that point. Phase 17
reconstructed the current `RequestPipelineBenchmark.parseAndRoute` fixture against Phase 0 commit
`db6e4a4` in a detached worktree and ran both revisions on the same host and JVM:

| Revision | ns/op | B/op |
|---|---:|---:|
| Phase 0 (`db6e4a4`) | 976.195 ± 45.924 | 224.007 |
| Phase 17 | 1,024.602 ± 50.744 | 0.007 |

The hardened parser's mean is 5.0% higher and removes effectively all 224 B/op. The 99.9%
confidence intervals overlap (`930.271–1,022.120` ns for Phase 0 and `973.858–1,075.345` ns for
Phase 17), so this run does not establish a statistically significant latency regression. This is
an honest reconstruction, not a claim that an absent historical run existed. Phase 17 recovered
about 4.5% by having `RequestParser` populate `Http1HeaderMap`'s zero-copy index during the same
validated header pass instead of rescanning every line; all security checks remain in that path.
