import { parseCraftCommand } from './capri-craft-service'

/**
 * What the player can ask Capri when she's in their world as a companion (her own body, not their
 * player): join, leave, follow, wait, come, get things, mine, kill, build, give. Pure parsing, so it can
 * be tested without the game.
 */

export type CompanionOrder =
  | { action: 'join' }
  | { action: 'leave' }
  | { action: 'follow' }
  | { action: 'stay' }
  | { action: 'come' }
  | { action: 'stop' }
  | { action: 'inventory' }
  | { action: 'drop' }
  | { action: 'get'; item: string; count: number; give: boolean }
  | { action: 'mine'; block: string; count: number; give: boolean }
  | { action: 'kill'; mob: string; count: number }
  | { action: 'build'; what: 'house' | 'shelter' | 'tower' | 'wall'; material?: 'stone' | 'wood' }
  | { action: 'give'; item: string; count?: number }
  | { action: 'goto'; x: number; y?: number; z: number }

// Word edges that know "á" is a letter (\b doesn't)
const W = (body: string) => new RegExp(`(?<!\\p{L})(?:${body})(?!\\p{L})`, 'iu')

const WORLD = /mundo|minecraft|maincra|partida|server|servidor|world|game/i
const JOIN = W(
  'un[ií]te|unite|sum[aá]te|j[uú]ntate|entr[aá]|ven[ií]|veni|aparec[eé]|conect[aá]te|join|come|hop on|get in'
)
const LEAVE = W('sal[ií]|salite|and[aá]te|andate|ir?te|desconect[aá]te|leave|quit|log off|disconnect')
// Said to her while she's in the world, no "world" needed: "log off", "volvé al escritorio"
const LEAVE_BARE = W(
  'log ?off|log ?out|desloguea?te|deslogu[eé]ate|desconect[aá]te|disconnect|volv[eé] (?:al escritorio|a la (?:pc|compu|computadora))|vuelve al escritorio|' +
    'go back to (?:the |my )?(?:desktop|computer|pc)|back to (?:the |my )?desktop|come back to (?:the |my )?(?:desktop|computer|pc)'
)
const LEAVE_ALONE = /^(?:capri[\s,]*)?(?:leave|and[aá]te|vete|(?:chau|bye|goodbye)\s+capri)[\s.!]*$/iu
const FOLLOW = W('segu[ií]me|seguime|sigueme|s[ií]gueme|ven[ií] conmigo|veni conmigo|vamos juntos|follow me|come with me')
const STAY = W(
  'qued[aá]te(?: ac[aá]| ah[ií]| quieta)?|quedate|esper[aá](?:me)? (?:ac[aá]|ah[ií])|no te muevas|stay(?: here)?|wait (?:here|there)|hold on here'
)
const COME = W('ven[ií] (?:ac[aá]|para ac[aá]|aqu[ií])|veni aca|vení acá|ven aqu[ií]|vuelve|volv[eé]|come here|come back|get over here')
const STOP = /^(?:capri[\s,]*)?(?:par[aá]|det[eé]nte|stop|basta|fren[aá]|cancel[aá]|dej[aá] eso|olvidalo|olvídalo|never ?mind)(?!\p{L})/iu
const INVENTORY = W('qu[eé] ten[eé]s|qu[eé] tienes|qu[eé] llev[aá]s|tu inventario|what do you have|what are you carrying|your inventory')
const DROP = W('tir[aá] todo|dej[aá] todo (?:ac[aá]|en el piso)|drop everything|drop it all')
const GIVE_ALL = W('dame todo|pasame todo|d[aá]me lo que tengas|give me everything|give me all|hand over everything')
const GIVE = W('dame|d[aá]me|pasame|p[aá]same|tirame|tírame|give me|hand me|pass me|toss me')
const KILL = W('mat[aá]|matame|asesin[aá]|liquid[aá]|cag[aá] a palos|atac[aá]|elimin[aá]|kill|slay|attack|murder|hunt down|take out')
const BUILD = W('constru[ií]|construime|construinos|arm[aá]|armame|hac[eé]me|haceme|hacenos|hac[eé]|build|make|craft')
const FOR_ME = W('me|traeme|tr[aá]eme|dame|conseguime|cons[ií]gueme|buscame|b[uú]scame|juntame|for me|para m[ií]|to me')

