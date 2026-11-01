(ns clai.tools
  "Public API for the `clai` tool registry.

  Ollama's `/api/chat` accepts a `:tools` vector describing function-style
  tools the model may call. When the model decides to call one, the response
  message carries a `:tool_calls` vector; the agent is expected to execute
  each call, append a `{:role \"tool\" ...}` message with the result, and chat
  again. This namespace re-exports:

    * `register-tool!` / `unregister-tool!` -- mutate the global registry.
    * `spec` / `specs` / `specs-for`       -- Ollama tool definitions, ready
                                              to send.
    * `invoke`                             -- run a named tool by id with
                                              parsed arguments.
    * `execute-calls`                      -- run a batch of Ollama
                                              `tool_calls`, returning the
                                              `tool`-role result messages.
    * `built-in-tools`                     -- a default set of filesystem/
                                              shell/eval tools.

  The implementation lives in smaller namespaces under `clai.tools.*`:

    * `clai.tools.schema`    -- malli schemas shared by the others.
    * `clai.tools.registry`  -- the global tool registry.
    * `clai.tools.invoke`    -- parsing and executing model tool calls.
    * `clai.tools.builtin`   -- the default built-in tools (and installation).
    * `clai.tools.builtin.*` -- individual built-in tool implementations.

  A tool is a map with three keys:

    :id    the tool name (matches the Ollama `function.name`).
    :fn    a Clojure function `(args ctx) -> string`, where `args` is the
           parsed JSON arguments map and `ctx` carries session context
           (`:cwd`, ...). Returning a string keeps the tool-result message
           simple; non-string return values are pr-str'd.
    :spec  the Ollama tool definition map:
           `{:type \"function\" :function {:name ... :description ...
            :parameters {:type \"object\" :properties {...} :required [...]}}}`

  Tool execution never throws out to the caller: if a tool function raises,
  the exception message is returned as the tool result so the model can react."
  (:require [clai.tools.builtin :as builtin]
            [clai.tools.invoke :as invoke]
            [clai.tools.registry :as registry]
            [clai.tools.schema :as schema]))

(defmacro ^:private import-vars
  "Define public vars in this namespace that alias the implementation vars
  named by `syms`, copying their docs and malli schemas."
  [& syms]
  `(do
     ~@(for [sym syms]
         (let [local (symbol (name sym))]
           `(do
              (def ~local ~sym)
              (alter-meta! (var ~local)
                           merge
                           (select-keys (meta (var ~sym))
                                        [:doc :arglists :malli/schema])))))))

;; Schemas
(import-vars schema/non-empty-string
             schema/tool-id
             schema/tool-parameters
             schema/tool-spec
             schema/tool-args
             schema/tool-ctx
             schema/tool-result
             schema/tool
             schema/tool-registry
             schema/tool-call
             schema/tool-call-message)

;; Registry
(import-vars registry/register-tool!
             registry/unregister-tool!
             registry/registered-tools
             registry/spec
             registry/specs
             registry/specs-for)

;; Invocation
(import-vars invoke/parse-arguments
             invoke/invoke
             invoke/tool-call->id+args
             invoke/execute-calls)

;; Built-ins
(import-vars builtin/built-in-tools)
