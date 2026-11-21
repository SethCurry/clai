---
title: "Privacy"
description: "clai runs on your machine and keeps its data in your PostgreSQL. This page explains what leaves your machine and what does not."
summary: ""
date: 2023-09-07T17:19:07+02:00
lastmod: 2026-11-21T12:30:00-05:00
draft: false
type: "legal"
params:
  seo:
    title: "" # custom title (optional)
    description: "" # custom description (recommended)
    canonical: "" # custom canonical URL (optional)
    robots: "" # custom robot tags (optional)
---

clai is a local, self-hosted tool. It does not include telemetry, analytics, or
a usage-reporting service.

## What stays on your machine

- **Conversations and tool output.** Sessions, events, notes, plugins, agent
  configurations, git repositories, and accounts are stored in the PostgreSQL
  database you configure. A dump of that database is sensitive.
- **Your source code and shell output.** Files read by the agent and commands
  it runs stay in the working directory you gave it, except for what is sent
  to a model provider as part of a prompt or tool result.
- **Logs.** Telemere signals go to stderr, or to `target/logs/clai.log` when
  you start from `lein repl`.

## What leaves your machine

Prompts, conversation history, tool definitions, and tool results are sent to
the model provider you configure:

- A **local Ollama** server keeps them on your machine or network.
- **Ollama Cloud**, **OpenRouter**, or another remote provider receives them
  and handles them under its own terms and privacy policy.

If you configure the OpenProject tools, the agent sends requests to your
OpenProject instance. Nothing else calls out on your behalf.

## This documentation site

This site is a static Hugo build. It does not set its own analytics cookies.
If it is hosted by a third party, that host may keep standard request logs
under its own policy.

## Changes

This is a personal project with no compatibility promise. If the data
practices change, this page will change with them.
