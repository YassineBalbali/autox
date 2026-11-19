// Talk to Assistant AutoX on a local API, one turn per line, the state carried from turn to turn.
//
//   node eval/assistant/chat.mjs [http://localhost:8081] [--listing <id>]
//
// --listing starts the conversation from a listing's page. Type a message and press Enter; an empty
// line or Ctrl+D ends. Each answer is printed block by block, as the codes and figures the server
// returns (the web and the app word them from labels.fr.json in A4 and A5). Never point it at
// production: every turn spends the shared Groq quota.
import { createInterface } from 'node:readline/promises';
import { stdin, stdout } from 'node:process';

const args = process.argv.slice(2);
const api = (args.find((arg) => arg.startsWith('http')) ?? 'http://localhost:8081').replace(/\/$/, '');
const listingAt = args.indexOf('--listing');
const context = listingAt >= 0 ? { listingId: args[listingAt + 1] } : undefined;

const euros = (minor) => `${(minor / 100).toLocaleString('fr-FR')} €`;
const card = (l) =>
  `${l.title} · ${l.year} · ${l.mileageKm.toLocaleString('fr-FR')} km · ${
    l.offer === 'LEASING' ? `${l.leasingType} ${euros(l.leasingMonthly)}/mois` : euros(l.priceAmount)
  }${l.verdict ? ` · ${l.verdict}` : ''}${l.damaged ? ' · endommagé' : ''}  /annonces/${l.id}`;

function show(block) {
  switch (block.type) {
    case 'LISTINGS':
      return block.listings.map((l, i) => `  ${i + 1}. ${card(l)}`).join('\n');
    case 'LISTING_FACTS':
      return `  ${card(block.listings[0])}\n  facts: ${JSON.stringify(block.facts)}`;
    case 'COMPARISON':
      return `${block.listings.map((l) => `  - ${card(l)}`).join('\n')}\n  differences: ${JSON.stringify(block.differences)}`;
    case 'SEARCH_LINK':
      return `  ${block.search.count}${block.search.capped ? '+' : ''} résultats, tri ${block.search.sort}: ${JSON.stringify(
        Object.fromEntries(Object.entries(block.search.criteria).filter(([, v]) => v !== null && !(Array.isArray(v) && !v.length))),
      )}`;
    case 'FINANCING': {
      const f = block.financing;
      return `  ${euros(f.borrowedAmount)} sur ${f.months} mois à ${f.annualRatePercent} %${f.rateIsExample ? ' (taux exemple)' : ''}: ${euros(
        f.monthlyAmount,
      )}/mois, coût ${euros(f.creditCost)} · ${f.mentions.join(', ')}`;
    }
    case 'CRIT_AIR':
      return `  Crit'Air ${block.critAir.critAir ?? `inconnue (${block.critAir.unsettled})`}`;
    case 'VALUATION': {
      const v = block.valuation;
      return v.estimate
        ? `  La Cote: ${euros(v.estimate.medianAmount)} (${euros(v.estimate.lowAmount)} – ${euros(v.estimate.highAmount)}), ${v.comparableCount} annonces`
        : `  La Cote: ${v.comparableCount} annonce(s) comparable(s), ${v.minimumComparables} nécessaires; élargir: ${JSON.stringify(v.widerScopes)}`;
    }
    case 'HELP_PASSAGE':
      return block.help.map((p) => `  ${p.pageTitle} › ${p.heading ?? ''} (${p.path})\n    ${p.plainText.replace(/\n/g, '\n    ')}`).join('\n');
    case 'NOT_UNDERSTOOD':
      return `  non compris: ${block.notUnderstood.join(', ')}`;
    case 'NO_ANSWER':
      return `  pas de réponse: ${block.reason}${block.listingId ? ` (${block.listingId})` : ''}`;
    case 'CLARIFY':
      return `  question: ${block.question}`;
    default:
      return '';
  }
}

const capabilities = await fetch(`${api}/api/v1/assistant/capabilities`);
if (capabilities.status === 404) {
  console.log('The assistant is off: set AUTOX_ASSISTANT_ENABLED=true and AUTOX_AI_PROVIDER=groq, then restart the API.');
  process.exit(1);
}
console.log('capabilities', await capabilities.json());

const prompt = createInterface({ input: stdin, output: stdout });
let state;
for (;;) {
  const message = (await prompt.question('\nvous> ').catch(() => '')).trim();
  if (!message) break;
  const started = Date.now();
  const response = await fetch(`${api}/api/v1/assistant/turns`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, state, context }),
  });
  const body = await response.json();
  if (!response.ok) {
    console.log(`  ${response.status} ${body.code}${response.headers.get('retry-after') ? `, retry after ${response.headers.get('retry-after')} s` : ''}`);
    continue;
  }
  for (const block of body.blocks) console.log(`[${block.type}]\n${show(block)}`);
  console.log(`  (${Date.now() - started} ms, tour ${body.state.turns}, ${body.state.shown.length} annonce(s) en mémoire)`);
  state = body.state;
}
prompt.close();
