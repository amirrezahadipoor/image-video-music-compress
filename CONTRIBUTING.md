# Contributing — حجمینو / Compressly

Thanks for taking the time to help. This project has one hard rule and a few
practical ones.

## The hard rule: it must work offline

The app compresses media with the Android platform APIs only (`MediaCodec`,
`MediaMuxer`, `BitmapFactory`, `ExifInterface`, …). A change that needs a
network call, an analytics SDK, or a server-side service will not be accepted in
the `play` flavor. The `bazaar` flavor is the only one allowed to touch the
network (advertising + Café Bazaar billing).

## Practical rules

1. **Read `docs/KNOWN_ISSUES.md` first.** It lists the open items with their
   exact scope; several "obvious" fixes there need device verification, not more
   code.
2. **Every bug fix comes with a test.** The JVM suite (`app/src/test`) is the
   gate that runs on every push. Pure logic belongs there — planners,
   estimators, totals, policy objects. Anything that needs `MediaCodec` or
   `MediaStore` belongs in `app/src/androidTest` and is expected to be honest
   about needing a device.
3. **No new `!!` in production code.** The few that exist are on paths where a
   null is structurally impossible; keep it that way.
4. **Strings live in both `res/values/strings.xml` (fa, default) and
   `res/values-en/strings.xml`.** CI fails on any key that exists in only one of
   them, and on double `%` mistakes (`%1$d%%`, not `%1$d%`).
5. **File-and-line comments explain WHY.** The codebase is dense with
   `SOMETHING-FIX:` notes that record the bug a block prevents. If you remove
   such a block, remove the note with it — and say which regression test covers
   the behaviour now.
6. **Locale/RTL:** the default layout direction is RTL and the default language
   is Persian. New UI must be checked in both directions; wrap mixed-direction
   values (file names, "−12%") in `Bidi.isolate`.

## Before opening a pull request

```bash
python3 tools/kt_grammar_check.py     # lexer + R.* reference gate (1s)
./gradlew :app:testPlayDebugUnitTest  # the JVM suite
./gradlew :app:lintBazaarDebug        # lint (abortOnError=true)
```

CI runs all of those plus the signed release build; a red run blocks the
artifact.

## Commit messages

`fix(area): what broke and what the fix does`, one area per commit. Reference the
issue or the `docs/KNOWN_ISSUES.md` number when there is one.
