/**
 * The whitespace-delimited word that contains (or ends at) `caret`, plus its
 * bounds in `value`. Returns null when `value` is not a string.
 *
 * The whole word is returned even when the caret sits in the middle of it, so
 * accepting a completion replaces the token rather than appending to it.
 */
export function tokenAt(value, caret) {
    if (typeof value !== "string") return null;
    const position = Math.min(
        Math.max(Number.isInteger(caret) ? caret : value.length, 0),
        value.length,
    );
    let start = position;
    while (start > 0 && !/\s/.test(value[start - 1])) start -= 1;
    let end = position;
    while (end < value.length && !/\s/.test(value[end])) end += 1;
    return { start, end, text: value.slice(start, end) };
}

/**
 * The `@` mention token around `caret`, or null when the user is not typing
 * one. A mention starts with `@` and is the whole word: `see @src/cl` yields
 * `@src/cl`, while an address like `me@example.com` yields nothing because the
 * word does not start with `@`. `path` is the text after the `@`, which may be
 * empty when the user has just typed it.
 */
export function mentionAt(value, caret) {
    const token = tokenAt(value, caret);
    if (!token || !token.text.startsWith("@")) return null;
    return { ...token, path: token.text.slice(1) };
}
