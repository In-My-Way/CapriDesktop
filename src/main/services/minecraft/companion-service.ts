import { consciousnessService } from '../consciousness-service'
import { getLocalModel, llmFetch } from '../llm-gateway'
import { CompanionOrder, parseCompanionOrder, parseJoinLeave } from './companion-commands'

/**
 * Capri in your Minecraft world with a body of her own (the Capri Craft mod spawns her next to you,
 * with her skin). You ask by voice or in the game's chat; she answers out loud and in the chat too,
 * and tells you how it's going: what she found, what she crafted, when she got hurt or died.
 */

const BRIDGE = 'http://127.0.0.1:25599'

interface CompanionEvent {
  id: number
  kind: string
  detail: string
}

interface CompanionStatus {
  present: boolean
  server?: boolean
  mode?: string
  task?: string
  step?: string
  health?: number
  maxHealth?: number
  inventory?: Record<string, number>
  equipment?: Record<string, string>
  events?: CompanionEvent[]
}

async function bridge<T>(path: string, init?: RequestInit, timeoutMs = 1500): Promise<T | null> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const res = await fetch(`${BRIDGE}${path}`, { ...init, signal: controller.signal })
    if (!res.ok && res.status !== 202) return null
    return (await res.json()) as T
  } catch {
    return null
  } finally {
    clearTimeout(timer)
  }
}

