# Browsing Clojure namespaces in the web UI

The **Namespaces** tab of the explorer panel shows the Clojure runtime that the
**REPL** panel evaluates in. It is a way to find what you want to call before
you type it: open a namespace, look at its Vars, click one to read its
docstring, argument lists, and source, then copy the symbol into the REPL.

The session you have selected has its own namespace, `sessions.<session-id>`
(the same one the REPL evaluates in), and that namespace starts open, so you
see your own definitions first.

## Using it

- **Namespaces** in the panel header switches from the Files tab, **Files**
  switches back. The `×` hides the panel; **Show explorer** in the top bar
  brings it back.
- Click a namespace to open it and click it again to close it. Its public Vars
  are fetched the first time you open it, so a big runtime does not load all at
  once.
- Click a Var to read it in the pane at the bottom. Click it again (or the `×`)
  to stop reading it.
- **Filter namespaces and vars** narrows both levels as you type, matching
  names and docstrings, ignoring case. A namespace that matches by name or
  docstring keeps all of its Vars; a namespace that only holds a matching Var
  narrows to that Var.
- **copy** puts the qualified symbol (`clojure.string/join`) on your
  clipboard, ready to paste into the REPL.
- **Refresh** re-reads the namespace list, every open namespace, and the Var
  you are reading, which is how a `def` you just evaluated shows up.

## What the preview shows

| Section         | What it holds                                          |
| --------------- | ------------------------------------------------------ |
| Usage           | the argument lists, e.g. `[coll] [separator coll]`      |
| Docstring       | the Var's own docstring                                |
| Malli schema    | its `:malli/schema`, when it has one                   |
| Defined in      | the source file and line, when it has one              |
| Source          | the form that defines it, for Vars loaded from a file  |
| Value           | its current value, for Vars that hold data             |

A Var defined at the REPL has no file, so it shows no **Defined in** or
**Source** section, and a function has no **Value** (printing one would only
show an object id). Macros are marked **macro**.

## What it lists

- Every namespace loaded in the daemon's JVM, with its docstring and how many
  public Vars it holds. Session namespaces come first, then everything else in
  name order.
- Each namespace's **public** Vars. Private Vars are left out because the REPL
  cannot call them, and referred names are left out because they belong to the
  namespace that defined them -- look under `clai.repl` for the helpers a
  session namespace refers in.

The panel reads the runtime the daemon runs in, which is the same JVM as the
REPL for `clai web`. If you attach a separate nREPL client with
`clai shell`, definitions you make there are visible to the panel too, because
they live in the same server.

## See also

- [Browsing files in the web UI](file-explorer.md): the Files tab, for the
  session's working directory.
- [Plugins](plugins.md): add behavior to every prompt.
