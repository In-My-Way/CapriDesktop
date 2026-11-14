import { consciousnessService } from '../consciousness-service'
import { getLocalModel, llmFetch } from '../llm-gateway'

/**
 * Capri Craft: she plays Minecraft as the player. The Capri Craft mod (minecraft-mod/) runs inside
 * the game and listens on 127.0.0.1:25599; this side understands what was asked ("andá a buscarme
 * diamantes"), hands it to the mod, and talks about how it's going while the mod does the work.
 */

const BRIDGE = 'http://127.0.0.1:25599'

export type CraftTask =
  | { action: 'get'; item: string; count: number }
  | { action: 'mine'; block: string; count: number }
  | { action: 'goto'; x: number; y?: number; z: number }
  | { action: 'nether' }
  | { action: 'stop' }

interface CraftEvent {
  id: number
  kind: 'started' | 'step' | 'got' | 'placed' | 'crafted' | 'smelted' | 'done' | 'failed' | 'stopped' | 'fled' | 'fight' | 'ate' | 'wore' | 'arrived'
  detail: string
}

interface CraftStatus {
  state: 'idle' | 'running'
  inWorld?: boolean
  action?: string
  target?: string
  count?: number
  step?: string
  have?: number
  need?: number
  events?: CraftEvent[]
}

// What she understands, the specific things before the general ones ("pico de hierro" before "hierro")
const ITEMS: { re: RegExp; item: string; plural?: RegExp; many: number }[] = [
  { re: /pico de diamante|diamond pick(?:axe)?/i, item: 'diamond_pickaxe', many: 1 },
  { re: /pico de hierro|iron pick(?:axe)?/i, item: 'iron_pickaxe', many: 1 },
  { re: /pico de piedra|stone pick(?:axe)?/i, item: 'stone_pickaxe', many: 1 },
  { re: /pico de madera|wood(?:en)? pick(?:axe)?/i, item: 'wooden_pickaxe', many: 1 },
  { re: /\bpico\b|pickaxe/i, item: 'wooden_pickaxe', many: 1 },
  { re: /espada de hierro|iron sword/i, item: 'iron_sword', many: 1 },
  { re: /\bespada\b|\bsword\b/i, item: 'stone_sword', many: 1 },
  { re: /\bcasco\b|helmet/i, item: 'iron_helmet', many: 1 },
  { re: /pechera|peto|chestplate/i, item: 'iron_chestplate', many: 1 },
  { re: /pantal[oó]n|pantalones|grebas|leggings/i, item: 'iron_leggings', many: 1 },
  { re: /\bbotas\b|\bboots\b/i, item: 'iron_boots', many: 1 },
  { re: /armadura|\barmor\b|\barmour\b/i, item: 'iron_armor', many: 1 },
  { re: /encendedor|mechero|flint and steel|yesquero/i, item: 'flint_and_steel', many: 1 },
  { re: /pedernal|\bflint\b/i, item: 'flint', many: 1 },
  { re: /obsidiana|obsidian/i, item: 'obsidian', many: 10 },
  { re: /comida|\bcomer\b|algo de comer|\bfood\b|something to eat|carne|\bmeat\b/i, item: 'food', many: 4 },
  { re: /mesa de (?:crafteo|trabajo|crafting)|crafting table|workbench/i, item: 'crafting_table', many: 1 },
  { re: /\bhorno\b|furnace/i, item: 'furnace', many: 1 },
  { re: /diamante|diamond/i, item: 'diamond', plural: /diamantes|diamonds/i, many: 3 },
  { re: /lingote|\bhierro\b|\biron\b/i, item: 'iron_ingot', many: 3 },
  { re: /carb[oó]n|\bcoal\b/i, item: 'coal', many: 8 },
  { re: /tabla|plank/i, item: 'planks', many: 16 },
  { re: /\bpalos?\b|stick/i, item: 'stick', many: 8 },
  { re: /madera|tronco|\bwood\b|\blogs?\b/i, item: 'log', many: 16 },
  { re: /piedra|\broca\b|cobble|\bstone\b/i, item: 'cobblestone', many: 32 }
]

const NUMBER_WORDS: Record<string, number> = {
  un: 1, una: 1, uno: 1, a: 1, an: 1, one: 1, dos: 2, two: 2, tres: 3, three: 3, cuatro: 4, four: 4, cinco: 5, five: 5,
  seis: 6, six: 6, siete: 7, seven: 7, ocho: 8, eight: 8, nueve: 9, nine: 9, diez: 10, ten: 10, doce: 12, twelve: 12,
  quince: 15, veinte: 20, twenty: 20, treinta: 30, thirty: 30
}

