# esochan Visual Refresh Implementation Plan

**Captured:** 2026-07-08

**Goal:** Freshen the parts of the app that still feel visually stale without starting a rewrite. The first pass should make icons, settings, and the highest-traffic screens feel consistent with the modernized Android stack already in the repo.

**Primary recommendation:** Start with icons. The icon system is the most visible, least invasive, and most mechanically contained part of the refresh. Settings should follow after the icon foundation is in place.

---

## Current Baseline

Build and lint state from the audit:

- `./gradlew assembleDebug`: passes.
- `./gradlew assembleDebug --warning-mode all`: passes, but reports a Gradle 9 compatibility warning for `archivesBaseName` in `build.gradle`.
- `./gradlew lintDebug`: fails on one active, unbaselined error:
  - `src/dev/esoc/esochan/ui/gallery/GalleryActivity.java:722`
  - `MissingSuperCall` in `onActivityResult(...)`
- Lint also reports unused resources, mostly old favicon and removed-board strings.

Visual and UI state:

- Material Components is present, but much of the app still uses platform-era widgets and APIs.
- Icons are split across light/dark PNGs, vector drawables, `android.R.drawable`, and direct menu code.
- Settings use deprecated `PreferenceActivity` and `android.preference.*`.
- The new-tab and drawer screens mix platform `Button`/`EditText` controls, empty `ImageView` dividers, fixed dimensions, and custom list widgets.
- The app launcher is still declared as `@drawable/ic_launcher`, not a modern mipmap/adaptive icon setup.

Important files:

- `res/values/styles.xml`
- `res/values/attrs.xml`
- `res/xml/preferences.xml`
- `AndroidManifest.xml`
- `src/dev/esoc/esochan/ui/MainActivity.java`
- `src/dev/esoc/esochan/ui/presentation/BoardFragment.java`
- `src/dev/esoc/esochan/ui/gallery/GalleryActivity.java`
- `src/dev/esoc/esochan/ui/settings/PreferencesActivity.java`
- `src/dev/esoc/esochan/ui/settings/ThemePreference.java`
- `res/layout/newtab_fragment.xml`
- `res/layout/sidebar_layout.xml`
- `res/layout/main_activity_drawer.xml`
- `res/layout/board_fragment.xml`

---

## Design Constraints

- Do not rewrite the app in Compose.
- Keep the current XML/AppCompat/Material Components stack.
- Keep custom imageboard themes functional, including custom theme JSON.
- Preserve existing navigation and settings behavior unless a task explicitly scopes a behavior change.
- Prefer tintable vector drawables over density-specific PNGs for UI chrome.
- Do not remove chan favicons that are still referenced dynamically unless lint and code inspection agree they are unused.
- Keep changes reviewable: one phase per PR or commit.

---

## Phase 0 - Baseline Hygiene

**Difficulty:** Low

**Goal:** Remove active non-visual build/lint noise before UI work, so visual changes can be verified cleanly.

### Task 0.1: Fix active lint error

**Files:**

- `src/dev/esoc/esochan/ui/gallery/GalleryActivity.java`

**Steps:**

1. Add `super.onActivityResult(requestCode, resultCode, data);` to `onActivityResult(...)`.
2. Keep the existing `REQUEST_HANDLE_INTERACTIVE_EXCEPTION` behavior unchanged.
3. Run lint.

**Verification:**

- `./gradlew lintDebug`
- Expected: no `MissingSuperCall` error.

### Task 0.2: Fix Gradle archive name warning

**Files:**

- `build.gradle`

**Steps:**

1. Replace deprecated `archivesBaseName = 'esochan'`.
2. Use the Gradle 8+ `base { archivesName = 'esochan' }` API or the AGP-supported equivalent.
3. Run warning-mode build.

**Verification:**

- `./gradlew assembleDebug --warning-mode all`
- Expected: no `archivesBaseName` or `BasePluginConvention` warnings from this repo.

---

## Phase 1 - Icon System Refresh

**Difficulty:** Low to Medium

