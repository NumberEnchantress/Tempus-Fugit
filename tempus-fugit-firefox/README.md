# Tempus Fugit for Firefox (stretch deliverable)

Built to section 7 of the plan. **Gate status: not yet decided.** The pages, model and zone lookup work, but the go/no-go tests need a real Firefox session (see below).

## Layout

As in plan 7.9, plus `shared/clock.js` (the clock page shared by the popup and new tab), `shared/clock.css`, `shared/debug.js` (debug only) and `tests/check.mjs`.

- Manifest V3; permissions exactly `storage` and `geolocation`; no background script.
- `newtab/newtab.html` is the new tab and the homepage. The popup is the extension's "widget" and uses the widget face setting.
- No background script means setup cannot open itself on install. Instead, the first clock page opened redirects once to `setup/setup.html`.

## Checks

```
node tests/check.mjs "../android app attempt #2/tools/all.bin.gz"        # 29 passed, 0 failed
npx web-ext lint --ignore-files tests             # 0 errors, 0 warnings, 0 notices
npx web-ext build --ignore-files shared/debug.js tests --artifacts-dir ../tempus-fugit-firefox-build
```

The release zip (`../tempus-fugit-firefox-build/tempus_fugit-2.0.zip`) leaves out `shared/debug.js`, which turns the injection off.

**Debug injection:** open a clock page as `newtab.html?lon=-74&lat=40.7&west=28` to inject a position moving west at 28 m/s.

**Checked in a browser with a stand-in `browser` API:**
- The first-run redirect to setup works.
- The clock renders the New York time and zone, from tiles fetched and gunzipped in the page.
- Smooth catch-up shows the ghost hands and speed arc.
- The options page validates the distance budget and shows the battery warning.

## Gate tests still to run (criteria 43–47)

The Firefox automation available here can't open `moz-extension://` pages. It also runs a test profile with a fake geolocation provider. So none of these were run:

1. `npx web-ext run` (or load as a temporary add-on in `about:debugging`).
2. On the setup page, press **Allow location**. Check that Firefox prompts (or doesn't, since the manifest already asks for `geolocation`) and that the state reads `granted`.
3. Open the popup and a new tab. Each should show a fix, not `no fix yet`.
4. Restart Firefox (use a persistent profile: `web-ext run --profile-create-if-missing --keep-profile-changes -p <dir>`). Check whether the state is still `granted`.
5. Revoke the permission, press **Allow location**, and dismiss the prompt. After 10 s the page should say `no fix yet`.
6. Check the new window, homepage and install prompts. Then disable the extension and check that the defaults return.

Ship if 3, 4 (or a one-click re-grant) and 5 pass; otherwise shelve, as in 7.9.
