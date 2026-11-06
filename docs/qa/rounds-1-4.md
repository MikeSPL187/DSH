# QA rounds 1–4: implementation and verification

Baseline: PR #2, `12871835ad24fae08ce15a95c110ce74bff6b738`, APK 1.1.4 (114), published rootfs 2.0.2.
The original reports tested different revisions. An old Fixed or Still verdict is not evidence about the final APK.

## Changes in this iteration

- Monetary model-usage estimates replace token counts in expense fields. CNY tariff snapshot verified against https://api-docs.deepseek.com/zh-cn/quick_start/pricing on 2026-11-06: Flash input/cache/output 1/0.02/4 CNY per million off-peak, twice those prices at peak; Pro 4.5/0.15/13.5, twice at peak. Only official API-key route and explicitly documented model aliases are priced. A range avoids inventing provider billing timestamps or holiday rules. Search/fetch and external requests excluded. Unknown categories/routes/history stay unpriced; partial coverage is labelled; currencies never combined.
- Ledger retains old token-only records and persists price snapshots. Replay deduplicates by host/session/turn/step/attempt. Local calendar boundaries, cache costs, retry charges and migration have regression tests.
- Session titles retain the complete first nonblank line. UI owns visual ellipsis.
- ModelProviders no longer reports success when Android Keystore saving fails. Failed encryption preserves previous ciphertext; a staged new remote credential is removed on local failure.
- Native Save test reads the value back from actual Android Keystore. New native onboarding test physically taps Continue while the IME is open.
- Native QA covers Porcelain and System day/night as well as Graphite, and API 35 in addition to 30/33. Captures revision, gfxinfo and memory.

## Verification rules

A passing unit test establishes logic only; a controlled UI callback is not a real provider test. Native tests run one capped KVM AVD at a time (3072 MB, 3 cores; stop below 1 GiB MemAvailable). Live DeepSeek budget <= 1 CNY; no provider secret is configured in this environment. Physical-device performance is unavailable. Never expose secrets in evidence.

The table below tracks coverage, not a claim that every defect is closed. `Existing` identifies code and tests already on the baseline. Final CI links and limitations are recorded after execution.

| ID | Verification / remaining acceptance |
|---|---|
| F001 | Existing Compose names test; native screen-wide accessibility audit required |
| F002 | About contains source revision/version; candidate remains a PR until release |
| F003 | Exact validation snapshot; live provider validation requires test secret |
| F004 | Native Save Show/Hide a11y test; onboarding Compose semantics test |
| F005 | Native production onboarding Skip; repeat with keyboard visible |
| F006 | Backup restore/cancel/corrupt archive scenario required |
| F007 | Existing Details Back handler; force installer error for native verification |
| F008 | Native rootfs install and Host startup |
| F009 | Check label is Start; installation failure-path verification |
| F010 | Existing local Host error vs network tests |
| F011 | Host startup/restart verified; user proxy scenario required |
| F012 | MCP validation tests; real MCP connect/restart scenario required |
| F013 | Address hint/validation tests |
| F015 | No token suffix; legacy migration tests |
| F016 | MCP state/fingerprint tests; real MCP lifecycle acceptance |
| F017 | Explicit two-tap delete; retest native |
| F018 | Native cold startup; stop/start/restart acceptance |
| F019 | By design: brand names with Light/Dark descriptions |
| F020 | By design: locale persists |
| F021 | Native two RU/EN cycles retain Appearance |
| F022 | Installed version comparison tests; About on final APK |
| F023 | Preserve inventory on failed polls; loading/host reconnection audit |
| F024 | CI uses one capped KVM emulator, watchdog and cleanup |
| F025 | Agent coding cycle with real provider requires secret |
| F026 | Keystore failures no longer succeed; native encrypted readback; Host roundtrip remaining |
| F027 | Native input and terminal commands |
| F028 | Native input and terminal commands |
| N01 | Existing network classification and retry; offline native scenario |
| N02 | Durable partial response/retry tests; live force-stop scenario |
| N03 | Cached UI during Host warmup; measure cold startup |
| N04 | Single-line-break parser test |
| N05 | Composer measured insets; native streaming + IME acceptance |
| N06 | Balance/usage separated, CNY expense estimate |
| N07 | Native real terminal commands |
| N08 | Full title preserved; Unicode/long-token regression |
| N09 | Destructive command approval policy tests; live consent flow |
| N10 | Existing journal/unsent reason and retry tests |
| N11 | Required fields hint, Done disabled; native form acceptance |
| N12 | Neutral toggle names, checked state; native a11y audit |
| N13 | Stable provider/model/reasoning resolution tests; real scheduled run |
| N14 | Versioned tariff estimates, cache/retry/unknown/migration/timezone tests |
| N15 | Native Graphite/Porcelain/System |
| N16 | Typed terminal error outcome; sandbox-denial integration |
| N17 | Native system bars + System night on API30/33/35 |
| N18 | Activity rotation native; chat+IME+font1.3 acceptance remaining |
| N19 | Emulator metrics only; physical-device verdict unavailable |
| N20 | Native Save+Keystore and onboarding Continue under IME |
| N21 | Native Plugins Back returns to Settings |
| N22 | Native RU/EN foreground/navigation cycles |
| N23 | Export one-shot/FileProvider tests; native receiving-app acceptance |
| N24 | Schedule/constraints/DST/dedup tests; genuine scheduled fire remaining |
| R4-N01 | Default terminal enabled; deliberate disable respected; native enable path |
| R4-N02 | Host foreground/busy/idle lifecycle tests; idle native acceptance |
| R4-N03 | Key status refresh; check final APK |
| R4-N04 | Application-owned sender; recreation/process-death journal tests |
| R4-N05 | Native dpkg build/install/upgrade/purge + curl on rootfs2.0.2 |
| R4-N06 | Native cancel frees owned port and preserves unrelated listener |
| R4-N07 | Automation model resolver and creation validation; native scheduled run |
| R4-N08 | Network/auth classification tests; offline native acceptance |

F014 does not exist in the supplied reports. N25 has no reproducible defect definition. LaTeX preservation, orphan sessions and post-Send navigation are additional prose observations to examine, not silently invented closed IDs.

## Delivery

Do not replace published rootfs2.0.2. If agent/image inputs change, prepare a newly versioned candidate. Do not merge the PR or publish an app release as part of running QA. Final APK must identify its exact source and SHA256. Validate clean install and upgrade preserving user data.