const MOBS: [RegExp, string][] = [
  [W('zombies?|zombis?'), 'zombie'],
  [W('esqueletos?|skeletons?'), 'skeleton'],
  [W('creepers?'), 'creeper'],
  [W('arañas?|spiders?'), 'spider'],
  [W('endermans?|endermen'), 'enderman'],
  [W('brujas?|witch(?:es)?'), 'witch'],
  [W('slimes?'), 'slime'],
  [W('vacas?|cows?'), 'cow'],
  [W('cerdos?|chanchos?|pigs?'), 'pig'],
  [W('ovejas?|sheep'), 'sheep'],
  [W('pollos?|gallinas?|chickens?'), 'chicken'],
  [W('conejos?|rabbits?'), 'rabbit'],
  [W('monstruos?|bichos?|mobs?|hostiles?|enemigos?|monsters?|enemies'), 'hostile']
]

const NUMBER_WORDS: Record<string, number> = {
  dos: 2, two: 2, tres: 3, three: 3, cuatro: 4, four: 4, cinco: 5, five: 5, seis: 6, six: 6, siete: 7, seven: 7,
  ocho: 8, eight: 8, nueve: 9, nine: 9, diez: 10, ten: 10, veinte: 20, twenty: 20
}

function countIn(t: string): number | undefined {
  const digits = t.match(/\b(\d{1,3})\b/)
  if (digits) return Number(digits[1])
  for (const word of t.toLowerCase().split(/[^a-záéíóúñ]+/)) if (NUMBER_WORDS[word]) return NUMBER_WORDS[word]
  return undefined
}

/** Is this asking her to come into (or leave) the game? Works whether or not she's there yet */
export function parseJoinLeave(text: string): CompanionOrder | null {
  const t = text.trim()
  if (!WORLD.test(t)) return null
  if (LEAVE.test(t) && !/no te vayas|don'?t leave/i.test(t)) return { action: 'leave' }
  if (JOIN.test(t)) return { action: 'join' }
  return null
}

/** An order for her body in the world, or null when it isn't one */
export function parseCompanionOrder(text: string): CompanionOrder | null {
  const t = text.trim()
  const joinLeave = parseJoinLeave(t)
  if (joinLeave) return joinLeave
  if ((LEAVE_BARE.test(t) || LEAVE_ALONE.test(t)) && !/no te vayas|don'?t leave/i.test(t)) return { action: 'leave' }
  if (STOP.test(t)) return { action: 'stop' }
  if (INVENTORY.test(t)) return { action: 'inventory' }
  if (DROP.test(t)) return { action: 'drop' }
  if (GIVE_ALL.test(t)) return { action: 'give', item: 'all' }
  if (FOLLOW.test(t)) return { action: 'follow' }
  if (STAY.test(t)) return { action: 'stay' }
  if (COME.test(t)) return { action: 'come' }

  if (KILL.test(t)) {
    const mob = MOBS.find(([re]) => re.test(t))
    if (mob) {
      const all = /todos|todas|every|all/i.test(t)
      return { action: 'kill', mob: mob[1], count: countIn(t) ?? (all || mob[1] === 'hostile' ? 10 : 1) }
    }
  }

  if (BUILD.test(t)) {
    const what = /casa|casita|house|home/i.test(t)
      ? 'house'
      : /refugio|shelter|hut|choza/i.test(t)
        ? 'shelter'
        : /torre|tower/i.test(t)
          ? 'tower'
          : /muro|pared|wall/i.test(t)
            ? 'wall'
            : null
    if (what) {
      const material = /piedra|stone|roca|cobble/i.test(t) ? 'stone' : /madera|wood|tablas|planks/i.test(t) ? 'wood' : undefined
      return material ? { action: 'build', what, material } : { action: 'build', what }
    }
  }

  // "Dame 10 de piedra" asks for it like "traeme": she gets what she's missing and hands it over
  const craft = parseCraftCommand(t, true) ?? (GIVE.test(t) ? parseCraftCommand(t.replace(GIVE, 'traeme'), true) : null)
  if (craft?.action === 'goto') return craft
  if (craft?.action === 'get') {
    // "Dame un pico" when she might already carry one: get it (she skips what she has) and hand it over
    const give = FOR_ME.test(t) || GIVE.test(t)
    return { action: 'get', item: craft.item, count: craft.count, give }
  }
  return null
}