// Word edges that know "á" is a letter (\b doesn't)
const GET_VERB =
  /(?<!\p{L})(?:busca(?:me)?|busc[aá](?:me)?|buscarme|tra(?:e|é)(?:me)?|consegu[ií](?:me)?|cons[ií]gue(?:me)?|junt[aá](?:me)?|min[aá](?:me)?|hac[eé](?:me|te)?|craftea(?:me|te)?|crafte[aá](?:me|te)?|fabric[aá](?:me|te)?|get(?: me)?|find(?: me)?|mine|bring(?: me)?|collect|gather|make(?: me)?|craft)(?!\p{L})/iu
// "Pará" only at the start: "buscame diamantes para la casa" is not a stop
const STOP_VERB =
  /^(?:capri[\s,]*)?(?:par[aá]|det[eé]nte|stop|basta|fren[aá]|cancel[aá])(?!\p{L})|dej[aá] de jugar|solt[aá] el control|devolv[eé]me el control|give me (?:back )?(?:the )?control|stop playing/iu
const MINECRAFT = /\bminecraft\b|\bmaincra\b/i
const NETHER = /\bnether\b|\binfierno\b/i
const GO_VERB = /(?<!\p{L})(?:and[aá]|ve|vamos|llev[aá]me|llevame|entr[aá]|entremos|viaj[aá]|go|take me|let'?s go|enter|travel)(?!\p{L})/iu

/** What was asked, or null when it isn't something for Minecraft */
export function parseCraftCommand(text: string, taskRunning = false): CraftTask | null {
  const t = text.trim()
  const mentionsGame = MINECRAFT.test(t)

  if (STOP_VERB.test(t) && (mentionsGame || taskRunning)) return { action: 'stop' }

  const coords = t.match(/\b(?:and[aá]|ve|vete|camin[aá]|go|walk)\s+(?:a|al|hasta|to)\s+(?:las coordenadas\s+|coords?\s+)?(-?\d+)[\s,]+(-?\d+)(?:[\s,]+(-?\d+))?/i)
  if (coords) {
    const [x, a, b] = [Number(coords[1]), Number(coords[2]), coords[3] !== undefined ? Number(coords[3]) : undefined]
    return b === undefined ? { action: 'goto', x, z: a } : { action: 'goto', x, y: a, z: b }
  }

  if (NETHER.test(t) && (GO_VERB.test(t) || GET_VERB.test(t) || mentionsGame)) return { action: 'nether' }

  if (!GET_VERB.test(t) && !mentionsGame) return null
  const found = ITEMS.find((i) => i.re.test(t))
  if (!found) return null

  let count = 0
  const digits = t.match(/\b(\d{1,3})\b/)
  if (digits) count = Number(digits[1])
  else {
    for (const word of t.toLowerCase().split(/[^a-záéíóúñ]+/)) {
      if (NUMBER_WORDS[word] && word !== 'a' && word !== 'un' && word !== 'una') {
        count = NUMBER_WORDS[word]
        break
      }
    }
  }
  if (!count) count = found.plural ? (found.plural.test(t) ? found.many : 1) : found.many
  return { action: 'get', item: found.item, count }
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

const ITEM_NAMES: Record<string, [string, string]> = {
  diamond: ['diamantes', 'diamonds'],
  iron_ingot: ['hierro', 'iron'],
  coal: ['carbón', 'coal'],
  log: ['madera', 'wood'],
  planks: ['tablas', 'planks'],
  stick: ['palos', 'sticks'],
  cobblestone: ['piedra', 'stone'],
  iron_pickaxe: ['un pico de hierro', 'an iron pickaxe'],
  stone_pickaxe: ['un pico de piedra', 'a stone pickaxe'],
  wooden_pickaxe: ['un pico de madera', 'a wooden pickaxe'],
  crafting_table: ['una mesa de crafteo', 'a crafting table'],
  furnace: ['un horno', 'a furnace'],
  diamond_pickaxe: ['un pico de diamante', 'a diamond pickaxe'],
  stone_sword: ['una espada', 'a sword'],
  iron_sword: ['una espada de hierro', 'an iron sword'],
  iron_armor: ['una armadura de hierro completa', 'a full set of iron armor'],
  iron_helmet: ['un casco de hierro', 'an iron helmet'],
  iron_chestplate: ['una pechera de hierro', 'an iron chestplate'],
  iron_leggings: ['unos pantalones de hierro', 'iron leggings'],
  iron_boots: ['unas botas de hierro', 'iron boots'],
  food: ['comida', 'food'],
  flint: ['pedernal', 'flint'],
  flint_and_steel: ['un encendedor', 'a flint and steel'],
  obsidian: ['obsidiana', 'obsidian']
}

// What the model may answer with when it reads a free request
const MODEL_ITEMS = [
  'diamond', 'iron_ingot', 'raw_iron', 'coal', 'log', 'planks', 'stick', 'cobblestone', 'crafting_table', 'furnace',
  'wooden_pickaxe', 'stone_pickaxe', 'iron_pickaxe', 'diamond_pickaxe', 'stone_sword', 'iron_sword', 'iron_armor',
  'iron_helmet', 'iron_chestplate', 'iron_leggings', 'iron_boots', 'food', 'flint', 'flint_and_steel', 'obsidian'
]

const MODEL_ALIASES: Record<string, string> = {
  cooked_meat: 'food', meat: 'food', steak: 'food', cooked_beef: 'food', cooked_porkchop: 'food', bread: 'food', apple: 'food', apples: 'food',
  iron: 'iron_ingot', iron_ingots: 'iron_ingot', diamonds: 'diamond', wood: 'log', logs: 'log', oak_log: 'log', stone: 'cobblestone',
  sticks: 'stick', plank: 'planks', oak_planks: 'planks', sword: 'stone_sword', pickaxe: 'stone_pickaxe', armor: 'iron_armor',
  lighter: 'flint_and_steel', table: 'crafting_table', workbench: 'crafting_table', wool: 'white_wool'
}

// Worth asking the model about: short, and sounds like asking her to do something
const REQUEST_HINT =
  /(?<!\p{L})(?:and[aá]|ve|vamos|tra[eé]|busc[aá]|hac[eé]|hacete|constru[ií]|mat[aá]|consegu[ií]|junt[aá]|prepar[aá]|equip[aá]te|pon[eé]te|com[eé]|caz[aá]|llev[aá]me|minecraft|jug[aá]|go|get|make|build|kill|find|bring|craft|collect|hunt|eat|put on|take me|play)(?!\p{L})/iu

export type ModelReading = CraftTask | { action: 'unsupported'; what: string } | null

/** Checks what the model answered: only things the mod can actually do get through */
export function readModelAnswer(raw: string): ModelReading {
  let data: any
  try {
    data = JSON.parse(raw.replace(/^[^{]*/, '').replace(/[^}]*$/, ''))
  } catch {
    return null
  }
  const action = String(data?.action || '')
  const count = Math.max(1, Math.min(Number(data?.count) || 1, 640))
  if (action === 'get' || action === 'mine') {
    // Small models make names up: "cooked meat", "minecraft:sand", "iron". Bring them back to what the mod knows
    const raw = String(data.item || data.block || '').toLowerCase().replace(/^minecraft:/, '').trim().replace(/[\s-]+/g, '_')
    const name = MODEL_ALIASES[raw] || raw
    if (MODEL_ITEMS.includes(name)) return { action: 'get', item: name, count }
    // Anything else that looks like a block id gets dug up
    if (/^[a-z_]{3,40}$/.test(name)) return { action: 'mine', block: name, count }
    return null
  }
  if (action === 'goto' && Number.isFinite(data.x) && Number.isFinite(data.z)) {
    return Number.isFinite(data.y) ? { action: 'goto', x: Math.round(data.x), y: Math.round(data.y), z: Math.round(data.z) } : { action: 'goto', x: Math.round(data.x), z: Math.round(data.z) }
  }
  if (action === 'nether') return { action: 'nether' }
  if (action === 'stop') return { action: 'stop' }
  if (action === 'unsupported') return { action: 'unsupported', what: String(data.what || '').slice(0, 80) }
  return null
}

class CapriCraftService {
  private speak: (text: string) => void = () => {}
  private polling: NodeJS.Timeout | null = null
  private lastEvent = 0
  private running = false

  /** How she says things while a task runs (set once by the agent manager) */
  public setSpeaker(fn: (text: string) => void): void {
    this.speak = fn
    this.watchForGame()
  }

  private gameWatch: NodeJS.Timeout | null = null
  private warmedAt = 0

  /**
   * While the player is in a world, keeps her model loaded: a cold load takes ~20-40s, and a free
   * request ("preparate para pelear") shouldn't wait that long. A loopback ping every 20s costs nothing.
   */
  private watchForGame(): void {
    if (this.gameWatch) return
    this.gameWatch = setInterval(async () => {
      if (Date.now() - this.warmedAt < 20 * 60 * 1000) return
      const ping = await this.ping()
      if (!ping?.inWorld) return
      this.warmedAt = Date.now()
      llmFetch('http://127.0.0.1:11434/api/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: getLocalModel(), prompt: '', keep_alive: '30m' })
      }).catch(() => {})
    }, 20000)
    this.gameWatch.unref?.()
  }

  private get es(): boolean {
    return consciousnessService.getPreferredLanguage() === 'es'
  }

  /** Is Minecraft open with the mod, and the player in a world? */
  public async ping(): Promise<{ ok: boolean; inWorld: boolean } | null> {
    return bridge<{ ok: boolean; inWorld: boolean }>('/ping', undefined, 500)
  }

  /**
   * Takes a chat or voice line. `handled` false when it isn't for Minecraft, so the rest of her
   * brain gets it.
   */
  public async handleCommand(text: string): Promise<{ handled: boolean; reply?: string }> {
    let task = parseCraftCommand(text, this.running)
    const mentionsGame = MINECRAFT.test(text)
    if (!task) {
      // Not one of the phrases she knows: if she's in a world and it sounds like a request, the model reads it
      if (text.trim().split(/\s+/).length > 30 || !(REQUEST_HINT.test(text) || this.running)) return { handled: false }
      const ping = await this.ping()
      if (!ping?.inWorld) return { handled: false }
      const reading = await this.askModel(text)
      if (!reading) return { handled: false }
      if (reading.action === 'unsupported') return { handled: true, reply: this.unsupportedLine(reading.what) }
      task = reading
    }

    const ping = await this.ping()
    // "Buscame diamantes" with no game open is about something else
    if (!ping) {
      if (!mentionsGame) return { handled: false }
      return {
        handled: true,
        reply: this.es
          ? '[curious] No veo Minecraft con Capri Craft abierto. Abrí Minecraft 26.3 con Fabric, Baritone y el mod Capri Craft, entrá a un mundo y pedímelo de nuevo.'
          : "[curious] I can't see Minecraft with Capri Craft running. Open Minecraft 26.3 with Fabric, Baritone and the Capri Craft mod, join a world and ask me again."
      }
    }
    if (!ping.inWorld && task.action !== 'stop') {
      return {
        handled: true,
        reply: this.es ? '[curious] Minecraft está abierto, pero todavía no entraste a un mundo.' : "[curious] Minecraft is open, but you're not in a world yet."
      }
    }

    // Whatever the mod reported before this task was already said
    if (task.action !== 'stop') {
      const before = await bridge<CraftStatus>('/status?since=0')
      this.lastEvent = Math.max(this.lastEvent, ...(before?.events || []).map((e) => e.id))
    }
    const sent = await bridge<{ ok: boolean }>('/task', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: asciiJson(task) })
    if (!sent?.ok) {
      return { handled: true, reply: this.es ? '[sad] El mod no me respondió. ¿Sigue abierto Minecraft?' : "[sad] The mod didn't answer. Is Minecraft still open?" }
    }

    if (task.action === 'stop') {
      this.stopPolling()
      return { handled: true, reply: this.es ? '[happy] Listo, suelto el control. Es todo tuyo.' : "[happy] Okay, I'm letting go. It's all yours." }
    }

    this.startPolling()
    return { handled: true, reply: this.acceptLine(task) }
  }

  /** The model turns a free request into one task, "unsupported", or nothing (it wasn't for Minecraft) */
  private async askModel(text: string): Promise<ModelReading> {
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
                'The player is in Minecraft and you control their character. Turn what they say into ONE JSON task you can do, and nothing else.\n' +
                `{"action":"get","item":ITEM,"count":N} where ITEM is one of: ${MODEL_ITEMS.join(', ')} ("food" = cooked meat; "log" = wood)\n` +
                '{"action":"mine","block":"<minecraft block id, like sand or oak_log>","count":N} to dig up any other block\n' +
                '{"action":"goto","x":X,"y":Y,"z":Z} to walk to coordinates (y optional)\n' +
                '{"action":"nether"} to build a portal and go to the Nether\n' +
                '{"action":"stop"} to stop playing and give control back\n' +
                '{"action":"unsupported","what":"<short description>"} for a Minecraft request outside that list (building, farming, fighting a boss, the End...)\n' +
                '{"action":"none"} when it is not a request to do something in Minecraft (a question, small talk).\n' +
                'Examples: "preparate para pelear" -> {"action":"get","item":"iron_sword","count":1}; "tengo hambre" -> {"action":"get","item":"food","count":4}; ' +
                '"traeme 20 de arena" -> {"action":"mine","block":"sand","count":20}; "construime una casa" -> {"action":"unsupported","what":"construir una casa"}; ' +
                '"matá al dragón" -> {"action":"unsupported","what":"matar al Ender Dragon (está en el End)"}; "qué hora es" -> {"action":"none"}. ' +
                'Use get only when ITEM is exactly the thing they asked for. Plain blocks like dirt (tierra), sand (arena), gravel, clay or wool are mine with that block id. ' +
                'Farming, building, taming, enchanting, brewing, trading, diamond or netherite armor and tools other than the diamond pickaxe, buckets, beds, the End: unsupported. ' +
                'Never turn a request you cannot do into a different task: use unsupported.'
            },
            { role: 'user', content: text }
          ]
        })
      })
      if (!res.ok) return null
      const data = (await res.json()) as { message?: { content?: string } }
      const reading = readModelAnswer(data.message?.content || '')
      console.log(`[CapriCraft] The model read "${text}" as ${JSON.stringify(reading)}`)
      return reading
    } catch (err: any) {
      console.warn('[CapriCraft] The model could not read the request:', err?.message || err)
      return null
    } finally {
      clearTimeout(timer)
    }
  }

  private unsupportedLine(what: string): string {
    return this.es
      ? `[thoughtful] Eso${what ? ` (${what})` : ''} todavía no lo sé hacer. Puedo conseguirte materiales, picos, espada, armadura, comida, ir a unas coordenadas o llevarte al Nether.`
      : `[thoughtful] I can't do that${what ? ` (${what})` : ''} yet. I can get you materials, pickaxes, a sword, armor, food, walk to coordinates or take you to the Nether.`
  }

  private acceptLine(task: CraftTask): string {
    const es = this.es
    if (task.action === 'nether') {
      return es
        ? '[excited] ¡Nos vamos al Nether! Necesito un pico de diamante, un encendedor y diez de obsidiana para el portal. Lo que me falte lo consigo en el camino. Puede tardar un buen rato. Apretá K si querés el control de vuelta.'
        : "[excited] We're going to the Nether! I need a diamond pickaxe, a flint and steel and ten obsidian for the portal; whatever I'm missing I'll get on the way. It may take a while. Press K if you want control back."
    }
    if (task.action === 'goto') {
      return es ? `[excited] Voy para ${task.x}, ${task.z}. Apretá K si querés el control de vuelta.` : `[excited] Heading to ${task.x}, ${task.z}. Press K if you want control back.`
    }
    if (task.action !== 'get') return es ? '[excited] ¡Voy!' : '[excited] On it!'
    const [nameEs, nameEn] = ITEM_NAMES[task.item] || [task.item, task.item]
    const amount = task.count > 1 && !/^(?:un|una|a|an) /.test(es ? nameEs : nameEn) ? `${task.count} ` : ''
    if (task.item === 'diamond') {
      return es
        ? `[excited] ¡Tomo el control! Voy a buscarte ${amount}diamantes. Si me falta el pico, me lo hago en el camino. Apretá K si querés el control de vuelta.`
        : `[excited] Taking over! Going to find you ${amount}diamonds. If I don't have the pickaxe, I'll make it on the way. Press K if you want control back.`
    }
    return es
      ? `[excited] ¡Tomo el control! Voy a conseguirte ${amount}${nameEs}. Apretá K si querés el control de vuelta.`
      : `[excited] Taking over! Getting you ${amount}${nameEn}. Press K if you want control back.`
  }

  private startPolling(): void {
    this.running = true
    this.lastStep = ''
    this.lastSpokeAt = Date.now()
    if (this.polling) return
    this.polling = setInterval(() => void this.poll(), 1500)
  }

  private stopPolling(): void {
    this.running = false
    if (this.polling) clearInterval(this.polling)
    this.polling = null
  }

  // What she said last, so she doesn't talk over herself or repeat the same thing
  private lastStep = ''
  private lastSpokeAt = 0
  private busyUntil = 0
  private queued: string[] = []
  private flushTimer: NodeJS.Timeout | null = null

  /** One line at a time: what comes in while she's still talking waits, and goes out together */
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
    // Only the first emotion tag stays: it sets her face for the whole line
    const parts = this.queued.splice(0)
    if (!parts.length) return
    const line = parts.map((p, i) => (i === 0 ? p : p.replace(/^\s*\[[a-z ]+\]\s*/i, ''))).join(' ').trim()
    this.speak(line)
    this.lastSpokeAt = now
    this.busyUntil = now + Math.max(3500, line.length * 70)
  }

  private async poll(): Promise<void> {
    const status = await bridge<CraftStatus>(`/status?since=${this.lastEvent}`)
    if (!status) {
      // Minecraft closed in the middle of it
      this.stopPolling()
      return
    }
    const lines: string[] = []
    for (const event of status.events || []) {
      this.lastEvent = Math.max(this.lastEvent, event.id)
      const line = this.lineFor(event, status.target || '')
      if (line) lines.push(line)
    }
    if (lines.length) this.say(lines.join(' '))
    else if (status.state === 'running' && Date.now() - this.lastSpokeAt > 45000) {
      // A long search: tell the player how it's going
      const update = this.progressLine(status)
      if (update) this.say(update)
    }
    if (status.state === 'idle') this.stopPolling()
  }

  private pick(options: string[]): string {
    return options[Math.floor(Math.random() * options.length)]
  }

  /** What it's all for, given what she was asked */
  private why(target: string): [string, string] {
    if (target === 'diamond') return ['para los diamantes', 'for the diamonds']
    if (target === 'iron_pickaxe') return ['para el pico de hierro', 'for the iron pickaxe']
    if (target === 'iron_ingot') return ['para sacar el hierro', 'to get the iron']
    return ['', '']
  }

  /** What she says about one thing the mod did */
  private lineFor(e: CraftEvent, target: string): string | null {
    const es = this.es
    const d = e.detail || ''
    switch (e.kind) {
      case 'step':
        if (d === this.lastStep) return null
        this.lastStep = d
        return this.stepLine(d, target)
      case 'got':
        if (d === 'chop wood') return es ? this.pick(['[happy] Listo, ya tengo madera.', '[happy] Bueno, madera suficiente.']) : this.pick(['[happy] Got the wood.', '[happy] Okay, enough wood.'])
        if (d === 'mine stone') return es ? '[happy] Ya tengo piedra.' : '[happy] Got the stone.'
        if (d === 'mine iron') return es ? this.pick(['[excited] ¡Encontré hierro!', '[excited] ¡Ahí está el hierro!']) : this.pick(['[excited] Found iron!', '[excited] There is the iron!'])
        if (d === 'mine diamonds') return es ? '[excited] ¡Diamantes! ¡Los tengo!' : '[excited] Diamonds! Got them!'
        if (d === 'mine coal') return es ? '[happy] Ya tengo el carbón.' : '[happy] Got the coal.'
        return null
      case 'crafted':
        if (d.endsWith('wooden_pickaxe')) return es ? '[proud] Pico de madera, hecho.' : '[proud] Wooden pickaxe, done.'
        if (d.endsWith('stone_pickaxe')) return es ? '[proud] Pico de piedra listo. Mucho mejor.' : '[proud] Stone pickaxe ready. Much better.'
        if (d.endsWith('iron_pickaxe')) return es ? '[excited] ¡Pico de hierro! Con esto ya puedo picar diamantes.' : '[excited] Iron pickaxe! With this I can mine diamonds.'
        if (d.endsWith('furnace')) return es ? '[happy] Horno armado.' : '[happy] Furnace built.'
        return null
      case 'smelted':
        if (d === 'food') return es ? '[happy] Comida lista, bien cocinada.' : '[happy] Food is ready, nicely cooked.'
        return es ? '[happy] Listo, hierro fundido.' : '[happy] Done, the iron is smelted.'
      case 'fight':
        return es
          ? this.pick([`[surprised] ¡Un ${this.mobName(d)}! Me defiendo.`, `[surprised] Uy, ${this.mobName(d)}. Dame un segundo que me lo saco de encima.`])
          : this.pick([`[surprised] A ${d.replace(/_/g, ' ')}! Fighting back.`, `[surprised] Ugh, a ${d.replace(/_/g, ' ')}. Give me a second.`])
      case 'ate':
        return es ? this.pick(['[happy] Comí algo, ya estoy mejor.', '[happy] Ñam. Con hambre no se puede minar.']) : this.pick(['[happy] Ate something, feeling better.', "[happy] Yum. Can't mine on an empty stomach."])
      case 'wore':
        return es ? '[proud] Me la puse.' : '[proud] Put it on.'
      case 'arrived':
        return es ? '[excited] ¡Llegamos al Nether! Cuidado con la lava.' : '[excited] We made it to the Nether! Watch out for the lava.'
      case 'placed':
        if (d === 'portal lit') return es ? '[excited] ¡Se prendió el portal!' : '[excited] The portal is lit!'
        return null
      case 'done':
        if (d.startsWith('nether')) return es ? '[happy] Listo, estamos en el Nether. Te devuelvo el control.' : "[happy] There, we're in the Nether. Control is yours again."
        return es ? '[excited] ¡Listo! Ya tenés lo que me pediste. Te devuelvo el control.' : '[excited] Done! You have what you asked for. Control is yours again.'
      case 'stopped':
        if (d === 'replaced') return null
        return es ? '[happy] Ok, te devuelvo el control.' : '[happy] Okay, control is yours again.'
      case 'fled':
        return es
          ? '[worried] ¡Me quedaban dos corazones! Salí al menú antes de que nos maten y perdamos todo. Cuando quieras, entrá de nuevo y comé algo.'
          : "[worried] I was down to two hearts! I left to the title screen before we died and lost everything. Go back in and eat something when you're ready."
      case 'failed':
        return this.failLine(d)
      default:
        return null
    }
  }

  /** What she's about to do, and why */
  private stepLine(step: string, target: string): string | null {
    const es = this.es
    const [whyEs, whyEn] = this.why(target)
    const forEs = whyEs ? ` ${whyEs}` : ''
    const forEn = whyEn ? ` ${whyEn}` : ''
    switch (step) {
      case 'chop wood':
        return es
          ? this.pick([`[happy] No tengo pico, así que primero madera. Voy a talar un árbol${forEs}.`, '[happy] Arranco por lo básico: madera. Busco un árbol.'])
          : this.pick([`[happy] No pickaxe yet, so wood first. Going to chop a tree${forEn}.`, '[happy] Starting with the basics: wood. Looking for a tree.'])
      case 'craft planks':
        return es ? '[happy] Hago tablas con los troncos.' : '[happy] Turning the logs into planks.'
      case 'craft sticks':
        return es ? '[happy] Unos palos para el mango.' : '[happy] Some sticks for the handle.'
      case 'craft crafting table':
        return es ? '[happy] Me armo una mesa de crafteo.' : '[happy] Making a crafting table.'
      case 'place crafting table':
        return es ? '[happy] La pongo acá.' : '[happy] Putting it down here.'
      case 'back to the crafting table':
        return es ? '[happy] Vuelvo a la mesa que dejé, así no gasto madera.' : "[happy] Going back to the table I left, so I don't waste wood."
      case 'craft wooden pickaxe':
        return es ? '[happy] Y ahora, un pico de madera.' : '[happy] And now, a wooden pickaxe.'
      case 'mine stone':
        return es ? '[happy] Ahora piedra, para un pico mejor.' : '[happy] Now stone, for a better pickaxe.'
      case 'craft stone pickaxe':
        return es ? '[happy] Me hago el pico de piedra.' : '[happy] Making the stone pickaxe.'
      case 'mine iron':
        return es
          ? `[excited] Ahora a buscar hierro${forEs}. Voy a cavar hacia abajo, sin trampa: solo lo que vea.`
          : `[excited] Now to find iron${forEn}. I'll dig down and look, no cheating: only what I can see.`
      case 'craft iron chestplate':
      case 'craft iron leggings':
      case 'craft iron helmet':
      case 'craft iron boots':
        return es ? `[happy] Me hago ${this.armorName(step)}.` : `[happy] Making the iron ${step.replace('craft iron ', '')}.`
      case 'craft furnace':
        return es ? '[happy] Necesito un horno para fundir el hierro.' : '[happy] I need a furnace to smelt the iron.'
      case 'place furnace':
        return es ? '[happy] Pongo el horno.' : '[happy] Putting the furnace down.'
      case 'smelt iron':
        return es ? '[happy] A fundir el hierro. Tarda unos segundos por lingote.' : '[happy] Smelting the iron. It takes a few seconds per ingot.'
      case 'craft iron pickaxe':
        return es ? '[excited] ¡Ahora el pico de hierro!' : '[excited] Now the iron pickaxe!'
      case 'craft diamond pickaxe':
        return es ? '[excited] ¡Pico de diamante! El mejor que hay.' : '[excited] A diamond pickaxe! The best there is.'
      case 'craft stone sword':
        return es ? '[happy] Me hago una espada, por las dudas. Abajo hay bichos.' : "[happy] Making a sword, just in case. There are monsters down there."
      case 'craft iron sword':
        return es ? '[happy] Una espada de hierro, para pelear en serio.' : '[happy] An iron sword, for a real fight.'
      case 'hunt':
        return es ? '[happy] Me voy a cazar algo para comer.' : '[happy] Going to hunt something to eat.'
      case 'cook food':
        return es ? '[happy] La cocino en el horno, cruda no.' : '[happy] Cooking it in the furnace, not eating it raw.'
      case 'dig gravel':
        return es ? '[happy] Cavo grava hasta que salga un pedernal.' : '[happy] Digging gravel until some flint drops.'
      case 'craft flint and steel':
        return es ? '[happy] Con el pedernal y un hierro me hago un encendedor.' : '[happy] Flint and iron make a flint and steel.'
      case 'mine obsidian':
        return es ? '[excited] Ahora obsidiana para el portal. Es durísima, cada bloque tarda.' : '[excited] Now obsidian for the portal. It is tough, every block takes a while.'
      case 'build portal':
        return es ? '[excited] Armo el portal: cuatro de ancho y cinco de alto.' : '[excited] Building the portal: four wide, five tall.'
      case 'enter portal':
        return es ? '[excited] ¡Adentro! Agarrate.' : '[excited] In we go! Hold on.'
      case 'mine diamonds':
        return es
          ? '[excited] ¡Ahora sí, a buscar diamantes! Bajo bien hondo y cavo un túnel hasta verlos. Puede tardar un rato.'
          : "[excited] Now for the diamonds! I'm going deep and digging a tunnel until I see some. It might take a while."
      case 'mine coal':
        return es ? '[happy] Busco carbón.' : '[happy] Looking for coal.'
      default:
        return null
    }
  }

  private mobName(id: string): string {
    const names: Record<string, string> = {
      zombie: 'zombie', skeleton: 'esqueleto', creeper: 'creeper', spider: 'araña', cave_spider: 'araña de cueva',
      drowned: 'ahogado', husk: 'zombie del desierto', stray: 'esqueleto helado', witch: 'bruja', slime: 'slime',
      enderman: 'enderman', piglin: 'piglin', zombified_piglin: 'piglin zombi', blaze: 'blaze', magma_cube: 'cubo de magma'
    }
    return names[id] || id.replace(/_/g, ' ')
  }

  private armorName(step: string): string {
    if (step.endsWith('chestplate')) return 'la pechera de hierro'
    if (step.endsWith('leggings')) return 'los pantalones de hierro'
    if (step.endsWith('helmet')) return 'el casco de hierro'
    return 'las botas de hierro'
  }

  /** How a long search is going */
  private progressLine(status: CraftStatus): string | null {
    const es = this.es
    const things: Record<string, [string, string]> = {
      'mine iron': ['el hierro', 'iron'],
      'mine diamonds': ['los diamantes', 'diamonds'],
      'mine coal': ['el carbón', 'coal'],
      'mine stone': ['la piedra', 'stone'],
      'chop wood': ['la madera', 'wood']
    }
    const name = things[status.step || '']
    if (!name) return null
    const counted =
      status.have !== undefined && status.need !== undefined && status.have > 0
        ? es
          ? ` Ya tengo ${status.have} de ${status.need}.`
          : ` I have ${status.have} of ${status.need}.`
        : ''
    return es
      ? this.pick([`[thoughtful] Sigo buscando ${name[0].replace(/^(el|la|los) /, '')}.${counted}`, `[thoughtful] Todavía nada... sigo cavando.${counted}`, `[curious] Paciencia, ${name[0]} tiene que estar por acá.${counted}`])
      : this.pick([`[thoughtful] Still looking for ${name[1]}.${counted}`, `[thoughtful] Nothing yet... still digging.${counted}`, `[curious] Patience, the ${name[1]} has to be around here.${counted}`])
  }

  private failLine(reason: string): string {
    const es = this.es
    if (reason.startsWith('could_not_find:')) {
      return es
        ? '[sad] No encuentro más de lo que necesito por acá cerca. Llevame a otro lado y pedímelo de nuevo.'
        : "[sad] I can't find any more of what I need around here. Take me somewhere else and ask again."
    }
    if (reason.startsWith('no_room_for')) return es ? '[sad] No tengo lugar para poner la mesa o el horno acá.' : "[sad] There's no room here to put down the table or the furnace."
    if (reason === 'player_dead') return es ? '[curious] Estás muerto en el juego. Tocá Reaparecer y pedímelo de nuevo.' : "[curious] You're dead in the game. Hit Respawn and ask me again."
    if (reason === 'no_room_for:portal') return es ? '[sad] No encuentro un lugar plano para armar el portal. Llevame a un lugar más abierto.' : "[sad] I can't find a flat spot for the portal. Take me somewhere more open."
    if (reason === 'portal_did_not_light') return es ? '[sad] El portal no se prendió. Fijate si el marco quedó bien.' : "[sad] The portal didn't light. Check the frame."
    if (reason === 'could_not_find:hunt') return es ? '[sad] No veo animales por acá para cazar. Llevame a otro lado.' : "[sad] I don't see any animals around to hunt. Take me somewhere else."
    if (reason === 'died') return es ? '[sad] Me mataron... Perdón. Te devuelvo el control.' : '[sad] I got killed... Sorry. Control is yours again.'
    if (reason === 'not_in_world') return es ? '[sad] Saliste del mundo, así que paré.' : '[sad] You left the world, so I stopped.'
    if (reason.startsWith('unknown')) return es ? '[curious] No sé qué es eso en Minecraft.' : "[curious] I don't know what that is in Minecraft."
    return es ? `[sad] Algo salió mal y no pude terminar (${reason}). Te devuelvo el control.` : `[sad] Something went wrong and I couldn't finish (${reason}). Control is yours again.`
  }
}

export const capriCraftService = new CapriCraftService()
