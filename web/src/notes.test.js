import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  EMPTY_NOTE_FORM,
  formToNoteInput,
  linkLabel,
  noteId,
  notePreview,
  noteTitle,
  noteToForm,
  resolveTitle,
  sortNotes,
  titleIds,
  titleLookup,
} from './notes.js'

test('noteToForm keeps only the fields the editor shows', () => {
  const stored = {
    noteId: '7',
    title: 'BM25',
    content: 'Notes are ranked by BM25, see [[Links]].',
    links: ['2'],
    backlinks: ['9'],
    createdAt: '2024-01-02T03:04:05Z',
    updatedAt: '2024-01-02T04:04:05Z',
  }
  assert.deepEqual(noteToForm(stored), {
    title: 'BM25',
    content: 'Notes are ranked by BM25, see [[Links]].',
  })
  assert.deepEqual(noteToForm(null), EMPTY_NOTE_FORM)
})

test('formToNoteInput trims the title and content', () => {
  assert.deepEqual(
    formToNoteInput({
      title: '  BM25  ',
      content: '\n Notes are ranked. \n',
    }),
    { title: 'BM25', content: 'Notes are ranked.' },
  )
})

test('formToNoteInput requires a title and content', () => {
  assert.throws(() => formToNoteInput({ title: '  ', content: 'x' }), {
    message: 'A note title is required.',
  })
  assert.throws(() => formToNoteInput({ title: 'x', content: ' \n ' }), {
    message: 'Note content is required.',
  })
})

test('notePreview collapses whitespace and ellipsizes long content', () => {
  assert.equal(notePreview('one\ntwo\t three'), 'one two three')
  assert.equal(notePreview('abcdef', 5), 'abcd…')
  assert.equal(notePreview('abc', 5), 'abc')
  assert.equal(notePreview(null), '')
})

test('noteId and noteTitle handle missing values', () => {
  assert.equal(noteId({ noteId: '4' }), '4')
  assert.equal(noteId({ noteId: 4 }), '4')
  assert.equal(noteId({}), null)
  assert.equal(noteId(null), null)
  assert.equal(noteTitle({ title: '  ' }), 'Untitled note')
  assert.equal(noteTitle({ title: ' BM25 ' }), 'BM25')
})

test('sortNotes puts the most recently updated note first and does not mutate', () => {
  const notes = [
    { noteId: '1', updatedAt: '2024-01-01T00:00:00Z' },
    { noteId: '3', updatedAt: '2024-01-03T00:00:00Z' },
    { noteId: '2', updatedAt: '2024-01-03T00:00:00Z' },
    { noteId: '4' },
  ]
  assert.deepEqual(
    sortNotes(notes).map((note) => note.noteId),
    ['3', '2', '1', '4'],
  )
  assert.deepEqual(
    notes.map((note) => note.noteId),
    ['1', '3', '2', '4'],
  )
})

test('titleLookup and linkLabel resolve ids to titles with a fallback', () => {
  const titles = titleLookup([
    { noteId: '1', title: 'BM25' },
    { noteId: '2', title: 'Links' },
  ])
  assert.equal(linkLabel(titles, '1'), 'BM25')
  assert.equal(linkLabel(titles, 1), 'BM25')
  assert.equal(linkLabel(titles, '99'), 'Note #99')
  assert.equal(linkLabel(titleLookup([]), '5'), 'Note #5')
})

test('titleIds and resolveTitle resolve a [[Title]] reference like storage', () => {
  const ids = titleIds([
    { noteId: '1', title: 'BM25 ranking' },
    { noteId: '2', title: 'Links' },
    { title: 'no id' },
  ])
  assert.equal(resolveTitle(ids, 'BM25 ranking'), '1')
  assert.equal(resolveTitle(ids, '  bm25 RANKING '), '1')
  assert.equal(resolveTitle(ids, 'Links'), '2')
  assert.equal(resolveTitle(ids, 'missing'), null)
  assert.equal(resolveTitle(ids, '  '), null)
  assert.equal(resolveTitle(ids, null), null)
})
