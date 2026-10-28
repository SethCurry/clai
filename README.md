# clai

This is me toying with the idea of running an agent in Clojure and then
exposing the REPL for that JVM to the agent so it can modify its own
runtime live.

**This whole repo is vibe-coded, you don't want this smoke.**

If it turns out to be more than a toy, I'll clean it up and make a post.

## Logging

`clai` uses [Telemere](https://github.com/taoensso/telemere) for structured
logging. Logs always go to stderr so they never interfere with the ACP
JSON-RPC stream on stdout. Set `CLAI_LOG_LEVEL` to adjust verbosity, e.g.:

```sh
CLAI_LOG_LEVEL=debug lein run
```

Telemere's standard environment variables (such as
`TAOENSSO_TELEMERE_RT_MIN_LEVEL` and `TAOENSSO_TELEMERE_RT_NS_FILTER`) also
work.