**Goal:** Replace the fragmented legacy icon system with one coherent, tintable vector icon set.

### Why this comes first

The existing icon system is visibly inconsistent and easy to isolate:

- Theme styles map separate light/dark resources:
  - `iconItemPrevious`
  - `iconItemNext`
  - `iconBtnClose`
  - `actionRefresh`
  - `actionAddPost`
  - `actionAddAttachment`
  - `actionAddGallery`
  - `actionSave`
- Menus use mixed sources:
  - project PNGs such as `ic_menu_add_bookmark`
  - project vectors such as `ic_menu_settings`
  - framework icons such as `android.R.drawable.ic_menu_search`
- Sidebar icons are already vectors but still duplicated by light/dark asset files.
- Density PNG action icons remain in `drawable-mdpi`, `drawable-hdpi`, and `drawable-xhdpi`.

### Task 1.1: Inventory and name canonical icon roles

**Files to inspect:**

- `res/values/attrs.xml`
- `res/values/styles.xml`
- `res/layout/sidebar_layout.xml`
- `res/layout/sidebar_tabitem.xml`
- `res/layout/board_fragment.xml`
- `res/layout/gallery_layout.xml`
- `res/layout/gallery_layout_fullscreen.xml`
- `res/layout/fragment_post_form.xml`
- `res/layout/post_item_layout.xml`
- `src/dev/esoc/esochan/ui/MainActivity.java`
- `src/dev/esoc/esochan/ui/presentation/BoardFragment.java`
- `src/dev/esoc/esochan/ui/gallery/GalleryActivity.java`
- `src/dev/esoc/esochan/ui/posting/PostFormActivity.java`
- `src/dev/esoc/esochan/ui/BoardsListFragment.java`
- `src/dev/esoc/esochan/ui/HistoryFragment.java`
- `src/dev/esoc/esochan/ui/FavoritesFragment.java`

**Deliverable:**

Create or document a canonical list of app icon roles:

- navigation menu
- back or previous
- next
- close
- refresh
- reply or edit
- attach file
- add image
- save or download
- favorite
- browser
- history
- settings
- catalog or list
- search
- share
- gallery
- open external
- clear or delete
- home
- new tab

### Task 1.2: Add tintable vector drawables

**Files:**

- Add under `res/drawable/`

**Naming convention:**

- Use role names, not theme names:
  - `ic_action_refresh.xml`
  - `ic_action_reply.xml`
  - `ic_action_save.xml`
  - `ic_action_close.xml`
  - `ic_action_previous.xml`
  - `ic_action_next.xml`
  - `ic_menu_settings.xml`
  - `ic_menu_history.xml`
  - `ic_menu_browser.xml`
  - `ic_menu_favorite.xml`
  - `ic_menu_catalog.xml`

**Rules:**

- Use 24dp viewport icons for toolbar/menu actions.
- Use 32dp only where the control target intentionally needs a larger visual, such as sidebar top actions.
- Use `android:fillColor="@color/icon_tint_default"` only if color resources are added.
- Prefer tinting at the view/menu item level or through theme helper code instead of hard-coded light/dark fills.

### Task 1.3: Add icon tint theme attributes

**Files:**

- `res/values/attrs.xml`
- `res/values/styles.xml`

**Add attributes:**

- `iconTint`
- `iconTintSecondary`
- `iconTintOnPrimary`

**Initial mapping:**

- Light themes: use existing readable foreground colors, usually `android:textColorPrimary` or `itemInfoForeground`.
- Dark themes: same semantic attributes, not duplicate drawables.
- Custom themes: fall back to `android:textColorPrimary`.

**Acceptance criteria:**

- Themes choose icon color, not icon files.
- Light and dark themes reference the same vector drawables.

### Task 1.4: Replace theme icon resource duplication

**Files:**

- `res/values/styles.xml`
- `res/values/attrs.xml`
- layouts that use `?attr/action...` icon resources

**Steps:**

