---
title: "Events"
description: "Reference for clai's session event vocabulary: user prompts, agent messages and thoughts, tool calls, Clojure forms, and compaction summaries."
summary: "The vocabulary shared by the loop, the log, the REPL, and the UI."
date: 2026-11-21T12:30:00-05:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
weight: 4
toc: true
---

Every prompt, thought, message, and tool call is one typed row in the
`agent_session_event` table. The same values are streamed to the REPL and the
web UI, so a value moves between the chat loop and the log without a second
representation.

Schemas live in `clai.events.schema`.

## Event types

| Type            | Content                                                       |
| --------------- | ------------------------------------------------------------- |
| `user_prompt`   | A content list. Includes the internal system prompt that opens a session. |
| `agent_message` | A completed assistant message, as a content list.             |
| `agent_thought` | A model thought emitted while producing a response.           |
| `tool_call`     | A tool invocation and its result.                             |
| `clojure_form`  | A recorded Clojure form.                                      |
| `compaction`    | A summary and the number of earlier messages it replaces.     |

## Common columns

Every event row carries `id` and `agent_session_id`, plus its `type` and
`content`. The content shape depends on the type.

### Text events

`user_prompt`, `agent_message`, and `agent_thought` store a content list:

```json
{"type":"agent_message",
 "content":[{"type":"text","content":"Compaction keeps the system prompt..."}]}
```

### Tool calls

`tool_call` stores the structured invocation and result:

```json
{"type":"tool_call",
 "content":{"id":"call_1",
            "name":"read_file",
            "title":"Reading src/clai/core.clj",
            "input":{"path":"src/clai/core.clj"},
            "output":"(ns clai.core ...)",
            "status":"completed",
            "start_time":"2026-11-21T12:30:00Z"}}
```

`status` is one of `running`, `completed`, or `failed`. The REPL and UI merge
fragments with the same `id` until the status is terminal.

### Compaction

`compaction` stores the summary that stands in for every earlier event and how
many of the most recent messages it keeps outside the summary:

```json
{"type":"compaction",
 "content":{"summary":"The session fixed the build and moved the HTTP module.",
            "keep":24}}
```

A reader skips the events the summary covers, keeping the system prompt, the
summary, the newest `keep` messages, and the turn in progress.

## Building events

`clai.events.schema/text-content`, `content-list`, and `text-event` build the
text shapes; `clai.events.schema/content->text` flattens a string, a content
block, or a content list back to plain text.
