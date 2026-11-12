import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mentionAt, tokenAt } from './mentions.js'

test('tokenAt returns the word under the caret', () => {
  assert.deepEqual(tokenAt('see @src/cl', 11), {
    start: 4,
    end: 11,
    text: '@src/cl',
  })
  assert.deepEqual(tokenAt('see @src/cl now', 8), {
    start: 4,
    end: 11,
    text: '@src/cl',
  })
})

test('tokenAt clamps impossible carets and rejects non-strings', () => {
  assert.equal(tokenAt('abc', 99).text, 'abc')
  assert.equal(tokenAt('abc', 0).text, 'abc')
  assert.equal(tokenAt('abc', -3).text, 'abc')
  assert.equal(tokenAt(null, 0), null)
})

test('mentionAt only matches words that start with @', () => {
  assert.deepEqual(mentionAt('see @src/cl', 11), {
    start: 4,
    end: 11,
    text: '@src/cl',
    path: 'src/cl',
  })
  assert.equal(mentionAt('me@example.com', 14), null)
  assert.equal(mentionAt('plain text', 10), null)
  assert.equal(mentionAt('', 0), null)
})

test('mentionAt accepts a bare @ and an empty path', () => {
  assert.deepEqual(mentionAt('@', 1), {
    start: 0,
    end: 1,
    text: '@',
    path: '',
  })
  assert.equal(mentionAt('@', 0).path, '')
})

test('mentionAt works after a newline', () => {
  assert.equal(mentionAt('line\n@src', 10).path, 'src')
})