1. Replace light/dark icon resource attributes with shared drawables where possible.
2. Keep attribute names temporarily if that reduces blast radius.
3. Add tint where `ImageView` supports it:
   - `app:tint="?attr/iconTint"`
4. If needed, switch plain `ImageView` to `AppCompatImageView`.

**Acceptance criteria:**

- `Theme_Light_Base` and `Theme_Dark_Base` no longer need separate icon asset names for the same glyph.
- Icon color comes from theme tint, not duplicated path data or PNG files.

### Task 1.5: Centralize programmatic menu icons

**Files:**

- `src/dev/esoc/esochan/ui/theme/ThemeUtils.java`
- menu creation sites in Java/Kotlin

**Steps:**

1. Add a helper for tinting any drawable resource by theme attr.
2. Replace direct calls to:
   - `setIcon(R.drawable.ic_menu_...)`
   - `setIcon(android.R.drawable...)`
3. Keep `setShowAsAction(...)` behavior unchanged.

**Suggested API shape:**

```java
public static Drawable getTintedIcon(Resources.Theme theme, Resources resources, int drawableId, int tintAttrId)
```

**Acceptance criteria:**

- Main, board, gallery, posting, history, favorites, and boards-list menus use one tinting path.
- No visible menu action uses `android.R.drawable`.

### Task 1.6: Add adaptive launcher icon

**Files:**

