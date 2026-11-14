import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  EMPTY_NOTE_FORM,
  addLink,
  formToNoteInput,
  linkCandidates,
  linkLabel,
  noteId,
  notePreview,
  noteTitle,
  noteToForm,
  normalizeLinks,
  removeLink,
  sortNotes,
  titleLookup,
} from './notes.js'

test('normalizeLinks trims, drops blanks, and de-duplicates as strings', () => {
  assert.deepEqual(normalizeLinks([' 2 ', 2, '', null, '1', '  ']), ['2', '1'])
  assert.deepEqual(normalizeLinks(undefined), [])
  assert.deepEqual(normalizeLinks('1'), [])
})

test('noteToForm round-trips a loaded note', () => {
  const stored = {
    noteId: '7',
    title: 'BM25',
    content: 'Notes are ranked by BM25.',
    links: ['2'],
    backlinks: ['9'],
    createdAt: '2024-01-02T03:04:05Z',
    updatedAt: '2024-01-02T04:04:05Z',
  }
  assert.deepEqual(noteToForm(stored), {
    title: 'BM25',
    content: 'Notes are ranked by BM25.',
    links: ['2'],
  })
  assert.deepEqual(noteToForm(null), EMPTY_NOTE_FORM)
})

test('formToNoteInput trims the title and content and keeps the links', () => {
  assert.deepEqual(
    formToNoteInput({
      title: '  BM25  ',
      content: '\n Notes are ranked. \n',
      links: ['3', '3'],
    }),
    { title: 'BM25', content: 'Notes are ranked.', links: ['3'] },
  )
})

test('formToNoteInput requires a title and content and always returns links', () => {
  assert.throws(() => formToNoteInput({ title: '  ', content: 'x' }), {
    message: 'A note title is required.',
  })
  assert.throws(() => formToNoteInput({ title: 'x', content: ' \n ' }), {
    message: 'Note content is required.',
  })
  assert.deepEqual(formToNoteInput({ title: 'x', content: 'y' }).links, [])
})

test('addLink adds once and removeLink drops only that link', () => {
  assert.deepEqual(addLink(['1'], '2'), ['1', '2'])
  assert.deepEqual(addLink(['1'], 1), ['1'])
  assert.deepEqual(addLink(['1'], ''), ['1'])
  assert.deepEqual(removeLink(['1', '2'], 1), ['2'])
  assert.deepEqual(removeLink(['1'], '5'), ['1'])
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

test('linkCandidates excludes the note itself and its current links', () => {
  const notes = [
    { noteId: '1', title: 'Zeta' },
    { noteId: '2', title: 'alpha' },
    { noteId: '3', title: 'Middle' },
    { title: 'no id' },
  ]
  assert.deepEqual(
    linkCandidates(notes, ['3'], '1').map((note) => note.noteId),
    ['2'],
  )
  assert.deepEqual(
    linkCandidates(notes, [], null).map((note) => note.noteId),
    ['2', '3', '1'],
  )
})
