# Posting Reliability & Competitive Parity — Orchestrated Plan

> **Goal:** Make esochan trustworthy for posting first, then close media/download gaps that drive competitor churn — without breaking captcha, CF, or drafts.

**Source grades:** competitor complaint map + dual code verification (2026-07-08).  
**Related:** `2026-07-08-competitor-complaint-investigation.md` (pain inventory), visual-refresh plan (orthogonal polish).

**Non-goals:** Compose rewrite, captcha solvers, Play listing strategy, multi-chan re-enable, matching Read Chan gestures 1:1, ads/telemetry.

---

## Operating principles (industry practice)

1. **Correctness before features.** False-success and draft loss ship before filename rename or streaming video.
2. **Small vertical PRs.** One failure mode per PR; each PR leaves the app buildable and postable.
3. **Evidence gates.** Device QA checklist must pass before merging P0 that touches captcha/POST. CI = compile/lint; posting = human/device.
4. **Observe before inventing.** Log shapes on parse miss; do not invent 4chan email-verify fields without a live HAR.
5. **Prefer fail-closed for user-visible success.** Unknown server HTML is an error, never a success.
6. **Do not “optimize” fingerprint paths.** Captcha stays WebView-backed unless dual-path A/B proves OkHttp-only is safe.
7. **Preserve what already works.** Gallery sibling cancel, sequential downloads, hide/unhide, Pass, shared UA — do not regress.
8. **Dependency DAG, not a wishlist.** Later tiers may start design notes early; code lands only when deps merge.

---

## Current truth (frozen findings)

| Finding | Severity | Status |
|---------|----------|--------|
| Unknown post HTML → `sendPost` returns `null` → `PostingService` treats success, **clears draft**, fakes redirect, success notif | **P0 blocker** | Confirmed |
| Captcha WebView does **not** bridge cookies into OkHttp `CookieStore` (CF path does for `cf_clearance` only) | **P0 risk** | Confirmed |
| Captcha ticket `@Volatile` process-memory only | P0 hardening | Confirmed |
| Email field discarded (`allowEmails=false`; POST email sage/empty only) | P0 product gap | Confirmed missing |
| Filename randomize absent on 4chan | P2 feature | Confirmed missing |
| Video = full download then Media3; swipe cancel **already** works | P1 UX | Confirmed |
| Batch DL sequential + per-item continue; no 429/403 backoff | P1 | Confirmed |
| Hide + unhide complete (`showHiddenItems()` hardcoded true) | Done | No work |
| 404 does **not** wipe thread data; weak signal (`autoupdateError` → tab `[X]`) | P2 UX | Confirmed |

---

## Success criteria (program done)

User-facing:

1. Failed/unknown posts **never** show success or clear drafts.
2. Post errors are human-readable (ban / captcha / flood / unexpected body) with optional “copy details.”
3. Captcha + CF flow can complete and POST on WiFi and cellular with shared cookie jar.
4. Captcha ticket survives process death for a documented TTL window (if site still issues tickets).
5. Batch download survives 50+ files without silent full abort; 429/403 backs off.
6. Video failure offers retry / external open; download progress remains clear.
7. Optional upload filename randomize works for 4chan without content mutation.
8. Autoupdate/404 failure is visible in-thread, not only sidebar `[X]`.

Engineering:

- Each tier has automated unit tests where pure (response classification, cookie merge, filename rewrite).
- Device QA checklist green after every P0 merge.
- No Play push until Tier 0–1 green.

---

## Orchestration model

```text
Tier 0  Correctness stop-the-bleed     ──► merge gate A
Tier 1  Posting transport integrity    ──► merge gate B  (can claim "posts honestly")
Tier 2  Captcha resilience             ──► merge gate C  (can claim "posts reliably" after live QA)
Tier 3  Product gaps (email/pass UX)   ──► research gate then implement or WONTFIX
Tier 4  Media & downloads              ──► independent of Tier 3 after Gate B
Tier 5  Reading UX signals             ──► independent after Gate A
```

**Parallelism rules**

| Track | Can start after | Parallel with |
|-------|-----------------|---------------|
| Tier 0 | now | nothing that changes `sendPost` contract until 0 lands |
| Tier 1 | Gate A | Tier 5 design only |
| Tier 2 | Gate A only | Tier 5; Tier 1 too, if `Chan4Captcha.kt` is worktree-isolated — otherwise serialize with PR-1.1 to avoid merge thrash on that file |
| Tier 3 research | now (read-only) | all |
| Tier 3 implement | Gate C + research freeze | Tier 4 |
| Tier 4 | Gate B | Tier 3 implement, Tier 5 |
| Tier 5 | Gate A | Tier 2–4 |