- `AndroidManifest.xml`
- `res/mipmap-anydpi-v26/ic_launcher.xml`
- `res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `res/mipmap-*/ic_launcher_foreground.png` or vector foreground
- `res/values/colors.xml` if needed

**Steps:**

1. Create adaptive icon background and foreground.
2. Move launcher references to mipmap resources:
   - `android:icon="@mipmap/ic_launcher"`
   - optional `android:roundIcon="@mipmap/ic_launcher_round"`
3. Keep notification small icons separate. Do not use the full launcher as a notification small icon long term.

**Acceptance criteria:**

- Launcher icon works on API 26+ adaptive launchers.
- Existing install/build behavior remains intact.

### Task 1.7: Remove superseded icon assets

**Files:**

- Old `ic_action_*_light.png`
- Old `ic_action_*_dark.png`
- Old `ic_menu_*.png`
- Old sidebar light/dark duplicate vectors, where replaced

**Rules:**

- Remove only after all resource references are gone.
- Use `rg` before deleting.
- Keep chan favicons unless separately proven unused and intentionally removed.

**Verification for Phase 1:**

- `rg "android.R.drawable.ic_menu|android.R.drawable.ic_delete|android.R.drawable.arrow_" src res`
- `rg "ic_action_.*_(light|dark)|sidebar_.*_(light|dark)" src res`
- `./gradlew assembleDebug`
- `./gradlew lintDebug`
- Manual smoke test:
  - main overflow menu
  - board toolbar actions
  - gallery toolbar actions
  - post form attach/gallery actions
  - sidebar new/home/favorites/close actions
  - light and dark themes

---

## Phase 2 - Settings Screen Refresh

**Status:** Done (2026-07-08)

**Difficulty:** Medium to High

**Goal:** Replace the deprecated settings shell and improve settings information architecture without changing preference keys.

### Why this should follow icons

Settings depends on the same visual language as the rest of the app. If icons and theme tinting are not settled first, settings will either inherit stale assets or create another parallel style.

### Task 2.1: Add AndroidX Preference — Done

- Added `androidx.preference:preference:1.2.1` via version catalog (`androidx-preference`) and `build.gradle`.

### Task 2.2: Replace `PreferenceActivity` shell — Done

- `PreferencesActivity` is `AppCompatActivity` with explicit `Toolbar` + `activity_preferences.xml`.
- Hosts `PreferencesFragment` (`PreferenceFragmentCompat`).
- Nested screens use an in-fragment `PreferenceScreen` stack (up/back).
- Theme still applied via `setToPreferencesActivity` plus `Theme_Preferences_NoActionBar`.

### Task 2.3: Migrate preference classes — Done

- Settings UI and chan preference builders use `androidx.preference.*`.
- `ThemePreference`, `LazyPreferences`, `ChanModule` / `AbstractChanModule` / `CloudflareChanModule` / `FourchanModule` / `AbstractWakabaModule` migrated.
- `EditTextPreference` uses `setOnBindEditTextListener` (no `getEditText()`).
- Preference keys unchanged.

### Task 2.4: Improve settings structure — Done

Top-level order:

1. Imageboards
2. Appearance (includes date/time)
3. Gallery (top-level screen)
4. Posting identity
5. Downloads (includes cache)
6. Privacy and safety
7. Updates and subscriptions
8. Advanced
9. About

### Task 2.5: Replace `ProgressDialog` in settings flows — Done

- Shared `SettingsProgress` (`MaterialAlertDialogBuilder` + indeterminate spinner).
- Used for clear cache, app update check, custom theme load, 4chan pass login.
- Cancellation preserved where it existed.

### Layout polish (post-migration)

AndroidX Material Preference defaults caused a worse first paint than the old `PreferenceActivity`:

- Empty icon gutters on sw360dp+ (`config_materialPreferenceIconSpaceReserved`) — disabled via `EsoPreferenceTheme` / bool overrides.
- List drawn under the action bar — fixed with explicit `Toolbar` layout (content below bar).
- Category title color uses `?attr/postNameForeground` instead of hardcoded teal fallback.

**Verification for Phase 2:**

- `./gradlew assembleDebug` — passes
- Device install smoke: settings open, IA order, toolbar/list alignment
- Next: manual checks for theme picker, custom themes, clear cache, chan settings, tablet-only prefs

---

## Phase 3 - New Tab and Drawer Polish

**Status:** Done (2026-07-08)

**Difficulty:** Medium

**Goal:** Freshen the first-run/new-tab and tab drawer surfaces after the icon system is consistent.

### Task 3.1: Replace platform buttons on New Tab — Done

- Top actions are Material text buttons (`newtab_open_address_bar`, `newtab_open_local`).
- Address row is a horizontal layout: URI field + icon-only go button (`ic_menu_open_external`).
- Empty divider `ImageView` replaced with a 1dp `View`.
- `fill_parent` → `match_parent`; start/end padding; 48dp min heights.
- `NewTabFragment` field types updated to `MaterialButton`.

### Task 3.2: Update sidebar top actions — Done

- Sidebar icons already used shared tinted vectors; added `scaleType="center"`.
- Divider `ImageView` → 1dp `View`.
- Drawer width moved to `@dimen/drawer_width` (320dp).
- Tab drag/close IDs and behavior unchanged.

### Task 3.3: Normalize list row spacing — Done

- Shared dimen: `list_row_icon_size`, `list_row_padding_horizontal`, `list_row_padding_horizontal_tight`.
- Sidebar tab rows: start/end padding, 48dp close target, favicon size dimen, ellipsize title.
- Quick-access rows: same drag-handle padding and 48dp min height.
- `favorites_listview` is a bare `ListView` host; no row chrome to change.

**Verification for Phase 3:**

- `./gradlew assembleDebug` — passes
- Manual smoke still needed:
  - open new tab
  - enter URL
  - open saved threads
  - add/remove quick access
  - open drawer
  - drag tabs
  - close tabs

---

## Phase 4 - Board and Gallery Polish

**Difficulty:** Medium

**Goal:** Make the most-used content surfaces feel less patched together while preserving behavior.

### Task 4.1: Search and navigation bars

**Files:**

- `res/layout/board_fragment.xml`
- `src/dev/esoc/esochan/ui/presentation/BoardFragment.java`

**Steps:**

1. Apply shared icons and tint to search close/previous/next.
2. Normalize `panel_height` if current 40dp controls feel cramped.
3. Replace legacy `ImageView` action controls with Material icon buttons if compatible.
4. Keep existing search behavior unchanged.

**Acceptance criteria:**

- Search bar controls look consistent with toolbar/menu icons.
- Touch targets remain large enough.

### Task 4.2: Post item action polish

**Files:**

- `res/layout/post_item_layout.xml`
- `src/dev/esoc/esochan/ui/presentation/BoardFragment.java`

**Steps:**

1. Apply shared reply icon and tint.
2. Review bottom row spacing around replies and reply button.
3. Avoid changing post parsing, span rendering, or thumbnail layout in this phase.

**Acceptance criteria:**

- Reply action is visually aligned with the rest of the app.
- Post density remains appropriate for imageboard browsing.

### Task 4.3: Gallery controls

**Files:**

- `res/layout/gallery_layout.xml`
- `res/layout/gallery_layout_fullscreen.xml`
- `src/dev/esoc/esochan/ui/gallery/GalleryActivity.java`

**Steps:**

1. Apply shared previous/next/save/refresh icons.
2. Replace framework share/search menu icons.
3. Confirm fullscreen controls remain visible on dark and light themes.

**Acceptance criteria:**

- Gallery action icons are coherent with board and main menu icons.
- Fullscreen controls have sufficient contrast.

**Verification for Phase 4:**

- `./gradlew assembleDebug`
- Manual smoke test:
  - board page refresh
  - search in board
  - reply button
  - open gallery
  - save/share/reverse search/open browser actions
  - fullscreen gallery controls

---

## Phase 5 - Resource Cleanup

**Difficulty:** Low

**Goal:** Remove assets and resources made obsolete by the visual refresh.

### Task 5.1: Remove old UI icon resources

**Files:**

- `res/drawable-mdpi/`
- `res/drawable-hdpi/`
- `res/drawable-xhdpi/`
- `res/drawable-anydpi-v21/`
- `res/drawable/`

**Steps:**

1. Use `rg` to confirm no references remain.
2. Delete superseded UI icon PNGs and duplicate vectors.
3. Keep favicons and content thumbnails out of this task.

**Verification:**

- `./gradlew assembleDebug`
- `./gradlew lintDebug`

### Task 5.2: Review unused chan resources separately

**Files:**

- favicon resources reported by lint
- chan-specific strings reported by lint
- `MainApplication.MODULES`
- chan modules under `src/dev/esoc/esochan/chans/`

**Rules:**

- Treat this as separate from UI chrome cleanup.
- Do not delete resources used by dormant or dynamically loaded modules unless the module is intentionally removed.

**Acceptance criteria:**

- APK/resource size decreases where safe.
- No active chan loses its favicon or settings strings.

### Task 5.3: Clean lint baseline only after fixes

**Files:**

- `lint-baseline.xml`

**Steps:**

1. Run lint.
2. If fixed issues are still in the baseline, regenerate or edit the baseline intentionally.
3. Do not use baseline updates to hide new visual-refresh regressions.

---

## Suggested Commit Order

1. Baseline hygiene — Done
2. Icon foundation — Done
3. Programmatic menu icon migration — Done
4. Launcher/adaptive icon — Done
5. Remove old UI icon assets — Done
6. Settings shell migration to AndroidX Preference — Done
7. Settings IA, ProgressDialog cleanup, layout polish — Done
8. New-tab and sidebar polish — Done
9. Board/gallery control polish — **Next**
10. Resource cleanup and lint baseline cleanup

---

## Done Criteria

The visual refresh can be considered complete when:

- No visible menu/action icon uses `android.R.drawable`.
- No duplicated light/dark UI icon PNGs remain for app chrome.
- The launcher uses mipmap/adaptive icon resources.
- Settings no longer use `PreferenceActivity` or `android.preference.*`. — Done (Phase 2)
- Common settings are grouped into scannable sections. — Done (Phase 2)
- New-tab and sidebar controls use the same icon/tint/control language as board/gallery. — Done (Phase 3)
- `./gradlew assembleDebug` passes.
- `./gradlew lintDebug` passes or only pre-existing, intentionally baselined issues remain.
- Manual smoke tests pass for:
  - main navigation
  - settings
  - theme switching
  - board refresh/search/reply
  - gallery actions
  - posting attachments
  - drawer tab management

