import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  parseNoteLinkTitles,
  remarkNoteLinks,
  splitNoteLinks,
} from './noteLinks.js'

test('parseNoteLinkTitles returns trimmed, de-duplicated titles in order', () => {
  assert.deepEqual(
    parseNoteLinkTitles('See [[BM25 ranking]], then [[ Links ]] and [[bm25 ranking]].'),
    ['BM25 ranking', 'Links'],
  )
  assert.deepEqual(parseNoteLinkTitles('No links, just [text](url).'), [])
  assert.deepEqual(parseNoteLinkTitles('A [[]] blank and a stray [[bracket.'), [])
  assert.deepEqual(parseNoteLinkTitles(null), [])
})

test('splitNoteLinks keeps the text around a reference', () => {
  assert.deepEqual(splitNoteLinks('See [[BM25]] now.'), [
    { type: 'text', value: 'See ' },
    { type: 'note', title: 'BM25' },
    { type: 'text', value: ' now.' },
  ])
  assert.deepEqual(splitNoteLinks('[[BM25]]'), [
    { type: 'note', title: 'BM25' },
  ])
  assert.deepEqual(splitNoteLinks('none'), [{ type: 'text', value: 'none' }])
})

test('remarkNoteLinks turns resolved references into links and leaves the rest', () => {
  const tree = {
    type: 'root',
    children: [
      {
        type: 'paragraph',
        children: [
          { type: 'text', value: 'See [[BM25]] and [[Missing]].' },
        ],
      },
      {
        type: 'code',
        children: [{ type: 'text', value: '[[BM25]]' }],
      },
    ],
  }

  remarkNoteLinks({ resolve: (title) => (title === 'BM25' ? '7' : null) })(tree)

  assert.deepEqual(tree.children[0].children, [
    { type: 'text', value: 'See ' },
    {
      type: 'link',
      url: '#note/7',
      children: [{ type: 'text', value: 'BM25' }],
    },
    { type: 'text', value: ' and [[Missing]].' },
  ])
  assert.deepEqual(
    tree.children[1].children,
    [{ type: 'text', value: '[[BM25]]' }],
    'code is left untouched',
  )
})

test('remarkNoteLinks leaves references alone without a resolver', () => {
  const tree = {
    type: 'root',
    children: [{ type: 'text', value: 'See [[BM25]].' }],
  }
  remarkNoteLinks()(tree)
  assert.deepEqual(tree.children, [
    { type: 'text', value: 'See [[BM25]].' },
  ])
})
