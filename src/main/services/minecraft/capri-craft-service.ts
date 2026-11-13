import { consciousnessService } from '../consciousness-service'

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
  | { action: 'stop' }

interface CraftEvent {
  id: number
  kind: 'started' | 'step' | 'got' | 'placed' | 'crafted' | 'smelted' | 'done' | 'failed' | 'stopped'
  detail: string
}

interface CraftStatus {
  state: 'idle' | 'running'
  inWorld?: boolean
  action?: string
  target?: string
  count?: number
  step?: string
  events?: CraftEvent[]
}

// What she understands, the specific things before the general ones ("pico de hierro" before "hierro")
const ITEMS: { re: RegExp; item: string; plural?: RegExp; many: number }[] = [
  { re: /pico de hierro|iron pick(?:axe)?/i, item: 'iron_pickaxe', many: 1 },
  { re: /pico de piedra|stone pick(?:axe)?/i, item: 'stone_pickaxe', many: 1 },
  { re: /pico de madera|wood(?:en)? pick(?:axe)?/i, item: 'wooden_pickaxe', many: 1 },
  { re: /\bpico\b|pickaxe/i, item: 'wooden_pickaxe', many: 1 },
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
  furnace: ['un horno', 'a furnace']
}

class CapriCraftService {
  private speak: (text: string) => void = () => {}
  private polling: NodeJS.Timeout | null = null
  private lastEvent = 0
  private running = false

  /** How she says things while a task runs (set once by the agent manager) */
  public setSpeaker(fn: (text: string) => void): void {
    this.speak = fn
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
    const task = parseCraftCommand(text, this.running)
    if (!task) return { handled: false }
    const mentionsGame = MINECRAFT.test(text)

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

  private acceptLine(task: CraftTask): string {
    const es = this.es
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
    if (this.polling) return
    this.polling = setInterval(() => void this.poll(), 1500)
  }

  private stopPolling(): void {
    this.running = false
    if (this.polling) clearInterval(this.polling)
    this.polling = null
  }

  private async poll(): Promise<void> {
    const status = await bridge<CraftStatus>(`/status?since=${this.lastEvent}`)
    if (!status) {
      // Minecraft closed in the middle of it
      this.stopPolling()
      return
    }
    for (const event of status.events || []) {
      this.lastEvent = Math.max(this.lastEvent, event.id)
      const line = this.lineFor(event)
      if (line) this.speak(line)
    }
    if (status.state === 'idle') this.stopPolling()
  }

  /** What she says about one thing the mod did; null for the small stuff (planks, sticks) */
  private lineFor(e: CraftEvent): string | null {
    const es = this.es
    const d = e.detail || ''
    switch (e.kind) {
      case 'step':
        if (d === 'chop wood') return es ? '[happy] Primero, madera. Voy a talar un árbol.' : '[happy] Wood first. Going to chop a tree.'
        if (d === 'mine stone') return es ? '[happy] Ahora piedra, para un pico mejor.' : '[happy] Now stone, for a better pickaxe.'
        if (d === 'mine iron') return es ? '[excited] Voy a buscar hierro.' : '[excited] Going to look for iron.'
        if (d === 'mine diamonds') return es ? '[excited] ¡Ahora sí, a buscar diamantes!' : '[excited] Now for the diamonds!'
        if (d === 'mine coal') return es ? '[happy] Buscando carbón.' : '[happy] Looking for coal.'
        if (d === 'smelt iron') return es ? '[happy] Fundiendo el hierro, dame unos segundos.' : '[happy] Smelting the iron, give me a few seconds.'
        return null
      case 'crafted':
        if (d.endsWith('wooden_pickaxe')) return es ? '[happy] Me hice un pico de madera.' : '[happy] Made a wooden pickaxe.'
        if (d.endsWith('stone_pickaxe')) return es ? '[happy] Pico de piedra, listo.' : '[happy] Stone pickaxe, done.'
        if (d.endsWith('iron_pickaxe')) return es ? '[excited] ¡Pico de hierro! Ya puedo picar diamantes.' : '[excited] Iron pickaxe! Now I can mine diamonds.'
        if (d.endsWith('furnace')) return es ? '[happy] Me armé un horno.' : '[happy] Built a furnace.'
        return null
      case 'done':
        return es ? '[excited] ¡Listo! Ya tenés lo que me pediste. Te devuelvo el control.' : '[excited] Done! You have what you asked for. Control is yours again.'
      case 'stopped':
        if (d === 'replaced') return null
        return es ? '[happy] Ok, te devuelvo el control.' : '[happy] Okay, control is yours again.'
      case 'failed':
        return this.failLine(d)
      default:
        return null
    }
  }

  private failLine(reason: string): string {
    const es = this.es
    if (reason.startsWith('could_not_find:')) {
      return es
        ? '[sad] No encuentro más de lo que necesito por acá cerca. Llevame a otro lado y pedímelo de nuevo.'
        : "[sad] I can't find any more of what I need around here. Take me somewhere else and ask again."
    }
    if (reason.startsWith('no_room_for')) return es ? '[sad] No tengo lugar para poner la mesa o el horno acá.' : "[sad] There's no room here to put down the table or the furnace."
    if (reason === 'not_in_world') return es ? '[sad] Saliste del mundo, así que paré.' : '[sad] You left the world, so I stopped.'
    if (reason.startsWith('unknown')) return es ? '[curious] No sé qué es eso en Minecraft.' : "[curious] I don't know what that is in Minecraft."
    return es ? `[sad] Algo salió mal y no pude terminar (${reason}). Te devuelvo el control.` : `[sad] Something went wrong and I couldn't finish (${reason}). Control is yours again.`
  }
}

export const capriCraftService = new CapriCraftService()
