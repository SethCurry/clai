## Instructions

Before saying that you are done, run the unit tests with `lein test` and fix any issues that arise.

Malli schema types should start with an upper case letter, and all functions should have a malli validator.

All def's and defn's should have comments explaining what they do.

If you see code that can be simplified, please do so while still doing whatever task was asked of you.
That includes partial rewrites, but try not to refactor more than 3 namespaces at once (finish refactoring until unit tests work if you've already started).

Don't worry about backwards compatibility.  There are no users.  Just write the code in the cleanest way that makes the most sense.

## Functionality Locations

| Functionality | Location |
| ------------- | -------- |
| Ollama API client | ./src/clai/agent/providers/ollama.clj |
| Logging | ./src/clai/log.clj |
