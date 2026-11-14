import assert from 'node:assert/strict'
import { test } from 'node:test'
import { pathAt, tokenAt } from './paths.js'

test('tokenAt returns the word under the caret', () => {
  assert.deepEqual(tokenAt('see ./src/cl', 12), {
    start: 4,
    end: 12,
    text: './src/cl',
  })
  assert.deepEqual(tokenAt('see ./src/cl now', 9), {
    start: 4,
    end: 12,
    text: './src/cl',
  })
})

test('tokenAt clamps impossible carets and rejects non-strings', () => {
  assert.equal(tokenAt('abc', 99).text, 'abc')
  assert.equal(tokenAt('abc', 0).text, 'abc')
  assert.equal(tokenAt('abc', -3).text, 'abc')
  assert.equal(tokenAt(null, 0), null)
})

test('pathAt only matches words that start with ./', () => {
  assert.deepEqual(pathAt('see ./src/cl', 12), {
    start: 4,
    end: 12,
    text: './src/cl',
    path: 'src/cl',
  })
  assert.equal(pathAt('see src/cl', 10), null)
  assert.equal(pathAt('a./b', 4), null)
  assert.equal(pathAt('...', 3), null)
  assert.equal(pathAt('plain text', 10), null)
  assert.equal(pathAt('', 0), null)
})

test('pathAt accepts a bare ./ and an empty path', () => {
  assert.deepEqual(pathAt('./', 2), {
    start: 0,
    end: 2,
    text: './',
    path: '',
  })
  assert.equal(pathAt('./', 0).path, '')
})

test('pathAt works after a newline', () => {
  assert.equal(pathAt('line\n./src', 11).path, 'src')
})
