const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')

const source = fs.readFileSync(path.join(__dirname, '../overrides/kubejs/server_scripts/armor_abilities.js'), 'utf8')
const slots = ['boots', 'leggings', 'chestplate', 'helmet']
const suits = {
  magneto: slots.map(name => 'magnetohydrodynamicallyconstrainedstarmatter_' + name),
  fermium: slots.map(name => 'fermium_' + name),
  spaceFermium: slots.map(name => 'space_fermium_' + name),
  infinity: ['infinity_boots', 'infinity_pants', 'infinity_chestplate', 'infinity_helmet']
}

function fixture(set = 'fermium') {
  let speed = Math.fround(.05), updates = 0, mutationCount = 0, message = null
  let sneak = false
  let items = suits[set].map(id => ({ getId: () => (set === 'infinity' ? 'avaritia:' : 'kubejs:') + id, isEmpty: () => false }))
  const values = { fspeed: 1 }
  let tick
  const listeners = {}
  const player = {
    age: 20,
    abilities: { mayfly: true, getFlyingSpeed: () => speed, setFlyingSpeed: x => { speed = Math.fround(x); mutationCount++ } },
    persistentData: {
      getInt: name => values[name] || 0,
      putInt: (name, value) => { values[name] = value },
      getBoolean: () => false
    },
    potionEffects: { add() {} },
    onUpdateAbilities: () => { updates++ },
    setStatusMessage: text => { message = text },
    isSteppingCarefully: () => sneak,
    getName: () => ({ getString: () => 'Fixture' }),
    getLevel: () => ({ getDimension: () => 'minecraft:overworld' }),
    getHeldItem: () => null,
    getArmorSlots: () => ({
      // Do not rely on a Java stack-list display representation.
      toString: () => 'armor with damage/NBT or a different serialization',
      iterator: () => { let i = 0; return { hasNext: () => i < items.length, next: () => items[i++] } }
    })
  }
  vm.runInNewContext(source, {
    global: { gtlPlayer: {
      armorSets: suits,
      isWearing(player, ids) {
        const iterator = player.getArmorSlots().iterator()
        for (let i = 0; i < 4; i++) {
          if (!iterator.hasNext()) return false
          const stack = iterator.next()
          if (stack.isEmpty() || stack.getId().split(':').pop() !== ids[i]) return false
        }
        return true
      }
    } },
    PlayerEvents: { tick: cb => { tick = cb } },
    NetworkEvents: { dataReceived: (name, cb) => { listeners[name] = cb } },
    EntityEvents: { hurt() {} }
  })
  return {
    player, values, press: () => listeners['global.flyingspeedKey.consumeClick']({ player }),
    tick: () => tick({ player }), read: () => ({ speed, updates, mutationCount, message }),
    sneak: () => { sneak = true }, emptySlot: () => { items[3] = { isEmpty: () => true } },
    otherNamespace: () => { items[3] = { isEmpty: () => false, getId: () => 'foreign:' + suits[set][3] } },
    setLiveSpeed: value => { speed = Math.fround(value) }
  }
}

test('full armor matches item IDs and a stale saved counter cannot stall the first step', () => {
  const f = fixture()
  f.values.fspeed = 99
  f.press()
  assert.deepEqual(f.read(), { speed: Math.fround(.1), updates: 1, mutationCount: 1, message: '飞行速度x2' })
  assert.equal(f.values.fspeed, 2)
})

test('every suit reaches but never exceeds its own cap, including Float rounding', () => {
  for (const [suit, count] of Object.entries({ magneto: 8, fermium: 4, spaceFermium: 4, infinity: 6 })) {
    const f = fixture(suit)
    for (let i = 1; i < count; i++) f.press()
    assert.equal(f.read().speed, Math.fround(count * .05))
    assert.equal(f.values.fspeed, count)
    f.press()
    assert.equal(f.read().mutationCount, count - 1)
    assert.equal(f.read().message, '达到极限')
  }
})

test('Shift resets, then the next ordinary key responds to the live speed', () => {
  const f = fixture()
  f.setLiveSpeed(.15)
  f.sneak()
  f.press()
  assert.equal(f.read().speed, Math.fround(.05))
  assert.equal(f.values.fspeed, 1)
  assert.equal(f.read().updates, 1)
})

test('partial set and ungranted flight cannot change speed via the server event', () => {
  const partial = fixture()
  partial.emptySlot()
  partial.press()
  assert.equal(partial.read().mutationCount, 0)
  const grounded = fixture()
  grounded.player.abilities.mayfly = false
  grounded.press()
  assert.equal(grounded.read().mutationCount, 0)
  grounded.tick()
  assert.equal(grounded.player.abilities.mayfly, true)
  grounded.press()
  assert.equal(grounded.read().speed, Math.fround(.10))
})

test('changing to a lower-cap suit clamps a higher inherited speed on the next press', () => {
  const f = fixture('fermium')
  f.setLiveSpeed(.4)
  f.values.fspeed = 8
  f.press()
  assert.deepEqual(f.read(), { speed: Math.fround(.2), updates: 1, mutationCount: 1, message: '达到极限' })
  assert.equal(f.values.fspeed, 4)
  f.press()
  assert.equal(f.read().mutationCount, 1)
})

test('a foreign item with the same path is not part of the registered suit', () => {
  const f = fixture()
  f.otherNamespace()
  f.press()
  assert.equal(f.read().mutationCount, 0)
})