**Owner cadence:** one PR open per hot file cluster (`FourchanModule` / `PostingService` / captcha kt) to avoid merge thrash. Media/download PRs may parallel once Gate B is green.

---

## Tier 0 — Fail-closed posting (stop the lie)

**Why first:** App currently reports success when the server response is unrecognized. That is worse than a hard error.

### PR-0.1 — Classify post responses; never null-as-success

**Files**

- `chans/fourchan/FourchanModule.java` (`doSendPost`)
- `ui/posting/PostingService.java`
- Optionally small helper: `chans/fourchan/FourchanPostResponse.kt` (pure parse)

**Work**

1. Capture real fixtures before writing the classifier: save actual response bodies from live device posts (or existing logs) for success, errmsg, blank, and CF-interstitial cases. Do not hand-author synthetic HTML — that only proves the parser matches its own assumptions, not the live site.
2. Parse response into sealed outcomes: `Success(redirectUrl)`, `ServerError(message)`, `Unexpected(bodySnippet)`, `Network` (propagated exception).
3. `doSendPost` **throws** or returns a typed error for non-success; **never** returns `null` for unknown HTML.
4. Keep legitimate “success with no redirect URL” only if a documented success marker exists without URL (today success is the HTML comment — require it).
5. `PostingService`: clear draft **only** on confirmed success; keep draft on all failures; surface message + “copy details” (body snippet / status).
6. Unit tests: golden HTML fixtures (from step 1) — success comment, errmsg span, blank, CF interstitial fragment, random HTML.

**Done when**

- [ ] Fixtures are captured from real responses, not hand-authored
- [ ] Unit tests cover the four HTML shapes
- [ ] `Unexpected` responses log a local (non-remote) body snippet for diagnosis
- [ ] Device: force airplane mid-post → error, draft retained
- [ ] Device: normal text post still succeeds and clears draft
- [ ] `./gradlew assembleDebug` green

**Risk:** Over-strict parse false-negatives real success if 4chan changes comment format — and unlike today's silent false-positive, this fails in the *opposite* direction (real success reported as failure). Because the product has no remote telemetry (no ACRA, by design), this can't be detected at a distance. Mitigate:
- Log full body (truncated) locally on every `Unexpected` outcome — never remote.
- Treat the first release under this change as a manual soft-launch: before trusting the classifier, re-check a sample of your own `Unexpected`-classified posts against the live thread.
- Keep `Unexpected` visually and textually distinct from `Network`/`ServerError` so a misclassification pattern is recognizable quickly, not lost in a generic "failed" bucket.
- Still fail closed by default; quick-fix path is parse-only (no behavior rollback needed, just a regex/marker update).

---

## Tier 1 — Transport integrity (cookies, headers, errors)

**Depends on:** Gate A (PR-0.1 merged).  
**Why:** Soft-ban / CF recovery is a top competitor complaint; captcha WebView isolation is a confirmed gap.

### PR-1.1 — Cookie bridge: captcha WebView → OkHttp

**Files**

- `chans/fourchan/Chan4Captcha.kt`
- `http/cloudflare/*` (reuse patterns from `CloudflareChecker`)
- `http/client/ExtendedHttpClient.java` / cookie store APIs

**Work**

1. After captcha WebView finishes (including CF interstitial solved *inside* captcha dialog), export relevant cookies from `CookieManager` for `sys.4chan.org` / `.4chan.org` / `.4channel.org`.
2. Merge into OkHttp `CookieStore` (same domain/path rules as CF checker). Do not wipe unrelated cookies.
3. Prefer a single shared helper: `WebViewCookieBridge.syncTo(client, hosts)`.
4. Document one-way vs two-way: at minimum WebView → OkHttp before POST; evaluate OkHttp → WebView only if captcha fetch needs pass cookie (Pass already uses OkHttp session).

**Done when**

- [ ] Log (debug) lists cookie names synced (not values) on captcha complete
- [ ] Device: CF challenge inside captcha dialog → subsequent POST does not immediately re-challenge for same session
- [ ] No cookie loss for `pass_id` / existing CF clearance

