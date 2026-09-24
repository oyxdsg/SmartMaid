// 女仆背包槽位规则验证脚本（与 Java 端 MaidInventoryMenu / SmartMaidEntity.syncInventoryArmor 保持一致）
// 用法: node tools/maid_inventory_sim.js   （Node 环境，无第三方依赖）
// 作用: 验证"女仆=玩家"的槽位布局与放置规则：
//       0-8 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手；主手=热键第0格；副手任意物品；盔甲按部位。

const SLOTS = 41

// —— 槽位映射（与 Java 端 armorSlotForIndex / syncSlot 一致）——
function armorSlotForIndex(idx) {
  switch (idx) {
    case 36: return 'HEAD'
    case 37: return 'CHEST'
    case 38: return 'LEGS'
    case 39: return 'FEET'
    case 40: return 'OFFHAND'
    default: return null
  }
}

// 物品默认可装备部位（模拟 isEquippableInSlot 的装备槽判断；MC 里盾牌=OFFHAND，盔甲=对应部位）
function defaultEquipSlot(item) {
  if (item.startsWith('armor_')) {
    const m = item.match(/armor_(head|chest|legs|feet)/)
    if (m) return m[1].toUpperCase()
    return null
  }
  if (item === 'shield') return 'OFFHAND'
  return null // 剑/工具/食物等默认无装备部位（但主手/副手可拿）
}

// —— 女仆 GUI 槽位放置规则（mayPlace）——
// 盔甲槽只允许对应部位；副手（40）像玩家一样任意物品；主手=热键第0格任意物品
function mayPlace(slotIdx, item) {
  const es = armorSlotForIndex(slotIdx)
  if (es === null) return true                     // 热键/背包：任意
  if (es === 'OFFHAND') return true                // 副手：任意（与玩家一致）
  return defaultEquipSlot(item) === es             // 盔甲：按部位
}

// —— 主手持物：热键第0格（仿玩家默认选中格，Java 端 syncSlot(MAINHAND, 0)）——
function mainHandItem(inv) {
  return inv[0] || 'empty'
}

// —— 死亡全掉落：41 格全掉（与 dropAllDeathLoot 一致，玩家死亡全掉原则）——
function deathDropAll(inv) {
  return inv.map((v, i) => (v && v !== 'empty' ? `${i}:${v}` : null)).filter(Boolean)
}

// —— 测试 ——
let pass = 0, fail = 0
function check(name, ok) {
  if (ok) { pass++; console.log(`  ✓ ${name}`) }
  else { fail++; console.log(`  ✗ ${name}`) }
}

console.log('【槽位布局】0-8 热键 / 9-35 背包 / 36-39 盔甲 / 40 副手 = 41 格（与玩家背包一致）')
check('总格数 = 41', SLOTS === 41)
check('36=HEAD 37=CHEST 38=LEGS 39=FEET 40=OFFHAND',
  armorSlotForIndex(36) === 'HEAD' && armorSlotForIndex(37) === 'CHEST' &&
  armorSlotForIndex(38) === 'LEGS' && armorSlotForIndex(39) === 'FEET' &&
  armorSlotForIndex(40) === 'OFFHAND')
check('0-35 非装备槽（返回 null）', [0, 8, 9, 35].every(i => armorSlotForIndex(i) === null))

console.log('【放置规则】')
check('副手可放任意物品（剑）', mayPlace(40, 'sword'))
check('副手可放任意物品（盾牌）', mayPlace(40, 'shield'))
check('副手可放任意物品（食物）', mayPlace(40, 'food'))
check('副手可放任意物品（方块）', mayPlace(40, 'stone'))
check('热键栏可放任意物品', mayPlace(0, 'sword') && mayPlace(3, 'stone'))
check('背包可放任意物品', mayPlace(9, 'food') && mayPlace(35, 'torch'))
check('头部盔甲槽只收头盔', mayPlace(36, 'armor_head') && !mayPlace(36, 'armor_chest'))
check('胸甲槽只收胸甲', mayPlace(37, 'armor_chest') && !mayPlace(37, 'armor_legs'))
check('护腿槽只收护腿', mayPlace(38, 'armor_legs') && !mayPlace(38, 'armor_feet'))
check('靴子槽只收靴子', mayPlace(39, 'armor_feet') && !mayPlace(39, 'armor_head'))
check('盔甲槽不收非装备（剑）', !mayPlace(36, 'sword') && !mayPlace(37, 'shield'))

console.log('【主手持物】')
let invEmpty = Array(SLOTS).fill('empty')
check('热键第0格有剑 → 主手拿剑', mainHandItem(['sword', ...invEmpty.slice(1)]) === 'sword')
let invItem = Array(SLOTS).fill('empty'); invItem[0] = 'diamond'
check('热键第0格有钻石 → 主手拿钻石', mainHandItem(invItem) === 'diamond')
check('热键第0格空 → 主手空', mainHandItem(invEmpty) === 'empty')

console.log('【死亡全掉落（玩家死亡全掉原则）】')
let inv = Array(SLOTS).fill('empty'); inv[0] = 'sword'; inv[36] = 'armor_head'; inv[40] = 'shield'
const drops = deathDropAll(inv)
check('热键/盔甲/副手全部掉落', drops.length === 3 && drops.includes('0:sword') && drops.includes('36:armor_head') && drops.includes('40:shield'))
check('空槽不掉落', deathDropAll(invEmpty).length === 0)

console.log(`\n结果: ${pass} 通过, ${fail} 失败`)
if (fail > 0) process.exit(1)
