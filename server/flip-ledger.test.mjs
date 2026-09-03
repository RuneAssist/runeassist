import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { replay, snapshot } from './flip-ledger.mjs';

const __dirname = dirname(fileURLToPath(import.meta.url));
const vectors = JSON.parse(readFileSync(join(__dirname, 'flip-ledger-vectors.json'), 'utf8'));

function assertEq(label, expected, actual) {
  if (expected !== actual) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)} got ${JSON.stringify(actual)}`);
  }
}

function assertFlip(label, expected, actual) {
  for (const key of ['itemId', 'openedQuantity', 'closedQuantity', 'spent', 'profit', 'taxPaid', 'status']) {
    assertEq(`${label}.${key}`, expected[key], actual[key]);
  }
}

let failed = 0;
for (const c of vectors.cases) {
  try {
    const got = snapshot(replay(c.transactions));
    const exp = c.expect;
    assertEq(`${c.name}.profit`, exp.stats.profit, got.stats.profit);
    assertEq(`${c.name}.gross`, exp.stats.gross, got.stats.gross);
    assertEq(`${c.name}.taxPaid`, exp.stats.taxPaid, got.stats.taxPaid);
    assertEq(`${c.name}.flipsMade`, exp.stats.flipsMade, got.stats.flipsMade);
    assertEq(`${c.name}.portfolioValue`, exp.portfolioValue, got.portfolioValue);
    assertEq(`${c.name}.closed.length`, exp.closedFlips.length, got.closedFlips.length);
    exp.closedFlips.forEach((e, i) => assertFlip(`${c.name}.closed[${i}]`, e, got.closedFlips[i]));
    assertEq(`${c.name}.open.length`, exp.openPositions.length, got.openPositions.length);
    exp.openPositions.forEach((e, i) => assertFlip(`${c.name}.open[${i}]`, e, got.openPositions[i]));
    console.log(`ok  ${c.name}`);
  } catch (err) {
    failed++;
    console.error(`fail ${c.name}: ${err.message}`);
  }
}

if (failed) {
  console.error(`${failed} vector(s) failed`);
  process.exit(1);
}
console.log(`all ${vectors.cases.length} vectors passed`);