### PR-1.2 — Post header parity pass

**Status: opportunistic, non-blocking.** Does not gate Gate B, PR-1.3, or anything downstream — land whenever HAR-comparison time is available.

**Files**

- `FourchanModule.getPostHeaders`
- Compare once to Chrome mobile POST (manual HAR)

**Work**

1. Add missing browser-common headers only where safe: `Accept-Language`, `Sec-Fetch-*` if we can match WebView consistently.
2. Do **not** invent headers that diverge from WebView UA stack.
3. Record HAR diff in PR description; ship only deltas with rationale.

**Done when**

- [ ] Written header matrix in PR body
- [ ] Post still works WiFi + cellular smoke

### PR-1.3 — Error taxonomy for known errmsg strings

**Files**

- `FourchanModule` / post response helper
- strings.xml

**Work**

1. Map common errmsg substrings: banned, captcha, flood, duplicate, file too large, verify email, etc. → stable string resources.
2. No auto-retry on ban/captcha/flood. Network-only retry remains user-driven or explicit single retry later.

**Done when**

- [ ] Fixture tests for mapped strings
- [ ] Unknown errmsg still shows raw server text (fail closed, not silent)

**Gate B:** PR-0.1 + PR-1.1 merged and device post checklist green.

---

## Tier 2 — Captcha resilience

**Depends on:** Gate A only. PR-2.1 (ticket persistence) has no logical dependency on PR-1.1 (cookie bridge) — they're separate mechanisms (in-memory ticket storage vs. cookie jar merging). Sequencing 1.1 before 2.1 in the suggested merge order below is a merge-thrash avoidance choice, since both touch `Chan4Captcha.kt`, not a correctness requirement. A second owner can build PR-2.1 in parallel with PR-1.1 from Gate A, isolated in a worktree.

### PR-2.1 — Ticket persistence with TTL

**Files**

- `Chan4Captcha.kt`, `Chan4CaptchaData.kt`
- `SecurePreferences` or existing prefs pattern

**Work**

1. Persist ticket + stored-at timestamp; clear on explicit invalidation / false ticket / parse error that says so.
2. TTL: start conservative (e.g. session-length or observed site behavior from logs); document assumption.
3. Never persist captcha challenge/response solutions long-term (one-shot only).

### PR-2.2 — Parse resilience + failure telemetry

**Files**

- `Chan4CaptchaData.kt`

**Work**

1. On unknown JSON shape: user-visible error + redacted key list log (already partially logs raw take(500); formalize).
2. Keep sealed class exhaustiveness; add fields only when live API shows them.
3. No silent fallback to empty solve.

### PR-2.3 — Manual captcha QA harness (checklist + optional debug entry)

**Work**

1. Codify checklist in this plan (below) as release gate.
2. Optional debug-only “dump last captcha parse” — only if it does not ship enabled by default.

**Gate C:** Live slider + image-selection + noop/pass paths exercised on device WiFi **and** cellular; ticket survives process kill mid-flow.

---

## Tier 3 — Email verify / Pass product decision

**Research track (read-only, starts immediately)**

### R-3.1 — Live email-verify research

1. Capture current 4chan web flow (HAR): URLs, form fields, cookies, success markers.
2. If flow is dead or Pass-only in practice → product WONTFIX with in-app messaging.
3. If live → freeze minimal spec: settings entry, cookie storage, post gate when server demands verify.

### PR-3.1a — Implement email verify (only if R-3.1 says yes)

- Controlled email path separate from sage.
- Never reuse sage field for verify.
- Surface server “verify” messages from Tier 1 taxonomy.

### PR-3.1b — Explicit Pass-only / range-ban messaging (if WONTFIX)

- Settings copy: Pass is the supported path for range bans.
- Post form / error path points users there when server mentions ban/verify.

**Gate:** Research freeze written into this plan’s appendix before any 3.1a code.

---

## Tier 4 — Media & downloads

**Depends on:** Gate B (so we do not thrash network stack during posting fixes). May parallel Tier 3.

### PR-4.1 — Video error recovery

**Files:** `GalleryActivity.java`

**Work**

1. On ExoPlayer error: toast + retry action + open external.
2. Keep existing sibling cancel (do not “re-implement”).
3. Ensure progress UI remains visible during full-file download (no silent stall).

