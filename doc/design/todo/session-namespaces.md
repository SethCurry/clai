# Session Namespaces

## Goal

To create isolation between session namespaces, allowing each
one to operate indepedently and be able to be resumed.

## What does a session need in its namespace?

- It's own cwd for shell calls
    - Also all of the tools
- Its session ID
- An agent instance, that can be changed separate from the other sessions

## What improvements could this offer?

- Passing session ID through loggers, tying log messages to a session

## Implementation Options

- Use bindings maybe when creating the namespace?
