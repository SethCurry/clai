import assert from 'node:assert/strict'
import { test } from 'node:test'
import { credentialError, EMPTY_CREDENTIALS, formToCredentials } from './auth.js'

test('formToCredentials trims the username and keeps the password verbatim', () => {
  assert.deepEqual(formToCredentials({ username: '  admin ', password: ' s3cret ' }), {
    username: 'admin',
    password: ' s3cret ',
  })
  assert.deepEqual(formToCredentials(EMPTY_CREDENTIALS), {
    username: '',
    password: '',
  })
})

test('formToCredentials tolerates missing fields', () => {
  assert.deepEqual(formToCredentials(undefined), { username: '', password: '' })
  assert.deepEqual(formToCredentials({ password: 'x' }), {
    username: '',
    password: 'x',
  })
})

test('credentialError names the first missing field', () => {
  assert.equal(credentialError(EMPTY_CREDENTIALS), 'Enter your username.')
  assert.equal(
    credentialError({ username: 'admin', password: '' }),
    'Enter your password.',
  )
  assert.equal(
    credentialError({ username: '   ', password: 'admin' }),
    'Enter your username.',
    'a whitespace-only username is blank',
  )
  assert.equal(credentialError({ username: 'admin', password: 'admin' }), null)
})