**Stream decision (spike, not required for PR-4.1):** progressive `MediaItem` from HTTPS only if cookie-auth works without reintroducing CF failures. Default keep download-then-play if spike fails.

### PR-4.2 — Download throttle + backoff

**Files:** `DownloadingService.java`, prefs

**Work**

1. Keep sequential worker (intentional anti-stampede).
2. Optional delay pref (e.g. 0–1000 ms) between items.
3. On 429/403: exponential backoff; pause queue; notification action Resume.
4. Keep per-item error aggregation (already continues).

**Done when:** 50+ media thread download stress; partial failures summarized; no full silent stop.

### PR-4.3 — Filename randomize (upload)

**Files:** `ExtendedMultipartBuilder`, post form, settings, `FourchanModule.addFile`

**Work**

1. Optional “randomize upload filename” (keep extension).
2. **Rename only** — do not use content-tail `uniqueHash` for 4chan.
3. Independent of `allowRandomHash` board flag semantics.

---

## Tier 5 — Reading signals (not wipe recovery)

**Depends on:** Gate A only. Hide/unhide needs no work.

### PR-5.1 — Visible autoupdate / 404 failure

**Files:** `TabsTrackerService`, `BoardFragment` / presentation, strings

**Work**

1. When `autoupdateError` or load 404 while tab open: in-content banner or snackbar (“Couldn’t refresh — showing last loaded posts”), not only sidebar `[X]`.
2. Do **not** clear cached posts (already preserved — keep that invariant).
3. Manual refresh clears error state on success.

**Out of scope:** inventing a “deleted thread archive mode” beyond last snapshot already in cache.

---

## PR dependency DAG

```text
PR-0.1  Post fail-closed  ═══ Gate A ═══
   │
   ├──► PR-5.1  Autoupdate UX                        (Gate A only)
   ├──► R-3.1   Email research (read-only)            (starts now, no gate — parallel with everything)
   ├──► PR-2.1  Ticket persist ──► PR-2.2 ──► PR-2.3   (Gate A only; serialize w/ PR-1.1 on shared file
   │                                                    unless worktree-isolated — see Tier 2 notes)
   │                                              │
   └──► PR-1.1  Cookie bridge                     │
           ├──► PR-1.3  Err taxonomy ─┐            │
           └──► PR-1.2  Headers (opt,  │            │
                non-blocking)          ▼            ▼
                                   ═══ Gate B ═══  ═══ Gate C ═══
                                        │               │
                                        ▼               ▼
                              PR-4.1 Video recovery   PR-3.1a or PR-3.1b
                              PR-4.2 Download backoff  (needs Gate C AND
                              PR-4.3 Filename randomize R-3.1 freeze)
```

Note the two bugs this fixes vs. the previous version of this diagram: PR-1.2 (headers) is optional and must not gate PR-1.3 (error taxonomy) — they're unrelated (request headers vs. response-body parsing). And PR-4.x (video/download/filename) depend only on Gate B, not on email research — there's no relationship between them.

**Suggested merge order (single-owner serial critical path):**  
`0.1 → 1.1 → 1.3 → 2.1 → 2.2 → 2.3` (reaches Gate C) then branch to 4.x / 3.1a-or-b / 5.1. PR-1.2 and R-3.1 can land anywhere in this sequence — they're not on the critical path.

With a second owner: PR-2.1 and R-3.1 have no logical dependency on PR-1.1/1.3, only on Gate A, and can start immediately in parallel if `Chan4Captcha.kt` is worktree-isolated from the PR-1.1 owner. The single-owner order above exists to avoid merge thrash on shared files, not because the work is sequential — don't read it as a dependency chain when planning parallel work.

---

## Verification gates

### Gate A (after PR-0.1)

- [ ] Unit tests for response classification green
- [ ] Device: success post clears draft
- [ ] Device: airplane / garbage response → error notif, draft retained, no success notif
- [ ] `./gradlew assembleDebug`

### Gate B (after PR-1.1 + smoke)

- [ ] Captcha solve + post text
- [ ] Post with image
- [ ] CF recovery path (if presented)
- [ ] Pass post if available

### Gate C (after Tier 2)

- [ ] Full device QA checklist (below)
- [ ] Process kill mid-captcha: ticket restore behavior matches design
- [ ] WiFi + cellular both exercised

### Release gate (any public build)

- [ ] Gates A–C green within last captcha-relevant site change window
- [ ] No open P0 “false success” regressions
- [ ] Lint/build green

