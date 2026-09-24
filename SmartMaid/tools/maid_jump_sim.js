// 女仆移动/跳跃速度计算脚本（基于 prismarine-physics 权威公式，与 MC 原版逐 tick 一致）
// 用法: node tools/maid_jump_sim.js   （Node 环境，无第三方依赖）
// 作用: 标定 MOVEMENT_SPEED → 地面速度、跳跃落点、朝向。修改 MOVE_SPEED 可重算。

const MOVE_SPEED = 0.1          // 女仆 MOVEMENT_SPEED 属性（与玩家一致）
const SPRINT_MULT = 1.3
const SLIPPERINESS = 0.6        // 普通方块摩擦
const GRAVITY = 0.08
const VERT_DRAG = 0.98
const AIR_INERTIA = 0.91
const AIR_ACCEL = 0.02          // 空中持续前进输入的加速度
const SPRINT_JUMP_BOOST = 0.2   // 疾跑起跳水平冲量

const fwd = (yaw) => ({ x: -Math.sin(yaw), z: Math.cos(yaw) })

// 模拟: 助跑 runUpTicks → 起跳 → 空中（含 0.91 摩擦 + 可选空中输入）直到落地
function simulate({ sprint, airInput, runUpTicks = 60, yaw = 0 }) {
  let pos = { x: 0, y: 0, z: 0 }
  let vel = { x: 0, z: 0, y: 0 }
  const inertiaGround = SLIPPERINESS * AIR_INERTIA
  const attr = MOVE_SPEED * (sprint ? SPRINT_MULT : 1)

  // 助跑（地面）：vel=(vel+attr)×inertiaGround，收敛于 vel*=attr×inertia/(1-inertia)
  for (let t = 0; t < runUpTicks; t++) {
    const f = fwd(yaw)
    vel.x += f.x * attr
    vel.z += f.z * attr
    pos.x += vel.x
    pos.z += vel.z
    vel.x *= inertiaGround
    vel.z *= inertiaGround
  }
  const groundVel = Math.hypot(vel.x, vel.z)          // 摩擦后 vel（起跳继承值）
  const groundDisp = groundVel + attr                  // 位移速度（含本 tick accel）
  const takeoffX = pos.x, takeoffZ = pos.z

  // 起跳：vy=0.42；sprint +0.2 冲量；水平继承当前 deltaMovement
  vel.y = 0.42
  if (sprint) {
    const f = fwd(yaw)
    vel.x += f.x * SPRINT_JUMP_BOOST
    vel.z += f.z * SPRINT_JUMP_BOOST
  }

  // 空中（MC/prismarine 顺序：先输入加速 → 位移 → 重力/垂直drag → 水平drag）
  let flightTicks = 0
  for (let t = 0; t < 40; t++) {
    if (airInput) {
      const f = fwd(yaw)
      vel.x += f.x * AIR_ACCEL
      vel.z += f.z * AIR_ACCEL
    }
    pos.x += vel.x
    pos.y += vel.y
    pos.z += vel.z
    vel.y -= GRAVITY
    vel.y *= VERT_DRAG
    vel.x *= AIR_INERTIA
    vel.z *= AIR_INERTIA
    flightTicks++
    if (pos.y <= 0) break
  }
  return {
    groundVel, groundDisp,
    flightTicks,
    landDist: Math.hypot(pos.x - takeoffX, pos.z - takeoffZ),
    landVec: { x: +(pos.x - takeoffX).toFixed(2), z: +(pos.z - takeoffZ).toFixed(2) },
  }
}

console.log(`MOVEMENT_SPEED = ${MOVE_SPEED}（玩家一致）`)
console.log('--- 地面速度 ---')
for (const sprint of [false, true]) {
  const s = simulate({ sprint, airInput: false, runUpTicks: 200 })
  console.log(`  ${sprint ? '跑' : '走'}: 位移速度=${s.groundDisp.toFixed(3)} blocks/tick = ${(s.groundDisp * 20).toFixed(2)} m/s`)
}
console.log('--- 跳跃落点（从起跳点） ---')
for (const sprint of [false, true]) {
  for (const airInput of [false, true]) {
    const s = simulate({ sprint, airInput, runUpTicks: 60 })
    console.log(`  ${sprint ? '跑' : '走'}跳 ${airInput ? '空中输入' : '纯惯性'}: 落点=${s.landDist.toFixed(2)}格 飞行=${s.flightTicks}tick`)
  }
}
console.log('--- 朝向验证（跑跳+空中输入） ---')
for (const yawDeg of [0, 45, 90, 180, 225]) {
  const yaw = yawDeg * Math.PI / 180
  const s = simulate({ sprint: true, airInput: true, runUpTicks: 60, yaw })
  console.log(`  yaw=${yawDeg}° → 落点 (${s.landVec.x}, ${s.landVec.z}) 距离=${s.landDist.toFixed(2)}`)
}

console.log('--- pillar 垫高（垂直跳跃，原地放脚下） ---')
// 起跳 vy0=0.42，垂直顺序：位移先于重力。脚底 y 从 0 起，>1.0 即离开原站立格，
// 此时原格空出，放方块到脚下 → 落地站在新方块上，高度 +1。
{
  let vy = 0.42, y = 0
  let leaveTick = -1, apexTick = -1, apexY = 0
  for (let t = 1; t <= 14; t++) {
    y += vy
    vy = (vy - 0.08) * 0.98
    if (leaveTick < 0 && y > 1.0) leaveTick = t
    if (y > apexY) { apexY = y; apexTick = t }
    if (y <= 0 && t > 1) break
  }
  // 放方块最佳时机 = 脚底离开站立格的第一 tick（原格刚空出，越早越稳）
  // 落地 tick：y<=0
  let yy = 0, vvy = 0.42, landTick = 0
  for (let t = 1; t <= 20; t++) {
    yy += vvy; vvy = (vvy - 0.08) * 0.98
    if (yy <= 0) { landTick = t; break }
  }
  console.log(`  PILLAR_PLACE_TICK = ${leaveTick}（起跳后第 ${leaveTick} tick，脚底离开站立格 y>1.0，原格空出可放）`)
  console.log(`  最高点 y=${apexY.toFixed(3)} @t=${apexTick}  落地 t=${landTick}（飞行约 ${landTick} tick）`)
}
