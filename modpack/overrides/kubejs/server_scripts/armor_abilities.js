// priority: 1
// 玩家状态：高级套装飞行、夜视、创世维度检查、伤害防护和扭曲器次数恢复。
// 秒级状态先处理，随后由回血奖励模块统计同一刻的生命变化。
;(() => {
    const helpers = global.gtlPlayer
    const baseFlyingSpeed = 0.05
    const flightSpeedEpsilon = 0.00001
    const flightLimits = { magneto: 8, fermium: 4, spaceFermium: 4, infinity: 6 }
    // Compare item IDs in the four armor slots, independent of damage/NBT/display names.
    function wears(player, set) {
        const namespace = set == "infinity" ? "avaritia:" : "kubejs:"
        const ids = helpers.armorSets[set]
        const slots = player.getArmorSlots().iterator()
        for (const id of ids) {
            if (!slots.hasNext()) return false
            // The pack’s Rhino interpreter must refresh this binding for each slot.
            let stack = slots.next()
            if (stack.isEmpty() || String(stack.getId()) != namespace + id) return false
        }
        return true
    }
    PlayerEvents.tick(event => {
        const player = event.player
        if (player.age % 20 != 0) return
        player.onUpdateAbilities()
        const hfa = wears(player, "spaceFermium"), fa = wears(player, "fermium"), ma = wears(player, "magneto")
        const name = player.getName().getString()
        // 创世维度只允许磁流体星物质套装；仍按原顺序传送、击杀并清理周围实体。
        if (player.getLevel().getDimension() == "kubejs:create") {
            if (!ma) {
                player.getServer().runCommandSilent(`execute in minecraft:overworld as ${name} run tp 0 100 0`)
                player.kill()
            }
            event.getServer().runCommandSilent(`execute at ${name} run kill @e[distance=..100,name=!${name},type=!item]`)
        }
        if (ma || fa || hfa) {
            player.potionEffects.add("minecraft:saturation", 200, 0, false, false)
            if (player.persistentData.getBoolean("nv")) player.potionEffects.add("minecraft:night_vision", 300, 0, false, false)
            if (!player.abilities.mayfly) {
                player.abilities.mayfly = true
                player.persistentData.putInt("fly", 1)
                player.persistentData.putInt("fspeed", 1)
                player.onUpdateAbilities()
            }
        } else if (player.persistentData.getInt("fly") == 1) {
            player.abilities.mayfly = false
            player.persistentData.putInt("fly", 0)
            player.abilities.setFlyingSpeed(baseFlyingSpeed)
            player.persistentData.putInt("fspeed", 1)
            player.onUpdateAbilities()
        }
        // 保留原次数判断：每秒加一；超过二十时仅将提示显示为二十。
        const uses = player.persistentData.getInt("tt")
        const held = player.getHeldItem("main_hand") == "kubejs:time_twister"
        if (uses > 20) {
            if (held) player.setStatusMessage("可使用次数：" + 20)
        } else {
            player.persistentData.putInt("tt", uses + 1)
            if (held) player.setStatusMessage("可使用次数：" + (uses + 1))
        }
    })
    function flyingSpeed(player, maxMultiplier) {
        const abilities = player.abilities
        if (player.isSteppingCarefully()) {
            abilities.setFlyingSpeed(baseFlyingSpeed)
            player.setStatusMessage("飞行速度重置")
            player.persistentData.putInt("fspeed", 1)
            player.onUpdateAbilities()
            return
        }
        const liveSpeed = abilities.getFlyingSpeed()
        const maxSpeed = baseFlyingSpeed * maxMultiplier
        if (liveSpeed >= maxSpeed - flightSpeedEpsilon) {
            if (liveSpeed > maxSpeed + flightSpeedEpsilon) {
                abilities.setFlyingSpeed(maxSpeed)
                player.persistentData.putInt("fspeed", maxMultiplier)
                player.onUpdateAbilities()
            }
            player.setStatusMessage("达到极限")
            return
        }
        // Read the authoritative ability, not a counter that can outlive equipment changes.
        const next = Math.min(maxMultiplier, Math.max(1, Math.floor((liveSpeed + flightSpeedEpsilon) / baseFlyingSpeed)) + 1)
        abilities.setFlyingSpeed(baseFlyingSpeed * next)
        player.persistentData.putInt("fspeed", next)
        player.setStatusMessage("飞行速度x" + next)
        player.onUpdateAbilities()
    }
    NetworkEvents.dataReceived("global.flyingspeedKey.consumeClick", event => {
        const player = event.player
        if (!player || !player.abilities.mayfly) return
        for (const set of Object.keys(flightLimits)) {
            if (wears(player, set)) {
                flyingSpeed(player, flightLimits[set])
                return
            }
        }
    })
    NetworkEvents.dataReceived("global.nightvisionKey.consumeClick", event => {
        const player = event.player
        if (wears(player, "fermium") || wears(player, "magneto") || wears(player, "spaceFermium")) {
            if (player.persistentData.getBoolean("nv")) {
                player.persistentData.putBoolean("nv", false)
                player.removeEffect("minecraft:night_vision")
                player.setStatusMessage("夜视关闭")
            } else {
                player.persistentData.putBoolean("nv", true)
                player.setStatusMessage("夜视开启")
            }
        }
    })
    EntityEvents.hurt("player", event => {
        if (helpers.isWearing(event.entity, helpers.armorSets.magneto)) event.cancel()
        // 太空服和空间镄保留氧气、溺水免伤条件及原事件对象。
        if ((event.getSource().type().msgId() == "oxygen" || event.getSource().type().msgId() == "drown") &&
            (helpers.isWearing(event.entity, helpers.armorSets.quarktech) || helpers.isWearing(event.entity, helpers.armorSets.quarktechAdv) || helpers.isWearing(event.player, helpers.armorSets.spaceFermium))) event.cancel()
    })
})()
