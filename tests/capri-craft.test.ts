import test from 'node:test'
import assert from 'node:assert/strict'
import { parseCraftCommand, readModelAnswer } from '../src/main/services/minecraft/capri-craft-service'

test('diamonds, with or without a number', () => {
  assert.deepEqual(parseCraftCommand('andá a buscarme diamantes'), { action: 'get', item: 'diamond', count: 3 })
  assert.deepEqual(parseCraftCommand('traeme 5 diamantes'), { action: 'get', item: 'diamond', count: 5 })
  assert.deepEqual(parseCraftCommand('consígueme un diamante'), { action: 'get', item: 'diamond', count: 1 })
  assert.deepEqual(parseCraftCommand('go get me two diamonds'), { action: 'get', item: 'diamond', count: 2 })
})

test('the specific pickaxe wins over the material', () => {
  assert.deepEqual(parseCraftCommand('hacete un pico de hierro'), { action: 'get', item: 'iron_pickaxe', count: 1 })
  assert.deepEqual(parseCraftCommand('craft a stone pickaxe'), { action: 'get', item: 'stone_pickaxe', count: 1 })
  assert.deepEqual(parseCraftCommand('buscame hierro'), { action: 'get', item: 'iron_ingot', count: 3 })
  assert.deepEqual(parseCraftCommand('juntame madera'), { action: 'get', item: 'log', count: 16 })
})

test('stop only when it is a stop', () => {
  assert.deepEqual(parseCraftCommand('pará', true), { action: 'stop' })
  assert.deepEqual(parseCraftCommand('capri, basta', true), { action: 'stop' })
  assert.equal(parseCraftCommand('pará', false), null)
  assert.deepEqual(parseCraftCommand('dejá de jugar al minecraft'), { action: 'stop' })
  // "para" as a preposition is not a stop
  assert.deepEqual(parseCraftCommand('buscame madera para la casa', true), { action: 'get', item: 'log', count: 16 })
})

test('coordinates', () => {
  assert.deepEqual(parseCraftCommand('andá a 100 64 -200'), { action: 'goto', x: 100, y: 64, z: -200 })
  assert.deepEqual(parseCraftCommand('go to 10, -5'), { action: 'goto', x: 10, z: -5 })
})

test('ordinary talk is not for Minecraft', () => {
  assert.equal(parseCraftCommand('hace calor hoy'), null)
  assert.equal(parseCraftCommand('qué es un diamante'), null)
  assert.equal(parseCraftCommand('abrí youtube'), null)
})

test('the Nether is a trip, not a thing', () => {
  assert.deepEqual(parseCraftCommand('andá al nether'), { action: 'nether' })
  assert.deepEqual(parseCraftCommand('llevame al Nether'), { action: 'nether' })
  assert.deepEqual(parseCraftCommand('go to the nether'), { action: 'nether' })
  assert.equal(parseCraftCommand('el nether da miedo'), null)
})

test('survival things', () => {
  assert.deepEqual(parseCraftCommand('hacete una espada'), { action: 'get', item: 'stone_sword', count: 1 })
  assert.deepEqual(parseCraftCommand('hacete una espada de hierro'), { action: 'get', item: 'iron_sword', count: 1 })
  assert.deepEqual(parseCraftCommand('hacete una armadura'), { action: 'get', item: 'iron_armor', count: 1 })
  assert.deepEqual(parseCraftCommand('conseguí comida'), { action: 'get', item: 'food', count: 4 })
  assert.deepEqual(parseCraftCommand('buscame obsidiana'), { action: 'get', item: 'obsidian', count: 10 })
  assert.deepEqual(parseCraftCommand('hacete un encendedor'), { action: 'get', item: 'flint_and_steel', count: 1 })
  // "pico de diamante" is the pickaxe, not diamonds
  assert.deepEqual(parseCraftCommand('hacete un pico de diamante'), { action: 'get', item: 'diamond_pickaxe', count: 1 })
})

test('the model can only ask for what the mod can do', () => {
  assert.deepEqual(readModelAnswer('{"action":"get","item":"iron_sword","count":1}'), { action: 'get', item: 'iron_sword', count: 1 })
  assert.deepEqual(readModelAnswer('Sure! {"action":"mine","block":"sand","count":20}'), { action: 'mine', block: 'sand', count: 20 })
  assert.deepEqual(readModelAnswer('{"action":"goto","x":10.4,"z":-3}'), { action: 'goto', x: 10, z: -3 })
  assert.deepEqual(readModelAnswer('{"action":"unsupported","what":"build a house"}'), { action: 'unsupported', what: 'build a house' })
  // A made-up name comes back to what the mod knows; any other block id is dug up
  assert.deepEqual(readModelAnswer('{"action":"get","item":"cooked meat","count":2}'), { action: 'get', item: 'food', count: 2 })
  assert.deepEqual(readModelAnswer('{"action":"get","item":"minecraft:sand","count":5}'), { action: 'mine', block: 'sand', count: 5 })
  assert.equal(readModelAnswer('{"action":"mine","block":"../../etc","count":1}'), null)
  assert.equal(readModelAnswer('{"action":"none"}'), null)
  assert.equal(readModelAnswer('not json'), null)
  assert.deepEqual(readModelAnswer('{"action":"get","item":"diamond","count":99999}'), { action: 'get', item: 'diamond', count: 640 })
})
