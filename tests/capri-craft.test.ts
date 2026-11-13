import test from 'node:test'
import assert from 'node:assert/strict'
import { parseCraftCommand } from '../src/main/services/minecraft/capri-craft-service'

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