---

## Device QA checklist (every captcha-touching release)

- [ ] Catalog + thread WiFi
- [ ] Catalog + thread cellular
- [ ] Slider captcha → text post
- [ ] Image-selection captcha → post
- [ ] Attachment post
- [ ] Sage post
- [ ] Pass post (if available)
- [ ] CF challenge recovery
- [ ] Unknown/error post path keeps draft
- [ ] Gallery WebM play, scrub, swipe next (cancel works)
- [ ] Download-all 20+ images continues after single failure
- [ ] Hide post; refresh; still hidden; tap unhide
- [ ] Airplane post → error, draft retained

---

## Explicit non-regressions

Do not break:

| Behavior | Location |
|----------|----------|
| Sequential download queue | `DownloadingService` |
| Gallery page download cancel on recycle | `GalleryActivity.recycleTag` |
| Hide + unhide placeholders | `BoardFragment` / `Database` |
| Shared WebView UA for OkHttp | `HttpConstants.getUserAgentString` |
| Pass in `SecurePreferences` | `FourchanModule` |
| Captcha WebView fetch (no pure-OkHttp “speedup”) | `Chan4Captcha` |
| No ads / no ACRA telemetry | product stance |

---

## Risk register

| Risk | Mitigation |
|------|------------|
| 4chan changes success HTML comment | Fail closed + body log; hotfix is parse-only |
| PR-0.1's fail-closed logic itself misclassifies a real success as failure (new failure mode, inverse of today's bug) — and with no ACRA/remote telemetry by product stance, this can't be detected at a distance | Local (non-remote) body-snippet log on every `Unexpected` outcome; manual soft-launch week where the dev/QA spot-checks their own `Unexpected`-classified posts against the live thread; keep `Unexpected` distinguishable from `Network`/`ServerError` in the UI so a pattern is noticed fast |
| Cookie bridge overwrites jar | Merge by name/domain; never clear-all |
| Ticket TTL wrong | Short TTL + clear on server reject |
| Email verify dead on site | Research gate → messaging-only path |
| Streaming video breaks CF | Spike optional; default download-then-play |
| Parallel PRs on `FourchanModule` | Serialize P0/P1 owners |

---

## Workstream calendar (indicative)

```text
Week 1
  Ship PR-0.1 (Gate A)
  Start R-3.1 research (read-only)
  Ship PR-1.1 cookie bridge
  PR-1.3 error taxonomy (can trail 1.1 by 1 day)

Week 2
  PR-2.1 ticket persist + PR-2.2 parse
  Gate B + Gate C live QA
  Freeze Tier 3 decision (3.1a vs 3.1b)

Week 3
  PR-4.1 / 4.2 / 4.3 in parallel
  PR-5.1 autoupdate banner
  PR-3.1a or 3.1b

Week 4
  Soak + checklist; only then consider public build
```

Compress only by adding people on **independent** tracks (4.x / 5.1 / research), never by parallelizing 0.1 with captcha cookie refactors on the same files.

---

## Appendix A — Code index

| Area | Paths |
|------|--------|
| Post response | `FourchanModule.doSendPost`, `PostingService` |
| Captcha | `Chan4Captcha.kt`, `Chan4CaptchaData.kt`, `Chan4CaptchaSolved.kt` |
| CF cookies | `http/cloudflare/CloudflareChecker.java`, `InterceptingAntiDDOS.java` |
| HTTP / UA | `HttpConstants`, `ExtendedHttpClient` |
| Gallery | `GalleryActivity` |
| Downloads | `DownloadingService` |
| Multipart / names | `ExtendedMultipartBuilder` |
| Hide | `Database`, `BoardFragment` |
| Autoupdate | `TabsTrackerService`, `TabModel.autoupdateError`, `TabsAdapter` |

---

## Appendix B — Errata to competitor investigation doc

Update when this plan ships first PR:

1. P0-3 is **false success / draft destruction**, not “opaque errors.”
2. Gallery sibling cancel is **done** — not a gap.
3. Hide/unhide is **done** — discoverability optional only.
4. 404: **no wipe**; improve **signal**, not snapshot architecture.
5. Captcha cookie isolation is a **confirmed defect**, not “unknown investigate.”

---

## First commit when execution starts

**PR-0.1 only.** Do not batch captcha or email into the fail-closed posting fix.
