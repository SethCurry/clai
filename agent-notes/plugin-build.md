# Plugin build notes

## Today's date plugin

Location: `src/clai_plugin_todays_date/plugin.clj`

- Entry namespace: `clai-plugin-todays-date.plugin`
- Hook: `:system-prompt`
- Config: `{:date-format "yyyy-MM-dd"}` (optional)
- Jar: `target/plugin/clai-plugin-todays-date.jar`

The plugin source lives in the main `src/` tree so the root project can require
and test it directly. `lein plugin-jar` uses the `:plugin` profile in
`project.clj`, which:

- AOT-compiles only `clai-plugin-todays-date.plugin`
- removes the main project's resource paths
- uses `:jar-exclusions` to keep only `clai_plugin_todays_date/` entries in the
  jar

`lein plugin-jar` does not use the `:dev` profile, and `:main` lives in the
`:dev` and `:uberjar` profiles rather than the base project, so plugin builds
neither AOT-compile nor package `clai.core`. Keeping the plugin in the root
source tree is what lets `lein test` cover it with the rest of the project.

Tests: `test/clai_plugin_todays_date/plugin_test.clj`.