/** JSON with every non-ASCII character escaped: the mod counts the body in bytes and chars alike */
const asciiJson = (value: unknown): string => JSON.stringify(value).replace(/[\u0080-￿]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`)

const NAMES_ES: Record<string, string> = {
  diamond: 'diamantes', iron_ingot: 'hierro', raw_iron: 'hierro crudo', coal: 'carbón', log: 'madera', planks: 'tablas', stick: 'palos',
  cobblestone: 'piedra', food: 'comida', iron_armor: 'una armadura de hierro', diamond_armor: 'una armadura de diamante', flint: 'pedernal',
  obsidian: 'obsidiana', wooden_pickaxe: 'un pico de madera', stone_pickaxe: 'un pico de piedra', iron_pickaxe: 'un pico de hierro',
  diamond_pickaxe: 'un pico de diamante', stone_sword: 'una espada', iron_sword: 'una espada de hierro', crafting_table: 'una mesa de crafteo',
  furnace: 'un horno', flint_and_steel: 'un encendedor', iron_helmet: 'un casco de hierro', iron_chestplate: 'una pechera de hierro',
  iron_leggings: 'unos pantalones de hierro', iron_boots: 'unas botas de hierro', sand: 'arena', dirt: 'tierra', gravel: 'grava',
  leather: 'cuero', string: 'hilo', wool: 'lana', torch: 'antorchas', bread: 'pan'
}

const MOBS_ES: Record<string, string> = {
  zombie: 'zombie', skeleton: 'esqueleto', creeper: 'creeper', spider: 'araña', cave_spider: 'araña de cueva', drowned: 'ahogado',
  husk: 'zombie del desierto', stray: 'esqueleto helado', witch: 'bruja', slime: 'slime', enderman: 'enderman', piglin: 'piglin',
  zombified_piglin: 'piglin zombi', cow: 'vaca', pig: 'cerdo', sheep: 'oveja', chicken: 'pollo', rabbit: 'conejo', hostile: 'monstruos',
  phantom: 'phantom', pillager: 'saqueador', vindicator: 'vindicador', blaze: 'blaze'
}

// Worth asking the model about while she's in the world: sounds like asking her to do something
const REQUEST_HINT =
  /(?<!\p{L})(?:and[aá]|ve|vamos|tra[eé]|busc[aá]|hac[eé]|hacete|constru[ií]|mat[aá]|consegu[ií]|junt[aá]|prepar[aá]|equip[aá]te|pon[eé]te|com[eé]|caz[aá]|cav[aá]|pic[aá]|tal[aá]|cort[aá]|dame|necesito|quiero|go|get|make|build|kill|find|bring|craft|collect|hunt|mine|chop|dig|give|need|want)(?!\p{L})/iu

/** Chat-sized pieces (the game shows long lines fine, but a wall of text is hard to read while playing) */
export function chatLines(text: string, max = 200): string[] {
  const lines: string[] = []
  let rest = text.trim()
  while (rest.length > max) {
    const window = rest.slice(0, max + 1)
    let cut = Math.max(window.lastIndexOf('. '), window.lastIndexOf('! '), window.lastIndexOf('? '), window.lastIndexOf('… '))
    cut = cut > max / 3 ? cut + 1 : window.lastIndexOf(' ')
    if (cut <= 0) cut = max
    lines.push(rest.slice(0, cut).trim())
    rest = rest.slice(cut).trim()
  }
  if (rest) lines.push(rest)
  return lines.slice(0, 6)
}

export type CompanionReading = CompanionOrder | { action: 'unsupported'; what: string } | null

/** Checks what the model answered: only orders the mod knows get through */
export function readCompanionAnswer(raw: string): CompanionReading {
  let data: any
  try {
    data = JSON.parse(raw.replace(/^[^{]*/, '').replace(/[^}]*$/, ''))
  } catch {
    return null
  }
  const action = String(data?.action || '')
  const count = Math.max(1, Math.min(Number(data?.count) || 1, 640))
  const clean = (v: unknown) => String(v || '').toLowerCase().replace(/^minecraft:/, '').trim().replace(/[\s-]+/g, '_')
  switch (action) {
    case 'get': {
      const item = clean(data.item)
      return /^[a-z_]{2,40}$/.test(item) ? { action: 'get', item, count, give: data.give !== false } : null
    }
    case 'mine': {
      const block = clean(data.block || data.item)
      return /^[a-z_]{2,40}$/.test(block) ? { action: 'mine', block, count, give: data.give === true } : null
    }
    case 'kill': {
      const mob = clean(data.mob) || 'hostile'
      return /^[a-z_]{2,40}$/.test(mob) ? { action: 'kill', mob, count } : null
    }
    case 'build': {
      const what = ['house', 'shelter', 'tower', 'wall'].includes(data.what) ? data.what : 'house'
      const material = data.material === 'stone' ? 'stone' : data.material === 'wood' ? 'wood' : undefined
      return material ? { action: 'build', what, material } : { action: 'build', what }
    }
    case 'give':
      return { action: 'give', item: clean(data.item) || 'all', ...(data.count ? { count } : {}) }
    case 'goto':
      if (Number.isFinite(data.x) && Number.isFinite(data.z)) {
        return Number.isFinite(data.y) ? { action: 'goto', x: Math.round(data.x), y: Math.round(data.y), z: Math.round(data.z) } : { action: 'goto', x: Math.round(data.x), z: Math.round(data.z) }
      }
      return null
    case 'follow':
    case 'stay':
    case 'come':
    case 'stop':
    case 'join':
    case 'leave':
      return { action }
    case 'unsupported':
      return { action: 'unsupported', what: String(data.what || '').slice(0, 80) }
    default:
      return null
  }
}

class CompanionService {
  private speak: (text: string) => void = () => {}
  private prompt: (text: string) => void = () => {}
  private presence: (inWorld: boolean) => void = () => {}
  private present = false
  private inWorld = false
  private lastEvent = 0
  private primed = false
  private polling: NodeJS.Timeout | null = null
  private busyUntil = 0
  private queued: string[] = []
  private flushTimer: NodeJS.Timeout | null = null
  private lastStep = ''
  private lastStepAt = 0
  private lastFightAt = 0
  private status: CompanionStatus | null = null
  // You wrote to her in the game's chat before she joined: her answer goes back there for a while
  private chatReplyUntil = 0

  /** How she talks (set once by the agent manager): out loud, and a way to hand a chat line to her brain */
  public setup(speak: (text: string) => void, prompt: (text: string) => void, presence?: (inWorld: boolean) => void): void {
    this.speak = speak
    this.prompt = prompt
    if (presence) this.presence = presence
    if (this.polling) return
    this.polling = setInterval(() => void this.poll(), 1200)
    this.polling.unref?.()
  }

  /** Is she in the world right now? (her answers go to the game's chat too while she is) */
  public isPresent(): boolean {
    return this.present
  }

  private get es(): boolean {
    return consciousnessService.getPreferredLanguage() === 'es'
  }

  /** Writes it in the game's chat as her ("<Capri> ..."), while she's there or answering what you wrote in it */
  public mirror(text: string): void {
    if (!this.present && !(this.inWorld && Date.now() < this.chatReplyUntil)) return
    void this.postToChat(text)
  }

  /** A long answer goes as several chat lines, cut between sentences, instead of being chopped */
  /** She's in the game now, or back (the desktop shows it: she glitches out of it or onto it) */
  private setPresent(present: boolean): void {
    if (present === this.present) return
    this.present = present
    this.presence(present)
  }

  private async postToChat(text: string): Promise<void> {
    const clean = (text || '').replace(/\[[^\]\n]{1,40}\]/g, '').replace(/\s+/g, ' ').trim()
    for (const line of chatLines(clean)) {
      await bridge('/companion', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: asciiJson({ action: 'say', text: line }) })
    }
  }

  /**
   * What her brain should know while she's in the world: she's playing there with you, what she's
   * doing, and that her answer also lands in the game's chat
   */
  public contextNote(): string | null {
    if (!this.present) return null
    const s = this.status
    const doing = s?.task && s.task !== 'none' ? `busy with "${s.task}"${s.step ? ` (now: ${s.step})` : ''}` : s?.mode === 'stay' ? 'waiting where {name} told you to stay' : 'following {name} around'
    const carrying = Object.entries(s?.inventory || {})
      .sort((a, b) => b[1] - a[1])
      .slice(0, 6)
      .map(([k, v]) => `${v} ${k.replace(/_/g, ' ')}`)
      .join(', ')
    return (
      "Right now you're also inside {name}'s Minecraft world with your own body (the Capri Craft mod), playing with them. " +
      `You're ${doing}${s?.health !== undefined ? `, with ${Math.ceil(s.health / 2)} of 10 hearts` : ''}${carrying ? `, carrying ${carrying}` : ''}. ` +
      "What you say is also posted in the game's chat, so keep it short (one or two sentences) and talk like a friend playing alongside them. " +
      "Game orders (get, mine, kill, build, follow...) are carried out separately: never claim you started doing something in the game unless they asked for it."
    )
  }

  /**
   * A chat or voice line. `handled` false when it isn't for her body in the game, so the rest of her
   * brain gets it (and the older "play as me" mode, when she isn't in the world).
   */
  public async handleCommand(text: string): Promise<{ handled: boolean; reply?: string }> {
    const joinLeave = parseJoinLeave(text)
    if (!joinLeave && !this.present) return { handled: false }

    const ping = await bridge<{ inWorld: boolean; companion?: boolean; server?: boolean }>('/ping', undefined, 500)
    if (joinLeave && !ping) {
      return {
        handled: true,
        reply: this.es
          ? '[curious] No veo Minecraft con el mod Capri Craft abierto. Abrí Minecraft 26.3 con Fabric y Capri Craft, entrá a tu mundo y pedímelo de nuevo.'
          : "[curious] I can't see Minecraft with the Capri Craft mod running. Open Minecraft 26.3 with Fabric and Capri Craft, join your world and ask me again."
      }
    }
    if (!ping) return { handled: false }
    if (!ping.server || !ping.inWorld) {
      if (!joinLeave) return { handled: false }
      return { handled: true, reply: this.es ? '[curious] Minecraft está abierto, pero todavía no entraste a un mundo.' : "[curious] Minecraft is open, but you're not in a world yet." }
    }
    this.setPresent(!!ping.companion)

    let order: CompanionReading = parseCompanionOrder(text)
    if (!order) {
      if (!this.present || text.trim().split(/\s+/).length > 30 || !REQUEST_HINT.test(text)) return { handled: false }
      order = await this.askModel(text)
      if (!order) return { handled: false }
      if (order.action === 'unsupported') return { handled: true, reply: this.unsupportedLine(order.what) }
    }

    if (order.action === 'join' && this.present) {
      await this.send({ action: 'come' })
      return { handled: true, reply: this.es ? '[happy] ¡Ya estoy en tu mundo! Voy para donde estás.' : "[happy] I'm already in your world! Coming to you." }
    }
    if (order.action === 'leave' && !this.present) {
      return { handled: true, reply: this.es ? '[curious] No estoy en tu mundo ahora.' : "[curious] I'm not in your world right now." }
    }
    if (order.action !== 'join' && !this.present) return { handled: false }

    if (order.action === 'inventory') return { handled: true, reply: await this.inventoryLine() }

    const sent = await this.send(order)
    if (!sent) return { handled: true, reply: this.es ? '[sad] El mod no me respondió. ¿Sigue abierto Minecraft?' : "[sad] The mod didn't answer. Is Minecraft still open?" }
    if (order.action === 'join' || order.action === 'leave') return { handled: true }
    return { handled: true, reply: this.acceptLine(order) }
  }

  private async send(order: object): Promise<boolean> {
    const res = await bridge<{ ok: boolean }>('/companion', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: asciiJson(order) })
    return !!res?.ok
  }

  /** The model turns a free request into one order, "unsupported", or nothing (it wasn't for the game) */
  private async askModel(text: string): Promise<CompanionReading> {
    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), 20000)
    try {
      const res = await llmFetch('http://127.0.0.1:11434/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: controller.signal,
        body: JSON.stringify({
          model: getLocalModel(),
          stream: false,
          format: 'json',
          keep_alive: '30m',
          options: { temperature: 0, num_predict: 80, quick: true },
          messages: [
            {
              role: 'system',
              content:
                'You are Capri, a companion with your own body inside the player\'s Minecraft world. Turn what they say into ONE JSON order, nothing else.\n' +
                '{"action":"get","item":"<minecraft item id>","count":N,"give":true} get or craft any item (you find, mine, smelt and craft everything it needs). give=true to hand it to the player. Use "food" for cooked meat, "log" for wood, "iron_armor"/"diamond_armor" for a full set.\n' +
                '{"action":"mine","block":"<minecraft block id>","count":N} dig up blocks\n' +
                '{"action":"kill","mob":"<entity id or hostile>","count":N}\n' +
                '{"action":"build","what":"house|shelter|tower|wall","material":"wood|stone"}\n' +
                '{"action":"give","item":"<item id or all>"} hand the player something you carry\n' +
                '{"action":"follow"} {"action":"stay"} {"action":"come"} {"action":"stop"} {"action":"leave"}\n' +
                '{"action":"goto","x":X,"y":Y,"z":Z}\n' +
                '{"action":"unsupported","what":"<short description>"} for a Minecraft request outside that list (farming, enchanting, the End, redstone...)\n' +
                '{"action":"none"} when it is not an order (a question, small talk, feelings).\n' +
                'Examples: "preparate para pelear" -> {"action":"get","item":"iron_sword","count":1,"give":false}; "tengo hambre" -> {"action":"get","item":"food","count":4,"give":true}; ' +
                '"traeme 20 de arena" -> {"action":"mine","block":"sand","count":20}; "haceme un escudo" -> {"action":"get","item":"shield","count":1,"give":true}; ' +
                '"cuidame" -> {"action":"follow"}; "limpiá la zona de monstruos" -> {"action":"kill","mob":"hostile","count":10}; "qué lindo día" -> {"action":"none"}.'
            },
            { role: 'user', content: text }
          ]
        })
      })
      if (!res.ok) return null
      const data = (await res.json()) as { message?: { content?: string } }
      const reading = readCompanionAnswer(data.message?.content || '')
      console.log(`[CapriCompanion] The model read "${text}" as ${JSON.stringify(reading)}`)
      return reading
    } catch (err: any) {
      console.warn('[CapriCompanion] The model could not read the request:', err?.message || err)
      return null
    } finally {
      clearTimeout(timer)
    }
  }

  // ---- Watching the game ----

  private async poll(): Promise<void> {
    const status = await bridge<CompanionStatus>(`/companion/status?since=${this.lastEvent}`, undefined, 1000)
    if (!status) {
      this.setPresent(false)
      this.inWorld = false
      return
    }
    this.inWorld = !!status.server
    this.setPresent(!!status.present)
    this.status = status
    const events = status.events || []
    // Whatever happened before the app was watching was already said (or never will be)
    if (!this.primed) {
      this.primed = true
      this.lastEvent = Math.max(this.lastEvent, ...events.map((e) => e.id), 0)
      return
    }
    const lines: string[] = []
    for (const e of events) {
      this.lastEvent = Math.max(this.lastEvent, e.id)
      if (e.kind === 'chat') {
        this.onChat(e.detail)
        continue
      }
      const line = this.lineFor(e)
      if (line) lines.push(line)
    }
    if (lines.length) this.say(lines.join(' '))
  }

  /** You typed in the game's chat: it goes to her like anything you'd say to her */
  private onChat(text: string): void {
    const t = text.trim()
    if (!t || t.startsWith('/')) return
    // Without her in the world, only lines for her ("capri, unite a mi mundo")
    if (!this.present && !/\bcapri\b/i.test(t) && !parseJoinLeave(t)) return
    this.prompt(t.replace(/^capri[\s,:]+/i, ''))
  }

  private say(line: string): void {
    this.queued.push(line)
    this.flush()
  }

  private flush(): void {
    const now = Date.now()
    if (now < this.busyUntil) {
      if (!this.flushTimer) {
        this.flushTimer = setTimeout(() => {
          this.flushTimer = null
          this.flush()
        }, this.busyUntil - now + 50)
      }
      return
    }
    const parts = this.queued.splice(0)
    if (!parts.length) return
    const line = parts.map((p, i) => (i === 0 ? p : p.replace(/^\s*\[[a-z ]+\]\s*/i, ''))).join(' ').trim()
    this.speak(line)
    this.mirrorAlways(line)
    this.busyUntil = now + Math.max(3000, line.length * 65)
  }

  /** Her narration goes to the chat even in the moment she's arriving or leaving */
  private mirrorAlways(text: string): void {
    if (this.inWorld) void this.postToChat(text)
  }

  private pick(options: string[]): string {
    return options[Math.floor(Math.random() * options.length)]
  }

  private thing(id: string): string {
    const base = id.replace(/ x\d+$/, '')
    return this.es ? NAMES_ES[base] || base.replace(/_/g, ' ') : base.replace(/_/g, ' ')
  }

  private mob(id: string): string {
    return this.es ? MOBS_ES[id] || id.replace(/_/g, ' ') : id.replace(/_/g, ' ')
  }

  private lineFor(e: CompanionEvent): string | null {
    const es = this.es
    const d = e.detail || ''
    switch (e.kind) {
      case 'joined':
        if (d === 'again') return null
        return d === 'back'
          ? es ? this.pick(['[excited] ¡Volví! Traje todas mis cosas.', '[happy] ¡Acá estoy de nuevo, con todo lo que tenía!']) : "[excited] I'm back! I brought all my stuff."
          : es
            ? '[excited] ¡Hola! Ya estoy en tu mundo. Te sigo a todos lados. Pedime lo que quieras: madera, diamantes, una casa, que mate monstruos... por voz o por el chat. Si me hacés clic derecho ves mi inventario.'
            : "[excited] Hi! I'm in your world now and I'll follow you around. Ask me anything: wood, diamonds, a house, clearing out monsters... by voice or in the chat. Right-click me to see my inventory."
      case 'left':
        return d === 'not_here' ? null : es ? '[happy] Me voy por ahora. Me guardo mis cosas para la próxima.' : "[happy] Heading out for now. I'll keep my things for next time."
      case 'mode':
        return d === 'stay'
          ? es ? '[happy] Me quedo acá. Avisame cuando quieras que te siga.' : "[happy] I'll stay right here. Tell me when you want me to follow."
          : es ? '[happy] ¡Te sigo!' : "[happy] Right behind you!"
      case 'step':
        return this.stepLine(d)
      case 'seen':
        if (/diamond/.test(d)) return es ? '[excited] ¡Veo diamantes!' : '[excited] I see diamonds!'
        if (/iron/.test(d)) return es ? this.pick(['[excited] ¡Ahí hay hierro!', '[happy] Encontré hierro.']) : '[excited] There\'s iron!'
        if (/gold/.test(d)) return es ? '[excited] ¡Oro!' : '[excited] Gold!'
        if (/obsidian/.test(d)) return es ? '[excited] ¡Obsidiana a la vista!' : '[excited] Obsidian in sight!'
        return null
      case 'crafted':
        if (/pickaxe|sword|axe|shovel|helmet|chestplate|leggings|boots|shield|bucket|bow/.test(d)) {
          return es ? this.pick([`[proud] Listo: ${this.thing(d)}.`, `[proud] Me hice ${this.thing(d)}.`]) : `[proud] Made ${this.thing(d)}.`
        }
        return null
      case 'smelted':
        return d === 'food' ? (es ? '[happy] Comida cocinada.' : '[happy] Food is cooked.') : es ? '[happy] Ya fundí lo que necesitaba.' : '[happy] Done smelting.'
      case 'fight': {
        if (Date.now() - this.lastFightAt < 15000) return null
        this.lastFightAt = Date.now()
        return es ? this.pick([`[surprised] ¡Un ${this.mob(d)}! Yo me encargo.`, `[surprised] ¡Cuidado, ${this.mob(d)}!`]) : `[surprised] A ${this.mob(d)}! I've got it.`
      }
      case 'flee':
        return es ? '[worried] ¡Creeper! Me alejo.' : '[worried] Creeper! Backing off.'
      case 'ate':
        return es ? '[happy] Comí algo, me siento mejor.' : '[happy] Ate something, feeling better.'
      case 'wore':
        return es ? `[proud] Me puse ${this.thing(d)}.` : `[proud] Put on ${this.thing(d)}.`
      case 'died':
        return es
          ? `[sad] Me mataron (${d})... pero volví al lado tuyo y no perdí nada.`
          : `[sad] I got killed (${d})... but I'm back next to you and didn't lose anything.`
      case 'gave':
        return es ? '[happy] Tomá, es para vos.' : '[happy] Here, this is for you.'
      case 'build_needs': {
        const [mat, n] = d.split(' x')
        return es
          ? `[thoughtful] Para esto necesito unos ${n} bloques de ${mat === 'cobblestone' ? 'piedra' : 'tablas'}. Si no me alcanzan, los consigo primero.`
          : `[thoughtful] I need about ${n} ${mat} for this. If I don't have enough, I'll get them first.`
      }
      case 'building':
        return es ? '[excited] ¡Tengo todo! Empiezo a construir.' : '[excited] I have everything! Starting to build.'
      case 'built':
        return es ? '[proud] ¡Terminé! ¿Qué te parece?' : '[proud] Done! What do you think?'
      case 'done':
        return this.doneLine(d)
      case 'failed':
        return this.failLine(d)
      case 'stopped':
        return es ? '[happy] Ok, paro.' : '[happy] Okay, stopping.'
      case 'dropped':
        return es ? '[happy] Dejé todo acá.' : '[happy] Left everything here.'
      default:
        return null
    }
  }

  private stepLine(step: string): string | null {
    const es = this.es
    // The same step again, or too soon after the last one: quiet
    if (step === this.lastStep || Date.now() - this.lastStepAt < 6000) return null
    this.lastStep = step
    this.lastStepAt = Date.now()
    const [verb, ...rest] = step.split(' ')
    const what = rest.join(' ')
    switch (verb) {
      case 'gather':
        if (what === 'wood') return es ? this.pick(['[happy] Voy a talar un árbol.', '[happy] Primero, madera.']) : '[happy] Going to chop a tree.'
        if (what === 'stone') return es ? '[happy] Saco piedra.' : '[happy] Getting stone.'
        return es ? `[happy] Voy a buscar ${this.thing(what)}.` : `[happy] Getting ${this.thing(what)}.`
      case 'dig':
        if (what === 'for diamonds') return es ? '[excited] Bajo a buscar diamantes. Cavo una escalera hasta abajo de todo y después un túnel.' : "[excited] Going down for diamonds: a staircase all the way down, then a tunnel."
        return es ? `[happy] Cavo para buscar ${this.thing(what.replace(/^for /, ''))}.` : `[happy] Digging for ${what.replace(/^for /, '')}.`
      case 'craft':
        return /pickaxe|sword|table|furnace|door|ladder/.test(what) ? (es ? `[happy] Me hago ${this.thing(what)}.` : `[happy] Making ${this.thing(what)}.`) : null
      case 'smelt':
        return es ? '[happy] Al horno.' : '[happy] Into the furnace.'
      case 'hunt':
        return es ? `[happy] Voy a cazar ${what === 'food' ? 'algo para comer' : this.thing(what)}.` : `[happy] Hunting ${what === 'food' ? 'something to eat' : what}.`
      case 'kill':
        return null
      case 'build':
        return null
      default:
        return null
    }
  }

  private doneLine(step: string): string | null {
    const es = this.es
    if (step.startsWith('give') || step.startsWith('build')) return null
    if (step === 'come') return es ? '[happy] Acá estoy.' : '[happy] Here I am.'
    if (step.startsWith('goto')) return es ? '[happy] Llegué.' : '[happy] Made it.'
    if (step.startsWith('kill')) return es ? '[proud] Listo, zona limpia.' : '[proud] Done, all clear.'
    return es ? '[excited] ¡Listo!' : '[excited] Done!'
  }

  private failLine(reason: string): string {
    const es = this.es
    if (reason === 'not_here') return es ? '[curious] No estoy en tu mundo. Pedime que me una primero.' : "[curious] I'm not in your world. Ask me to join first."
    if (reason === 'no_player') return es ? '[curious] No te encuentro en el mundo.' : "[curious] I can't find you in the world."
    if (reason.startsWith('only:')) return es ? `[happy] Maté ${reason.slice(5)} y no veo más por acá.` : `[happy] Got ${reason.slice(5)}, and I don't see any more around.`
    if (reason.startsWith('could_not_find:')) {
      const what = reason.slice(15)
      return es ? `[sad] No encuentro ${this.thing(what)} por acá. Llevame a otro lado y pedímelo de nuevo.` : `[sad] I can't find ${what} around here. Take me somewhere else and ask again.`
    }
    if (reason.startsWith('blocked:lava')) return es ? '[worried] Hay lava en el camino, mejor no sigo por ahí.' : "[worried] There's lava in the way, I'd better not keep going."
    if (reason.startsWith('blocked:')) return es ? '[sad] No puedo seguir cavando por acá.' : "[sad] I can't keep digging here."
    if (reason.startsWith('dont_have:')) return es ? `[curious] No tengo ${this.thing(reason.slice(10))}.` : `[curious] I don't have ${reason.slice(10)}.`
    if (reason.startsWith('unknown_mob:')) return es ? '[curious] No sé qué bicho es ese.' : "[curious] I don't know that mob."
    if (reason.startsWith('unknown:') || reason.startsWith('cannot_get:')) return es ? '[curious] No sé cómo conseguir eso en Minecraft.' : "[curious] I don't know how to get that in Minecraft."
    if (reason === 'no_room_for:build_water') return es ? '[thoughtful] Ahí hay mucha agua para construir. Llevame a un lugar seco y plano y pedímelo de nuevo.' : "[thoughtful] There's too much water there to build. Take me somewhere dry and flat and ask again."
    if (reason.startsWith('no_room_for')) return es ? '[sad] No tengo lugar para poner la mesa o el horno acá.' : "[sad] There's no room here for the table or the furnace."
    if (reason.startsWith('cant_reach')) return es ? '[sad] No llego hasta ahí.' : "[sad] I can't get there."
    return es ? `[sad] No pude terminar (${reason}).` : `[sad] I couldn't finish (${reason}).`
  }

  private unsupportedLine(what: string): string {
    return this.es
      ? `[thoughtful] Eso${what ? ` (${what})` : ''} todavía no lo sé hacer. Puedo conseguirte cualquier material o herramienta, cazar, matar monstruos, construir una casa, un refugio, una torre o un muro, seguirte o esperarte.`
      : `[thoughtful] I can't do that${what ? ` (${what})` : ''} yet. I can get you any material or tool, hunt, kill monsters, build a house, a shelter, a tower or a wall, follow you or wait.`
  }

  private acceptLine(order: CompanionOrder): string {
    const es = this.es
    switch (order.action) {
      case 'follow':
      case 'stay':
        return '' // the mod's own event says it
      case 'come':
        return es ? '[happy] ¡Voy!' : '[happy] Coming!'
      case 'stop':
        return ''
      case 'drop':
        return ''
      case 'get': {
        const name = this.thing(order.item)
        const amount = order.count > 1 && !/^(?:un|una|unos|unas) /.test(name) && es ? `${order.count} de ` : ''
        if (es) return order.give ? `[excited] ¡Dale! Te consigo ${amount}${name}. Lo que me falte para eso, lo hago en el camino.` : `[excited] ¡Dale! Me consigo ${amount}${name}.`
        return `[excited] On it! Getting ${order.count > 1 ? order.count + ' ' : ''}${order.item.replace(/_/g, ' ')}${order.give ? ' for you' : ''}.`
      }
      case 'mine':
        return es ? `[excited] Voy a picar ${order.count} de ${this.thing(order.block)}.` : `[excited] Mining ${order.count} ${order.block.replace(/_/g, ' ')}.`
      case 'kill':
        return es
          ? order.mob === 'hostile' ? '[excited] ¡A limpiar la zona de monstruos!' : `[excited] Voy por ${order.count > 1 ? `${order.count} ${this.mob(order.mob)}s` : `el ${this.mob(order.mob)}`}.`
          : `[excited] Going after ${order.mob === 'hostile' ? 'the monsters' : order.mob}!`
      case 'build': {
        const names: Record<string, [string, string]> = { house: ['una casa', 'a house'], shelter: ['un refugio', 'a shelter'], tower: ['una torre', 'a tower'], wall: ['un muro', 'a wall'] }
        const [nEs, nEn] = names[order.what]
        return es ? `[excited] ¡Te construyo ${nEs} acá adelante!` : `[excited] Building you ${nEn} right in front of you!`
      }
      case 'give':
        return es ? (order.item === 'all' ? '[happy] Te doy todo lo que tengo.' : `[happy] Te doy ${this.thing(order.item)}.`) : '[happy] Here you go.'
      case 'goto':
        return es ? `[excited] Voy para ${order.x}, ${order.z}.` : `[excited] Heading to ${order.x}, ${order.z}.`
      default:
        return es ? '[happy] ¡Dale!' : '[happy] On it!'
    }
  }

  private async inventoryLine(): Promise<string> {
    const status = await bridge<CompanionStatus>('/companion/status?since=9007199254740991')
    const es = this.es
    const entries = Object.entries(status?.inventory || {}).sort((a, b) => b[1] - a[1])
    const { mainhand, ...armor } = status?.equipment || {}
    const worn = Object.values(armor)
    const health = status?.health !== undefined ? (es ? ` Tengo ${Math.ceil(status.health / 2)} corazones.` : ` I have ${Math.ceil(status.health / 2)} hearts.`) : ''
    if (!entries.length && !worn.length && !mainhand) return (es ? '[happy] No llevo nada encima todavía.' : "[happy] I'm not carrying anything yet.") + health
    const list = entries.slice(0, 8).map(([k, v]) => `${v} ${this.thing(k)}`).join(', ')
    const wearing = worn.length ? (es ? ` Tengo puesto: ${worn.map((w) => this.thing(w)).join(', ')}.` : ` Wearing: ${worn.map((w) => w.replace(/_/g, ' ')).join(', ')}.`) : ''
    const holding = mainhand ? (es ? ` En la mano tengo ${this.thing(mainhand)}.` : ` Holding ${mainhand.replace(/_/g, ' ')}.`) : ''
    return (es ? `[happy] Llevo ${list || 'nada más'}.` : `[happy] I'm carrying ${list || 'nothing else'}.`) + wearing + holding + health
  }
}

export const companionService = new CompanionService()
