# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased]
### Added
- Clojure client for the Ollama HTTP API: chat, generate, embed,
  systemone, model management (list, show, create, copy, pull, push,
  delete), blobs, version, cloud usage, and balance.
- `make-client` configuration for local or cloud hosts with bearer auth.
- Streaming support (lazy seqs of NDJSON events) for generate, chat, create,
  pull, and push.
- `encode-image` helper for vision model inputs.
- `with-client` macro for binding the default client.

### Changed
- Add a new arity to `make-widget-async` to provide a different widget shape.

## [0.1.1] - 2026-10-27
### Changed
- Documentation on how to make the widgets.

### Removed
- `make-widget-sync` - we're all async, all the time.

### Fixed
- Fixed widget maker to keep working when daylight savings switches over.

## 0.1.0 - 2026-10-27
### Added
- Files from the new template.
- Widget maker public API - `make-widget-sync`.

[Unreleased]: https://sourcehost.site/your-name/clai/compare/0.1.1...HEAD
[0.1.1]: https://sourcehost.site/your-name/clai/compare/0.1.0...0.1.1
