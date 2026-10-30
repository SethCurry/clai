# clai

**Be very careful running this, you have been warned.**

This is me toying with the idea of running an agent in Clojure and then
exposing the REPL for that JVM to the agent so it can modify its own
runtime live.

It should enable the agent to modify itself at runtime, in major ways.

At the end of this, you should end up with an agent where you can just say
"I want to use a new LLM provider.  My key is $x, and the API docs are at $y.
Write a provider for them, and then run this prompt: $z"

It should be able to write a provider that implements that and then send that
prompt, or even add subagent support to itself and use that.

**WARNING**

This is not sandboxed at all.  Your LLM can modify its harness to steal your
memecoins and tell your cat that Santa isn't real.

Be extremely careful.  You should _not_ run this outside of Docker, a VM, something like that.

Also, _the entire repo is vibecoded_.  This is mostly a toy, I'm not reviewing what goes on in here
for the most part.  There could be cryptominers in here, I have no idea, and I make no promises.
