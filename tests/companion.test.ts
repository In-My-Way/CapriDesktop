import test from 'node:test'
import assert from 'node:assert/strict'
import { parseCompanionOrder, parseJoinLeave } from '../src/main/services/minecraft/companion-commands'
import { readCompanionAnswer } from '../src/main/services/minecraft/companion-service'

test('joining and leaving the world', () => {
  assert.deepEqual(parseJoinLeave('capri unite a mi mundo'), { action: 'join' })
  assert.deepEqual(parseJoinLeave('vení a minecraft'), { action: 'join' })
  assert.deepEqual(parseJoinLeave('join my world'), { action: 'join' })
  assert.deepEqual(parseJoinLeave('salí del mundo'), { action: 'leave' })
  assert.deepEqual(parseJoinLeave('leave the game'), { action: 'leave' })
  assert.equal(parseJoinLeave('vení acá'), null)
  assert.equal(parseJoinLeave('qué lindo día'), null)
})

test('following, waiting, coming', () => {
  assert.deepEqual(parseCompanionOrder('seguime'), { action: 'follow' })
  assert.deepEqual(parseCompanionOrder('follow me'), { action: 'follow' })
  assert.deepEqual(parseCompanionOrder('quedate acá'), { action: 'stay' })
  assert.deepEqual(parseCompanionOrder('wait here'), { action: 'stay' })
  assert.deepEqual(parseCompanionOrder('vení acá'), { action: 'come' })
  assert.deepEqual(parseCompanionOrder('come here'), { action: 'come' })
  assert.deepEqual(parseCompanionOrder('pará'), { action: 'stop' })
})

test('getting things, for the player or for herself', () => {
  assert.deepEqual(parseCompanionOrder('traeme 5 diamantes'), { action: 'get', item: 'diamond', count: 5, give: true })
  assert.deepEqual(parseCompanionOrder('hacete una espada de hierro'), { action: 'get', item: 'iron_sword', count: 1, give: false })
  assert.deepEqual(parseCompanionOrder('juntame madera'), { action: 'get', item: 'log', count: 16, give: true })
  assert.deepEqual(parseCompanionOrder('get me food'), { action: 'get', item: 'food', count: 4, give: true })
})

test('killing', () => {
  assert.deepEqual(parseCompanionOrder('matá a los zombies'), { action: 'kill', mob: 'zombie', count: 1 })
  assert.deepEqual(parseCompanionOrder('matá 3 vacas'), { action: 'kill', mob: 'cow', count: 3 })
  assert.deepEqual(parseCompanionOrder('matá todos los monstruos'), { action: 'kill', mob: 'hostile', count: 10 })
  assert.deepEqual(parseCompanionOrder('kill 2 skeletons'), { action: 'kill', mob: 'skeleton', count: 2 })
})

test('building', () => {
  assert.deepEqual(parseCompanionOrder('construime una casa'), { action: 'build', what: 'house' })
  assert.deepEqual(parseCompanionOrder('haceme una casa de piedra'), { action: 'build', what: 'house', material: 'stone' })
  assert.deepEqual(parseCompanionOrder('build a tower'), { action: 'build', what: 'tower' })
  assert.deepEqual(parseCompanionOrder('armá un refugio de madera'), { action: 'build', what: 'shelter', material: 'wood' })
})

test('giving and asking what she has', () => {
  assert.deepEqual(parseCompanionOrder('dame todo'), { action: 'give', item: 'all' })
  assert.deepEqual(parseCompanionOrder('qué tenés'), { action: 'inventory' })
  assert.deepEqual(parseCompanionOrder('andá a 100 64 -200'), { action: 'goto', x: 100, y: 64, z: -200 })
})

test('small talk is not an order', () => {
  assert.equal(parseCompanionOrder('qué lindo atardecer'), null)
  assert.equal(parseCompanionOrder('te quiero mucho'), null)
})

test('the model answer is checked', () => {
  assert.deepEqual(readCompanionAnswer('{"action":"get","item":"minecraft:shield","count":1,"give":true}'), { action: 'get', item: 'shield', count: 1, give: true })
  assert.deepEqual(readCompanionAnswer('{"action":"kill","mob":"zombie","count":2}'), { action: 'kill', mob: 'zombie', count: 2 })
  assert.deepEqual(readCompanionAnswer('{"action":"build","what":"castle"}'), { action: 'build', what: 'house' })
  assert.deepEqual(readCompanionAnswer('{"action":"follow"}'), { action: 'follow' })
  assert.equal(readCompanionAnswer('{"action":"none"}'), null)
  assert.equal(readCompanionAnswer('not json'), null)
})
